package com.secretvault.app.core.processing

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Strips sensitive EXIF metadata (GPS location, camera serial numbers, device model, timestamps)
 * from image files before encryption into the vault, while preserving image orientation.
 */
object ExifSanitizer {

    private val SENSITIVE_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
    )

    /**
     * Sanitizes [file] in-place by removing all location, hardware identification, and timestamp tags.
     * Preserves image orientation.
     */
    fun sanitizeInPlace(file: File): Boolean {
        return try {
            val exif = ExifInterface(file)
            val orientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION)

            for (tag in SENSITIVE_TAGS) {
                exif.setAttribute(tag, null)
            }

            if (orientation != null) {
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
            }

            exif.saveAttributes()
            true
        } catch (e: Exception) {
            // Non-JPEG format or no EXIF header
            false
        }
    }
}
