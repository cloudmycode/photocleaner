package com.maxdevlab.cleaner.photo

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import com.maxdevlab.cleaner.photo.ads.CleanupAds
import kotlinx.coroutines.flow.MutableStateFlow

/** Deterministic SDK boundary for deletion tests; never contacts an advertising network. */
class TestCleanupAds : CleanupAds {
    override val privacyOptionsRequired = MutableStateFlow(false)
    var ready = false
    var failToShow = false
    var attempts = 0
    var dialog: AlertDialog? = null
    override fun prepare(activity: ComponentActivity, canShowConsent: () -> Boolean) = Unit
    override fun showIfReady(activity: ComponentActivity, finished: () -> Unit) {
        attempts++
        if (failToShow) error("Simulated ad presentation failure")
        if (!ready) { finished(); return }
        ready = false
        dialog = AlertDialog.Builder(activity).setTitle("Test interstitial")
            .setMessage("Only a test double. No real advertising.")
            .setPositiveButton("Close test ad") { _, _ -> }
            .create().apply { setOnDismissListener { finished() }; show() }
    }
    override fun showPrivacyOptions(activity: ComponentActivity) = Unit
}
