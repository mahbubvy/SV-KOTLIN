package com.secretvault.app.core.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class StreamEncoder(rotation: Int, onConfig: (StreamConfig) -> Unit,
    onFrame: (StreamFrame) -> Unit, private val onError: (Throwable) -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    private val codec = MediaCodec.createEncoderByType("video/avc")
    private val thread = HandlerThread("VaultStreamEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var input: Surface? = null
    val inputSurface: Surface get() = requireNotNull(input)

    init {
        try {
            val capabilities = codec.codecInfo.getCapabilitiesForType("video/avc")
            check(capabilities.videoCapabilities.areSizeAndRateSupported(1280, 720, 30.0)) { "Encoder cannot supply 720p30" }
            check(capabilities.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }) { "Low-delay H.264 is unavailable" }
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    synchronized(this@StreamEncoder) {
                        if (closed.get()) return
                        try {
                            fun csd(name: String) = requireNotNull(format.getByteBuffer(name)).duplicate().let { bytes -> ByteArray(bytes.remaining()).also(bytes::get) }
                            onConfig(StreamConfig(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), 30, rotation, csd("csd-0"), csd("csd-1")))
                        } catch (error: Exception) { onError(error) }
                    }
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    synchronized(this@StreamEncoder) {
                        if (closed.get()) return
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (info.size > StreamProtocol.MAX_FRAME) throw IOException("Encoded video frame is too large")
                                val buffer = requireNotNull(codec.getOutputBuffer(index))
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size).also(buffer::get)
                                onFrame(StreamFrame(0, info.presentationTimeUs, info.flags and 1, bytes))
                            }
                        } catch (error: Exception) { onError(error) }
                        finally { runCatching { codec.releaseOutputBuffer(index, false) } }
                    }
                }
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { if (!closed.get()) onError(error) }
            }, handler)
            val format = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface()
            codec.start()
        } catch (error: Exception) { close(); throw error }
    }

    fun requestKeyFrame() { handler.post { synchronized(this) {
        if (!closed.get()) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            .onFailure(onError)
    } } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            runCatching { codec.stop() }; runCatching { codec.release() }
            try { input?.release() } finally { input = null; thread.quitSafely() }
        }
    }
}
