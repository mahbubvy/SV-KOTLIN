package com.secretvault.app.ui

import com.secretvault.app.core.image.galleryThumbnailSampleSize
import org.junit.Assert.*
import org.junit.Test

class GalleryThumbnailSampleTest {
    @Test fun ordinaryPhotosKeepEnoughPixelsForSharpGridCrop() {
        assertEquals(4, galleryThumbnailSampleSize(4000, 3000))
        assertEquals(4, galleryThumbnailSampleSize(3000, 4000))
        assertEquals(1, galleryThumbnailSampleSize(512, 512))
    }
    @Test fun veryWideImagesStillLimitDecoderMemory() {
        assertEquals(8, galleryThumbnailSampleSize(16000, 1000))
    }
}
