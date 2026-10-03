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
    val photoAvailable: Boolean = false, val photoBusy: Boolean = false, val photoMessage: String? = null,
    val cameraState: StreamCameraState = StreamCameraState(), val cameraBusy: Boolean = false, val cameraMessage: String? = null,
    val recordingState: StreamRecordingState = StreamRecordingState(), val recordingBusy: Boolean = false,
    val recordingRequestedStart: Boolean? = null, val recordingMessage: String? = null,
    val settingsState: StreamSettingsState = StreamSettingsState(), val settingsBusy: Boolean = false, val settingsMessage: String? = null) {
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
    private val commandResults = Channel<StreamMessage>(1)
    private val commandPending = AtomicBoolean(false)
    private val requestIds = AtomicLong()
    @Volatile private var commandOutput: DataOutputStream? = null
    private var commandTimeout: Job? = null
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
                   capturePhoto: (suspend () -> Boolean)? = null, cameraState: StateFlow<StreamCameraState>? = null,
                   selectCamera: (suspend (String) -> Boolean)? = null,
                   recordingState: StateFlow<StreamRecordingState>? = null, setRecording: (suspend (Boolean) -> Boolean)? = null,
                   settingsState: StateFlow<StreamSettingsState>? = null, setSetting: (suspend (Int, Int) -> Boolean)? = null) {
        sendSource(pinManager, capturePhoto, cameraState, selectCamera, recordingState, setRecording, settingsState, setSetting) { onConfig, onFrame, onError ->
            val video = StreamEncoder(rotation, onConfig, onFrame, onError)
            try { renderer.attachEncoder(video.inputSurface, rotation, viewAspect) }
            catch (error: Exception) { video.close(); throw error }
            requestKeyFrame = video::requestKeyFrame
            Closeable { try { renderer.detachEncoder() } finally { video.close() } }
        }
    }

    fun refreshCameraFrame() { requestKeyFrame?.invoke() }

    private fun sendSource(pinManager: StreamPinManager? = null, capturePhoto: (suspend () -> Boolean)? = null,
                           cameraState: StateFlow<StreamCameraState>? = null, selectCamera: (suspend (String) -> Boolean)? = null,
                           recordingState: StateFlow<StreamRecordingState>? = null, setRecording: (suspend (Boolean) -> Boolean)? = null,
                           settingsState: StateFlow<StreamSettingsState>? = null, setSetting: (suspend (Int, Int) -> Boolean)? = null,
                           startSource: ((StreamConfig) -> Unit, (StreamFrame) -> Unit, (Throwable) -> Unit) -> Closeable) {
        mutableState.value = StreamState("Starting camera…", busy = true)
        scope.launch {
            try {
                pinManager?.let { pins ->
                    if (pins.isPinRequired()) {
                        val pin = pins.readPin() ?: throw IOException("Set a streaming PIN first")
                        pin.fill('\u0000')
                    }
                }
                val network = wifi()
                val address = cm.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }.address
                val listener = StreamTls.listen(address, pinManager, cameraControls = cameraState != null && selectCamera != null,
                    recordingControls = recordingState != null && setRecording != null, settingsControls = settingsState != null && setSetting != null)
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
                    if (capturePhoto != null || selectCamera != null || setRecording != null || setSetting != null) scope.launch {
                        try {
                            val input = DataInputStream(peer.inputStream)
                            var lastRequest = 0L
                            while (isActive && !closed.get()) {
                                val type = try { input.readUnsignedByte() } catch (_: SocketTimeoutException) { continue }
                                val selection = if (type == 7 && selectCamera != null) StreamProtocol.readCameraRequest(input, type) else null
                                val recording = if (type == 10 && setRecording != null) StreamProtocol.readRecordingRequest(input, type) else null
                                val setting = if (type == 13 && setSetting != null) StreamProtocol.readSettingsRequest(input, type) else null
                                val id = selection?.requestId ?: recording?.requestId ?: setting?.requestId ?: if (capturePhoto != null) StreamProtocol.readPhotoRequest(input, type)
                                    else throw IOException("Unsupported camera command")
                                if (!state.value.live || id != lastRequest + 1 ||
                                    selection != null && cameraState?.value?.options?.none { it.id == selection.targetId } != false ||
                                    setting?.kind == 0 && settingsState?.value?.modes?.contains(setting.value) != true ||
                                    setting?.kind == 1 && settingsState?.value?.flashAvailable != true ||
                                    recording == null && recordingState?.value?.let { it.saving || it.recording && setting?.kind != 1 } == true ||
                                    !commandPending.compareAndSet(false, true)) throw IOException("Invalid camera command")
                                lastRequest = id
                                mutableState.update { if (closed.get()) it else if (setting != null)
                                    it.copy(settingsBusy = true, settingsMessage = "Applying camera setting…")
                                    else if (recording != null)
                                    it.copy(recordingBusy = true, recordingRequestedStart = recording.start,
                                        recordingMessage = if (recording.start) "Starting recording…" else "Saving encrypted video…")
                                    else if (selection == null)
                                    it.copy(photoBusy = true, photoMessage = "Remote photo requested…")
                                    else it.copy(cameraBusy = true, cameraMessage = "Changing camera…") }
                                scope.launch {
                                    val success = try { withTimeout(if (recording != null && !recording.start) 60_000L else if (selection == null && recording == null && setting == null) 25_000L else 10_000L) {
                                        withContext(Dispatchers.Main.immediate) {
                                            if (setting != null) requireNotNull(setSetting)(setting.kind, setting.value)
                                            else if (recording != null) requireNotNull(setRecording)(recording.start)
                                            else if (selection == null) requireNotNull(capturePhoto)() else requireNotNull(selectCamera)(selection.targetId)
                                        }
                                    } }
                                    catch (_: TimeoutCancellationException) { false }
                                    catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { false }
                                    val result = if (setting != null) StreamSettingsResult(id, success)
                                        else if (recording != null) StreamRecordingResult(id, recording.start, success)
                                        else if (selection == null) StreamPhotoResult(id, success) else StreamCameraResult(id, success)
                                    if (!closed.get() && commandResults.trySend(result).isFailure)
                                        end("Camera response could not be delivered. Start a new session.")
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
                            var lastCameraState = cameraState?.value
                            lastCameraState?.let { StreamProtocol.writeCameraState(output, it) }
                            var lastRecordingState = recordingState?.value
                            lastRecordingState?.let { recording ->
                                StreamProtocol.writeRecordingState(output, recording)
                                mutableState.update { if (closed.get()) it else it.copy(recordingState = recording) }
                            }
                            var lastSettingsState = settingsState?.value
                            lastSettingsState?.let { settings ->
                                StreamProtocol.writeSettingsState(output, settings)
                                mutableState.update { if (closed.get()) it else it.copy(settingsState = settings, photoAvailable = settings.photoAvailable) }
                            }
                            writeStarted.set(0)
                            frames.clear(); publishing.set(true); requestKeyFrame?.invoke()
                            var sequence = 0L
                            var waitingForKey = true
                            var lastFrame = SystemClock.elapsedRealtime()
                            while (isActive && !closed.get()) {
                                val latestSettings = settingsState?.value
                                if (latestSettings != null && latestSettings != lastSettingsState) {
                                    writeStarted.set(SystemClock.elapsedRealtime())
                                    StreamProtocol.writeSettingsState(output, latestSettings)
                                    mutableState.update { if (closed.get()) it else it.copy(settingsState = latestSettings, photoAvailable = latestSettings.photoAvailable) }
                                    writeStarted.set(0); lastSettingsState = latestSettings
                                }
                                val latestRecordingState = recordingState?.value
                                if (latestRecordingState != null && latestRecordingState != lastRecordingState) {
                                    writeStarted.set(SystemClock.elapsedRealtime())
                                    StreamProtocol.writeRecordingState(output, latestRecordingState)
                                    mutableState.update { if (closed.get()) it else it.copy(recordingState = latestRecordingState) }
                                    writeStarted.set(0); lastRecordingState = latestRecordingState
                                }
                                val latestCameraState = cameraState?.value
                                if (latestCameraState != null && latestCameraState != lastCameraState) {
                                    writeStarted.set(SystemClock.elapsedRealtime())
                                    StreamProtocol.writeCameraState(output, latestCameraState)
                                    writeStarted.set(0); lastCameraState = latestCameraState
                                }
                                commandResults.tryReceive().getOrNull()?.let { result ->
                                    writeStarted.set(SystemClock.elapsedRealtime())
                                    when (result) {
                                        is StreamPhotoResult -> {
                                            mutableState.update { if (closed.get()) it else it.copy(photoBusy = false,
                                                photoMessage = if (result.saved) "Remote photo saved in this vault" else "Photo could not be confirmed. Check this vault.") }
                                            commandPending.set(false)
                                            StreamProtocol.writePhotoResult(output, result)
                                        }
                                        is StreamCameraResult -> {
                                            mutableState.update { if (closed.get()) it else it.copy(cameraBusy = false,
                                                cameraMessage = if (result.applied) "Camera changed" else "Camera change could not be confirmed") }
                                            commandPending.set(false)
                                            StreamProtocol.writeCameraResult(output, result)
                                        }
                                        is StreamRecordingResult -> {
                                            mutableState.update { if (closed.get()) it else it.copy(recordingBusy = false, recordingRequestedStart = null,
                                                recordingMessage = recordingResultText(result, true)) }
                                            commandPending.set(false)
                                            StreamProtocol.writeRecordingResult(output, result)
                                        }
                                        is StreamSettingsResult -> {
                                            mutableState.update { if (closed.get()) it else it.copy(settingsBusy = false,
                                                settingsMessage = if (result.applied) "Camera setting applied" else "Setting could not be applied. Check the camera device.") }
                                            commandPending.set(false); StreamProtocol.writeSettingsResult(output, result)
                                        }
                                        else -> throw IOException("Invalid camera response")
                                    }
                                    writeStarted.set(0)
                                }
                                val frame = frames.poll()
                                if (frame == null) {
                                    if (SystemClock.elapsedRealtime() - lastFrame > if (state.value.cameraBusy || state.value.settingsBusy) 10_000 else 3000)
                                        throw IOException("Camera stopped supplying frames")
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

    fun view(endpoint: StreamEndpoint, surface: Surface) {
        viewSource(surface) { network, onSocket -> StreamTls.connect(endpoint, network.socketFactory, onSocket) }
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
                commandOutput = DataOutputStream(peer.outputStream)
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
                            if (message.requestId != requestIds.get() || !state.value.photoBusy || !commandPending.compareAndSet(true, false)) throw IOException("Unexpected photo result")
                            commandTimeout?.cancel()
                            mutableState.update { if (closed.get()) it else it.copy(photoBusy = false,
                                photoMessage = if (message.saved) "Photo saved on camera device" else "Photo could not be confirmed. Check the camera vault.") }
                        }
                        is StreamCameraState -> mutableState.update { if (closed.get()) it else it.copy(cameraState = message) }
                        is StreamSettingsState -> mutableState.update { if (closed.get()) it else it.copy(settingsState = message, photoAvailable = message.photoAvailable) }
                        is StreamSettingsResult -> {
                            if (message.requestId != requestIds.get() || !state.value.settingsBusy || !commandPending.compareAndSet(true, false)) throw IOException("Unexpected setting result")
                            commandTimeout?.cancel()
                            mutableState.update { if (closed.get()) it else it.copy(settingsBusy = false,
                                settingsMessage = if (message.applied) "Camera setting applied" else "Setting could not be applied. Check the camera device.") }
                        }
                        is StreamRecordingState -> mutableState.update { if (closed.get()) it else it.copy(recordingState = message) }
                        is StreamRecordingResult -> {
                            if (message.requestId != requestIds.get() || !state.value.recordingBusy || state.value.recordingRequestedStart != message.start ||
                                !commandPending.compareAndSet(true, false)) throw IOException("Unexpected recording result")
                            commandTimeout?.cancel()
                            mutableState.update { if (closed.get()) it else it.copy(recordingBusy = false, recordingRequestedStart = null,
                                recordingMessage = recordingResultText(message, false)) }
                        }
                        is StreamCameraResult -> {
                            if (message.requestId != requestIds.get() || !state.value.cameraBusy || !commandPending.compareAndSet(true, false))
                                throw IOException("Unexpected camera result")
                            commandTimeout?.cancel()
                            mutableState.update { if (closed.get()) it else it.copy(cameraBusy = false,
                                cameraMessage = if (message.applied) "Camera changed" else "Camera change could not be confirmed. Check the camera device.") }
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
        val output = commandOutput ?: return false
        if (closed.get() || !state.value.live || !state.value.photoAvailable || state.value.recordingState.let { it.recording || it.saving } ||
            !commandPending.compareAndSet(false, true)) return false
        val id = requestIds.incrementAndGet()
        mutableState.update { if (closed.get()) it else it.copy(photoBusy = true, photoMessage = "Taking photo…") }
        commandTimeout = scope.launch {
            delay(30_000)
            if (commandPending.get()) end("Photo result timed out. Check the camera vault before reconnecting.")
        }
        scope.launch {
            try { StreamProtocol.writePhotoRequest(output, id) }
            catch (_: Exception) { if (!closed.get()) end("Photo request connection failed. Check the camera vault.") }
        }
        return true
    }

    fun selectCamera(targetId: String): Boolean {
        val output = commandOutput ?: return false
        val current = state.value
        if (closed.get() || !current.live || current.cameraState.selectedId == null || current.cameraState.selectedId == targetId ||
            current.recordingState.let { it.recording || it.saving } ||
            current.cameraState.options.none { it.id == targetId } || !commandPending.compareAndSet(false, true)) return false
        val id = requestIds.incrementAndGet()
        mutableState.update { if (closed.get()) it else it.copy(cameraBusy = true, cameraMessage = "Changing camera…") }
        commandTimeout = scope.launch {
            delay(15_000)
            if (commandPending.get()) end("Camera change timed out. Check the camera device before reconnecting.")
        }
        scope.launch {
            try { StreamProtocol.writeCameraRequest(output, id, targetId) }
            catch (_: Exception) { if (!closed.get()) end("Camera request connection failed. Start a new session.") }
        }
        return true
    }

    fun setRecording(start: Boolean): Boolean {
        val output = commandOutput ?: return false
        val current = state.value
        if (closed.get() || !current.live || !current.recordingState.available || current.recordingState.saving ||
            current.cameraState.selectedId == null || current.recordingState.recording == start || !commandPending.compareAndSet(false, true)) return false
        val id = requestIds.incrementAndGet()
        mutableState.update { if (closed.get()) it else it.copy(recordingBusy = true, recordingRequestedStart = start,
            recordingMessage = if (start) "Starting recording…" else "Saving encrypted video…") }
        commandTimeout = scope.launch {
            delay(if (start) 15_000 else 65_000)
            if (commandPending.get()) end("Recording result timed out. Check the camera vault before reconnecting.")
        }
        scope.launch {
            try { StreamProtocol.writeRecordingRequest(output, id, start) }
            catch (_: Exception) { if (!closed.get()) end("Recording request failed. Check the camera vault.") }
        }
        return true
    }

    private fun recordingResultText(result: StreamRecordingResult, host: Boolean): String = when {
        !result.success -> "Recording could not be confirmed. Check ${if (host) "this vault" else "the camera vault"}."
        result.start -> "Recording started"
        host -> "Video saved in this vault"
        else -> "Video saved on camera device"
    }

    fun setSetting(kind: Int, value: Int): Boolean {
        val output = commandOutput ?: return false
        val current = state.value
        if (closed.get() || !current.live || current.cameraState.selectedId == null || current.recordingState.saving ||
            (kind == 0 && (current.recordingState.recording || value !in current.settingsState.modes || current.settingsState.mode == value)) ||
            (kind == 1 && (!current.settingsState.flashAvailable || value !in 0..2)) || kind !in 0..1 ||
            !commandPending.compareAndSet(false, true)) return false
        val id = requestIds.incrementAndGet()
        mutableState.update { if (closed.get()) it else it.copy(settingsBusy = true, settingsMessage = "Applying camera setting…") }
        commandTimeout = scope.launch { delay(15_000); if (commandPending.get()) end("Setting timed out. Check the camera device before reconnecting.") }
        scope.launch {
            try { StreamProtocol.writeSettingsRequest(output, id, kind, value) }
            catch (_: Exception) { if (!closed.get()) end("Setting request failed. Check the camera device.") }
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
        commandOutput = null; commandResults.cancel()
        mutableState.update { it.copy(message = "Stopping…", stopping = true, busy = true, live = false, invitation = null, endpoint = null,
            photoAvailable = false, photoBusy = false, cameraState = StreamCameraState(), cameraBusy = false,
            recordingState = StreamRecordingState(), recordingBusy = false, recordingRequestedStart = null,
            settingsState = StreamSettingsState(), settingsBusy = false,
            settingsMessage = if (it.settingsBusy) "Camera setting unknown. Check the camera device." else it.settingsMessage,
            recordingMessage = if (it.recordingBusy || it.recordingState.recording || it.recordingState.saving)
                "Recording ended. Check the camera vault for the saved video." else it.recordingMessage,
            cameraMessage = if (it.cameraBusy) "Camera selection unknown. Check the camera device." else it.cameraMessage,
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
