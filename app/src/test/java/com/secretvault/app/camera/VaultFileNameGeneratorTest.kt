package com.secretvault.app.camera

import com.secretvault.app.core.camera.VaultFileNameGenerator
import com.secretvault.app.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VaultFileNameGeneratorTest {

    private val testTimestamp = 1756454400000L // Fixed timestamp

    @Test
    fun testPhotoFileNameFormat() {
        val fileName = VaultFileNameGenerator.generateFileName(MediaType.PHOTO, testTimestamp)
        assertTrue(fileName.startsWith("SV_"))
        assertTrue(fileName.endsWith(".jpg"))

        val expectedDate = SimpleDateFormat("ddMMyyyy_HHmm", Locale.US).format(Date(testTimestamp))
        assertEquals("SV_$expectedDate.jpg", fileName)
    }

    @Test
    fun testVideoFileNameFormat() {
        val fileName = VaultFileNameGenerator.generateFileName(MediaType.VIDEO, testTimestamp)
        assertTrue(fileName.startsWith("SV_"))
        assertTrue(fileName.endsWith(".mp4"))

        val expectedDate = SimpleDateFormat("ddMMyyyy_HHmm", Locale.US).format(Date(testTimestamp))
        assertEquals("SV_$expectedDate.mp4", fileName)
    }

    @Test
    fun testFileNameCollisionAvoidance() {
        val expectedDate = SimpleDateFormat("ddMMyyyy_HHmm", Locale.US).format(Date(testTimestamp))
        val existing = setOf(
            "SV_$expectedDate.jpg",
            "SV_${expectedDate}_1.jpg"
        )

        val nextName = VaultFileNameGenerator.generateFileName(MediaType.PHOTO, testTimestamp, existing)
        assertEquals("SV_${expectedDate}_2.jpg", nextName)
    }
}
