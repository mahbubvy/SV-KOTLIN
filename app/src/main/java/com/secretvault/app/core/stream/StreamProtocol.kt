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
    private fun check(value: Boolean) { if (!value) throw IOException("Invalid stream message") }

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
