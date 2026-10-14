package com.crispy.tv.playerui

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/**
 * One row of the subtitle sheet. Engine-only tracks and catalog entries share
 * this shape and the `ext:<hash>` identity, so attaching a catalog subtitle
 * flips its checkmark in place instead of moving it between sections.
 */
private data class SubtitleOption(
    val key: String,
    val label: String,
    val language: String?,
    val subtitle: String?,
    val isSelected: Boolean,
    val trackId: String,
)

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

/**
 * The audio track picker, as a flat list in engine order.
 *
 * There are no language sections. A release carries a handful of audio tracks and
 * nearly always one language each, so grouping spent a header and a count on
 * structure the list already showed -- and it sorted by *language name*, which
 * threw away the engine's own ordering for no gain. Rows now appear in the order
 * the container declares them.
 *
 * Each row shows the engine's title when it has one and the language name
 * otherwise, then the language, codec and channel count, which is the web panel's
 * own row shape (`AudioPanel.tsx`): "Commentary" over "English • EAC3 • 6ch".
 */
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
                } else {
                    // The section headers used to carry a per-language count. With
                    // the sections gone the total is the one number worth showing,
                    // and the subtitle sheet's own "Off" row needs no equivalent.
                    item { SheetMeta("${audioTracks.size} available") }
                }

                items(audioTracks, key = { it.id }) { track ->
                    val languageName = languageLabelForCode(track.language, displayName)
                    TrackRow(
                        label = audioTrackName(track, languageName),
                        subtitle = audioTrackSubtitle(track, languageName),
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

    // One static list in a stable order: engine-only tracks (embedded or
    // stream-shipped, in engine order) first, then the add-on catalog in
    // published order. Attaching never adds or removes a row -- the catalog
    // entry wins the shared `ext:<hash>` identity and only flips its checkmark --
    // so the list only changes when the catalog itself does (fetch/refresh).
    val catalogIds = addonSubtitles.mapTo(HashSet()) { externalSubtitleTrackId(it.url) }
    val options =
        buildList {
            subtitleTracks
                .filter { it.id !in catalogIds }
                .forEach { track ->
                    add(
                        SubtitleOption(
                            key = track.id,
                            label = track.title?.takeIf { it.isNotBlank() } ?: languageLabelForCode(track.language, displayName),
                            language = track.language,
                            subtitle = track.title?.takeIf { it.isNotBlank() }?.let { languageLabelForCode(track.language, displayName) },
                            isSelected = track.id == selectedSubtitleTrackId,
                            trackId = track.id,
                        ),
                    )
                }
            addonSubtitles
                .distinctBy { externalSubtitleTrackId(it.url) }
                .forEach { subtitle ->
                    val key = externalSubtitleTrackId(subtitle.url)
                    add(
                        SubtitleOption(
                            key = key,
                            label = subtitle.display.ifBlank { languageLabelForCode(subtitle.language, displayName) },
                            language = subtitle.language,
                            subtitle = subtitle.addonName?.takeIf { it.isNotBlank() },
                            isSelected = key == selectedSubtitleTrackId,
                            trackId = key,
                        ),
                    )
                }
        }
    val groups = groupSubtitlesByLanguage(options, displayName)
    val languagePills = groups.map { LanguagePill(key = it.key, label = it.label, count = it.items.size) }
    // Seeded from the current selection, not 'All': the sheet is disposed on
    // close, so this runs once per open and only pill taps change it after.
    // A selection the list does not contain (nothing picked yet) falls back to
    // the first language; an empty list leaves it null and unfiltered.
    var selectedLang by remember {
        mutableStateOf(
            options.firstOrNull { it.trackId == selectedSubtitleTrackId }
                ?.let { normalizeLang(it.language) }
                ?.takeIf { lang -> groups.any { it.key == lang } }
                ?: groups.firstOrNull()?.key,
        )
    }
    val showPills = groups.size > 1
    val visibleOptions =
        if (!showPills || selectedLang == null) {
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

                if (showPills) {
                    item {
                        LanguagePillsRow(
                            languages = languagePills,
                            selectedKey = selectedLang,
                            palette = palette,
                            onSelect = { selectedLang = it },
                        )
                    }
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
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedKey, languages.size) {
        val index = languages.indexOfFirst { it.key == selectedKey }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(languages, key = { it.key }) { lang ->
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

/**
 * The one quiet line under a sheet title, e.g. "3 available".
 *
 * Smaller and dimmer than a row's own second line, so it reads as caption text
 * about the list rather than as something selected.
 */
@Composable
private fun SheetMeta(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
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
