package com.maxdevlab.cleaner.photo.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.maxdevlab.cleaner.photo.BuildConfig
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owner of a single preloaded ad. No load callback can present an ad. */
class AdMobCleanupAds(context: Context) : CleanupAds {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val consent by lazy { UserMessagingPlatform.getConsentInformation(this.context) }
    private val _privacyOptionsRequired = MutableStateFlow(false)
    override val privacyOptionsRequired = _privacyOptionsRequired.asStateFlow()
    private var consentRequested = false
    private var consentUpdated = false
    private var formShowing = false
    private var initializing = false
    private var initialized = false
    private var loading = false
    private var showing = false
    private var generation = 0
    private var ad: InterstitialAd? = null
    private var loadedAt = 0L
    private var lastLoadAttempt: Long? = null
    private val canRequest get() = BuildConfig.ADS_ENABLED && (BuildConfig.AD_TEST_MODE || consent.canRequestAds())

    override fun prepare(activity: ComponentActivity, canShowConsent: () -> Boolean) {
        if (!BuildConfig.ADS_ENABLED) return
        // Google's sample app ID has no publisher consent message. Only official test units bypass UMP.
        if (BuildConfig.AD_TEST_MODE) { initializeAndPreload(); return }
        if (!consentRequested) {
            consentRequested = true
            consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                consentUpdated = true
                updatePrivacyOptions()
                presentConsentIfSafe(activity, canShowConsent)
                initializeAndPreload()
            }, { updatePrivacyOptions(); initializeAndPreload() })
        } else if (consentUpdated) presentConsentIfSafe(activity, canShowConsent)
        initializeAndPreload()
    }
    private fun presentConsentIfSafe(activity: ComponentActivity, canShowConsent: () -> Boolean) {
        if (formShowing || showing || !activity.isResumed() || !canShowConsent()) return
        if (consent.consentStatus != ConsentInformation.ConsentStatus.REQUIRED) return
        formShowing = true
        UserMessagingPlatform.loadConsentForm(context, { form ->
            if (activity.isResumed() && canShowConsent()) {
                form.show(activity) { formShowing = false; updatePrivacyOptions(); initializeAndPreload() }
            } else formShowing = false
        }, { formShowing = false; updatePrivacyOptions(); initializeAndPreload() })
    }
    private fun updatePrivacyOptions() {
        _privacyOptionsRequired.value = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
    private fun initializeAndPreload() {
        if (!canRequest) return
        if (initialized) { preload(); return }
        if (initializing) return
        initializing = true
        MobileAds.initialize(context) {
            main.post { initialized = true; initializing = false; preload() }
        }
    }
    private fun preload() {
        if (!initialized || !canRequest || loading || showing || formShowing) return
        val now = SystemClock.elapsedRealtime()
        if (ad != null && now - loadedAt < MAX_AGE_MS) return
        ad = null
        if (lastLoadAttempt?.let { now - it < 30_000 } == true) return
        lastLoadAttempt = now; loading = true
        val requestGeneration = generation
        InterstitialAd.load(context, BuildConfig.ADMOB_INTERSTITIAL_ID, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(loaded: InterstitialAd) {
                if (requestGeneration != generation) return
                loading = false
                if (canRequest) { ad = loaded; loadedAt = SystemClock.elapsedRealtime(); lastLoadAttempt = null }
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                if (requestGeneration == generation) { loading = false; ad = null }
            }
        })
    }
    internal val hasReadyAd get() = ad != null && canRequest && SystemClock.elapsedRealtime() - loadedAt < MAX_AGE_MS

    override fun showIfReady(activity: ComponentActivity, finished: () -> Unit) {
        val ready = ad
        if (ready == null || !canRequest || showing || formShowing || !activity.isResumed() ||
            SystemClock.elapsedRealtime() - loadedAt >= MAX_AGE_MS) {
            finished(); initializeAndPreload(); return
        }
        ad = null; showing = true
        var delivered = false
        fun finish() {
            if (delivered) return
            delivered = true; showing = false
            finished(); preload()
        }
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finish()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finish()
        }
        runCatching { ready.show(activity) }.onFailure { finish() }
    }
    override fun showPrivacyOptions(activity: ComponentActivity) {
        if (!privacyOptionsRequired.value || showing || formShowing || !activity.isResumed()) return
        // Discard any ad loaded under earlier privacy choices, including in-flight responses.
        generation++; ad = null; loading = false; lastLoadAttempt = null; formShowing = true
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            formShowing = false; updatePrivacyOptions(); initializeAndPreload()
        }
    }
    private fun ComponentActivity.isResumed() = !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private companion object { const val MAX_AGE_MS = 55 * 60 * 1000L }
}
