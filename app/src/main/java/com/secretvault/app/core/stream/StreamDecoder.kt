package com.secretvault.app.core.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class StreamDecoder(config: StreamConfig, surface: Surface, onRendered: () -> Unit,
    private val onError: (Throwable) -> Unit) : Closeable {
    private val codec = MediaCodec.createDecoderByType("video/avc")
    private val running = AtomicBoolean(true)
    private val frames = StreamFrameQueue()
    private var nextSequence = 0L
    private val worker = Thread(::decode, "VaultStreamDecoder")

    init {
        try {
            val format = MediaFormat.createVideoFormat("video/avc", config.width, config.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0))
                setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, StreamProtocol.MAX_FRAME)
            }
            codec.configure(format, surface, null, 0)
            codec.setOnFrameRenderedListener({ _, _, _ -> if (running.get()) onRendered() }, Handler(Looper.getMainLooper()))
            codec.start(); worker.start()
        } catch (error: Exception) { running.set(false); codec.release(); throw error }
    }

    @Synchronized fun offer(frame: StreamFrame) {
        if (!running.get()) throw IOException("Viewer ended")
        if (frame.sequence != nextSequence || frame.ptsUs < 0 ||
            (nextSequence == 0L && frame.flags != 1)) throw IOException("Invalid video sequence")
        frames.offer(frame)
        nextSequence++
    }

    private fun decode() {
        var pending: StreamFrame? = null
        var pendingSince = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                if (pending == null) {
                    pending = frames.poll()
                    pendingSince = SystemClock.elapsedRealtime()
                }
                pending?.let { frame ->
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(input))
                        if (buffer.capacity() < frame.bytes.size) throw IOException("Video frame exceeds decoder capacity")
                        buffer.clear(); buffer.put(frame.bytes)
                        codec.queueInputBuffer(input, 0, frame.bytes.size, frame.ptsUs, frame.flags)
                        pending = null
                    } else if (SystemClock.elapsedRealtime() - pendingSince > 3000) throw IOException("Video decoder stalled")
                }
                var output = codec.dequeueOutputBuffer(info, 0)
                while (output >= 0) {
                    codec.releaseOutputBuffer(output, true)
                    output = codec.dequeueOutputBuffer(info, 0)
                }
                if (pending == null) Thread.sleep(5)
            }
        } catch (error: Exception) { if (running.get()) onError(error) }
        finally {
            running.set(false); frames.clear()
            runCatching { codec.stop() }; codec.release()
        }
    }

    override fun close() {
        running.set(false)
        worker.interrupt()
        if (Thread.currentThread() != worker) worker.join(1000)
    }
}
