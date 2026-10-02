package com.secretvault.app.stream

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretvault.app.core.stream.StreamConfig
import com.secretvault.app.ui.stream.streamPreviewTransform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamPreviewDeviceTest {
    @Test fun cameraAndDecoderShowTheSameUprightUnstretchedImage() {
        fun rotate(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (degrees) {
            0 -> x to y
            90 -> -y to x
            180 -> -x to -y
            270 -> y to -x
            else -> error("Invalid rotation")
        }
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                val rotation = (sensor - display + 360) % 360
                val config = StreamConfig(1280, 720, 30, rotation, byteArrayOf(), byteArrayOf())
                for ((width, height) in listOf(1080 to 1600, 1600 to 1080)) {
                    val contentWidth = if (rotation % 180 == 0) 1280f else 720f
                    val contentHeight = if (rotation % 180 == 0) 720f else 1280f
                    val scale = minOf(width / contentWidth, height / contentHeight)
                    for (camera in listOf(true, false)) {
                        val matrix = streamPreviewTransform(width, height, config, camera, display)
                        for ((x, y) in listOf(-640f to -360f, 640f to -360f, 640f to 360f, -640f to 360f)) {
                            val (expectedX, expectedY) = rotate(x, y, rotation)
                            val (inputX, inputY) = rotate(x, y, if (camera) sensor else 0)
                            val inputWidth = if (camera && sensor % 180 != 0) 720f else 1280f
                            val inputHeight = if (camera && sensor % 180 != 0) 1280f else 720f
                            val point = floatArrayOf((inputX / inputWidth + 0.5f) * width,
                                (inputY / inputHeight + 0.5f) * height)
                            matrix.mapPoints(point)
                            val label = "sensor=$sensor display=$display camera=$camera view=${width}x$height"
                            assertEquals(label, width / 2f + expectedX * scale, point[0], 0.01f)
                            assertEquals(label, height / 2f + expectedY * scale, point[1], 0.01f)
                        }
                    }
                }
            }
        }
    }
}
