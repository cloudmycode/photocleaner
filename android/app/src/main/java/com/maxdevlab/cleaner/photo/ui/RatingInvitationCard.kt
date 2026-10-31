package com.maxdevlab.cleaner.photo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** An optional next step after the result, never an interruption to sorting. */
@Composable internal fun RatingInvitationCard(enabled: Boolean, rate: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("rating-invitation")
        .background(Color.White, RoundedCornerShape(18.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.StarOutline, null, Modifier.size(21.dp), tint = Accent)
            DesignText("Share your experience", size = 15, weight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        DesignText("Your review helps improve Photo Cleaner.", size = 12, color = Muted,
            align = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        DesignButton("Rate Photo Cleaner", Modifier.fillMaxWidth(), enabled = enabled,
            background = Accent, color = Color.White, onClick = rate)
    }
}
