package com.maxdevlab.cleaner.photo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxdevlab.cleaner.photo.*
import com.maxdevlab.cleaner.photo.data.*
import java.time.YearMonth

@Composable fun HomeScreen(state: PhotoState, model: PhotoViewModel, request: () -> Unit, settings: () -> Unit) {
    val groups = remember(state.photos, state.records, state.mediaFilter) { state.groups }
    val (completed, pending) = remember(groups) { groups.partition { it.completed } }
    var showCompleted by rememberSaveable(state.mediaFilter) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand(); Spacer(Modifier.weight(1f))
            IconButton(settings) { Icon(Icons.Outlined.Tune, "Settings", tint = Accent) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaFilter.entries.forEach { filter ->
                FilterChip(selected = state.mediaFilter == filter, onClick = { model.setMediaFilter(filter) },
                    label = { Text(when(filter) { MediaFilter.ALL -> "All"; MediaFilter.PHOTOS -> "Photos"; MediaFilter.VIDEOS -> "Videos" }) })
            }
        }
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pending.firstOrNull()?.let { group ->
                item(key = group.id, span = { GridItemSpan(maxLineSpan) }) {
                    FeaturedMonthCard(group, group == groups.first()) { model.openMonth(group.id) }
                }
            }
            if (completed.isNotEmpty()) {
                if (pending.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    SortedSummary("All months sorted", mediaCount(state.photos.filter(state.mediaFilter::includes)))
                }
                item(key = "completed-header", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                        .semantics { stateDescription = if (showCompleted) "Expanded" else "Collapsed" }
                        .clickable(role = Role.Button) { showCompleted = !showCompleted }.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(DesignIcons.Check, null, tint = Accent)
                        DesignText("Completed months", Modifier.weight(1f), size = 16, weight = FontWeight.Medium)
                        DesignText(completed.size.toString(), color = Muted)
                        Icon(if (showCompleted) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                }
                if (showCompleted) items(completed, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { group ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
                        .clickable(role = Role.Button) { model.openMonth(group.id) }.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhotoImage(group.photos.first(), Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f)) {
                            DesignText("${group.title} ${group.year}", size = 15, weight = FontWeight.Medium)
                            DesignText(mediaCount(group.photos), size = 12, color = Muted)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                    }
                }
            }
            if (state.access == Access.SELECTED) item(span = { GridItemSpan(maxLineSpan) }) {
                DesignTextButton("Limited media access · Manage access", Modifier.fillMaxWidth(), onClick = request)
            }
            items(pending.drop(1), key = { it.id }) { group ->
                MonthCard(group) { model.openMonth(group.id) }
            }
            if (groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    EmptyState("No media in this filter", "Add photos or choose more photos to get started.")
                    DesignIconAction(Icons.Outlined.Refresh, "Refresh library", Modifier.fillMaxWidth().padding(horizontal = 11.dp), enabled = !state.busy) { model.refresh() }
                }
            }
        }
    }
}
@Composable internal fun SortedSummary(title: String, detail: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(DesignIcons.Check, null, tint = Accent)
        DesignText(title, size = 23, weight = FontWeight.SemiBold)
        DesignText(detail, color = Muted)
        action()
    }
}
private fun monthStatus(group: MonthGroup) = when {
    group.completed -> "✓ Completed"
    group.reviewed > 0 -> "${group.reviewed} / ${group.photos.size} sorted"
    else -> mediaCount(group.photos) + " · " + formatBytes(group.photos.sumOf { it.bytes })
}
@Composable private fun FeaturedMonthCard(group: MonthGroup, latest: Boolean, click: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .clickable(role = Role.Button, onClickLabel = "Review ${group.title} ${group.year}", onClick = click)) {
        PhotoImage(group.photos.first(), Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .2f), Color.Black.copy(alpha = .65f)))))
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            DesignText(if (latest) "Latest month · ${group.year}" else group.year.toString(), size = 10, weight = FontWeight.Medium, color = Color.White)
            Spacer(Modifier.height(8.dp))
            DesignText(group.title, color = Color.White, size = 28, weight = FontWeight(550), spacing = -.5f)
            Spacer(Modifier.height(5.dp))
            DesignText(monthStatus(group), color = Color.White.copy(alpha = .9f), size = 12)
            Spacer(Modifier.height(18.dp))
            DesignButton(if (group.reviewed > 0) "Continue sorting" else "Start sorting",
                Modifier.fillMaxWidth(), onClick = click)
            Spacer(Modifier.height(12.dp))
            DesignProgress(group.fraction, Modifier.fillMaxWidth(), Color.White, Color.White.copy(alpha = .3f))
        }
    }
}
@Composable private fun MonthCard(group: MonthGroup, click: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(.8f).clip(RoundedCornerShape(18.dp))
        .clickable(role = Role.Button, onClickLabel = "Review ${group.title} ${group.year}", onClick = click)) {
        PhotoImage(group.photos.first(), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.4f to Color.Transparent, 1f to Color.Black.copy(alpha = .6f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 15.dp, end = 15.dp, bottom = 17.dp)) {
            DesignText(group.title, color = Color.White, size = 20, weight = FontWeight(550), spacing = -.5f)
            Spacer(Modifier.height(5.dp))
            DesignText(monthStatus(group), color = Color.White.copy(alpha = .8f), size = 11)
            if (group.year != YearMonth.now().year) DesignText(group.year.toString(), color = Color.White, size = 10)
            Spacer(Modifier.height(11.dp))
            DesignProgress(group.fraction, Modifier.fillMaxWidth(), Color.White, Color.White.copy(alpha = .26f))
        }
    }
}
@Composable internal fun PageHeader(title: String, backLabel: String = "Back to months", back: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        DesignRound(DesignIcons.Back, backLabel, onClick = back)
        DesignText(title, Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center, size = 15, weight = FontWeight(550), spacing = .1f)
        Spacer(Modifier.width(44.dp))
    }
}
@Composable fun CompletionScreen(state: PhotoState, model: PhotoViewModel) {
    val group = state.currentGroup
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        PageHeader(if (state.reviewSource == "screenshots") "Screenshots" else group?.title ?: "Month sorted", back = { model.navigate(state.reviewSource) })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(25.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            group?.photos?.firstOrNull()?.let { photo ->
                Box(Modifier.size(112.dp, 140.dp).graphicsLayer { rotationZ = -6f }) {
                    Box(Modifier.matchParentSize().offset(9.dp, 9.dp).clip(RoundedCornerShape(15.dp)).background(Soft))
                    PhotoImage(photo, Modifier.matchParentSize().clip(RoundedCornerShape(15.dp)))
                }
                Spacer(Modifier.height(22.dp))
            }
            DesignText(if (group != null) { if (state.reviewSource == "screenshots") "Screenshots, sorted." else "Month, sorted." } else "No photos left.", size = 25, weight = FontWeight.Medium, spacing = -.7f)
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                SummaryCount(DesignIcons.Check, "${group?.photos?.count { ReviewRules.record(it, state.records)?.decision == Decision.KEEP } ?: 0} kept")
                SummaryCount(DesignIcons.Trash, "${group?.photos?.count { ReviewRules.record(it, state.records)?.decision == Decision.REMOVE } ?: 0} to remove")
            }
            Spacer(Modifier.height(25.dp))
            if (state.marked.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Soft)
                    .clickable(enabled = !state.busy, role = Role.Button) { model.navigate("trash") }.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(DesignIcons.Trash, null, Modifier.size(18.dp), tint = Muted)
                    DesignText("Review removals", Modifier.weight(1f), size = 14, color = Ink, weight = FontWeight.Medium)
                    DesignText(state.marked.size.toString(), size = 13, color = Muted)
                    Icon(DesignIcons.Back, null, Modifier.size(14.dp).graphicsLayer { rotationZ = 180f }, tint = Muted)
                }
            } else DesignButton("Next month", Modifier.fillMaxWidth(), enabled = !state.busy,
                background = Soft, color = Ink, onClick = model::openNextMonth)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DesignTextButton("Browse again", Modifier.weight(1f), enabled = group != null && !state.busy) { group?.let { if (state.reviewSource == "screenshots" && state.month == null) model.openScreenshots() else model.openMonth(it.id, state.reviewSource) } }
                DesignTextButton("Undo last swipe", Modifier.weight(1f), enabled = state.canUndo && !state.busy, onClick = model::undo)
            }
        }
    }
}
@Composable private fun SummaryCount(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(icon, null, Modifier.size(17.dp), tint = Muted); DesignText(text, size = 12, color = Muted)
    }
}
