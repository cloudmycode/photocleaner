@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.maxdevlab.cleaner.photo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual primitives for all screens. */
internal object DesignIcons {
    private fun vector(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    val Back = vector("Back", "M15 5l-7 7 7 7")
    val Up = vector("Up", "M12 19V5m-6 6 6-6 6 6")
    val Down = vector("Down", "M12 5v14m-6-6 6 6 6-6")
    val Undo = vector("Undo", "M8 5 3 10l5 5M3 10h11a6 6 0 0 1 0 12")
    val Share = vector("Share", "M12 15V3m-4 4 4-4 4 4M8 9H5v12h14V9h-3")
    val Trash = vector("Trash", "M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7")
    val Grid = vector("Months", "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z")
    val Check = vector("Check", "m5 12 4 4L19 6")
    val More = vector("More", "M5 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2M19 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2")
    val Help = vector("Help", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20M9 8a3 3 0 0 1 6 0c0 2-3 2-3 5m0 4v.1")
}
@Composable internal fun DesignText(text: String, modifier: Modifier = Modifier, size: Int = 13,
    color: Color = Ink, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, align: TextAlign? = null) {
    Text(text, modifier, color = color, textAlign = align, style = TextStyle(fontSize = size.sp,
        lineHeight = (size * 1.2f).sp, fontWeight = weight, letterSpacing = spacing.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)))
}
@Composable internal fun DesignButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    background: Color = Accent, color: Color = Color.White, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 52.dp).alpha(if (enabled) 1f else .3f).clip(RoundedCornerShape(17.dp))
        .background(background).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 15.dp), contentAlignment = Alignment.Center) {
        DesignText(text, size = 15, color = color, weight = FontWeight.Medium, align = TextAlign.Center)
    }
}
@Composable internal fun DesignTextButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Accent, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else .3f).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        DesignText(text, size = 14, color = color, weight = FontWeight.Medium, align = TextAlign.Center)
    }
}
@Composable internal fun DesignRound(icon: ImageVector, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, tint: Color = Accent, background: Color = Color.Transparent,
    dimWhenDisabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(44.dp).alpha(if (enabled || !dimWhenDisabled) 1f else .3f).clip(CircleShape).background(background)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(22.dp), tint = tint)
    }
}
@Composable internal fun DesignProgress(progress: Float, modifier: Modifier, color: Color, track: Color) {
    Box(modifier.height(2.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}
@Composable internal fun DesignTabs(screen: String, count: Int, navigate: (String) -> Unit) {
    Column(Modifier.background(Color.White.copy(alpha = .8f)).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        HorizontalDivider(thickness = 1.dp, color = Color(0x203C3C43))
        Row(Modifier.fillMaxWidth().height(74.dp).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf(Triple("home", DesignIcons.Grid, "Months"), Triple("trash", DesignIcons.Trash, if (count > 0) "Remove · $count" else "Remove")).forEach { (target, icon, label) ->
                val tint = if (screen == target) Accent else Muted
                Column(Modifier.widthIn(min = 80.dp).fillMaxHeight().clickable(role = Role.Tab) { navigate(target) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = tint)
                    Spacer(Modifier.height(8.dp)); DesignText(label, size = 10, color = tint)
                }
            }
        }
    }
}
@Composable internal fun DesignSheet(title: String, copy: String, dismiss: () -> Unit, actions: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White, scrimColor = Color.Black.copy(alpha = .4f), shape = RoundedCornerShape(topStart = 27.dp, topEnd = 27.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 23.dp).size(32.dp, 4.dp).clip(CircleShape).background(Color(0xFFD1D1D6))) }) {
        SystemBars(false)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            DesignText(title, size = 24, weight = FontWeight(550), spacing = -.7f)
            Spacer(Modifier.height(12.dp))
            Text(copy, style = TextStyle(fontSize = 13.sp, lineHeight = 20.8.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), color = Muted)
            Spacer(Modifier.height(22.dp))
            actions()
        }
    }
}
@Composable internal fun DesignEmpty(title: String, copy: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Soft), contentAlignment = Alignment.Center) { Icon(DesignIcons.Trash, null, Modifier.size(30.dp), tint = Accent) }
        Spacer(Modifier.height(15.dp)); DesignText(title, size = 25, weight = FontWeight.Medium, spacing = -.7f)
        Spacer(Modifier.height(15.dp)); DesignText(copy, size = 12, color = Muted, align = TextAlign.Center)
        action?.let { Spacer(Modifier.height(25.dp)); it() }
    }
}
