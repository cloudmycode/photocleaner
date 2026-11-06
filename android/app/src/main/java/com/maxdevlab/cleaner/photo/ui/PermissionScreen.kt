package com.maxdevlab.cleaner.photo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Explain the first cleanup before asking for library access. */
@Composable fun PermissionScreen(request: () -> Unit, settings: () -> Unit, privacy: () -> Unit, appSettings: () -> Unit, accessExplanation: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand(); Spacer(Modifier.weight(1f)); IconButton(appSettings) { Icon(Icons.Outlined.Tune, "Settings", tint = Accent) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DesignText("Make room for more", size = 27, weight = FontWeight(550), spacing = -.7f)
            DesignText("Find duplicate photos. Review. Free up space.", size = 13, color = Muted)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFE6EEFB)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                DesignText("1  Find extra copies", size = 18, weight = FontWeight.SemiBold)
                DesignText("See duplicate and burst photos grouped together, with their potential space savings.", color = Muted)
                DesignText("2  Review what stays", size = 18, weight = FontWeight.SemiBold)
                DesignText("One photo in each group stays unselected. You can change every suggestion.", color = Muted)
                DesignText("3  Confirm your cleanup", size = 18, weight = FontWeight.SemiBold)
                DesignText("Nothing is deleted without your confirmation.", color = Muted)
            }
            DesignText("Media access lets us find extra photos and show your library.\nPhoto analysis stays on your device.", size = 12, color = Muted, align = TextAlign.Center)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignButton("Allow media access", Modifier.fillMaxWidth(), onClick = request)
            DesignText(accessExplanation, size = 11, color = Muted, align = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                DesignTextButton("Privacy policy", onClick = privacy)
                DesignTextButton("Photo settings", onClick = settings)
            }
        }
    }
}
