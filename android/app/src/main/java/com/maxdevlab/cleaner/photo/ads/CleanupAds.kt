package com.maxdevlab.cleaner.photo.ads

import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.StateFlow

/** Ad SDK stays behind this boundary; deletion and its receipts never depend on ad callbacks. */
interface CleanupAds {
    val privacyOptionsRequired: StateFlow<Boolean>
    fun prepare(activity: ComponentActivity, canShowConsent: () -> Boolean)
    fun showIfReady(activity: ComponentActivity, finished: () -> Unit)
    fun showPrivacyOptions(activity: ComponentActivity)
}
