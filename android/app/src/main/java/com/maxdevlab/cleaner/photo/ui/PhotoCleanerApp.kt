@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.maxdevlab.cleaner.photo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxdevlab.cleaner.photo.*
import com.maxdevlab.cleaner.photo.data.*

@Composable fun PhotoCleanerApp(model: PhotoViewModel, requestPhotos: () -> Unit, openAppSettings: () -> Unit,
    requestReminder: (Boolean) -> Unit, rateApp: () -> Unit, openPrivacy: () -> Unit, metadataRevision: Int = 0,
    requestPhotoLocation: () -> Unit = {}, sharePhoto: suspend (Photo) -> Unit = {},
    adPrivacyOptions: kotlinx.coroutines.flow.StateFlow<Boolean>? = null,
    openAdPrivacy: () -> Unit = {}, prepareAds: () -> Unit = {}) {
    CleanerTheme {
        val state by model.state.collectAsStateWithLifecycle()
        val adPrivacyRequired = adPrivacyOptions?.collectAsStateWithLifecycle()?.value == true
        LaunchedEffect(state.screen) { if (state.screen in listOf("smart", "home")) prepareAds() }
        var settings by rememberSaveable { mutableStateOf(false) }
        var privacy by rememberSaveable { mutableStateOf(false) }
        var reset by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(model.cleanupSelection.selected) }
        var confirmDelete by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<List<Photo>>(emptyList()) }
        var pendingDeleteCount by remember { mutableStateOf(0) }
        fun askToDelete() {
            pendingDelete = if (state.screen in listOf("duplicates", "bursts")) state.cleanupCandidates(selected)
                else ReviewRules.deleteCandidates(state.photos, state.records, selected)
            pendingDeleteCount = selected.size
            confirmDelete = true
        }
        LaunchedEffect(state.message) { if (state.message != null) { delay(2500); model.clearMessage() } }
        var selectionScreen by remember { mutableStateOf(model.cleanupSelection.screen) }
        var knownGroupPhotos by remember { mutableStateOf(model.cleanupSelection.known) }
        val selectionSnapshot = CleanupSelection(selectionScreen, selected, knownGroupPhotos)
        SideEffect { model.cleanupSelection = selectionSnapshot }
        LaunchedEffect(state.screen, state.cleanupGroups, state.marked, state.access, state.scanComplete) {
            val grouped = state.screen in listOf("duplicates", "bursts")
            if (selectionScreen != state.screen) { selected = emptySet(); knownGroupPhotos = emptySet(); selectionScreen = state.screen }
            if (state.access == Access.NONE) { selected = emptySet(); knownGroupPhotos = emptySet() }
            else if (grouped) {
                val groups = state.cleanup(if (state.screen == "duplicates") CleanupKind.DUPLICATE else CleanupKind.BURST)
                val available = groups.flatMap { it.photos }.map { it.uri }.toSet()
                val extras = groups.flatMap { it.extras }.map { it.uri }.toSet()
                if (available.isNotEmpty() || state.scanComplete) {
                    selected = selected.intersect(available) + (extras - knownGroupPhotos)
                    knownGroupPhotos = available
                }
            } else selected = selected.intersect(state.marked.map { it.uri }.toSet())
        }
        BackHandler(enabled = state.screen != "smart" && state.access != Access.NONE && !settings && !privacy && !confirmDelete) { model.back() }
        val reviewing = state.screen == "review" && state.access != Access.NONE
        SystemBars(reviewing)
        Scaffold(contentWindowInsets = WindowInsets.displayCutout, containerColor = if (reviewing) Color.Black else Paper, snackbarHost = {
            state.message?.let { text ->
                Box(Modifier.padding(horizontal = 24.dp, vertical = 11.dp).background(Color(0xED2C2C2E), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = .13f), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    DesignText(text, size = 12, color = Color.White)
                }
            }
        }, bottomBar = {
            if (state.access != Access.NONE && state.screen in listOf("home", "smart", "screenshots", "trash", "finish")) DesignTabs(if (state.screen == "finish") state.reviewSource else state.screen, state.marked.size, model::navigate)
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.access == Access.NONE -> PermissionScreen(requestPhotos, openAppSettings, { privacy = true }, { settings = true }, model.platform.accessExplanation)
                    state.screen == "smart" -> SmartCleanupScreen(state, model, { settings = true }, requestPhotos)
                    state.screen == "screenshots" -> ScreenshotsScreen(state, model, { settings = true }, requestPhotos)
                    state.screen in listOf("duplicates", "bursts") -> CleanupGroupsScreen(state, model, selected, { selected = it }, { askToDelete() })
                    state.screen == "review" -> ReviewScreen(state, model, metadataRevision, requestPhotoLocation, sharePhoto)
                    state.screen == "trash" -> TrashScreen(state, selected, { selected = it }, model, { askToDelete() })
                    state.screen == "finish" -> CompletionScreen(state, model)
                    state.screen == "cleanupTransition" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DesignText("Cleanup finished", color = Muted)
                    }
                    state.screen == "deleted" -> DeletionResultScreen(state, model, rateApp)
                    else -> HomeScreen(state, model, requestPhotos, { settings = true })
                }
            }
        }
        if (settings && !privacy) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = Paper) {
            SystemBars(false)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("At your own pace.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("A little photo cleanup, at your own pace.", color = Muted, fontSize = 13.sp)
                SettingRow(Icons.Outlined.NotificationsNone, "Monthly reminder", "First of each month, around 8 PM") {
                    Switch(state.reminder, requestReminder, modifier = Modifier.semantics { contentDescription = "Monthly reminder" })
                }
                SettingRow(Icons.Outlined.VolumeUp, "Cleanup sounds", "A soft sound when cleanup is complete") {
                    Switch(state.cleanupSoundsEnabled, model::setCleanupSounds,
                        modifier = Modifier.semantics { contentDescription = "Cleanup sounds" })
                }
                SettingsAction(Icons.Outlined.PhotoLibrary, "Photo access", when (state.access) { Access.FULL -> "All photos and videos"; Access.SELECTED -> "Limited photos or videos"; Access.NONE -> "Not allowed" }) { settings = false; openAppSettings() }
                SettingsAction(Icons.Outlined.StarOutline, "Rate Tidy It", "Share your feedback on Google Play", rateApp)
                SettingsAction(Icons.Outlined.Shield, "Privacy policy", "Your photos stay on your device") { privacy = true }
                if (adPrivacyRequired) SettingsAction(Icons.Outlined.Tune, "Ad privacy choices", "Manage your advertising privacy choices", openAdPrivacy)
                SettingsAction(Icons.Outlined.RestartAlt, "Reset review progress", "Your photos will not be deleted") { reset = true }
                Text("Tidy It ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(10.dp))
                DesignButton("Done", Modifier.fillMaxWidth()) { settings = false }
            }
        }
        if (privacy) ModalBottomSheet(onDismissRequest = { privacy = false }, containerColor = Paper) {
            SystemBars(false)
            Column {
                PageHeader("Privacy policy", if (settings) "Back to settings" else "Back", back = { privacy = false })
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your photos. Your device.", style = MaterialTheme.typography.headlineSmall)
                Text("Tidy It uses photo and video access to show a monthly timeline, identify screenshots, group duplicate and burst photos on your device, display photo previews and play local videos, and help you choose what to keep or delete. Selected-photo access is supported on newer Android versions. Older versions use storage permission to read photos, and Android 8.1–9 also require storage write permission to delete them. The app uses these permissions to access photos and videos you allow.")
                Text("Photos and their metadata are processed on this device. Tidy It has no account or photo upload service. Photos, photo locations and review history are not sent to advertising services. Sharing a photo sends only the photo you choose to the app you select.")
                Text("Tidy It uses Google AdMob to show ads after eligible cleanups. The advertising SDK may collect and share IP addresses (including approximate location), device identifiers, ad interactions and diagnostics for advertising, measurement and fraud prevention. Where required, you can manage consent in Ad privacy choices in settings. Ads are separate from photo processing.")
                Text("Opening photo details reads file information and, if you choose to allow photo location access, GPS coordinates saved in the photo. This does not access your current device location. These details stay on your device and are not uploaded.")
                Text("Review decisions and in-app favorites are stored locally. Thumbnails may be cached locally. Reset review progress clears decisions and favorites; Android app settings can clear all app data. Uninstalling removes app data, but does not restore photos you deleted.")
                Text("Marking a photo for removal does not delete it. Deletion requires your confirmation. ${model.platform.deleteExplanation} Permanent deletion may not be recoverable. You can revoke photo or notification access in Android settings at any time.")
                Text("Monthly reminders are optional and may arrive later due to battery settings. Google Play or your browser handles the rating page under its own privacy policy.")
                if (BuildConfig.PUBLISHER_NAME.isNotBlank()) Text("Publisher: ${BuildConfig.PUBLISHER_NAME}")
                if (BuildConfig.SUPPORT_EMAIL.isNotBlank()) Text("Privacy contact: ${BuildConfig.SUPPORT_EMAIL}")
                else if (BuildConfig.DEBUG) Text("Development build: configure the publisher contact and public privacy policy before distribution.", color = Muted)
                if (BuildConfig.PRIVACY_URL.isNotBlank()) DesignTextButton("Read the public privacy policy", onClick = openPrivacy)
                DesignButton(if (settings) "Back to settings" else "Done", Modifier.fillMaxWidth()) { privacy = false }
                }
            }
        }
        if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { SystemBars(false); Text("Reset review progress?") }, text = { Text("This clears your review decisions and in-app favorites. It does not delete photos or undo previous deletions.") }, confirmButton = {
            DesignTextButton("Reset progress", enabled = !state.busy, color = Destructive) { reset = false; settings = false; model.resetProgress() }
        }, dismissButton = { DesignTextButton("Cancel") { reset = false } })
        if (confirmDelete) {
            val available = state.photos.map { PhotoIdentity(it.uri, it.signature) }.toSet()
            val candidates = pendingDelete.filter { PhotoIdentity(it.uri, it.signature) in available }
            val confirmedUris = candidates.map { it.uri }.toSet()
            val includesBest = state.screen in listOf("duplicates", "bursts") && state.cleanup(if (state.screen == "duplicates") CleanupKind.DUPLICATE else CleanupKind.BURST).any { it.bestUri in confirmedUris }
            DesignSheet("Delete ${mediaCount(candidates)}?",
                "${formatBytes(candidates.sumOf { it.bytes })} · Permanent deletion. Review your selection first.\n\n${model.platform.deleteExplanation}" +
                    (if (includesBest) "\n\nYour selection includes a recommended best photo." else "") +
                    if (pendingDeleteCount > ReviewRules.DELETE_LIMIT) "\n\nOnly the first 2,000 selected photos are included." else "",
                dismiss = { confirmDelete = false }) {
                DesignButton(if (candidates.any { it.isVideo }) "Delete media" else "Delete photos", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !state.busy && candidates.isNotEmpty(), background = Destructive) {
                    confirmDelete = false; model.requestDelete(confirmedUris, candidates.map { PhotoIdentity(it.uri, it.signature) }.toSet())
                }
                DesignButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp), background = Soft, color = Accent) { confirmDelete = false }
            }
        }
    }
}
@Composable private fun SettingRow(icon: ImageVector, title: String, description: String, trailing: @Composable () -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Accent, modifier = Modifier.padding(end = 12.dp)); Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp); Text(description, fontSize = 11.sp, color = Muted) }; trailing()
} }
@Composable private fun SettingsAction(icon: ImageVector, title: String, description: String, action: () -> Unit) { Column(Modifier.clickable(role = Role.Button, onClick = action)) { SettingRow(icon, title, description) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Muted, modifier = Modifier.size(18.dp)) }; HorizontalDivider(color = Soft) } }
