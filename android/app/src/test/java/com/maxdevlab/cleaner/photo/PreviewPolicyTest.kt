package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.images.*
import org.junit.Assert.*
import org.junit.Test

class PreviewPolicyTest {
    @Test fun largeScreensAreBoundedWithoutChangingAspectRatio() {
        assertEquals(PreviewSize(2048, 1152), PreviewPolicy.size(3840, 2160))
        assertEquals(PreviewSize(1152, 2048), PreviewPolicy.size(2160, 3840))
        assertEquals(PreviewSize(2048, 1), PreviewPolicy.size(100_000, 1))
    }
    @Test fun smallScreensAreNotUpscaledAndUnmeasuredStagesDoNotDecodeOriginals() {
        assertEquals(PreviewSize(720, 1280), PreviewPolicy.size(720, 1280))
        assertNull(PreviewPolicy.size(0, 1280)); assertNull(PreviewPolicy.size(720, -1))
    }
    @Test fun cacheRespectsSmallHeapsAndHasAnAbsoluteUpperBound() {
        assertEquals(128L * 1024 * 1024 / 5, PreviewPolicy.cacheBytes(128).toLong())
        assertEquals(48 * 1024 * 1024, PreviewPolicy.cacheBytes(512))
        assertEquals(48 * 1024 * 1024, PreviewPolicy.cacheBytes(Int.MAX_VALUE))
    }
    @Test fun detailDecodeHasABudgetAndDoesNotUpscaleSmallPhotos() {
        for (heap in listOf(64, 128, 256, 512)) {
            val detail = PreviewPolicy.detailSize(12000, 9000, heap)!!
            val budget = (heap.toLong() * 1024 * 1024 / 16).coerceIn(2L * 1024 * 1024, 24L * 1024 * 1024)
            assertTrue(detail.width <= 4096 && detail.height <= 4096)
            assertTrue(detail.width.toLong() * detail.height * 4 <= budget)
        }
        assertEquals(PreviewSize(900, 600), PreviewPolicy.detailSize(900, 600, 256))
        assertNull(PreviewPolicy.detailSize(0, 600, 256))
    }
}
