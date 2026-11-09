package com.crispy.tv.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The app-drawn subtitle cue. mpv's tunneled mediacodec_embed surface cannot
 * carry mpv-rendered subtitles, so the player reads `sub-text` and draws it here
 * instead; the text arrives already sanitised ([PlayerUiState.currentSubtitleText]).
 */
@Composable
internal fun PlayerSubtitleCue(
    text: String?,
    modifier: Modifier = Modifier,
) {
    if (text.isNullOrBlank()) return
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
