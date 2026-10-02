package com.crispy.tv.playerui

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.nativeengine.playback.NativeTrack
import com.crispy.tv.nativeengine.playback.externalSubtitleTrackId
import com.crispy.tv.streams.SHEET_HEIGHT_FRACTION
import com.crispy.tv.streams.SHEET_MAX_WIDTH
import com.crispy.tv.ui.resources.Res
import org.jetbrains.compose.resources.DrawableResource
import com.crispy.tv.ui.resources.ic_check_filled
import com.crispy.tv.ui.resources.ic_graphic_eq_filled
import com.crispy.tv.ui.resources.ic_music_note_filled
import com.crispy.tv.ui.resources.ic_subtitles_filled
import org.jetbrains.compose.resources.painterResource


private data class LanguageGroup<T>(
    val key: String,
    val label: String,
    val items: List<T>,
)

private fun groupAudioByLanguage(
    tracks: List<NativeTrack>,
    displayName: (String) -> String,
): List<LanguageGroup<NativeTrack>> {
    if (tracks.isEmpty()) return emptyList()
    return tracks
        .groupBy { normalizeLang(it.language) }
        .map { (key, items) -> LanguageGroup(key, languageLabelForCode(key, displayName), items) }
        .sortedBy { it.label }
}

private sealed interface SubtitleOption {
    val key: String
    val label: String
    val language: String?
    val subtitle: String?
    val isSelected: Boolean
    val trackId: String
}

private data class EngineSubtitleOption(
    val track: NativeTrack,
    override val isSelected: Boolean,
    private val displayName: (String) -> String,
) : SubtitleOption {
    override val key = track.id
    override val label = track.title?.takeIf { it.isNotBlank() } ?: languageLabelForCode(track.language, displayName)
    override val language = track.language
    override val subtitle = track.title?.takeIf { it.isNotBlank() }?.let { languageLabelForCode(track.language, displayName) }
    override val trackId = track.id
}

private data class CatalogSubtitleOption(
    val addonSubtitle: AddonSubtitle,
    private val displayName: (String) -> String,
) : SubtitleOption {
    override val key = externalSubtitleTrackId(addonSubtitle.url)
    override val label = addonSubtitle.display.ifBlank { languageLabelForCode(addonSubtitle.language, displayName) }
    override val language = addonSubtitle.language
    override val subtitle = addonSubtitle.addonName?.takeIf { it.isNotBlank() }
    override val isSelected = false
    override val trackId = key
}

private fun groupSubtitlesByLanguage(
    options: List<SubtitleOption>,
    displayName: (String) -> String,
): List<LanguageGroup<SubtitleOption>> {
    if (options.isEmpty()) return emptyList()
    return options
        .groupBy { normalizeLang(it.language) }
        .map { (key, items) -> LanguageGroup(key, languageLabelForCode(key, displayName), items) }
        .sortedBy { it.label }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerAudioSheet(
    visible: Boolean,
    audioTracks: List<NativeTrack>,
    selectedAudioTrackId: String?,
    palette: DetailsPaletteColors,
    displayName: (String) -> String,
    onSelectAudioTrack: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val groups = groupAudioByLanguage(audioTracks, displayName)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = SHEET_MAX_WIDTH,
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                        .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SheetTitle("Audio tracks") }

                if (audioTracks.isEmpty()) {
                    item { EmptyHint("No audio tracks available") }
                }

                groups.forEach { group ->
                    item(key = "audio_header_${group.key}") {
                        LanguageGroupHeader(label = group.label, count = group.items.size)
                    }
                    items(group.items, key = { it.id }) { track ->
                        val title = track.title?.takeIf { it.isNotBlank() }
                        TrackRow(
                            label = title ?: languageLabelForCode(track.language, displayName),
                            subtitle = title?.let { languageLabelForCode(track.language, displayName) },
                            isSelected = track.id == selectedAudioTrackId,
                            palette = palette,
                            leadingIcon = if (track.language == null) Res.drawable.ic_music_note_filled else Res.drawable.ic_graphic_eq_filled,
                            onClick = { onSelectAudioTrack(track.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSubtitleSheet(
    visible: Boolean,
    subtitleTracks: List<NativeTrack>,
    selectedSubtitleTrackId: String?,
    addonSubtitles: List<AddonSubtitle>,
    addonSubtitlesLoading: Boolean,
    addonSubtitlesError: String?,
    palette: DetailsPaletteColors,
    displayName: (String) -> String,
    onSelectSubtitleTrack: (String?) -> Unit,
    onRefreshAddonSubtitles: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    val engineTrackIds = subtitleTracks.mapTo(HashSet()) { it.id }
    val options =
        buildList<SubtitleOption> {
            subtitleTracks.forEach { track ->
                add(EngineSubtitleOption(track, isSelected = track.id == selectedSubtitleTrackId, displayName = displayName))
            }
            addonSubtitles
                .distinctBy { externalSubtitleTrackId(it.url) }
                .filter { externalSubtitleTrackId(it.url) !in engineTrackIds }
                .forEach { subtitle -> add(CatalogSubtitleOption(subtitle, displayName)) }
        }
    val groups = groupSubtitlesByLanguage(options, displayName)
    val languagePills = groups.map { LanguagePill(key = it.key, label = it.label, count = it.items.size) }
    var selectedLang by remember { mutableStateOf<String?>(null) }
    val visibleOptions =
        if (selectedLang == null) {
            options
        } else {
            options.filter { normalizeLang(it.language) == selectedLang }
        }
    val offSelected = selectedSubtitleTrackId == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = SHEET_MAX_WIDTH,
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                        .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Subtitles",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(onClick = onRefreshAddonSubtitles, enabled = !addonSubtitlesLoading) {
                            Text(if (addonSubtitlesLoading) "Searching" else "Search")
                        }
                    }
                }

                addonSubtitlesError?.takeIf { !addonSubtitlesLoading }?.let { error ->
                    item {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    LanguagePillsRow(
                        languages = languagePills,
                        selectedKey = selectedLang,
                        palette = palette,
                        onSelect = { selectedLang = it },
                    )
                }

                item {
                    TrackRow(
                        label = "Off",
                        subtitle = null,
                        isSelected = offSelected,
                        palette = palette,
                        leadingIcon = Res.drawable.ic_subtitles_filled,
                        onClick = { onSelectSubtitleTrack(null) },
                    )
                }

                if (visibleOptions.isEmpty() && !addonSubtitlesLoading) {
                    item { EmptyHint("No subtitle tracks available") }
                }

                items(visibleOptions, key = { it.key }) { option ->
                    TrackRow(
                        label = option.label,
                        subtitle = option.subtitle,
                        isSelected = option.isSelected,
                        palette = palette,
                        leadingIcon = Res.drawable.ic_subtitles_filled,
                        onClick = { onSelectSubtitleTrack(option.trackId) },
                    )
                }
            }
        }
    }
}

private data class LanguagePill(
    val key: String,
    val label: String,
    val count: Int,
)

@Composable
private fun LanguagePillsRow(
    languages: List<LanguagePill>,
    selectedKey: String?,
    palette: DetailsPaletteColors,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedKey == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
            shape = RoundedCornerShape(16.dp),
            border = null,
            colors = LanguagePillColors(palette, selectedKey == null),
        )
        languages.forEach { lang ->
            FilterChip(
                selected = selectedKey == lang.key,
                onClick = { onSelect(lang.key) },
                label = {
                    Text(if (lang.count > 1) "${lang.label} (${lang.count})" else lang.label)
                },
                shape = RoundedCornerShape(16.dp),
                border = null,
                colors = LanguagePillColors(palette, selectedKey == lang.key),
            )
        }
    }
}

@Composable
private fun LanguagePillColors(
    palette: DetailsPaletteColors,
    selected: Boolean,
) = FilterChipDefaults.filterChipColors(
    containerColor = palette.pillBackground,
    labelColor = palette.onPillBackground,
    selectedContainerColor = palette.accent,
    selectedLabelColor = palette.onAccent,
)

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun LanguageGroupHeader(
    label: String,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (count > 1) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    subtitle: String?,
    isSelected: Boolean,
    palette: DetailsPaletteColors,
    leadingIcon: DrawableResource,
    onClick: () -> Unit,
) {
    val containerColor = if (isSelected) palette.accent else Color.Transparent
    val contentColor = if (isSelected) palette.onAccent else MaterialTheme.colorScheme.onSurface

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(containerColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(leadingIcon),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) palette.onAccent.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                painter = painterResource(Res.drawable.ic_check_filled),
                contentDescription = null,
                tint = palette.onAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
