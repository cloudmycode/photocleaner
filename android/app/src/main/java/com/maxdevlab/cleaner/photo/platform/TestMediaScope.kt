package com.maxdevlab.cleaner.photo.platform

import android.content.Context
import com.maxdevlab.cleaner.photo.BuildConfig

/** A separately named debug test app can only display/delete explicitly registered fixture URIs. */
class TestMediaScope(context: Context) {
    private val prefs = context.getSharedPreferences("isolated_test_media", Context.MODE_PRIVATE)
    val enabled get() = BuildConfig.ISOLATED_TEST_BUILD
    fun names(): Set<String> = prefs.getStringSet("names", emptySet()).orEmpty().toSet()
    fun allows(uri: String): Boolean = !enabled || uri in prefs.getStringSet("uris", emptySet()).orEmpty()
}
