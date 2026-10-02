package com.secretvault.app.stream

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamCameraDeviceTest {
    @Test fun supportsRequestedStreamConfigurationWithoutOpeningCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = instrumentation.targetContext.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val characteristics = manager.getCameraCharacteristics(id)
        val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val size = Size(1280, 720)
        val encoderSizes = map.getOutputSizes(MediaCodec::class.java).orEmpty()
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        instrumentation.sendStatus(2, Bundle().apply {
            putString("streamCameraId", id)
            putString("streamEncoderSizes", encoderSizes.joinToString())
            putString("streamPreviewSizes", previewSizes.joinToString())
            putString("streamFpsRanges", ranges.joinToString())
        })
        assertTrue("Rear camera has no 720p encoder output", size in encoderSizes)
        assertTrue("Rear camera has no 720p preview output", size in previewSizes)
        assertTrue("Rear camera has no range containing 30 FPS", ranges.any { 30 in it })
        val minimum = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
        assertTrue("720p minimum frame duration is $minimum ns", minimum == 0L || minimum <= 33_333_334L)

        val codec = MediaCodec.createEncoderByType("video/avc")
        var input: android.view.Surface? = null
        var started = false
        try {
            val capabilities = codec.codecInfo.getCapabilitiesForType("video/avc")
            instrumentation.sendStatus(2, Bundle().apply {
                putString("streamCodec", codec.name)
                putString("streamAvcProfiles", capabilities.profileLevels.joinToString { "${it.profile}/${it.level}" })
            })
            assertTrue("Encoder does not advertise 720p30", capabilities.videoCapabilities.areSizeAndRateSupported(1280, 720, 30.0))
            assertTrue("Encoder has no Baseline profile", capabilities.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline })
            val format = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface()
            codec.start(); started = true
        } finally {
            try { if (started) codec.stop() }
            finally { try { codec.release() } finally { input?.release() } }
        }
    }
}
