package com.maxdevlab.cleaner.photo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maxdevlab.cleaner.photo.PhotoState
import com.maxdevlab.cleaner.photo.PhotoViewModel
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.images.PreviewSize
import kotlinx.coroutines.launch

@Composable private fun CleanupHeader(title: String, settings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        DesignText(title, Modifier.weight(1f), size = 28, weight = FontWeight.Bold, spacing = -.9f)
        IconButton(settings) { Icon(Icons.Outlined.Tune, "Settings", tint = Accent) }
    }
}
@Composable private fun ScanStatus(state: PhotoState, model: PhotoViewModel) {
    if (state.scanning && state.scanTotal > 0) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        DesignText("Analyzing photos · ${state.scanned} / ${state.scanTotal}", color = Muted, size = 12)
        Spacer(Modifier.height(9.dp))
        LinearProgressIndicator(progress = { state.scanned.toFloat() / state.scanTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = Accent)
    } else if (state.scanError != null) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        DesignText(state.scanError, Modifier.weight(1f), color = Muted)
        DesignIconAction(Icons.Outlined.Refresh, "Retry", Modifier.width(64.dp)) { model.scanCleanup(force = true) }
    } else if (state.scanSkipped > 0) DesignText("${state.scanSkipped} unavailable photos skipped", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), size = 12, color = Muted)
}
/** Show one actionable category at a time; never add overlapping groups or screenshots to savings. */
@Composable fun SmartCleanupScreen(state: PhotoState, model: PhotoViewModel, settings: () -> Unit, requestAccess: () -> Unit) {
    val duplicates = remember(state.cleanupGroups) { state.cleanup(CleanupKind.DUPLICATE).flatMap { it.extras }.distinctBy { it.uri } }
    val bursts = remember(state.cleanupGroups) { state.cleanup(CleanupKind.BURST).flatMap { it.extras }.distinctBy { it.uri } }
    val ready = state.scanComplete && !state.scanning && state.scanError == null
    val recommended = if (duplicates.isNotEmpty()) CleanupKind.DUPLICATE else CleanupKind.BURST
    val candidates = if (recommended == CleanupKind.DUPLICATE) duplicates else bursts
    Column(Modifier.fillMaxSize()) {
        CleanupHeader("Tidy It", settings)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp)).background(Color(0xFFE6EEFB)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DesignText("MAKE ROOM FOR MORE", size = 11, color = Accent, weight = FontWeight.SemiBold, spacing = 1f)
                    DesignText(when {
                        state.scanning -> "Finding a quick cleanup…"
                        state.scanError != null -> "Let's finish your scan"
                        !ready -> "Find your first cleanup"
                        candidates.isNotEmpty() -> "Up to ${formatBytes(candidates.sumOf { it.bytes })} to free up"
                        state.scanSkipped > 0 -> "No extras found so far"
                        else -> "No extra copies found"
                    }, size = 29, weight = FontWeight.SemiBold, spacing = -.8f)
                    DesignText(when {
                        state.scanning -> "Checking your photos on this device. You can browse screenshots while we scan."
                        state.scanError != null -> "The scan was interrupted. Try again to see your cleanup suggestions."
                        !ready -> "Find duplicate and burst photos without sorting your whole library."
                        candidates.isNotEmpty() -> "${candidates.size} ${if (recommended == CleanupKind.DUPLICATE) "duplicate" else "burst"} extras to review. One photo in each group stays unselected."
                        else -> "You can still review old screenshots or sort a month at your own pace."
                    }, size = 13, color = Muted)
                    if (ready && candidates.isNotEmpty()) {
                        DesignButton(if (recommended == CleanupKind.DUPLICATE) "Review duplicates" else "Review burst photos", Modifier.fillMaxWidth(), enabled = !state.busy) {
                            model.navigate(if (recommended == CleanupKind.DUPLICATE) "duplicates" else "bursts")
                        }
                    } else if (!state.scanning && !ready) {
                        DesignButton("Scan photos", Modifier.fillMaxWidth(), enabled = !state.busy) { model.scanCleanup(force = true) }
                    } else if (ready && state.screenshots.isNotEmpty()) {
                        DesignButton("Review screenshots", Modifier.fillMaxWidth()) { model.navigate("screenshots") }
                    } else if (ready) {
                        DesignButton("Browse by month", Modifier.fillMaxWidth()) { model.navigate("home") }
                    }
                    DesignText("Nothing is deleted until you review and confirm.", size = 11, color = Muted)
                }
            }
            item { ScanStatus(state, model) }
            if (state.access == Access.SELECTED) item {
                Column {
                    DesignText("Showing only photos you've allowed. Results may cover part of your library.", size = 12, color = Muted)
                    DesignTextButton("Manage photo access", onClick = requestAccess)
                }
            }
            item { DesignText("QUICK CLEANUP", size = 11, color = Muted, spacing = 1f) }
            items(CleanupKind.entries) { kind ->
                val extras = if (kind == CleanupKind.DUPLICATE) duplicates else bursts
                CleanupEntry(kind.title, when {
                    !ready -> if (state.scanning) "Scanning…" else "Scan needed"
                    extras.isNotEmpty() -> "${extras.size} extras · up to ${formatBytes(extras.sumOf { it.bytes })}"
                    state.scanSkipped > 0 -> "No extras in checked photos"
                    else -> "No extras found"
                }, if (kind == CleanupKind.DUPLICATE) Icons.Outlined.Collections else Icons.Outlined.BurstMode) {
                    model.navigate(if (kind == CleanupKind.DUPLICATE) "duplicates" else "bursts")
                }
            }
            item {
                CleanupEntry("Review old screenshots", "${state.screenshots.size} screenshots · ${formatBytes(state.screenshots.sumOf { it.bytes })} total\nChoose what you no longer need.", Icons.Outlined.Screenshot) { model.navigate("screenshots") }
            }
            item {
                DesignText("AT YOUR OWN PACE", Modifier.padding(top = 8.dp), size = 11, color = Muted, spacing = 1f)
            }
            item { CleanupEntry("Sort by month", "Swipe through photos and videos, one month at a time.", Icons.Outlined.CalendarMonth) { model.navigate("home") } }
            item { DesignIconAction(Icons.Outlined.Refresh, "Scan again", Modifier.fillMaxWidth(), enabled = !state.scanning && !state.busy) { model.scanCleanup(force = true) } }
        }
    }
}

@Composable private fun CleanupEntry(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
        .clickable(role = Role.Button, onClick = action).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(28.dp), tint = Accent)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DesignText(title, size = 16, weight = FontWeight.SemiBold)
            DesignText(subtitle, size = 12, color = Muted)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}

@Composable fun ScreenshotsScreen(state: PhotoState, model: PhotoViewModel, settings: () -> Unit, requestAccess: () -> Unit) {
    val photos = state.screenshots
    val groups = remember(photos, state.records) { ReviewRules.groups(photos, state.records) }
    val remaining = photos.count { ReviewRules.record(it, state.records)?.decision == null }
    Column(Modifier.fillMaxSize()) {
        CleanupHeader("Screenshots", settings)
        if (photos.isEmpty()) {
            DesignEmpty("No screenshots", "Screenshots in your accessible library appear here.", Modifier.weight(1f).fillMaxWidth()) {
                DesignButton(if (state.access == Access.SELECTED) "Manage photo access" else "Refresh library", onClick = if (state.access == Access.SELECTED) requestAccess else { { model.refresh(); Unit } })
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                if (remaining == 0) {
                    SortedSummary("All screenshots sorted", photoCount(photos.size)) {
                        DesignButton("Review again", Modifier.fillMaxWidth(), onClick = model::openScreenshots)
                    }
                } else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp)).background(Color(0xFFE6EEFB)).padding(22.dp)) {
                    Row(Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(12.dp)), horizontalArrangement = Arrangement.Center) {
                        photos.take(3).forEachIndexed { i, photo -> PhotoImage(photo, Modifier.padding(4.dp).weight(1f).height(174.dp)
                            .graphicsLayer { rotationZ = (i - 1) * 7f }.clip(RoundedCornerShape(10.dp)).border(3.dp, Color.White, RoundedCornerShape(10.dp)), ContentScale.Fit) }
                    }
                    Spacer(Modifier.height(17.dp)); DesignText("Keep what matters.", size = 30, weight = FontWeight.Medium, spacing = -1f)
                    Spacer(Modifier.height(10.dp)); DesignText("${photos.size} screenshots · ${formatBytes(photos.sumOf { it.bytes })}", color = Muted)
                    Spacer(Modifier.height(6.dp)); DesignText("Swipe up to keep. Down to remove.", color = Muted)
                    Spacer(Modifier.height(22.dp)); DesignButton(if (remaining == photos.size) "Start sorting" else "Continue sorting", Modifier.fillMaxWidth(), onClick = model::openScreenshots)
                }
            }
            if (state.access == Access.SELECTED) item { DesignTextButton("Selected photos · Manage access", onClick = requestAccess) }
            item { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { DesignText("BY MONTH", Modifier.weight(1f), size = 11, color = Muted); DesignText("$remaining left to sort", size = 12, color = Muted) } }
            items(groups, key = { it.id }) { group ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color.White)
                    .clickable(role = Role.Button) { model.openMonth(group.id, "screenshots") }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoImage(group.photos.first(), Modifier.size(47.dp, 61.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DesignText("${group.title} ${group.year}", size = 14, weight = FontWeight.Medium)
                        DesignText("${group.photos.size} screenshots · ${group.reviewed} sorted", size = 12, color = Muted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Accent)
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable fun CleanupGroupsScreen(state: PhotoState, model: PhotoViewModel, selected: Set<String>, select: (Set<String>) -> Unit, confirm: () -> Unit) {
    val kind = if (state.screen == "duplicates") CleanupKind.DUPLICATE else CleanupKind.BURST
    val groups = state.cleanup(kind)
    val photos = remember(groups) { groups.flatMap { it.photos } }
    val extras = remember(groups) { groups.flatMap { it.extras }.map { it.uri }.toSet() }
    var previewUri by remember(kind) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(kind.title, "Back to cleanup", model::back)
        ScanStatus(state, model)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            DesignText("${groups.count { it.photos.size > 1 }} groups · ${photos.size} photos", Modifier.weight(1f), size = 12, color = Muted)
            DesignText("★ Best kept by default", Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFE5F3EC)).padding(8.dp), size = 11, color = Color(0xFF32825C))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            DesignText("Selected photos will be deleted", Modifier.weight(1f), size = 11, color = Muted)
            DesignTextButton(if (extras.isNotEmpty() && selected.containsAll(extras)) "Deselect extras" else "Select extras", enabled = !state.busy && extras.isNotEmpty()) {
                select(if (selected.containsAll(extras)) selected - extras else selected + extras)
            }
        }
        if (groups.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!state.scanning && state.scanComplete) DesignEmpty("All cleaned up", "No ${if (kind == CleanupKind.DUPLICATE) "duplicate" else "burst"} photos found in your accessible library.")
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp)) {
            groups.forEach { group ->
                item(key = "heading:${group.id}") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp)).background(Color.White).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            DesignText(group.photos.first().dateLabel, size = 12)
                            DesignText(if (group.photos.size == 1) "✓ Cleaned · 1 photo kept" else "${group.photos.size} photos · ${if (kind == CleanupKind.BURST) "Burst sequence" else "Same image"}", size = 11, color = Muted)
                        }
                        if (group.photos.size > 1) {
                            val ids = group.photos.map { it.uri }.toSet()
                            DesignTextButton(if (selected.containsAll(ids)) "Deselect" else "Select all", enabled = !state.busy) { select(if (selected.containsAll(ids)) selected - ids else selected + ids) }
                        }
                    }
                }
                items(group.photos.chunked(3), key = { "${group.id}:${it.first().uri}" }) { row ->
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { photo ->
                            CleanupTile(photo, photo.uri == group.bestUri, photo.uri in selected, !state.busy, Modifier.weight(1f),
                                preview = { previewUri = photo.uri }, toggle = { select(if (photo.uri in selected) selected - photo.uri else selected + photo.uri) })
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item(key = "footer:${group.id}") {
                    Spacer(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(bottomStart = 19.dp, bottomEnd = 19.dp)).background(Color.White))
                    Spacer(Modifier.height(17.dp))
                }
            }
        }
        if (state.busy) DeletionProgressPanel(model)
        Column(Modifier.fillMaxWidth().background(Color.White).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DesignText("${selected.size} selected · ${formatBytes(photos.filter { it.uri in selected }.sumOf { it.bytes })}", size = 12, color = Muted)
            Spacer(Modifier.height(10.dp)); DesignButton("Delete ${photoCount(selected.size)}", Modifier.fillMaxWidth(), enabled = !state.busy && !state.scanning && selected.isNotEmpty(), background = Destructive, onClick = confirm)
        }
    }
    previewUri?.let { uri ->
        if (photos.any { it.uri == uri }) CleanupPhotoPreview(photos, groups, uri, groups.map { it.bestUri }.toSet(), selected, select) { previewUri = null }
    }
}
@Composable private fun CleanupTile(photo: Photo, best: Boolean, picked: Boolean, enabled: Boolean, modifier: Modifier, preview: () -> Unit, toggle: () -> Unit) {
    Box(modifier.aspectRatio(.8f).clip(RoundedCornerShape(10.dp)).background(Soft)
        .border(if (picked) 2.dp else 0.dp, if (picked) Accent else Color.Transparent, RoundedCornerShape(10.dp))) {
        Box(Modifier.fillMaxSize().clickable(enabled = enabled, role = Role.Button, onClick = preview)
            .semantics { contentDescription = "Preview ${photo.name}" }) { PhotoImage(photo, Modifier.fillMaxSize()) }
        if (best) DesignText("★ Best", Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFFFF1)).padding(5.dp), size = 10, color = Color(0xFF65532B))
        Box(Modifier.align(Alignment.BottomEnd).size(48.dp).clickable(enabled = enabled, role = Role.Checkbox, onClick = toggle)
            .semantics { contentDescription = "Select ${photo.name} for deletion"; selected = picked }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(23.dp).clip(CircleShape).background(if (picked) Accent else Color.Black.copy(alpha = .3f)).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                if (picked) Icon(DesignIcons.Check, null, Modifier.size(14.dp), tint = Color.White)
            }
        }
    }
}
@Composable private fun CleanupPhotoPreview(photos: List<Photo>, groups: List<CleanupGroup>, uri: String, best: Set<String>, selected: Set<String>, select: (Set<String>) -> Unit, dismiss: () -> Unit) {
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.uri == uri }.coerceAtLeast(0), pageCount = { photos.size })
        val scope = rememberCoroutineScope()
        val photo = photos[pager.currentPage.coerceAtMost(photos.lastIndex)]
        val group = groups.first { group -> group.photos.any { it.uri == photo.uri } }
        val groupIndex = group.photos.indexOfFirst { it.uri == photo.uri }
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DesignRound(Icons.Outlined.Close, "Close preview", tint = Color.White, onClick = dismiss)
                DesignText("${groupIndex + 1} / ${group.photos.size}", Modifier.weight(1f), color = Color.White, align = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { photos[it].uri }) { page ->
                PhotoImage(photos[page], Modifier.fillMaxSize(), ContentScale.Fit, Color.Black, PreviewSize(1600, 1600))
            }
            DesignText("${photo.dateLabel} · ${formatBytes(photo.bytes)}${if (photo.uri in best) " · ★ Best" else ""}", Modifier.align(Alignment.CenterHorizontally).padding(12.dp), color = Muted, size = 12)
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DesignRound(DesignIcons.Back, "Previous photo", enabled = pager.currentPage > 0, tint = Color.White) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
                val picked = photo.uri in selected
                RemovalCheckbox(picked, Modifier.weight(1f)) { checked -> select(if (checked) selected + photo.uri else selected - photo.uri) }
                DesignRound(Icons.Outlined.ChevronRight, "Next photo", enabled = pager.currentPage < photos.lastIndex, tint = Color.White) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            }
        }
    }
}
