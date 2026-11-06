package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import org.junit.Assert.*
import org.junit.Test

class VideoMediaTest {
    private fun media(video: Boolean) = Photo("content://media/external/${if (video) "video" else "images"}/media/7", "Screenshot.mp4", 1790000000000, 1024, 320, 240, 1, "Screenshots", isVideo = video, durationMs = 65000)
    @Test fun sameNumericIdsRemainSeparateAndVideoIsNeverScreenshot() {
        val image = media(false); val video = media(true)
        assertNotEquals(image.uri, video.uri)
        assertFalse(video.isScreenshot)
        val records = mapOf(video.uri to ReviewRecord(video.signature, Decision.REMOVE, false))
        assertNull(ReviewRules.record(image, records))
        assertEquals(listOf(video), ReviewRules.deleteCandidates(listOf(image, video), records, setOf(image.uri, video.uri)))
        assertEquals("1 photo · 1 video", mediaCount(listOf(image, video)))
        assertEquals("1:05", videoTime(video.durationMs))
    }
    @Test fun filtersShareDecisionsButCountOnlyTheirOwnMedia() {
        val image = media(false); val video = media(true)
        val state = PhotoState(photos = listOf(image, video), records = mapOf(video.uri to ReviewRecord(video.signature, Decision.KEEP, false)))
        assertEquals(2, state.groups.single().photos.size)
        assertEquals(1, state.groups.single().reviewed)
        assertTrue(state.copy(mediaFilter = MediaFilter.VIDEOS).groups.single().completed)
        assertEquals(0, state.copy(mediaFilter = MediaFilter.PHOTOS).groups.single().reviewed)
    }
}
