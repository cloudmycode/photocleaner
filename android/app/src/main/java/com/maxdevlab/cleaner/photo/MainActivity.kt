package com.maxdevlab.cleaner.photo

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.maxdevlab.cleaner.photo.data.DeleteAwaiting
import com.maxdevlab.cleaner.photo.ui.PhotoCleanerApp
import com.maxdevlab.cleaner.photo.platform.enterFullscreen
import com.maxdevlab.cleaner.photo.platform.CleanupSoundPlayer
import com.maxdevlab.cleaner.photo.platform.PhotoShare
import com.maxdevlab.cleaner.photo.data.Photo
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Android result bridge only. Version-specific photo behavior lives in platform/. */
class MainActivity : ComponentActivity() {
    private val model: PhotoViewModel by viewModels()
    private val cleanupAds by lazy { (application as PhotoCleanerApplication).cleanupAds() }
    private var metadataRevision by mutableIntStateOf(0)
    private var locationAsked = false
    private val mediaLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        metadataRevision++
        if (!granted) model.message("Photo location access was not granted. Other details are still available.")
    }
    private var observerJob: Job? = null
    private var pendingDeleteId: String? = null
    private var pendingDeleteKind: DeleteAwaiting? = null
    private val photoPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.setReminder(granted) }
    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> deliverDeleteResult(granted) }
    private val deletion = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> deliverDeleteResult(result.resultCode == Activity.RESULT_OK) }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            observerJob?.cancel()
            observerJob = lifecycleScope.launch { delay(400); model.refresh() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationAsked = savedInstanceState?.getBoolean("location.asked") ?: false
        pendingDeleteId = savedInstanceState?.getString("delete.id")
        pendingDeleteKind = savedInstanceState?.getString("delete.kind")?.let { DeleteAwaiting.valueOf(it) }
        enableEdgeToEdge()
        window.enterFullscreen()
        runCatching { CleanupSoundPlayer(this) }.getOrNull()?.let { cleanupSound ->
            lifecycle.addObserver(cleanupSound)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    model.cleanupSounds.collect { if (model.state.value.cleanupSoundsEnabled) cleanupSound.play() }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.events.collect { event ->
                    try {
                        when (event) {
                            is PhotoEvent.CleanupReady -> {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.claimCleanupAd(event.id)) {
                                    cleanupAds.showIfReady(this@MainActivity) { model.finishCleanupPresentation(event.id) }
                                } else model.finishCleanupPresentation(event.id)
                            }
                            is PhotoEvent.WritePermission -> {
                                pendingDeleteId = event.id; pendingDeleteKind = DeleteAwaiting.WRITE_PERMISSION
                                writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                            is PhotoEvent.DeleteConsent -> {
                                pendingDeleteId = event.id; pendingDeleteKind = event.kind
                                deletion.launch(IntentSenderRequest.Builder(event.request).build())
                            }
                        }
                    } catch (_: Exception) {
                        if (event is PhotoEvent.CleanupReady) model.finishCleanupPresentation(event.id)
                        pendingDeleteId?.let(model::deleteLaunchFailed)
                        pendingDeleteId = null; pendingDeleteKind = null
                    }
                }
            }
        }
        setContent {
            PhotoCleanerApp(model, { photoPermission.launch(model.platform.readPermissions) },
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                { enabled -> if (enabled && model.platform.requestsNotificationPermission) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else model.setReminder(enabled) },
                ::rateApp, { if (BuildConfig.PRIVACY_URL.startsWith("https://")) openUrl(BuildConfig.PRIVACY_URL) },
                metadataRevision, ::requestPhotoLocation, ::sharePhoto, cleanupAds.privacyOptionsRequired,
                { cleanupAds.showPrivacyOptions(this) }, ::prepareAds)
        }
    }
    private fun prepareAds() {
        cleanupAds.prepare(this) {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.state.value.screen == "home" && !model.state.value.busy
        }
    }
    override fun onResume() { super.onResume(); model.setForeground(true); prepareAds() }
    override fun onPause() { model.setForeground(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.enterFullscreen()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("location.asked", locationAsked)
        outState.putString("delete.id", pendingDeleteId); outState.putString("delete.kind", pendingDeleteKind?.name)
        super.onSaveInstanceState(outState)
    }
    private fun requestPhotoLocation() {
        val permission = model.platform.mediaLocationPermission ?: return
        if (locationAsked && !shouldShowRequestPermissionRationale(permission)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            locationAsked = true
            mediaLocation.launch(permission)
        }
    }
    private suspend fun sharePhoto(photo: Photo) {
        try {
            val intent = PhotoShare.prepare(this, photo)
            val state = model.state.value
            val current = state.remaining.firstOrNull()
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || state.screen != "review" ||
                current?.uri != photo.uri || current.signature != photo.signature) return
            startActivity(intent)
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) {
            model.message("Photo access changed. Please check your photo permissions."); model.refresh()
        } catch (_: FileNotFoundException) {
            model.message("This photo changed or is no longer available."); model.refresh()
        } catch (_: ActivityNotFoundException) { model.message("No app is available to share this photo.") }
        catch (_: Exception) { model.message("Could not share this photo. Please try again.") }
    }
    private fun deliverDeleteResult(approved: Boolean) {
        val id = pendingDeleteId; val kind = pendingDeleteKind
        pendingDeleteId = null; pendingDeleteKind = null
        if (id != null && kind != null) model.deleteResult(id, kind, approved)
    }
    override fun onStart() { super.onStart(); metadataRevision++; contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer); model.refresh() }
    override fun onStop() { contentResolver.unregisterContentObserver(observer); observerJob?.cancel(); super.onStop() }
    private fun rateApp() {
        model.rateClicked()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) { openUrl("https://play.google.com/store/apps/details?id=$packageName") }
    }
    private fun openUrl(url: String): Boolean {
        return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
        catch (_: ActivityNotFoundException) { model.message("No app is available to open this link."); false }
    }
}
