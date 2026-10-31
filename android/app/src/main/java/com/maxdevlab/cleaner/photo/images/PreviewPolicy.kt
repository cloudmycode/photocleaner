package com.maxdevlab.cleaner.photo.images

import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PreviewSize(val width: Int, val height: Int)

/** A screen preview, never an original-size decode. Original photo files are untouched. */
object PreviewPolicy {
    const val MAX_EDGE = 2048
    fun size(width: Int, height: Int): PreviewSize? {
        if (width <= 0 || height <= 0) return null
        val ratio = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
        return PreviewSize((width * ratio).roundToInt().coerceAtLeast(1), (height * ratio).roundToInt().coerceAtLeast(1))
    }
    /** One current-photo detail bitmap, bounded by both dimensions and a fraction of the heap. */
    fun detailSize(width: Int, height: Int, memoryClassMb: Int): PreviewSize? {
        if (width <= 0 || height <= 0) return null
        val bytes = (memoryClassMb.toLong() * 1024 * 1024 / 16).coerceIn(2L * 1024 * 1024, 24L * 1024 * 1024)
        val ratio = minOf(1.0, 4096.0 / maxOf(width, height), sqrt(bytes / 4.0 / width / height))
        return PreviewSize((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
    }
    fun cacheBytes(memoryClassMb: Int): Int = (memoryClassMb.toLong() * 1024 * 1024 / 5)
        .coerceIn(1, 48L * 1024 * 1024).toInt()
}
