package com.crispy.tv.addons.streams

import com.crispy.tv.player.MetadataLabMediaType

/**
 * The stream value model: what a provider offers, plus the pure string rules for
 * reading magnet and torrent links out of it.
 *
 * ## Why this file exists
 *
 * `AddonStreamsService` was 1,068 lines whose first 242 were data classes and pure
 * helpers, sharing a file with a service that reaches for `Context`, OkHttp and
 * `org.json`. That put the model out of reach of portable code even though nothing in
 * it was platform-specific. Splitting the file is the entire change: no type moved
 * package, so no call site changed an import.
 *
 * `MetadataLabMediaType` comes from `:android:player`'s `commonMain`, which is what
 * makes the model portable at all.
 */

data class AddonStream(
    val providerId: String,
    val providerName: String,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val sources: List<String> = emptyList(),
    val requestHeaders: Map<String, String> = emptyMap(),
    val cached: Boolean = false,
    val stableKey: String,
    val subtitles: List<StreamSubtitle> = emptyList(),
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
    val clientResolve: StreamClientResolve? = null,
) {
    val playbackUrl: String?
        get() = url ?: externalUrl

    val directPlaybackUrl: String?
        get() = playbackUrl?.trim()?.takeIf { it.isNotEmpty() && !it.isMagnetLink() && !it.isTorrentSchemeUrl() }

    val p2pInfoHash: String?
        get() = infoHash.normalizedInfoHash()
            ?: (url ?: externalUrl)?.extractBtihInfoHash()
            ?: (url ?: externalUrl)?.extractTorrentSchemeInfoHash()

    val p2pFileIdx: Int?
        get() = fileIdx ?: (url ?: externalUrl)?.extractTorrentSchemeFileIdx()

    val isTorrentStream: Boolean
        get() = !infoHash.isNullOrBlank() ||
            url.isMagnetLink() || externalUrl.isMagnetLink() ||
            url.isTorrentSchemeUrl() || externalUrl.isTorrentSchemeUrl()

    val hasPlayableSource: Boolean
        get() = url != null || infoHash != null || externalUrl != null || clientResolve != null
}

data class StreamSubtitle(
    val url: String,
    val lang: String?,
    val name: String?,
)

data class StreamBehaviorHints(
    val bingeGroup: String? = null,
    val notWebReady: Boolean = false,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
    val proxyRequestHeaders: Map<String, String>? = null,
)

data class StreamClientResolve(
    val type: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val magnetUri: String? = null,
    val sources: List<String> = emptyList(),
    val torrentName: String? = null,
    val filename: String? = null,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val mediaOnlyId: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val service: String? = null,
    val serviceIndex: Int? = null,
    val serviceExtension: String? = null,
    val isCached: Boolean? = null,
    val stream: StreamClientResolveStream? = null,
) {
    val isDirectDebridCandidate: Boolean
        get() = type.equals("debrid", ignoreCase = true) &&
            !service.isNullOrBlank() &&
            isCached == true
}

data class StreamClientResolveStream(
    val raw: StreamClientResolveRaw? = null,
)

data class StreamClientResolveRaw(
    val torrentName: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val folderSize: Long? = null,
    val tracker: String? = null,
    val indexer: String? = null,
    val network: String? = null,
    val parsed: StreamClientResolveParsed? = null,
)

data class StreamClientResolveParsed(
    val rawTitle: String? = null,
    val parsedTitle: String? = null,
    val year: Int? = null,
    val resolution: String? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val quality: String? = null,
    val hdr: List<String> = emptyList(),
    val codec: String? = null,
    val audio: List<String> = emptyList(),
    val channels: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val group: String? = null,
    val network: String? = null,
    val edition: String? = null,
    val duration: Long? = null,
    val bitDepth: String? = null,
    val extended: Boolean? = null,
    val theatrical: Boolean? = null,
    val remastered: Boolean? = null,
    val unrated: Boolean? = null,
)

private fun String?.isMagnetLink(): Boolean =
    this?.trimStart()?.startsWith("magnet:", ignoreCase = true) == true

private fun String?.isTorrentSchemeUrl(): Boolean =
    this?.trimStart()?.startsWith("torrent://", ignoreCase = true) == true

private fun String?.extractTorrentSchemeInfoHash(): String? {
    val raw = this?.trimStart()?.takeIf { it.isTorrentSchemeUrl() } ?: return null
    return raw.removeRange(0, "torrent://".length)
        .substringBefore('/')
        .substringBefore('?')
        .trim()
        .takeIf { it.isValidInfoHash() }
}

private fun String?.extractTorrentSchemeFileIdx(): Int? {
    val raw = this?.trimStart()?.takeIf { it.isTorrentSchemeUrl() } ?: return null
    val path = raw.removeRange(0, "torrent://".length).substringBefore('?')
    if ('/' !in path) return null
    return path.substringAfter('/')
        .trim()
        .takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
        ?.toIntOrNull()
}

private fun String.isValidInfoHash(): Boolean =
    (length == 40 && all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) ||
        (length == 32 && all { it in '2'..'7' || it.lowercaseChar() in 'a'..'z' })

private fun String?.normalizedInfoHash(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.extractBtihInfoHash(): String? {
    val raw = this?.trim()?.takeIf { it.startsWith("magnet:", ignoreCase = true) } ?: return null
    val marker = "btih:"
    val markerIndex = raw.indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null
    val start = markerIndex + marker.length
    val end = raw.indexOf('&', start).takeIf { it >= 0 } ?: raw.length
    return raw.substring(start, end).trim().takeIf { it.isNotEmpty() }
}

private val ADDON_URL_HEX = "0123456789ABCDEF"

internal fun MetadataLabMediaType.asApiPath(): String =
    when (this) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "series"
        MetadataLabMediaType.ANIME -> "series"
    }

internal fun String.encodeAddonPathSegment(): String =
    buildString {
        encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(ADDON_URL_HEX[value shr 4])
                append(ADDON_URL_HEX[value and 0x0F])
            }
        }
    }

data class AddonSubtitle(
    val id: String,
    val url: String,
    val language: String,
    val display: String,
    val addonName: String? = null,
)

data class ProviderStreamsResult(
    val providerId: String,
    val providerName: String,
    val streams: List<AddonStream>,
    val errorMessage: String? = null,
    val attemptedUrl: String? = null,
)

data class StreamProviderDescriptor(
    val providerId: String,
    val providerName: String,
)
