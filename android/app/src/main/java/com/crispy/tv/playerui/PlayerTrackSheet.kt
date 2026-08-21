package com.crispy.tv.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
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
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.nativeengine.playback.NativeTrack
import com.crispy.tv.streams.AddonSubtitle
import java.util.Locale

private val ISO639_2_TO_1 =
    mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "deu" to "de", "ger" to "de",
        "ita" to "it", "por" to "pt", "rus" to "ru", "jpn" to "ja", "kor" to "ko",
        "chi" to "zh", "zho" to "zh", "ara" to "ar", "hin" to "hi", "nld" to "nl",
        "dut" to "nl", "tur" to "tr", "pol" to "pl", "vie" to "vi", "tha" to "th",
        "ind" to "id", "msa" to "ms", "may" to "ms", "tam" to "ta", "tel" to "te",
        "ben" to "bn", "mal" to "ml", "guj" to "gu", "kan" to "kn", "mar" to "mr",
        "pan" to "pa", "urd" to "ur", "fas" to "fa", "per" to "fa", "heb" to "he",
        "swe" to "sv", "nor" to "no", "dan" to "da", "fin" to "fi", "ell" to "el",
        "gre" to "el", "ces" to "cs", "cze" to "cs", "hun" to "hu", "ron" to "ro",
        "rum" to "ro", "bul" to "bg", "ukr" to "uk", "hrv" to "hr", "srp" to "sr",
        "slv" to "sl", "lit" to "lt", "lav" to "lv", "est" to "et", "cat" to "ca",
        "gle" to "ga", "isl" to "is", "ice" to "is", "mkd" to "mk", "mac" to "mk",
        "sqi" to "sq", "alb" to "sq", "afr" to "af", "aka" to "ak", "amh" to "am",
        "bod" to "bo", "bos" to "bs", "mya" to "my", "cmn" to "zh", "cym" to "cy",
        "eus" to "eu", "fao" to "fo", "glg" to "gl", "hat" to "ht",
        "hau" to "ha", "hye" to "hy", "ibo" to "ig", "jav" to "jv", "kat" to "ka",
        "kaz" to "kk", "khm" to "km", "kin" to "rw", "kir" to "ky", "lao" to "lo",
        "mon" to "mn", "mri" to "mi", "nya" to "ny", "ori" to "or",
        "pus" to "ps", "que" to "qu", "sun" to "su", "swa" to "sw", "tgk" to "tg",
        "tuk" to "tk", "uig" to "ug", "uzb" to "uz", "wol" to "wo", "yor" to "yo",
        "zul" to "zu",
    )

internal fun languageLabelForCode(code: String?): String {
    if (code.isNullOrBlank()) return "Unknown"
    val lower = code.trim().lowercase()
    return when (lower) {
        "none", "off" -> "Off"
        "forced" -> "Forced"
        "default" -> "Default"
        "device" -> "Device language"
        "original" -> "Original"
        "und" -> "Undetermined"
        else -> {
            val two = if (code.trim().length == 3) ISO639_2_TO_1[lower] ?: code.trim() else code.trim()
            runCatching {
                val display = Locale.forLanguageTag(two).getDisplayLanguage(Locale.ENGLISH)
                if (display.isNotBlank() && !display.equals(two, ignoreCase = true)) {
                    display.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                } else {
                    code.trim().uppercase(Locale.ENGLISH)
                }
            }.getOrDefault(code.trim().uppercase(Locale.ENGLISH))
        }
    }
}

private fun normalizeLang(code: String?): String {
    val raw = code?.trim().orEmpty().lowercase()
    if (raw.isBlank()) return "und"
    return if (raw.length == 3) ISO639_2_TO_1[raw] ?: raw else raw
}

private data class LanguageGroup<T>(
    val key: String,
    val label: String,
    val items: List<T>,
)

private fun groupAudioByLanguage(tracks: List<NativeTrack>): List<LanguageGroup<NativeTrack>> {
    if (tracks.isEmpty()) return emptyList()
    return tracks
        .groupBy { normalizeLang(it.language) }
        .map { (key, items) -> LanguageGroup(key, languageLabelForCode(key), items) }
        .sortedBy { it.label }
}

private sealed interface SubtitleOption {
    val key: String
    val label: String
    val language: String?
    val isSelected: Boolean
}

private data class EmbeddedSubtitleOption(
    val track: NativeTrack,
    override val isSelected: Boolean,
) : SubtitleOption {
    override val key = track.id
    override val label = track.title ?: languageLabelForCode(track.language)
    override val language = track.language
}

private data class AddonSubtitleOption(
    val subtitle: AddonSubtitle,
    override val isSelected: Boolean,
) : SubtitleOption {
    override val key = subtitle.id
    override val label = subtitle.display.ifBlank { languageLabelForCode(subtitle.language) }
    override val language = subtitle.language
}

private fun groupSubtitlesByLanguage(options: List<SubtitleOption>): List<LanguageGroup<SubtitleOption>> {
    if (options.isEmpty()) return emptyList()
    return options
        .groupBy { normalizeLang(it.language) }
        .map { (key, items) -> LanguageGroup(key, languageLabelForCode(key), items) }
        .sortedBy { it.label }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerAudioSheet(
    visible: Boolean,
    audioTracks: List<NativeTrack>,
    selectedAudioTrackId: String?,
    palette: DetailsPaletteColors,
    onSelectAudioTrack: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, skipPartiallyExpanded = true)
    val groups = groupAudioByLanguage(audioTracks)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 560.dp,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
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
                        label = title ?: languageLabelForCode(track.language),
                        subtitle = title?.let { languageLabelForCode(track.language) },
                        isSelected = track.id == selectedAudioTrackId,
                        palette = palette,
                        leadingIcon = if (track.language == null) Icons.Filled.MusicNote else Icons.Filled.GraphicEq,
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
    selectedAddonSubtitleId: String?,
    palette: DetailsPaletteColors,
    onSelectSubtitleTrack: (String?) -> Unit,
    onFetchAddonSubtitles: () -> Unit = {},
    onSelectAddonSubtitle: (AddonSubtitle) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, skipPartiallyExpanded = true)

    val options =
        buildList<SubtitleOption> {
            subtitleTracks.forEach { track ->
                add(EmbeddedSubtitleOption(track, isSelected = track.id == selectedSubtitleTrackId))
            }
            addonSubtitles.forEach { subtitle ->
                add(AddonSubtitleOption(subtitle, isSelected = subtitle.id == selectedAddonSubtitleId))
            }
        }
    val groups = groupSubtitlesByLanguage(options)
    val languagePills = groups.map { LanguagePill(key = it.key, label = it.label, count = it.items.size) }
    var selectedLang by remember { mutableStateOf<String?>(null) }
    val visibleOptions =
        if (selectedLang == null) {
            options
        } else {
            options.filter { normalizeLang(it.language) == selectedLang }
        }
    val offSelected = selectedSubtitleTrackId == null && addonSubtitles.none { it.id == selectedAddonSubtitleId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 560.dp,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SheetTitle("Subtitles") }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Addon subtitles",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onFetchAddonSubtitles) {
                        Text(if (addonSubtitlesLoading) "Searching..." else "Search")
                    }
                }
            }

            if (addonSubtitlesLoading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
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
                    leadingIcon = Icons.Filled.Subtitles,
                    onClick = { onSelectSubtitleTrack(null) },
                )
            }

            if (visibleOptions.isEmpty() && !addonSubtitlesLoading) {
                item { EmptyHint("No subtitle tracks available") }
            }

            items(visibleOptions, key = { it.key }) { option ->
                when (option) {
                    is EmbeddedSubtitleOption -> {
                        TrackRow(
                            label = option.label,
                            subtitle = option.track.title?.takeIf { it.isNotBlank() }?.let { languageLabelForCode(option.track.language) },
                            isSelected = option.isSelected,
                            palette = palette,
                            leadingIcon = Icons.Filled.Subtitles,
                            onClick = { onSelectSubtitleTrack(option.track.id) },
                        )
                    }
                    is AddonSubtitleOption -> {
                        TrackRow(
                            label = option.label,
                            subtitle = option.subtitle.addonName?.takeIf { it.isNotBlank() },
                            isSelected = option.isSelected,
                            palette = palette,
                            leadingIcon = Icons.Filled.Subtitles,
                            onClick = { onSelectAddonSubtitle(option.subtitle) },
                        )
                    }
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
            color = MaterialTheme.colorScheme.primary,
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
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
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
            imageVector = leadingIcon,
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
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = palette.onAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
