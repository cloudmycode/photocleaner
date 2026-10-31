package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.PhotoDetailFormat
import com.maxdevlab.cleaner.photo.platform.PhotoPlatform
import org.junit.Assert.*
import org.junit.Test

class PhotoDetailFormatTest {
    @Test fun unknownDatesAndSizesAreNotPresentedAsRealValues() {
        assertNull(PhotoDetailFormat.date(0)); assertNull(PhotoDetailFormat.date(-1))
        assertEquals("Not provided", PhotoDetailFormat.size(0))
        assertEquals("500 B", PhotoDetailFormat.size(500))
        assertEquals("1.5 KB", PhotoDetailFormat.size(1500))
        assertEquals("2.5 MB", PhotoDetailFormat.size(2500000))
    }
    @Test fun validCoordinatesIncludeEquatorAndRejectInvalidMetadata() {
        assertEquals("0.00000, 0.00000", PhotoDetailFormat.coordinates(0.0, 0.0))
        assertEquals("37.77490, -122.41940", PhotoDetailFormat.coordinates(37.7749, -122.4194))
        assertNull(PhotoDetailFormat.coordinates(Double.NaN, 0.0))
        assertNull(PhotoDetailFormat.coordinates(91.0, 0.0))
        assertNull(PhotoDetailFormat.coordinates(0.0, -181.0))
    }
    @Test fun photoGpsPermissionIsOnlyRequestedOnSupportedSystems() {
        assertNull(PhotoPlatform(27).mediaLocationPermission)
        assertNull(PhotoPlatform(28).mediaLocationPermission)
        assertEquals("android.permission.ACCESS_MEDIA_LOCATION", PhotoPlatform(29).mediaLocationPermission)
    }
}
