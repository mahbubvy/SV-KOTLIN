package com.secretvault.app.core.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class StreamState(val message: String = "Choose Send or View", val busy: Boolean = false, val stopping: Boolean = false,
    val live: Boolean = false, val invitation: String? = null, val config: StreamConfig? = null,
    val encodedFps: Int = 0, val receivedFps: Int = 0, val renderedFps: Int = 0, val startupMs: Long? = null) {
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
    private val configReady = CompletableDeferred<StreamConfig>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var host: StreamTls.Host? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var encoder: CameraStreamEncoder? = null
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
        mutableState.value = StreamState("Starting camera…", busy = true)
        scope.launch {
            try {
                val network = wifi()
                val address = cm.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }.address
                val listener = StreamTls.listen(address)
                synchronized(this@StreamSession) {
                    if (closed.get()) { listener.close(); return@launch }
                    host = listener
                }
                val camera = CameraStreamEncoder(context, preview, displayDegrees,
                    onConfig = { config ->
                        if (!closed.get()) {
                            configReady.complete(config)
                            mutableState.update { if (closed.get()) it else it.copy(config = config) }
                        }
                    }, onFrame = { frame ->
                        encoded.incrementAndGet()
                        if (publishing.get()) {
                            try { frames.offer(frame) } catch (error: Exception) { end(error.message ?: "Video connection failed") }
                        }
                    }, onError = { end("Camera stream could not start. ${it.message ?: "Try again."}") })
                synchronized(this@StreamSession) {
                    if (closed.get()) { camera.close(); return@launch }
                    encoder = camera
                }
                mutableState.update { if (closed.get()) it else it.copy(message = "Waiting for a viewer", invitation = listener.invitation.encode()) }
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
                    scope.launch {
                        try {
                            val config = withTimeout(5000) { configReady.await() }
                            val output = DataOutputStream(peer.outputStream)
                            writeStarted.set(SystemClock.elapsedRealtime())
                            StreamProtocol.writeConfig(output, config)
                            writeStarted.set(0)
                            frames.clear(); publishing.set(true); camera.requestKeyFrame()
                            var sequence = 0L
                            var waitingForKey = true
                            var lastFrame = SystemClock.elapsedRealtime()
                            while (isActive && !closed.get()) {
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
                        } catch (_: Exception) { if (!closed.get()) end("Viewer disconnected or connection stalled. Start a new session.") }
                    }
                }
            } catch (_: CancellationException) { }
            catch (error: Exception) { end(error.message ?: "Camera stream could not start") }
        }
    }

    fun view(invitation: StreamInvitation, surface: Surface) {
        mutableState.value = StreamState("Connecting…", busy = true)
        scope.launch {
            try {
                val network = wifi()
                val peer = StreamTls.connect(invitation, network.socketFactory) { candidate ->
                    synchronized(this@StreamSession) {
                        if (closed.get()) { candidate.close(); throw IOException("Session ended") }
                        socket = candidate
                    }
                }
                val pairedAt = SystemClock.elapsedRealtime()
                val input = DataInputStream(peer.inputStream)
                val config = StreamProtocol.readConfig(input)
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
                while (isActive && !closed.get()) {
                    val frame = StreamProtocol.readFrame(input)
                    received.incrementAndGet(); video.offer(frame)
                }
            } catch (_: CancellationException) { }
            catch (_: Exception) { if (!closed.get()) end("Could not receive camera. Check Wi-Fi and the connection details, then try again.") }
        }
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
        mutableState.update { it.copy(message = "Stopping…", stopping = true, busy = true, live = false, invitation = null) }
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
