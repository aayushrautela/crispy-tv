package com.crispy.tv.domain.metadata

data class NuvioMediaId(
    val contentId: String,
    val videoId: String?,
    val isEpisode: Boolean,
    val season: Int?,
    val episode: Int?,
    val kind: String,
    val addonLookupId: String
)

private val IMDB_ID_REGEX = Regex("^tt\\d+$", RegexOption.IGNORE_CASE)
private val NUMERIC_ID_REGEX = Regex("^\\d+$")
private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

private data class EpisodeSuffix(
    val baseId: String,
    val season: Int?,
    val episode: Int?
)

fun normalizeNuvioMediaId(raw: String): NuvioMediaId {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return NuvioMediaId(
            contentId = "",
            videoId = null,
            isEpisode = false,
            season = null,
            episode = null,
            kind = "content",
            addonLookupId = ""
        )
    }

    val suffix = parseEpisodeSuffix(trimmed)
    if (suffix.season != null && suffix.episode != null) {
        val baseId = canonicalizeBaseId(suffix.baseId)
        if (baseId.isNotEmpty()) {
            val videoId = "$baseId:${suffix.season}:${suffix.episode}"
            return NuvioMediaId(
                contentId = baseId,
                videoId = videoId,
                isEpisode = true,
                season = suffix.season,
                episode = suffix.episode,
                kind = "episode",
                addonLookupId = videoId
            )
        }
    }

    val normalized = canonicalizeBaseId(stripSeriesPrefix(trimmed))

    return NuvioMediaId(
        contentId = normalized,
        videoId = null,
        isEpisode = false,
        season = null,
        episode = null,
        kind = "content",
        addonLookupId = normalized
    )
}

fun formatIdForIdPrefixes(
    input: String,
    mediaType: String,
    idPrefixes: List<String> = emptyList()
): String? {
    val raw = input.trim()
    if (raw.isEmpty()) {
        return null
    }

    val suffix = parseEpisodeSuffix(raw)
    val baseRaw = suffix.baseId.trim()
    if (baseRaw.isEmpty()) {
        return null
    }

    val normalizedBase = canonicalizeBaseId(baseRaw)
    val episodeSuffix = if (suffix.season != null && suffix.episode != null) {
        ":${suffix.season}:${suffix.episode}"
    } else {
        ""
    }

    val providerKind = if (mediaType.equals("movie", ignoreCase = true)) "movie" else "show"
    val candidates = linkedSetOf<String>()

    val imdbId = normalizedBase.takeIf { IMDB_ID_REGEX.matches(it) }
    val tmdbId = normalizedBase.tmdbNumericIdOrNull()

    if (imdbId != null) {
        candidates += "$imdbId$episodeSuffix"
        candidates += "imdb:$imdbId$episodeSuffix"
        candidates += "imdb:$providerKind:$imdbId$episodeSuffix"
    }

    if (tmdbId != null) {
        candidates += "tmdb:$tmdbId$episodeSuffix"
        candidates += "tmdb:$providerKind:$tmdbId$episodeSuffix"
    }

    val needsNumericInference =
        NUMERIC_ID_REGEX.matches(normalizedBase) &&
            imdbId == null &&
            tmdbId == null &&
            !URI_SCHEME_REGEX.containsMatchIn(normalizedBase)
    if (needsNumericInference) {
        val inferredProvider = inferNumericProvider(idPrefixes)
        if (inferredProvider == null && idPrefixes.isNotEmpty()) {
            return null
        }
        if (inferredProvider != null) {
            candidates += "$inferredProvider:$normalizedBase$episodeSuffix"
            candidates += "$inferredProvider:$providerKind:$normalizedBase$episodeSuffix"
        }
    }

    candidates += "$normalizedBase$episodeSuffix"

    if (idPrefixes.isNotEmpty()) {
        return candidates.firstOrNull { candidate ->
            idPrefixes.any { prefix -> candidate.startsWith(prefix) }
        }
    }

    return candidates.firstOrNull()
}

private fun stripSeriesPrefix(value: String): String {
    if (!value.startsWith("series:", ignoreCase = true)) {
        return value
    }

    return value.substring(7)
}

private fun parseEpisodeSuffix(value: String): EpisodeSuffix {
    val normalized = stripSeriesPrefix(value.trim())
    val parts = normalized.split(":")
    if (parts.size < 3) {
        return EpisodeSuffix(baseId = normalized, season = null, episode = null)
    }

    val season = parts[parts.lastIndex - 1].toIntOrNull()
    val episode = parts.last().toIntOrNull()
    if (season == null || season <= 0 || episode == null || episode <= 0) {
        return EpisodeSuffix(baseId = normalized, season = null, episode = null)
    }

    val baseId = stripSeriesPrefix(parts.dropLast(2).joinToString(":").trim())
    return EpisodeSuffix(
        baseId = baseId,
        season = season,
        episode = episode
    )
}

private fun canonicalizeBaseId(value: String): String {
    val trimmed = stripSeriesPrefix(value.trim())
    if (trimmed.isEmpty()) {
        return ""
    }

    val imdb = extractImdbId(trimmed)
    if (imdb != null) {
        return imdb
    }

    val tmdb = extractTmdbId(trimmed)
    if (tmdb != null) {
        return "tmdb:$tmdb"
    }

    return trimmed
}

private fun extractImdbId(value: String): String? {
    val normalized = stripSeriesPrefix(value.trim())
    if (IMDB_ID_REGEX.matches(normalized)) {
        return normalized.lowercase()
    }

    val parts = normalized.split(':')
    if (parts.isEmpty() || !parts.first().equals("imdb", ignoreCase = true)) {
        return null
    }

    val token = parts.asReversed().firstOrNull { part -> IMDB_ID_REGEX.matches(part) } ?: return null
    return token.lowercase()
}

private fun extractTmdbId(value: String): String? {
    val normalized = stripSeriesPrefix(value.trim())
    if (!normalized.startsWith("tmdb:", ignoreCase = true)) {
        return null
    }

    val parts = normalized.split(':')
    if (parts.size < 2) {
        return null
    }

    val direct = parts.getOrNull(1)
    if (direct != null && NUMERIC_ID_REGEX.matches(direct)) {
        return direct
    }

    val typed = parts.getOrNull(2)
    return if (typed != null && NUMERIC_ID_REGEX.matches(typed)) typed else null
}

private fun String.tmdbNumericIdOrNull(): String? {
    if (!startsWith("tmdb:", ignoreCase = true)) {
        return null
    }
    val value = substringAfter(':').substringBefore(':')
    return value.takeIf { NUMERIC_ID_REGEX.matches(it) }
}

private fun inferNumericProvider(idPrefixes: List<String>): String? {
    if (idPrefixes.isEmpty()) {
        return null
    }

    val providers =
        idPrefixes
            .mapNotNull { prefix ->
                when {
                    prefix.startsWith("tmdb:", ignoreCase = true) -> "tmdb"
                    prefix.startsWith("trakt:", ignoreCase = true) -> "trakt"
                    prefix.startsWith("tvdb:", ignoreCase = true) -> "tvdb"
                    prefix.startsWith("simkl:", ignoreCase = true) -> "simkl"
                    else -> null
                }
            }
            .distinct()

    return providers.singleOrNull()
}
