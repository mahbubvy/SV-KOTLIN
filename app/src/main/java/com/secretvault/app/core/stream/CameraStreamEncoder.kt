package com.secretvault.app.core.stream

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamEncoder(context: Context, preview: Surface, displayDegrees: Int,
    onConfig: (StreamConfig) -> Unit, onFrame: (StreamFrame) -> Unit,
    private val onError: (Throwable) -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    private val codec = MediaCodec.createEncoderByType("video/avc")
    private val thread = HandlerThread("VaultStreamEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var input: Surface? = null
    private var device: CameraDevice? = null
    private var capture: CameraCaptureSession? = null

    init {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw IOException("Allow camera access to start streaming")
            val manager = context.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            val characteristics = manager.getCameraCharacteristics(id)
            val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val size = android.util.Size(1280, 720)
            check(size in map.getOutputSizes(MediaCodec::class.java) && size in map.getOutputSizes(SurfaceTexture::class.java)) { "720p camera streaming is unavailable" }
            val minimum = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
            check(minimum == 0L || minimum <= 33_333_334L) { "Camera cannot supply 720p30" }
            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val fps = ranges.filter { 30 in it }.minByOrNull { it.upper - it.lower }
                ?: throw IOException("Camera has no 30 FPS range")
            val rotation = ((characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) - displayDegrees + 360) % 360
            val capabilities = codec.codecInfo.getCapabilitiesForType("video/avc")
            check(capabilities.videoCapabilities.areSizeAndRateSupported(1280, 720, 30.0)) { "Encoder cannot supply 720p30" }
            check(capabilities.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }) { "Low-delay H.264 is unavailable" }
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    synchronized(this@CameraStreamEncoder) {
                        if (closed.get()) return
                        try {
                            fun csd(name: String) = requireNotNull(format.getByteBuffer(name)).duplicate().let { bytes -> ByteArray(bytes.remaining()).also(bytes::get) }
                            onConfig(StreamConfig(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), 30, rotation, csd("csd-0"), csd("csd-1")))
                        } catch (error: Exception) { onError(error) }
                    }
                }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    synchronized(this@CameraStreamEncoder) {
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
            open(manager, id, preview, fps)
        } catch (error: Exception) { close(); throw error }
    }

    @SuppressLint("MissingPermission")
    private fun open(manager: CameraManager, id: String, preview: Surface, fps: Range<Int>) {
        val callbackHandler = Handler(Looper.getMainLooper())
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                synchronized(this@CameraStreamEncoder) {
                    if (closed.get()) { camera.close(); return }
                    device = camera
                    try {
                        val encoderSurface = requireNotNull(input)
                        camera.createCaptureSession(listOf(preview, encoderSurface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                synchronized(this@CameraStreamEncoder) {
                                    if (closed.get()) { session.close(); return }
                                    capture = session
                                    try {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                            addTarget(preview); addTarget(encoderSurface)
                                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                        }
                                        session.setRepeatingRequest(request.build(), null, callbackHandler)
                                    } catch (error: Exception) { onError(error) }
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { session.close(); if (!closed.get()) onError(IOException("Camera rejected the streaming surfaces")) }
                        }, callbackHandler)
                    } catch (error: Exception) { onError(error) }
                }
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); if (!closed.get()) onError(IOException("Camera disconnected")) }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (!closed.get()) onError(IOException("Camera could not start ($error)")) }
        }, callbackHandler)
    }

    fun requestKeyFrame() { handler.post { synchronized(this) {
        if (!closed.get()) runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            .onFailure(onError)
    } } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            runCatching { capture?.close() }; runCatching { device?.close() }
            runCatching { codec.stop() }; runCatching { codec.release() }; input?.release()
            thread.quitSafely()
        }
    }
}
