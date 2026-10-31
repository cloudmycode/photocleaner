package com.maxdevlab.cleaner.photo.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A local illustration can be tried before access; it never reads or imports a photo. */
@Composable fun PermissionScreen(request: () -> Unit, settings: () -> Unit, privacy: () -> Unit, appSettings: () -> Unit, accessExplanation: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand(); Spacer(Modifier.weight(1f)); IconButton(appSettings) { Icon(Icons.Outlined.Tune, "Settings", tint = Accent) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DesignText("Swipe to clean up", size = 27, weight = FontWeight(550), spacing = -.7f)
            DesignText("Your photos, sorted by month.", size = 13, color = Muted)
            PermissionDemo()
            DesignText("Photo access lets you browse and sort your library.\nYour photos stay on your device.", size = 12, color = Muted, align = TextAlign.Center)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignButton("Allow photo access", Modifier.fillMaxWidth(), onClick = request)
            DesignText(accessExplanation, size = 11, color = Muted, align = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                DesignTextButton("Privacy policy", onClick = privacy)
                DesignTextButton("Photo settings", onClick = settings)
            }
        }
    }
}
@Composable private fun PermissionDemo() {
    var offset by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("Try a swipe") }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    fun finish(keep: Boolean?) {
        if (busy) return
        busy = true
        scope.launch {
            if (keep != null) {
                animate(offset, if (keep) -100 * density else 100 * density, animationSpec = tween(280)) { value, _ -> offset = value }
                feedback = if (keep) "Kept" else "Review before deleting"
            }
            animate(offset, 0f, animationSpec = tween(280)) { value, _ -> offset = value }
            busy = false
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(184.dp, 180.dp).graphicsLayer { rotationZ = -7f }.clip(RoundedCornerShape(20.dp)).background(Color(0xFFD9E6F4)))
            Canvas(Modifier.size(184.dp, 180.dp).testTag("permission-demo-photo").graphicsLayer {
                translationY = offset; rotationZ = offset / density / 80
            }.clip(RoundedCornerShape(20.dp)).pointerInput(busy) {
                if (!busy) detectVerticalDragGestures(onVerticalDrag = { change, amount -> change.consume(); offset += amount },
                    onDragEnd = { finish(if (abs(offset) > 40 * density) offset < 0 else null) }, onDragCancel = { finish(null) })
            }) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFB2D9ED), Color(0xFFF4E8D9))))
                drawCircle(Color(0xFFFFF5D9), size.width * .13f, Offset(size.width * .73f, size.height * .22f))
                val mountain = Path().apply {
                    moveTo(0f, size.height * .60f); lineTo(size.width * .39f, size.height * .25f)
                    lineTo(size.width * .70f, size.height * .59f); lineTo(size.width, size.height * .35f)
                    lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(mountain, Color(0xFF668F9F))
                drawRect(Color(0xFF356D83), Offset(0f, size.height * .70f), androidx.compose.ui.geometry.Size(size.width, size.height * .30f))
                drawLine(Color(0xFF9EC6CD), Offset(size.width * .13f, size.height * .82f), Offset(size.width * .74f, size.height * .82f), 2.dp.toPx())
                drawLine(Color(0xFF9EC6CD), Offset(size.width * .37f, size.height * .88f), Offset(size.width * .88f, size.height * .88f), 2.dp.toPx())
            }
            DesignButton("↑  Keep", Modifier.align(Alignment.TopCenter), enabled = !busy) { finish(true) }
            DesignButton("↓  Remove", Modifier.align(Alignment.BottomCenter), enabled = !busy, background = Destructive) { finish(false) }
        }
        DesignText(feedback, size = 12, color = Muted)
    }
}
