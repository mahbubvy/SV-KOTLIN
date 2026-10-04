package com.secretvault.app.core.stream

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

class StreamInvitation(val host: String, val port: Int, val fingerprint: ByteArray, val token: ByteArray) {
    fun encode(): String = "svstream://$host:$port/${fingerprint.hex()}/${token.hex()}"
    override fun toString(): String = "StreamInvitation(credentials hidden)"

    companion object {
        fun parse(value: String): StreamInvitation {
            require(value.length <= 256) { "Invalid connection details" }
            val match = Regex("svstream://([0-9.]+):([0-9]{1,5})/([0-9a-f]{64})/([0-9a-f]{32})")
                .matchEntire(value.trim()) ?: throw IllegalArgumentException("Invalid connection details")
            val (host, portText, pin, token) = match.destructured
            val parts = host.split('.').map { it.toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null && it in 0..255 }) { "Use a local Wi-Fi IPv4 address" }
            require(parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1]!! in 16..31)) { "Use a local Wi-Fi IPv4 address" }
            val port = portText.toInt()
            require(port in 1..65535) { "Invalid connection port" }
            return StreamInvitation(host, port, pin.unhex(), token.unhex())
        }

        private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

data class StreamConfig(val width: Int, val height: Int, val fps: Int, val rotation: Int,
    val csd0: ByteArray, val csd1: ByteArray)
sealed interface StreamMessage
data class StreamFrame(val sequence: Long, val ptsUs: Long, val flags: Int, val bytes: ByteArray) : StreamMessage
data class StreamPhotoAvailable(val available: Boolean) : StreamMessage
data class StreamPhotoResult(val requestId: Long, val saved: Boolean) : StreamMessage
data class StreamCameraOption(val id: String, val label: String)
data class StreamCameraState(val options: List<StreamCameraOption> = emptyList(), val selectedId: String? = null) : StreamMessage
data class StreamCameraRequest(val requestId: Long, val targetId: String)
data class StreamCameraResult(val requestId: Long, val applied: Boolean) : StreamMessage
data class StreamRecordingState(val available: Boolean = false, val recording: Boolean = false,
    val saving: Boolean = false, val audio: Boolean = false, val seconds: Int = 0, val paused: Boolean = false) : StreamMessage
data class StreamRecordingRequest(val requestId: Long, val start: Boolean)
data class StreamRecordingResult(val requestId: Long, val start: Boolean, val success: Boolean) : StreamMessage
data class StreamSettingsState(val modes: List<Int> = emptyList(), val mode: Int? = null,
    val flashAvailable: Boolean = false, val flashMode: Int = 0, val photoAvailable: Boolean = false) : StreamMessage
data class StreamSettingsRequest(val requestId: Long, val kind: Int, val value: Int)
data class StreamSettingsResult(val requestId: Long, val applied: Boolean) : StreamMessage

data class StreamInteractionState(val cameraId: String? = null, val minZoom: Float = 1f,
    val maxZoom: Float = 1f, val zoom: Float = 1f, val previewAspect: Float = 1f,
    val focusAvailable: Boolean = false, val microphoneAvailable: Boolean = false) : StreamMessage
data class StreamInteractionRequest(val requestId: Long, val cameraId: String, val kind: Int, val x: Float, val y: Float = 0f)
data class StreamInteractionResult(val requestId: Long, val kind: Int, val applied: Boolean) : StreamMessage

class StreamFrameQueue {
    private val frames = ArrayDeque<StreamFrame>()
    private var bytes = 0
    @Synchronized fun offer(frame: StreamFrame) {
        if (frame.bytes.isEmpty() || frame.bytes.size > StreamProtocol.MAX_FRAME || frames.size >= 6 ||
            bytes.toLong() + frame.bytes.size > 2 * 1024 * 1024) throw IOException("Video connection is too slow")
        frames.addLast(frame); bytes += frame.bytes.size
    }
    @Synchronized fun poll(): StreamFrame? = frames.removeFirstOrNull()?.also { bytes -= it.bytes.size }
    @Synchronized fun clear() { frames.clear(); bytes = 0 }
}

object StreamProtocol {
    const val MAX_FRAME = 1024 * 1024
    const val MAX_CONFIG = 64 * 1024
    private const val MAGIC = 0x53565331
    private const val OPEN_MAGIC = 0x53564F33
    private fun check(value: Boolean) { if (!value) throw IOException("Invalid stream message") }

    fun writeOpenAuth(output: DataOutputStream, sessionId: String) {
        check(sessionId.matches(Regex("[0-9a-f]{32}")))
        output.writeInt(OPEN_MAGIC); output.write(sessionId.toByteArray(Charsets.US_ASCII)); output.flush()
    }

    fun readOpenAuth(input: DataInputStream, sessionId: String) {
        check(sessionId.matches(Regex("[0-9a-f]{32}")) && input.readInt() == OPEN_MAGIC)
        val received = ByteArray(32).also(input::readFully)
        check(MessageDigest.isEqual(received, sessionId.toByteArray(Charsets.US_ASCII)))
    }

    fun writeAuth(output: DataOutputStream, token: ByteArray) {
        check(token.size == 16)
        output.writeInt(MAGIC); output.writeInt(1); output.write(token); output.flush()
    }

    fun readAuth(input: DataInputStream, expected: ByteArray) {
        check(expected.size == 16 && input.readInt() == MAGIC && input.readInt() == 1)
        val received = ByteArray(16).also(input::readFully)
        check(MessageDigest.isEqual(received, expected))
    }

    fun writeConfig(output: DataOutputStream, config: StreamConfig) {
        validateConfig(config)
        output.writeByte(1)
        output.writeInt(config.width); output.writeInt(config.height)
        output.writeInt(config.fps); output.writeInt(config.rotation)
        output.writeInt(config.csd0.size); output.write(config.csd0)
        output.writeInt(config.csd1.size); output.write(config.csd1)
        output.flush()
    }

    fun readConfig(input: DataInputStream): StreamConfig {
        check(input.readUnsignedByte() == 1)
        val width = input.readInt(); val height = input.readInt()
        val fps = input.readInt(); val rotation = input.readInt()
        validateDimensions(width, height, fps, rotation)
        val csd0 = readBytes(input, MAX_CONFIG - 1)
        val csd1 = readBytes(input, MAX_CONFIG - csd0.size)
        return StreamConfig(width, height, fps, rotation, csd0, csd1)
    }

    fun writeFrame(output: DataOutputStream, frame: StreamFrame) {
        check(frame.sequence >= 0 && frame.ptsUs >= 0 && frame.flags in 0..1 && frame.bytes.size in 1..MAX_FRAME)
        output.writeByte(2); output.writeLong(frame.sequence); output.writeLong(frame.ptsUs)
        output.writeInt(frame.flags); output.writeInt(frame.bytes.size); output.write(frame.bytes)
        output.flush()
    }

    fun readFrame(input: DataInputStream): StreamFrame {
        return readMessage(input) as? StreamFrame ?: throw IOException("Expected video frame")
    }

    fun readMessage(input: DataInputStream): StreamMessage = when (input.readUnsignedByte()) {
        2 -> readFramePayload(input)
        3 -> StreamPhotoAvailable(readFlag(input))
        4 -> {
            val requestId = input.readLong(); check(requestId > 0)
            StreamPhotoResult(requestId, readFlag(input))
        }
        6 -> {
            val count = input.readUnsignedByte(); check(count <= 8)
            val selected = readText(input, 32).ifEmpty { null }
            StreamCameraState(List(count) { StreamCameraOption(readText(input, 32), readText(input, 48)) }, selected)
                .also(::validateCameraState)
        }
        8 -> {
            val requestId = input.readLong(); check(requestId > 0)
            StreamCameraResult(requestId, readFlag(input))
        }
        9 -> StreamRecordingState(readFlag(input), readFlag(input), readFlag(input), readFlag(input), input.readInt(), readFlag(input))
            .also(::validateRecordingState)
        11 -> {
            val requestId = input.readLong(); check(requestId > 0)
            StreamRecordingResult(requestId, readFlag(input), readFlag(input))
        }
        12 -> {
            val count = input.readUnsignedByte(); check(count <= 4)
            val mode = input.readUnsignedByte().takeUnless { it == 255 }
            StreamSettingsState(List(count) { input.readUnsignedByte() }, mode, readFlag(input), input.readUnsignedByte(), readFlag(input))
                .also(::validateSettingsState)
        }
        14 -> {
            val id = input.readLong(); check(id > 0)
            StreamSettingsResult(id, readFlag(input))
        }
        15 -> StreamInteractionState(readText(input, 32).ifEmpty { null }, input.readFloat(), input.readFloat(),
            input.readFloat(), input.readFloat(), readFlag(input), readFlag(input)).also(::validateInteractionState)
        17 -> StreamInteractionResult(input.readLong(), input.readUnsignedByte(), readFlag(input)).also {
            check(it.requestId > 0 && it.kind in 0..2)
        }
        else -> throw IOException("Invalid stream message")
    }

    fun writePhotoAvailable(output: DataOutputStream, available: Boolean) {
        output.writeByte(3); output.writeByte(if (available) 1 else 0); output.flush()
    }

    fun writePhotoResult(output: DataOutputStream, result: StreamPhotoResult) {
        check(result.requestId > 0)
        output.writeByte(4); output.writeLong(result.requestId); output.writeByte(if (result.saved) 1 else 0); output.flush()
    }

    fun writePhotoRequest(output: DataOutputStream, requestId: Long) {
        check(requestId > 0)
        output.writeByte(5); output.writeLong(requestId); output.flush()
    }

    fun readPhotoRequest(input: DataInputStream, type: Int): Long {
        check(type == 5)
        return input.readLong().also { check(it > 0) }
    }

    fun writeCameraState(output: DataOutputStream, state: StreamCameraState) {
        validateCameraState(state)
        output.writeByte(6); output.writeByte(state.options.size); writeText(output, state.selectedId.orEmpty())
        state.options.forEach { writeText(output, it.id); writeText(output, it.label) }
        output.flush()
    }

    fun writeCameraRequest(output: DataOutputStream, requestId: Long, targetId: String) {
        check(requestId > 0 && validCameraId(targetId))
        output.writeByte(7); output.writeLong(requestId); writeText(output, targetId); output.flush()
    }

    fun readCameraRequest(input: DataInputStream, type: Int): StreamCameraRequest {
        check(type == 7)
        val id = input.readLong(); val target = readText(input, 32)
        check(id > 0 && validCameraId(target))
        return StreamCameraRequest(id, target)
    }

    fun writeCameraResult(output: DataOutputStream, result: StreamCameraResult) {
        check(result.requestId > 0)
        output.writeByte(8); output.writeLong(result.requestId); output.writeByte(if (result.applied) 1 else 0); output.flush()
    }

    private fun validCameraId(id: String) = id.matches(Regex("[A-Za-z0-9_./:-]{1,32}"))

    fun writeRecordingState(output: DataOutputStream, state: StreamRecordingState) {
        validateRecordingState(state)
        output.writeByte(9)
        listOf(state.available, state.recording, state.saving, state.audio).forEach { output.writeByte(if (it) 1 else 0) }
        output.writeInt(state.seconds); output.writeByte(if (state.paused) 1 else 0); output.flush()
    }

    fun writeRecordingRequest(output: DataOutputStream, id: Long, start: Boolean) {
        check(id > 0)
        output.writeByte(10); output.writeLong(id); output.writeByte(if (start) 1 else 0); output.flush()
    }

    fun readRecordingRequest(input: DataInputStream, type: Int): StreamRecordingRequest {
        check(type == 10)
        val id = input.readLong(); check(id > 0)
        return StreamRecordingRequest(id, readFlag(input))
    }

    fun writeRecordingResult(output: DataOutputStream, result: StreamRecordingResult) {
        check(result.requestId > 0)
        output.writeByte(11); output.writeLong(result.requestId)
        output.writeByte(if (result.start) 1 else 0); output.writeByte(if (result.success) 1 else 0); output.flush()
    }

    private fun validateRecordingState(state: StreamRecordingState) {
        check(state.seconds >= 0 && !(state.recording && state.saving) && (!state.recording || state.available) && (!state.paused || state.recording))
    }

    fun writeSettingsState(output: DataOutputStream, state: StreamSettingsState) {
        validateSettingsState(state)
        output.writeByte(12); output.writeByte(state.modes.size); output.writeByte(state.mode ?: 255)
        state.modes.forEach(output::writeByte)
        output.writeByte(if (state.flashAvailable) 1 else 0); output.writeByte(state.flashMode)
        output.writeByte(if (state.photoAvailable) 1 else 0); output.flush()
    }

    fun writeSettingsRequest(output: DataOutputStream, id: Long, kind: Int, value: Int) {
        check(id > 0 && validSetting(kind, value))
        output.writeByte(13); output.writeLong(id); output.writeByte(kind); output.writeByte(value); output.flush()
    }

    fun readSettingsRequest(input: DataInputStream, type: Int): StreamSettingsRequest {
        check(type == 13)
        val id = input.readLong(); val kind = input.readUnsignedByte(); val value = input.readUnsignedByte()
        check(id > 0 && validSetting(kind, value))
        return StreamSettingsRequest(id, kind, value)
    }

    fun writeSettingsResult(output: DataOutputStream, result: StreamSettingsResult) {
        check(result.requestId > 0)
        output.writeByte(14); output.writeLong(result.requestId); output.writeByte(if (result.applied) 1 else 0); output.flush()
    }

    // Kind 2 pauses (1) or resumes (0) an active recording.
    private fun validSetting(kind: Int, value: Int) = kind == 0 && value in 0..3 || kind == 1 && value in 0..2 || kind == 2 && value in 0..1
    fun writeInteractionState(output: DataOutputStream, state: StreamInteractionState) {
        validateInteractionState(state)
        output.writeByte(15); writeText(output, state.cameraId.orEmpty())
        listOf(state.minZoom, state.maxZoom, state.zoom, state.previewAspect).forEach(output::writeFloat)
        output.writeByte(if (state.focusAvailable) 1 else 0); output.writeByte(if (state.microphoneAvailable) 1 else 0); output.flush()
    }

    fun writeInteractionRequest(output: DataOutputStream, request: StreamInteractionRequest) {
        validateInteractionRequest(request)
        output.writeByte(16); output.writeLong(request.requestId); writeText(output, request.cameraId)
        output.writeByte(request.kind); output.writeFloat(request.x); output.writeFloat(request.y); output.flush()
    }

    fun readInteractionRequest(input: DataInputStream, type: Int): StreamInteractionRequest {
        check(type == 16)
        return StreamInteractionRequest(input.readLong(), readText(input, 32), input.readUnsignedByte(), input.readFloat(), input.readFloat())
            .also(::validateInteractionRequest)
    }

    fun writeInteractionResult(output: DataOutputStream, result: StreamInteractionResult) {
        check(result.requestId > 0 && result.kind in 0..2)
        output.writeByte(17); output.writeLong(result.requestId); output.writeByte(result.kind)
        output.writeByte(if (result.applied) 1 else 0); output.flush()
    }

    private fun validateInteractionState(state: StreamInteractionState) {
        check((state.cameraId == null || validCameraId(state.cameraId)) &&
            state.minZoom.isFinite() && state.maxZoom.isFinite() && state.zoom.isFinite() &&
            state.minZoom in 0.05f..100f && state.maxZoom in state.minZoom..100f && state.zoom in state.minZoom..state.maxZoom &&
            state.previewAspect.isFinite() && state.previewAspect in 0.1f..10f)
    }

    private fun validateInteractionRequest(request: StreamInteractionRequest) {
        check(request.requestId > 0 && validCameraId(request.cameraId) && request.x.isFinite() && request.y.isFinite() &&
            when (request.kind) {
                0 -> request.x in 0.05f..100f && request.y == 0f
                1 -> request.x in 0f..1f && request.y in 0f..1f
                2 -> request.x in listOf(0f, 1f) && request.y == 0f
                else -> false
            })
    }
    private fun validateSettingsState(state: StreamSettingsState) {
        check(state.modes.size <= 4 && state.modes.distinct().size == state.modes.size && state.modes.all { it in 0..3 } &&
            (state.mode == null || state.mode in state.modes) && state.flashMode in 0..2)
    }

    private fun validateCameraState(state: StreamCameraState) {
        check(state.options.size <= 8 && state.options.map { it.id }.distinct().size == state.options.size &&
            state.options.all { validCameraId(it.id) && it.label.toByteArray(Charsets.UTF_8).size in 1..48 && it.label.none(Char::isISOControl) } &&
            (state.selectedId == null || state.options.any { it.id == state.selectedId }))
    }

    private fun writeText(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        output.writeByte(bytes.size); output.write(bytes)
    }

    private fun readText(input: DataInputStream, maximum: Int): String {
        val size = input.readUnsignedByte(); check(size <= maximum)
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun readFlag(input: DataInputStream): Boolean = input.readUnsignedByte().also { check(it in 0..1) } == 1

    private fun readFramePayload(input: DataInputStream): StreamFrame {
        val sequence = input.readLong(); val pts = input.readLong(); val flags = input.readInt()
        check(sequence >= 0 && pts >= 0 && flags in 0..1)
        return StreamFrame(sequence, pts, flags, readBytes(input, MAX_FRAME))
    }

    private fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
        val length = input.readInt()
        check(length in 1..maximum)
        return ByteArray(length).also(input::readFully)
    }

    private fun validateDimensions(width: Int, height: Int, fps: Int, rotation: Int) {
        check(width in 16..1280 && height in 16..720 && width % 2 == 0 && height % 2 == 0 &&
            fps in 1..30 && rotation in listOf(0, 90, 180, 270))
    }

    private fun validateConfig(config: StreamConfig) {
        validateDimensions(config.width, config.height, config.fps, config.rotation)
        check(config.csd0.isNotEmpty() && config.csd1.isNotEmpty() &&
            config.csd0.size.toLong() + config.csd1.size <= MAX_CONFIG)
    }
}
