package com.secretvault.app.core.camera

import com.secretvault.app.core.model.MediaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VaultFileNameGenerator {

    private val dateFormat = SimpleDateFormat("ddMMyyyy_HHmm", Locale.US)

    /**
     * Generates a fixed local-time filename matching:
     * SV_DDMMYYYY_HHMM.jpg or SV_DDMMYYYY_HHMM.mp4
     */
    fun generateFileName(
        mediaType: MediaType,
        timestamp: Long = System.currentTimeMillis(),
        existingNames: Set<String> = emptySet()
    ): String {
        val extension = when (mediaType) {
            MediaType.PHOTO -> "jpg"
            MediaType.VIDEO -> "mp4"
        }
        val dateString = synchronized(dateFormat) {
            dateFormat.format(Date(timestamp))
        }

        val baseName = "SV_$dateString.$extension"
        if (!existingNames.contains(baseName)) {
            return baseName
        }

        // Handle collision within the same minute
        var counter = 1
        while (true) {
            val candidate = "SV_${dateString}_$counter.$extension"
            if (!existingNames.contains(candidate)) {
                return candidate
            }
            counter++
        }
    }
}
