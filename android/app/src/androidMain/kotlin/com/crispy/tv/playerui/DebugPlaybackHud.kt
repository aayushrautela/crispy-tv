package com.crispy.tv.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Whether this install was built as `debug`. The value comes from the
 * application module's `BuildConfig.DEBUG` through the Android composition root -- a KMP
 * library has no `BuildConfig` of its own -- and has no default, so a caller
 * that forgets to provide it fails loudly instead of silently hiding the HUD.
 */
internal val LocalIsDebugBuild = compositionLocalOf<Boolean> {
    error("LocalIsDebugBuild must be provided by AndroidAppRoot")
}

/**
 * A small, non-interactive readout of what the player is actually doing. It is
 * drawn only in debug installs and only outside PiP; the values come from the
 * engine dump already written to logcat, so the HUD cannot disagree with the
 * log used to diagnose a playback bug.
 */
@Composable
internal fun BoxScope.DebugPlaybackHud(
    uiState: PlayerUiState,
    engineDump: String,
    positionMs: Long,
) {
    val token: (String) -> String = { name ->
        engineDump.token(name)
    }
    val lines =
        listOf(
            "phase=${engineDump.phase()} ui=${token("ui")} want=${token("playWhenReady")} started=${token("started")}",
            "vo=${token("mpv.vo")} cur=${token("currentVo")} hw=${token("hwdec")}",
            "pause=${token("pause")} cache=${token("cachePaused")} seek=${token("seeking")} idle=${token("coreIdle")}",
            "surface=${token("surfaceAttached")} engine=${uiState.activeEngine} resize=${uiState.resizeMode}",
            "pos=${positionMs / 1000}s dur=${token("dur")}s hdr src=${uiState.videoIsHdr} out=${uiState.hdrOutputEnabled}",
            "ui play=${uiState.isPlaying} buf=${uiState.isBuffering} screen=${uiState.activeSurface}",
            "status=${uiState.statusMessage.replace('\n', ' ').take(40)}",
        )
    Text(
        text = lines.joinToString("\n"),
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .widthIn(max = 300.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clearAndSetSemantics {},
        color = Color(0xFFB9F6CA),
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        maxLines = lines.size,
        overflow = TextOverflow.Clip,
    )
}

/** Reads a `name=value` field from the engine dump without parsing the whole line. */
private fun String.token(name: String): String =
    substringAfter("$name=", "")
        .substringBefore(' ')
        .takeIf { it.isNotEmpty() }
        ?: "n/a"

private fun String.phase(): String =
    substringAfter("dump[", "")
        .substringBefore("]")
        .takeIf { it.isNotEmpty() }
        ?: "n/a"
