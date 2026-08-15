package com.crispy.tv.playerui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.nativeengine.playback.NativeTrack
import com.crispy.tv.streams.AddonSubtitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTrackSheet(
    visible: Boolean,
    audioTracks: List<NativeTrack>,
    selectedAudioTrackId: String?,
    subtitleTracks: List<NativeTrack>,
    selectedSubtitleTrackId: String?,
    addonSubtitles: List<AddonSubtitle> = emptyList(),
    addonSubtitlesLoading: Boolean = false,
    addonSubtitlesError: String? = null,
    selectedAddonSubtitleId: String? = null,
    onSelectAudioTrack: (String?) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onFetchAddonSubtitles: () -> Unit = {},
    onSelectAddonSubtitle: (AddonSubtitle) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(TrackTab.AUDIO) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTab == TrackTab.AUDIO,
                onClick = { selectedTab = TrackTab.AUDIO },
                label = { Text("Audio") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                },
            )
            FilterChip(
                selected = selectedTab == TrackTab.SUBTITLE,
                onClick = { selectedTab = TrackTab.SUBTITLE },
                label = { Text("Subtitles") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Subtitles,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                },
            )
        }

        when (selectedTab) {
            TrackTab.AUDIO -> AudioTrackList(
                tracks = audioTracks,
                selectedTrackId = selectedAudioTrackId,
                onSelectTrack = onSelectAudioTrack,
            )
            TrackTab.SUBTITLE -> SubtitleTrackList(
                tracks = subtitleTracks,
                selectedTrackId = selectedSubtitleTrackId,
                onSelectTrack = onSelectSubtitleTrack,
                addonSubtitles = addonSubtitles,
                addonSubtitlesLoading = addonSubtitlesLoading,
                addonSubtitlesError = addonSubtitlesError,
                selectedAddonSubtitleId = selectedAddonSubtitleId,
                onFetchAddonSubtitles = onFetchAddonSubtitles,
                onSelectAddonSubtitle = onSelectAddonSubtitle,
            )
        }
    }
}

@Composable
private fun AudioTrackList(
    tracks: List<NativeTrack>,
    selectedTrackId: String?,
    onSelectTrack: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        TrackRow(
            title = "Off",
            subtitle = null,
            isSelected = selectedTrackId == null,
            onClick = { onSelectTrack(null) },
            leadingIcon = Icons.Filled.MusicNote,
        )
        tracks.forEach { track ->
            TrackRow(
                title = trackLabel(track),
                subtitle = track.language,
                isSelected = track.id == selectedTrackId,
                onClick = { onSelectTrack(track.id) },
                leadingIcon = Icons.Filled.GraphicEq,
            )
        }
        if (tracks.isEmpty()) {
            Text(
                text = "No audio tracks available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SubtitleTrackList(
    tracks: List<NativeTrack>,
    selectedTrackId: String?,
    onSelectTrack: (String?) -> Unit,
    addonSubtitles: List<AddonSubtitle>,
    addonSubtitlesLoading: Boolean,
    addonSubtitlesError: String?,
    selectedAddonSubtitleId: String?,
    onFetchAddonSubtitles: () -> Unit,
    onSelectAddonSubtitle: (AddonSubtitle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Addon subtitles",
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onFetchAddonSubtitles) {
                Text(if (addonSubtitlesLoading) "Searching..." else "Search")
            }
        }

        if (addonSubtitlesLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }

        addonSubtitlesError?.takeIf { !addonSubtitlesLoading }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        addonSubtitles.forEach { subtitle ->
            TrackRow(
                title = subtitle.display,
                subtitle = subtitle.language,
                isSelected = subtitle.id == selectedAddonSubtitleId,
                onClick = { onSelectAddonSubtitle(subtitle) },
                leadingIcon = Icons.Filled.Subtitles,
            )
        }

        Text(
            text = "Embedded subtitles",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        TrackRow(
            title = "Off",
            subtitle = null,
            isSelected = selectedTrackId == null && addonSubtitles.none { it.id == selectedAddonSubtitleId },
            onClick = { onSelectTrack(null) },
            leadingIcon = Icons.Filled.Subtitles,
        )
        tracks.forEach { track ->
            TrackRow(
                title = trackLabel(track),
                subtitle = track.language,
                isSelected = track.id == selectedTrackId,
                onClick = { onSelectTrack(track.id) },
                leadingIcon = Icons.Filled.Subtitles,
            )
        }
        if (tracks.isEmpty() && addonSubtitles.isEmpty()) {
            Text(
                text = "No subtitle tracks available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun TrackRow(
    title: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) MaterialTheme.typography.titleMedium.fontWeight else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = subtitle?.takeIf { it.isNotBlank() }?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = {
            Icon(imageVector = leadingIcon, contentDescription = null)
        },
        trailingContent = if (isSelected) {
            { Text("Selected", style = MaterialTheme.typography.labelSmall) }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

private fun trackLabel(track: NativeTrack): String {
    val title = track.title?.takeIf { it.isNotBlank() }
    val lang = track.language?.takeIf { it.isNotBlank() }
    val id = track.id
    return title ?: lang ?: id
}

private enum class TrackTab {
    AUDIO,
    SUBTITLE,
}
