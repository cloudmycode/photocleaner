package com.maxdevlab.cleaner.photo.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PhotoLocationStatus { AVAILABLE, NOT_RECORDED, PERMISSION_REQUIRED, UNAVAILABLE }
data class PhotoDetails(
    val name: String, val folder: String? = null, val bytes: Long = 0,
    val dimensions: String? = null, val type: String? = null,
    val taken: String? = null, val modified: String? = null, val added: String? = null,
    val camera: String? = null, val coordinates: String? = null,
    val locationStatus: PhotoLocationStatus = PhotoLocationStatus.UNAVAILABLE,
    val unavailable: Boolean = false
)
object PhotoDetailFormat {
    private val date = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm z", Locale.ENGLISH)
    fun date(milliseconds: Long): String? = milliseconds.takeIf { it > 0 }?.let {
        date.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    fun size(bytes: Long): String = when {
        bytes <= 0 -> "Not provided"
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(Locale.ENGLISH, "%.1f KB", bytes / 1_000.0)
        bytes < 1_000_000_000 -> String.format(Locale.ENGLISH, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.ENGLISH, "%.2f GB", bytes / 1_000_000_000.0)
    }
    fun coordinates(latitude: Double, longitude: Double): String? =
        if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
            String.format(Locale.ENGLISH, "%.5f, %.5f", latitude, longitude) else null
}
