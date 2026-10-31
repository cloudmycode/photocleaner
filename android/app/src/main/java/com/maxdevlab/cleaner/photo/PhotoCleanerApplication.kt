package com.maxdevlab.cleaner.photo

import android.app.ActivityManager
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.maxdevlab.cleaner.photo.images.PreviewPolicy

/** One bounded cache shared by thumbnails, the visible pair, and one upcoming preview. */
class PhotoCleanerApplication : Application(), ImageLoaderFactory {
    internal var cleanupAdSession = com.maxdevlab.cleaner.photo.ads.CleanupAdSession()
    internal var cleanupAds: com.maxdevlab.cleaner.photo.ads.CleanupAds? = null
    fun cleanupAds(): com.maxdevlab.cleaner.photo.ads.CleanupAds = cleanupAds
        ?: com.maxdevlab.cleaner.photo.ads.AdMobCleanupAds(this).also { cleanupAds = it }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            val memoryClass = getSystemService(ActivityManager::class.java).memoryClass
            MemoryCache.Builder(this).maxSizeBytes(PreviewPolicy.cacheBytes(memoryClass)).build()
        }
        .bitmapFactoryMaxParallelism(2)
        .crossfade(false)
        .build()
}
