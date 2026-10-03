package com.secretvault.app.core.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.EOFException
import java.net.Inet4Address
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class StreamState(val message: String = "Choose Send or View", val busy: Boolean = false, val stopping: Boolean = false,
    val live: Boolean = false, val invitation: String? = null, val endpoint: StreamEndpoint? = null, val config: StreamConfig? = null,
    val encodedFps: Int = 0, val receivedFps: Int = 0, val renderedFps: Int = 0, val startupMs: Long? = null,
    val photoAvailable: Boolean = false, val photoBusy: Boolean = false, val photoMessage: String? = null) {
    override fun toString(): String = "StreamState($message, credentials hidden)"
}

class StreamSession(private val context: Context) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val publishing = AtomicBoolean(false)
    private val encoded = AtomicLong()
    private val received = AtomicLong()
    private val rendered = AtomicLong()
    private val writeStarted = AtomicLong()
    private val frames = StreamFrameQueue()
    private val photoResults = Channel<StreamPhotoResult>(1)
    private val photoPending = AtomicBoolean(false)
    private val photoIds = AtomicLong()
    @Volatile private var photoOutput: DataOutputStream? = null
    private var photoTimeout: Job? = null
    private val configReady = CompletableDeferred<StreamConfig>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var host: StreamTls.Host? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var encoder: Closeable? = null
    @Volatile private var requestKeyFrame: (() -> Unit)? = null
    @Volatile private var decoder: StreamDecoder? = null
    private val mutableState = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    private fun wifi(): Network {
        val network = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(it)?.linkAddresses?.any { address -> address.address is Inet4Address } == true
        } ?: throw IOException("Connect both phones to the same Wi-Fi")
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) { if (lost == network) end("Wi-Fi disconnected. Start a new session.") }
        }
        synchronized(this) {
            if (closed.get()) throw IOException("Session ended")
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), listener)
            callback = listener
        }
        return network
    }

    fun send(preview: Surface, displayDegrees: Int) {
        sendSource { onConfig, onFrame, onError ->
            CameraStreamEncoder(context, preview, displayDegrees, onConfig, onFrame, onError).also {
                requestKeyFrame = it::requestKeyFrame
            }
        }
    }

    fun sendShared(renderer: StreamPreviewRenderer, rotation: Int, viewAspect: Float, pinManager: StreamPinManager? = null,
                   capturePhoto: (suspend () -> Boolean)? = null) {
        sendSource(pinManager, capturePhoto) { onConfig, onFrame, onError ->
            val video = StreamEncoder(rotation, onConfig, onFrame, onError)
            try { renderer.attachEncoder(video.inputSurface, rotation, viewAspect) }
            catch (error: Exception) { video.close(); throw error }
            requestKeyFrame = video::requestKeyFrame
            Closeable { try { renderer.detachEncoder() } finally { video.close() } }
        }
    }

    fun refreshCameraFrame() { requestKeyFrame?.invoke() }

    private fun sendSource(pinManager: StreamPinManager? = null, capturePhoto: (suspend () -> Boolean)? = null,
                           startSource: ((StreamConfig) -> Unit, (StreamFrame) -> Unit, (Throwable) -> Unit) -> Closeable) {
        mutableState.value = StreamState("Starting camera…", busy = true)
        scope.launch {
            try {
                pinManager?.let { pins ->
                    val pin = pins.readPin() ?: throw IOException("Set a streaming PIN first")
                    pin.fill('\u0000')
                }
                val network = wifi()
                val address = cm.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }.address
                val listener = StreamTls.listen(address, pinManager)
                synchronized(this@StreamSession) {
                    if (closed.get()) { listener.close(); return@launch }
                    host = listener
                }
                val camera = startSource(
                    { config ->
                        if (!closed.get()) {
                            configReady.complete(config)
                            mutableState.update { if (closed.get()) it else it.copy(config = config) }
                        }
                    }, { frame ->
                        encoded.incrementAndGet()
                        if (publishing.get()) {
                            try { frames.offer(frame) } catch (error: Exception) { end(error.message ?: "Video connection failed") }
                        }
                    }, { end("Camera stream could not start. ${it.message ?: "Try again."}") })
                synchronized(this@StreamSession) {
                    if (closed.get()) { camera.close(); return@launch }
                    encoder = camera
                }
                mutableState.update { if (closed.get()) it else it.copy(message = "Waiting for a viewer",
                    invitation = if (pinManager == null) listener.invitation.encode() else null, endpoint = listener.endpoint) }
                stats()
                while (isActive && !closed.get()) {
                    val peer = try { listener.accept() } catch (error: SocketException) {
                        if (closed.get()) break else throw error
                    } catch (error: IOException) {
                        if (closed.get()) break else continue
                    }
                    synchronized(this@StreamSession) {
                        if (closed.get()) { peer.close(); return@launch }
                        socket = peer
                    }
                    if (capturePhoto != null) scope.launch {
                        try {
                            val input = DataInputStream(peer.inputStream)
                            var lastRequest = 0L
                            while (isActive && !closed.get()) {
                                val type = try { input.readUnsignedByte() } catch (_: SocketTimeoutException) { continue }
                                val id = StreamProtocol.readPhotoRequest(input, type)
                                if (!state.value.live || id != lastRequest + 1 || !photoPending.compareAndSet(false, true))
                                    throw IOException("Invalid photo request")
                                lastRequest = id
                                mutableState.update { if (closed.get()) it else it.copy(photoBusy = true, photoMessage = "Remote photo requested…") }
                                scope.launch {
                                    val saved = try { withTimeout(25_000) { withContext(Dispatchers.Main.immediate) { capturePhoto() } } }
                                    catch (_: TimeoutCancellationException) { false }
                                    catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { false }
                                    if (!closed.get() && photoResults.trySend(StreamPhotoResult(id, saved)).isFailure)
                                        end("Photo response could not be delivered. Check the camera vault.")
                                }
                            }
                        } catch (_: EOFException) { if (!closed.get()) end("Viewer disconnected or connection stalled. Start a new session.") }
                        catch (error: Exception) {
                            if (!closed.get()) { Log.w("VaultStream", "Command channel failed: ${error.javaClass.simpleName}"); end("Camera command rejected. Start a new session.") }
                        }
                    }
                    scope.launch {
                        try {
                            val config = withTimeout(5000) { configReady.await() }
                            val output = DataOutputStream(peer.outputStream)
                            writeStarted.set(SystemClock.elapsedRealtime())
                            StreamProtocol.writeConfig(output, config)
                            if (capturePhoto != null) StreamProtocol.writePhotoAvailable(output, true)
                            writeStarted.set(0)
                            frames.clear(); publishing.set(true); requestKeyFrame?.invoke()
                            var sequence = 0L
                            var waitingForKey = true
                            var lastFrame = SystemClock.elapsedRealtime()
                            while (isActive && !closed.get()) {
                                photoResults.tryReceive().getOrNull()?.let { result ->
                                    mutableState.update { if (closed.get()) it else it.copy(photoBusy = false,
                                        photoMessage = if (result.saved) "Remote photo saved in this vault" else "Photo could not be confirmed. Check this vault.") }
                                    photoPending.set(false)
                                    writeStarted.set(SystemClock.elapsedRealtime())
                                    StreamProtocol.writePhotoResult(output, result)
                                    writeStarted.set(0)
                                }
                                val frame = frames.poll()
                                if (frame == null) {
                                    if (SystemClock.elapsedRealtime() - lastFrame > 3000) throw IOException("Camera stopped supplying frames")
                                    delay(5); continue
                                }
                                if (waitingForKey && frame.flags != 1) continue
                                waitingForKey = false; lastFrame = SystemClock.elapsedRealtime()
                                writeStarted.set(lastFrame)
                                StreamProtocol.writeFrame(output, frame.copy(sequence = sequence++))
                                writeStarted.set(0)
                                if (sequence == 1L) mutableState.update { if (closed.get()) it else it.copy(message = "Sending live camera", live = true) }
                            }
                        } catch (error: Exception) {
                            if (!closed.get()) { Log.w("VaultStream", "Video writer failed: ${error.javaClass.simpleName}"); end("Viewer disconnected or connection stalled. Start a new session.") }
                        }
                    }
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) { end(error.message ?: "Camera stream could not start") }
        }
    }

    fun view(invitation: StreamInvitation, surface: Surface) {
        viewSource(surface) { network, onSocket -> StreamTls.connect(invitation, network.socketFactory, onSocket) }
    }

    fun view(endpoint: StreamEndpoint, pin: CharArray, surface: Surface) {
        viewSource(surface, { pin.fill('\u0000') }) { network, onSocket -> StreamTls.connect(endpoint, pin, network.socketFactory, onSocket) }
    }

    private fun viewSource(surface: Surface, clearSecret: () -> Unit = {},
                           connect: (Network, (Socket) -> Unit) -> Socket) {
        mutableState.value = StreamState("Connecting…", busy = true)
        scope.launch {
            var phase = "pairing"
            try {
                val network = wifi()
                val peer = connect(network) { candidate ->
                    synchronized(this@StreamSession) {
                        if (closed.get()) { candidate.close(); throw IOException("Session ended") }
                        socket = candidate
                    }
                }
                val pairedAt = SystemClock.elapsedRealtime()
                phase = "configuration"
                val input = DataInputStream(peer.inputStream)
                val config = StreamProtocol.readConfig(input)
                photoOutput = DataOutputStream(peer.outputStream)
                mutableState.update { if (closed.get()) it else it.copy(config = config, message = "Waiting for first frame…") }
                val video = StreamDecoder(config, surface, onRendered = {
                    if (!closed.get() && rendered.incrementAndGet() == 1L) mutableState.update {
                        if (closed.get()) it else it.copy(message = "Live camera", live = true, startupMs = SystemClock.elapsedRealtime() - pairedAt)
                    }
                }, onError = { end("Video could not decode. Start a new session.") })
                synchronized(this@StreamSession) {
                    if (closed.get()) { video.close(); return@launch }
                    decoder = video
                }
                stats()
                phase = "media"
                while (isActive && !closed.get()) {
                    when (val message = StreamProtocol.readMessage(input)) {
                        is StreamFrame -> { received.incrementAndGet(); video.offer(message) }
                        is StreamPhotoAvailable -> mutableState.update { if (closed.get()) it else it.copy(photoAvailable = message.available) }
                        is StreamPhotoResult -> {
                            if (message.requestId != photoIds.get() || !photoPending.compareAndSet(true, false)) throw IOException("Unexpected photo result")
                            photoTimeout?.cancel()
                            mutableState.update { if (closed.get()) it else it.copy(photoBusy = false,
                                photoMessage = if (message.saved) "Photo saved on camera device" else "Photo could not be confirmed. Check the camera vault.") }
                        }
                    }
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                if (!closed.get()) { Log.w("VaultStream", "Viewer $phase failed: ${error.javaClass.simpleName}"); end("Could not receive camera. Check Wi-Fi and the streaming PIN, then try again.") }
            }
            finally { clearSecret() }
        }.invokeOnCompletion { clearSecret() }
    }

    fun takePhoto(): Boolean {
        val output = photoOutput ?: return false
        if (closed.get() || !state.value.live || !state.value.photoAvailable || !photoPending.compareAndSet(false, true)) return false
        val id = photoIds.incrementAndGet()
        mutableState.update { if (closed.get()) it else it.copy(photoBusy = true, photoMessage = "Taking photo…") }
        photoTimeout = scope.launch {
            delay(30_000)
            if (photoPending.get()) end("Photo result timed out. Check the camera vault before reconnecting.")
        }
        scope.launch {
            try { StreamProtocol.writePhotoRequest(output, id) }
            catch (_: Exception) { if (!closed.get()) end("Photo request connection failed. Check the camera vault.") }
        }
        return true
    }

    private fun stats() {
        scope.launch {
            var counts = longArrayOf(encoded.get(), received.get(), rendered.get())
            var since = SystemClock.elapsedRealtime()
            while (isActive && !closed.get()) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val next = longArrayOf(encoded.get(), received.get(), rendered.get())
                fun rate(index: Int) = ((next[index] - counts[index]) * 1000 / (now - since).coerceAtLeast(1)).toInt()
                mutableState.update { if (closed.get()) it else it.copy(encodedFps = rate(0), receivedFps = rate(1), renderedFps = rate(2)) }
                counts = next; since = now
                val writing = writeStarted.get()
                if (writing > 0 && now - writing > 3000) end("Video connection stalled. Start a new session.")
            }
        }
    }

    private fun end(message: String) {
        if (!closed.compareAndSet(false, true)) return
        publishing.set(false)
        photoOutput = null; photoResults.cancel()
        mutableState.update { it.copy(message = "Stopping…", stopping = true, busy = true, live = false, invitation = null, endpoint = null,
            photoAvailable = false, photoBusy = false,
            photoMessage = if (it.photoBusy) "Photo result unknown. Check the camera vault." else it.photoMessage) }
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(this@StreamSession) {
                runCatching { callback?.let(cm::unregisterNetworkCallback) }
                runCatching { socket?.close() }; runCatching { host?.close() }
            }
            runCatching { encoder?.close() }; runCatching { decoder?.close() }; frames.clear()
            mutableState.update { it.copy(message = message, busy = false, stopping = false) }
        }
    }

    override fun close() = end("Stream stopped")
}
