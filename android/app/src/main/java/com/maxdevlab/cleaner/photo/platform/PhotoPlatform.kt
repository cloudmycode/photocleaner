package com.maxdevlab.cleaner.photo.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.maxdevlab.cleaner.photo.data.Access
import com.maxdevlab.cleaner.photo.data.DeleteMode

/** All photo permission and query capabilities are derived here, not in screens. */
data class PhotoPlatform(val sdk: Int) {
    val deleteMode get() = when { sdk >= 30 -> DeleteMode.BATCH; sdk == 29 -> DeleteMode.SCOPED; else -> DeleteMode.LEGACY }
    val supportsVolumes get() = sdk >= 29
    val supportsTrash get() = sdk >= 30
    val supportsSelectedPhotos get() = sdk >= 34
    val mediaLocationPermission: String? get() = if (sdk >= 29) Manifest.permission.ACCESS_MEDIA_LOCATION else null
    val requestsNotificationPermission get() = sdk >= 33
    val readPermissions: Array<String> get() = when {
        sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val accessExplanation get() = if (supportsSelectedPhotos) "You can allow all photos or choose specific photos." else "You can change photo access in Android settings."
    val deleteExplanation get() = when (deleteMode) {
        DeleteMode.LEGACY -> "Continuing permanently deletes the selected photos. Android may first ask for storage permission. There is no additional system deletion confirmation on this version of Android."
        DeleteMode.SCOPED -> "Android may ask you to approve each photo separately. Canceling stops the remaining deletions; photos already deleted cannot be restored by this app."
        DeleteMode.BATCH -> "Android will ask you to approve permanent deletion."
    }
    companion object { fun current() = PhotoPlatform(Build.VERSION.SDK_INT) }
}
class PhotoAccessPolicy(private val context: Context, val platform: PhotoPlatform = PhotoPlatform.current()) {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun access(): Access = when {
        platform.sdk >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        platform.supportsSelectedPhotos && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SELECTED
        platform.sdk <= 32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        else -> Access.NONE
    }
    fun needsMediaLocationPermission() = platform.mediaLocationPermission?.let { !granted(it) } ?: false
    fun needsWritePermission() = platform.deleteMode == DeleteMode.LEGACY && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}
