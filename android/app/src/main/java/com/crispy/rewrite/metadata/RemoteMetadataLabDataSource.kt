package com.crispy.rewrite.metadata

import android.content.Context
import android.net.Uri
import com.crispy.rewrite.domain.metadata.AddonMetadataCandidate
import com.crispy.rewrite.domain.metadata.MetadataRecord
import com.crispy.rewrite.domain.metadata.MetadataSeason
import com.crispy.rewrite.domain.metadata.MetadataVideo
import com.crispy.rewrite.domain.metadata.formatIdForIdPrefixes
import com.crispy.rewrite.player.MetadataLabDataSource
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.MetadataLabPayload
import com.crispy.rewrite.player.MetadataLabRequest
import com.crispy.rewrite.player.MetadataTransportStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RemoteMetadataLabDataSource(
    context: Context,
    addonManifestUrlsCsv: String,
    tmdbApiKey: String
) : MetadataLabDataSource {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)
    private val addonClient = AddonMetadataClient(addonRegistry)
    private val tmdbClient = TmdbMetadataClient(tmdbApiKey)

    override suspend fun load(
        request: MetadataLabRequest,
        normalizedId: com.crispy.rewrite.domain.metadata.NuvioMediaId
    ): MetadataLabPayload = withContext(Dispatchers.IO) {
        val contentId = normalizedId.contentId
        val streamLookupId = normalizedId.videoId ?: contentId
        val subtitleLookupId = normalizedId.videoId ?: contentId
        val tmdbMeta = tmdbClient.fetchMeta(request.mediaType, contentId)

        var addonCandidates = addonClient.fetchMeta(
            mediaType = request.mediaType,
            contentId = contentId,
            preferredAddonId = request.preferredAddonId
        )
        var transportStats = addonClient.fetchTransportStats(
            mediaType = request.mediaType,
            contentId = contentId,
            streamLookupId = streamLookupId,
            subtitleLookupId = subtitleLookupId,
            preferredAddonId = request.preferredAddonId
        )

        if (addonCandidates.isEmpty() && contentId.startsWith("tmdb:", ignoreCase = true)) {
            val bridgedImdb = tmdbMeta?.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
            if (bridgedImdb != null) {
                addonCandidates = addonClient.fetchMeta(
                    mediaType = request.mediaType,
                    contentId = bridgedImdb,
                    preferredAddonId = request.preferredAddonId
                )
                if (transportStats.isEmpty()) {
                    transportStats = addonClient.fetchTransportStats(
                        mediaType = request.mediaType,
                        contentId = bridgedImdb,
                        streamLookupId = rewriteLookupBase(streamLookupId, contentId, bridgedImdb),
                        subtitleLookupId = rewriteLookupBase(subtitleLookupId, contentId, bridgedImdb),
                        preferredAddonId = request.preferredAddonId
                    )
                }
            }
        }

        if (addonCandidates.isEmpty()) {
            val fallback = fallbackMetadata(contentId)
            return@withContext MetadataLabPayload(
                addonResults = listOf(
                    AddonMetadataCandidate(
                        addonId = "fallback.local",
                        mediaId = fallback.id,
                        title = "Unavailable"
                    )
                ),
                addonMeta = fallback,
                tmdbMeta = tmdbMeta,
                transportStats = transportStats
            )
        }

        val addonResults = addonCandidates.map {
            AddonMetadataCandidate(
                addonId = it.addonId,
                mediaId = it.record.id,
                title = it.title
            )
        }

        MetadataLabPayload(
            addonResults = addonResults,
            addonMeta = addonCandidates.first().record,
            tmdbMeta = tmdbMeta,
            transportStats = transportStats
        )
    }

    private fun fallbackMetadata(contentId: String): MetadataRecord {
        return MetadataRecord(
            id = contentId,
            imdbId = contentId.takeIf { it.startsWith("tt", ignoreCase = true) },
            cast = emptyList(),
            director = emptyList(),
            castWithDetails = emptyList(),
            similar = emptyList(),
            collectionItems = emptyList(),
            seasons = emptyList(),
            videos = emptyList()
        )
    }

    private fun rewriteLookupBase(lookupId: String, originalBase: String, bridgedBase: String): String {
        val prefix = "$originalBase:"
        return if (lookupId.startsWith(prefix)) {
            bridgedBase + lookupId.removePrefix(originalBase)
        } else {
            bridgedBase
        }
    }
}

private enum class AddonResourceKind(val apiPath: String) {
    META("meta"),
    STREAM("stream"),
    SUBTITLES("subtitles")
}

private data class AddonEndpoint(
    val addonId: String,
    val baseUrl: String,
    val encodedQuery: String?,
    val supportedTypes: Map<AddonResourceKind, Set<MetadataLabMediaType>>,
    val addonIdPrefixes: List<String>,
    val resourceIdPrefixes: Map<AddonResourceKind, Map<MetadataLabMediaType, List<String>>>
) {
    fun supports(kind: AddonResourceKind, mediaType: MetadataLabMediaType): Boolean {
        return supportedTypes[kind]?.contains(mediaType) == true
    }

    fun acceptedIdPrefixes(kind: AddonResourceKind, mediaType: MetadataLabMediaType): List<String> {
        val resourcePrefixes = resourceIdPrefixes[kind]?.get(mediaType)
        return if (!resourcePrefixes.isNullOrEmpty()) {
            resourcePrefixes
        } else {
            addonIdPrefixes
        }
    }

    fun formatLookupId(kind: AddonResourceKind, mediaType: MetadataLabMediaType, lookupId: String): String? {
        if (lookupId.isBlank()) {
            return null
        }

        return formatIdForIdPrefixes(
            input = lookupId,
            mediaType = mediaType.asIdKind(),
            idPrefixes = acceptedIdPrefixes(kind, mediaType)
        )
    }

    fun accepts(kind: AddonResourceKind, mediaType: MetadataLabMediaType, lookupId: String): Boolean {
        return formatLookupId(kind, mediaType, lookupId) != null
    }
}

private data class AddonCandidate(
    val addonId: String,
    val title: String,
    val record: MetadataRecord
)

private class AddonMetadataClient(
    private val addonRegistry: MetadataAddonRegistry
) {
    @Volatile
    private var resolvedEndpoints: List<AddonEndpoint>? = null
    @Volatile
    private var resolvedFingerprint: String? = null

    fun fetchMeta(
        mediaType: MetadataLabMediaType,
        contentId: String,
        preferredAddonId: String?
    ): List<AddonCandidate> {
        if (contentId.isBlank()) {
            return emptyList()
        }

        val endpoints = resolveEndpoints().filter { it.supports(AddonResourceKind.META, mediaType) }
        if (!isValidContentId(endpoints, AddonResourceKind.META, mediaType, contentId)) {
            return emptyList()
        }

        val orderedEndpoints = orderedEndpoints(endpoints, preferredAddonId)
        val candidates = mutableListOf<AddonCandidate>()
        for (endpoint in orderedEndpoints) {
            val requestId = endpoint.formatLookupId(AddonResourceKind.META, mediaType, contentId)
            if (requestId == null) {
                continue
            }
            val response = fetchAddonMeta(endpoint, mediaType, requestId) ?: continue
            val metaObject = response.optJSONObject("meta") ?: continue
            val record = parseAddonMetadata(metaObject, fallbackId = requestId)
            val title =
                nonBlank(metaObject.optString("name"))
                    ?: nonBlank(metaObject.optString("title"))
                    ?: record.id

            candidates += AddonCandidate(
                addonId = endpoint.addonId,
                title = title,
                record = record
            )
        }
        return candidates
    }

    fun fetchTransportStats(
        mediaType: MetadataLabMediaType,
        contentId: String,
        streamLookupId: String,
        subtitleLookupId: String,
        preferredAddonId: String?
    ): List<MetadataTransportStat> {
        if (contentId.isBlank()) {
            return emptyList()
        }

        val endpoints = resolveEndpoints()
        val orderedEndpoints = orderedEndpoints(endpoints, preferredAddonId)
        val stats = mutableListOf<MetadataTransportStat>()
        for (endpoint in orderedEndpoints) {
            var streamCount = 0
            var subtitleCount = 0
            var streamRequestId: String? = null
            var subtitleRequestId: String? = null

            val formattedStreamLookupId = endpoint.formatLookupId(AddonResourceKind.STREAM, mediaType, streamLookupId)
            if (
                endpoint.supports(AddonResourceKind.STREAM, mediaType) &&
                    formattedStreamLookupId != null
            ) {
                streamRequestId = formattedStreamLookupId
                streamCount = fetchResourceCount(
                    endpoint,
                    AddonResourceKind.STREAM,
                    mediaType,
                    formattedStreamLookupId
                )
            }

            val formattedSubtitleLookupId =
                endpoint.formatLookupId(AddonResourceKind.SUBTITLES, mediaType, subtitleLookupId)
            if (
                endpoint.supports(AddonResourceKind.SUBTITLES, mediaType) &&
                    formattedSubtitleLookupId != null
            ) {
                subtitleRequestId = formattedSubtitleLookupId
                subtitleCount = fetchResourceCount(
                    endpoint,
                    AddonResourceKind.SUBTITLES,
                    mediaType,
                    formattedSubtitleLookupId
                )
            }

            if (streamCount > 0 || subtitleCount > 0) {
                stats += MetadataTransportStat(
                    addonId = endpoint.addonId,
                    streamLookupId = streamRequestId ?: streamLookupId,
                    streamCount = streamCount,
                    subtitleLookupId = subtitleRequestId ?: subtitleLookupId,
                    subtitleCount = subtitleCount
                )
            }
        }

        return stats
    }

    @Synchronized
    private fun resolveEndpoints(): List<AddonEndpoint> {
        val seeds = addonRegistry.orderedSeeds()
        if (seeds.isEmpty()) {
            resolvedFingerprint = ""
            resolvedEndpoints = emptyList()
            return emptyList()
        }

        val fingerprint =
            seeds.joinToString(separator = "|") { seed ->
                listOf(
                    seed.installationId,
                    seed.manifestUrl,
                    seed.baseUrl,
                    seed.encodedQuery.orEmpty(),
                    seed.cachedManifestJson?.hashCode()?.toString().orEmpty()
                ).joinToString(separator = "::")
            }

        resolvedEndpoints?.let { cached ->
            if (resolvedFingerprint == fingerprint) {
                return cached
            }
        }

        val resolved = seeds.map { seed ->
            resolveEndpoint(seed = seed)
        }
        resolvedFingerprint = fingerprint
        resolvedEndpoints = resolved
        return resolved
    }

    private fun resolveEndpoint(seed: AddonManifestSeed): AddonEndpoint {
        val networkManifest = httpGetJson(seed.manifestUrl)
        if (networkManifest != null) {
            addonRegistry.cacheManifest(seed, networkManifest)
        }

        val manifest =
            networkManifest
                ?: parseCachedManifest(seed.cachedManifestJson)
                ?: fallbackManifestFor(seed)

        if (manifest == null) {
            return AddonEndpoint(
                addonId = seed.addonIdHint,
                baseUrl = seed.baseUrl,
                encodedQuery = seed.encodedQuery,
                supportedTypes =
                    mapOf(
                        AddonResourceKind.META to setOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES),
                        AddonResourceKind.STREAM to setOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES),
                        AddonResourceKind.SUBTITLES to setOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES)
                    ),
                addonIdPrefixes = emptyList(),
                resourceIdPrefixes = emptyMap()
            )
        }

        val addonId = nonBlank(manifest.optString("id")) ?: seed.addonIdHint
        val addonIdPrefixes =
            parseManifestStringArray(manifest.optJSONArray("idPrefixes")).ifEmpty {
                nonBlank(manifest.optString("idPrefix"))?.let(::listOf).orEmpty()
            }
        val resources = manifest.optJSONArray("resources")

        val supportedTypes = mutableMapOf<AddonResourceKind, MutableSet<MetadataLabMediaType>>()
        val resourcePrefixes =
            mutableMapOf<AddonResourceKind, MutableMap<MetadataLabMediaType, MutableList<String>>>()

        if (resources == null) {
            val defaults = setOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES)
            supportedTypes[AddonResourceKind.META] = defaults.toMutableSet()
            supportedTypes[AddonResourceKind.STREAM] = defaults.toMutableSet()
            supportedTypes[AddonResourceKind.SUBTITLES] = defaults.toMutableSet()
        } else {
            for (index in 0 until resources.length()) {
                when (val resource = resources.opt(index)) {
                    is String -> {
                        val kind = toResourceKindOrNull(resource) ?: continue
                        supportedTypes.getOrPut(kind) { mutableSetOf() }.add(MetadataLabMediaType.MOVIE)
                        supportedTypes.getOrPut(kind) { mutableSetOf() }.add(MetadataLabMediaType.SERIES)
                    }

                    is JSONObject -> {
                        val kind = toResourceKindOrNull(nonBlank(resource.optString("name"))) ?: continue
                        val types = parseManifestStringArray(resource.optJSONArray("types"))
                        val prefixes =
                            parseManifestStringArray(resource.optJSONArray("idPrefixes")).ifEmpty {
                                nonBlank(resource.optString("idPrefix"))?.let(::listOf).orEmpty()
                            }
                        val targets =
                            if (types.isEmpty()) {
                                listOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES)
                            } else {
                                types.mapNotNull { toMediaTypeOrNull(it) }
                            }

                        for (target in targets) {
                            supportedTypes.getOrPut(kind) { mutableSetOf() }.add(target)
                            if (prefixes.isNotEmpty()) {
                                resourcePrefixes
                                    .getOrPut(kind) { mutableMapOf() }
                                    .getOrPut(target) { mutableListOf() }
                                    .addAll(prefixes)
                            }
                        }
                    }
                }
            }
        }

        val frozenSupportedTypes = supportedTypes.mapValues { (_, values) -> values.toSet() }
        val frozenPrefixes =
            resourcePrefixes.mapValues { (_, byType) ->
                byType.mapValues { (_, values) -> values.distinct() }
            }

        return AddonEndpoint(
            addonId = addonId,
            baseUrl = seed.baseUrl,
            encodedQuery = seed.encodedQuery,
            supportedTypes = frozenSupportedTypes,
            addonIdPrefixes = addonIdPrefixes,
            resourceIdPrefixes = frozenPrefixes
        )
    }

    private fun parseCachedManifest(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun fallbackManifestFor(seed: AddonManifestSeed): JSONObject? {
        val looksLikeCinemeta =
            seed.addonIdHint.contains("cinemeta", ignoreCase = true) ||
                seed.manifestUrl.contains("cinemeta", ignoreCase = true)
        if (looksLikeCinemeta) {
            return JSONObject()
                .put("id", "com.linvo.cinemeta")
                .put("types", JSONArray().put("movie").put("series"))
                .put(
                    "resources",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("name", "catalog")
                                .put("types", JSONArray().put("movie").put("series"))
                                .put("idPrefixes", JSONArray().put("tt"))
                        )
                        .put(
                            JSONObject()
                                .put("name", "meta")
                                .put("types", JSONArray().put("movie").put("series"))
                                .put("idPrefixes", JSONArray().put("tt"))
                        )
                )
        }

        val looksLikeOpenSubtitles =
            seed.addonIdHint.contains("opensubtitles", ignoreCase = true) ||
                seed.manifestUrl.contains("opensubtitles", ignoreCase = true)
        if (looksLikeOpenSubtitles) {
            return JSONObject()
                .put("id", "org.stremio.opensubtitlesv3")
                .put("types", JSONArray().put("movie").put("series"))
                .put(
                    "resources",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "subtitles")
                            .put("types", JSONArray().put("movie").put("series"))
                            .put("idPrefixes", JSONArray().put("tt"))
                    )
                )
        }

        return null
    }

    private fun orderedEndpoints(
        endpoints: List<AddonEndpoint>,
        preferredAddonId: String?
    ): List<AddonEndpoint> {
        val preferred = preferredAddonId?.trim()?.takeIf { it.isNotEmpty() }
        return endpoints
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<AddonEndpoint>>(
                { endpoint ->
                    when {
                        preferred != null && endpoint.value.addonId.equals(preferred, ignoreCase = true) -> 0
                        endpoint.value.addonId.contains("cinemeta", ignoreCase = true) -> 1
                        else -> 2
                    }
                },
                { endpoint -> endpoint.index }
            )
            )
            .map { it.value }
    }

    private fun isValidContentId(
        endpoints: List<AddonEndpoint>,
        resourceKind: AddonResourceKind,
        mediaType: MetadataLabMediaType,
        contentId: String
    ): Boolean {
        if (contentId.isBlank()) {
            return false
        }

        if (endpoints.isEmpty()) {
            return false
        }

        val allPrefixes = endpoints.flatMap { it.acceptedIdPrefixes(resourceKind, mediaType) }.distinct()
        if (allPrefixes.isEmpty()) {
            return true
        }

        return formatIdForIdPrefixes(
            input = contentId,
            mediaType = mediaType.asIdKind(),
            idPrefixes = allPrefixes
        ) != null
    }

    private fun fetchAddonMeta(
        endpoint: AddonEndpoint,
        mediaType: MetadataLabMediaType,
        contentId: String
    ): JSONObject? {
        return fetchResource(
            endpoint = endpoint,
            resourceKind = AddonResourceKind.META,
            mediaType = mediaType,
            lookupId = contentId
        )
    }

    private fun fetchResourceCount(
        endpoint: AddonEndpoint,
        resourceKind: AddonResourceKind,
        mediaType: MetadataLabMediaType,
        lookupId: String
    ): Int {
        val response = fetchResource(endpoint, resourceKind, mediaType, lookupId) ?: return 0
        val arrayKey = when (resourceKind) {
            AddonResourceKind.STREAM -> "streams"
            AddonResourceKind.SUBTITLES -> "subtitles"
            AddonResourceKind.META -> "meta"
        }
        return response.optJSONArray(arrayKey)?.length() ?: 0
    }

    private fun fetchResource(
        endpoint: AddonEndpoint,
        resourceKind: AddonResourceKind,
        mediaType: MetadataLabMediaType,
        lookupId: String
    ): JSONObject? {
        val encodedId = URLEncoder.encode(lookupId, StandardCharsets.UTF_8.name())
        val typePath = mediaType.asApiPath()
        val url = buildString {
            append(endpoint.baseUrl.trimEnd('/'))
            append('/')
            append(resourceKind.apiPath)
            append('/')
            append(typePath)
            append('/')
            append(encodedId)
            append(".json")
            if (!endpoint.encodedQuery.isNullOrBlank()) {
                append('?')
                append(endpoint.encodedQuery)
            }
        }
        return httpGetJson(url)
    }

    private fun parseAddonMetadata(meta: JSONObject, fallbackId: String): MetadataRecord {
        val id = nonBlank(meta.optString("id")) ?: fallbackId
        val videos = parseVideos(meta.optJSONArray("videos"))

        return MetadataRecord(
            id = id,
            imdbId =
                nonBlank(meta.optString("imdb_id"))
                    ?: nonBlank(meta.optString("imdbId")),
            cast = parseStringArray(meta.optJSONArray("cast")),
            director = parseStringArray(meta.optJSONArray("director")),
            castWithDetails = parseCastWithDetails(meta.optJSONArray("castWithDetails")),
            similar = parseSimilarIds(meta.optJSONArray("similar")),
            collectionItems = parseCollectionItems(meta.optJSONObject("collection")),
            seasons = parseSeasons(meta.optJSONArray("seasons"), id, videos),
            videos = videos
        )
    }

    private fun parseVideos(videos: JSONArray?): List<MetadataVideo> {
        if (videos == null) {
            return emptyList()
        }

        val output = mutableListOf<MetadataVideo>()
        for (index in 0 until videos.length()) {
            val entry = videos.optJSONObject(index) ?: continue
            val season = entry.optInt("season", -1).takeIf { it > 0 }
            val episode = entry.optInt("episode", -1).takeIf { it > 0 }
            val released = nonBlank(entry.optString("released"))
            output += MetadataVideo(
                season = season,
                episode = episode,
                released = released
            )
        }
        return output
    }

    private fun parseSeasons(
        seasons: JSONArray?,
        contentId: String,
        videos: List<MetadataVideo>
    ): List<MetadataSeason> {
        if (seasons == null) {
            return emptyList()
        }

        val output = mutableListOf<MetadataSeason>()
        for (index in 0 until seasons.length()) {
            val seasonObj = seasons.optJSONObject(index) ?: continue
            val seasonNumber =
                seasonObj.optInt("season", -1).takeIf { it > 0 }
                    ?: seasonObj.optInt("season_number", -1).takeIf { it > 0 }
                    ?: continue

            val explicitEpisodeCount =
                seasonObj.optInt("episode_count", -1).takeIf { it > 0 }
                    ?: seasonObj.optInt("episodes", -1).takeIf { it > 0 }
            val derivedEpisodeCount =
                videos.count {
                    it.season == seasonNumber &&
                        it.episode != null &&
                        it.episode > 0
                }
            val episodeCount = explicitEpisodeCount ?: derivedEpisodeCount
            if (episodeCount <= 0) {
                continue
            }

            output += MetadataSeason(
                id = "$contentId:season:$seasonNumber",
                name = nonBlank(seasonObj.optString("name")) ?: "Season $seasonNumber",
                overview = nonBlank(seasonObj.optString("overview")) ?: "",
                seasonNumber = seasonNumber,
                episodeCount = episodeCount,
                airDate = nonBlank(seasonObj.optString("air_date"))
                    ?: nonBlank(seasonObj.optString("first_aired"))
            )
        }
        return output.sortedBy { it.seasonNumber }
    }

    private fun parseCastWithDetails(castWithDetails: JSONArray?): List<String> {
        if (castWithDetails == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (index in 0 until castWithDetails.length()) {
            val item = castWithDetails.opt(index)
            when (item) {
                is String -> output += item
                is JSONObject -> {
                    val name = nonBlank(item.optString("name"))
                    val character = nonBlank(item.optString("character"))
                    if (name != null && character != null) {
                        output += "$name as $character"
                    } else if (name != null) {
                        output += name
                    }
                }
            }
        }
        return output
    }

    private fun parseSimilarIds(similar: JSONArray?): List<String> {
        if (similar == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (index in 0 until similar.length()) {
            val item = similar.opt(index)
            when (item) {
                is String -> {
                    val normalized = nonBlank(item)
                    if (normalized != null) {
                        output += normalized
                    }
                }

                is JSONObject -> {
                    val id = nonBlank(item.optString("id"))
                    if (id != null) {
                        output += id
                    }
                }
            }
        }
        return output
    }

    private fun parseCollectionItems(collection: JSONObject?): List<String> {
        val items = collection?.optJSONArray("items") ?: return emptyList()
        val output = mutableListOf<String>()
        for (index in 0 until items.length()) {
            val item = items.opt(index)
            when (item) {
                is String -> {
                    val value = nonBlank(item)
                    if (value != null) {
                        output += value
                    }
                }

                is JSONObject -> {
                    val value = nonBlank(item.optString("id"))
                    if (value != null) {
                        output += value
                    }
                }
            }
        }
        return output
    }

    private fun parseStringArray(values: JSONArray?): List<String> {
        if (values == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (index in 0 until values.length()) {
            val value = nonBlank(values.optString(index))
            if (value != null) {
                output += value
            }
        }
        return output
    }

    private fun parseManifestStringArray(values: JSONArray?): List<String> {
        if (values == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (index in 0 until values.length()) {
            val raw = values.opt(index)
            if (raw is String) {
                nonBlank(raw)?.let { output += it }
            }
        }
        return output
    }

    private fun toMediaTypeOrNull(value: String): MetadataLabMediaType? {
        return when {
            value.equals("movie", ignoreCase = true) -> MetadataLabMediaType.MOVIE
            value.equals("series", ignoreCase = true) || value.equals("tv", ignoreCase = true) -> MetadataLabMediaType.SERIES
            else -> null
        }
    }

    private fun toResourceKindOrNull(value: String?): AddonResourceKind? {
        return when {
            value.equals("meta", ignoreCase = true) -> AddonResourceKind.META
            value.equals("stream", ignoreCase = true) -> AddonResourceKind.STREAM
            value.equals("subtitles", ignoreCase = true) -> AddonResourceKind.SUBTITLES
            else -> null
        }
    }

    private fun MetadataLabMediaType.asApiPath(): String {
        return if (this == MetadataLabMediaType.SERIES) "series" else "movie"
    }
}

private class TmdbMetadataClient(
    private val apiKey: String
) {
    fun fetchMeta(mediaType: MetadataLabMediaType, contentId: String): MetadataRecord? {
        if (apiKey.isBlank()) {
            return null
        }

        val tmdbId = resolveTmdbId(mediaType, contentId) ?: return null
        val details = fetchDetails(mediaType, tmdbId) ?: return null
        val credits = fetchCredits(mediaType, tmdbId)
        val recommendations = fetchRecommendations(mediaType, tmdbId)
        val externalIds = if (mediaType == MetadataLabMediaType.SERIES) {
            fetchExternalIds(mediaType, tmdbId)
        } else {
            null
        }

        val imdbId =
            nonBlank(details.optString("imdb_id"))
                ?: nonBlank(externalIds?.optString("imdb_id"))

        val castPairs = parseCast(credits)
        val castNames = castPairs.map { it.first }
        val castWithDetails = castPairs.mapNotNull { (name, character) ->
            if (character != null) "$name as $character" else name
        }

        val directors =
            if (mediaType == MetadataLabMediaType.MOVIE) {
                parseMovieDirectors(credits)
            } else {
                parseSeriesCreators(details)
            }

        val similar = parseRecommendations(recommendations)
        val collectionItems = if (mediaType == MetadataLabMediaType.MOVIE) {
            parseMovieCollection(details)
        } else {
            emptyList()
        }
        val seasons = if (mediaType == MetadataLabMediaType.SERIES) {
            parseSeriesSeasons(details, tmdbId)
        } else {
            emptyList()
        }

        return MetadataRecord(
            id = "tmdb:$tmdbId",
            imdbId = imdbId,
            cast = castNames,
            director = directors,
            castWithDetails = castWithDetails,
            similar = similar,
            collectionItems = collectionItems,
            seasons = seasons,
            videos = emptyList()
        )
    }

    private fun resolveTmdbId(mediaType: MetadataLabMediaType, contentId: String): Int? {
        val normalized = contentId.trim()
        if (normalized.startsWith("tmdb:", ignoreCase = true)) {
            return normalized.substringAfter("tmdb:").substringBefore(':').toIntOrNull()
        }

        if (normalized.startsWith("tt", ignoreCase = true)) {
            return findTmdbIdByImdb(mediaType, normalized)
        }

        return null
    }

    private fun findTmdbIdByImdb(mediaType: MetadataLabMediaType, imdbId: String): Int? {
        val response = getJson(
            path = "find/$imdbId",
            query = mapOf(
                "api_key" to apiKey,
                "external_source" to "imdb_id"
            )
        ) ?: return null

        val preferredKey = if (mediaType == MetadataLabMediaType.SERIES) "tv_results" else "movie_results"
        val preferredId = response.optJSONArray(preferredKey)?.optJSONObject(0)?.optInt("id", -1)
        if (preferredId != null && preferredId > 0) {
            return preferredId
        }

        val fallbackMovie = response.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id", -1)
        if (fallbackMovie != null && fallbackMovie > 0) {
            return fallbackMovie
        }

        val fallbackTv = response.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id", -1)
        if (fallbackTv != null && fallbackTv > 0) {
            return fallbackTv
        }

        return null
    }

    private fun fetchDetails(mediaType: MetadataLabMediaType, tmdbId: Int): JSONObject? {
        return getJson(
            path = "${mediaPath(mediaType)}/$tmdbId",
            query = mapOf(
                "api_key" to apiKey,
                "language" to "en-US"
            )
        )
    }

    private fun fetchCredits(mediaType: MetadataLabMediaType, tmdbId: Int): JSONObject? {
        return getJson(
            path = "${mediaPath(mediaType)}/$tmdbId/credits",
            query = mapOf(
                "api_key" to apiKey,
                "language" to "en-US"
            )
        )
    }

    private fun fetchRecommendations(mediaType: MetadataLabMediaType, tmdbId: Int): JSONObject? {
        return getJson(
            path = "${mediaPath(mediaType)}/$tmdbId/recommendations",
            query = mapOf(
                "api_key" to apiKey,
                "language" to "en-US",
                "page" to "1"
            )
        )
    }

    private fun fetchExternalIds(mediaType: MetadataLabMediaType, tmdbId: Int): JSONObject? {
        return getJson(
            path = "${mediaPath(mediaType)}/$tmdbId/external_ids",
            query = mapOf("api_key" to apiKey)
        )
    }

    private fun parseCast(credits: JSONObject?): List<Pair<String, String?>> {
        val castArray = credits?.optJSONArray("cast") ?: return emptyList()
        val output = mutableListOf<Pair<String, String?>>()
        for (index in 0 until minOf(castArray.length(), 12)) {
            val actor = castArray.optJSONObject(index) ?: continue
            val name = nonBlank(actor.optString("name")) ?: continue
            val character = nonBlank(actor.optString("character"))
            output += name to character
        }
        return output
    }

    private fun parseMovieDirectors(credits: JSONObject?): List<String> {
        val crewArray = credits?.optJSONArray("crew") ?: return emptyList()
        val output = mutableListOf<String>()
        for (index in 0 until crewArray.length()) {
            val member = crewArray.optJSONObject(index) ?: continue
            val job = nonBlank(member.optString("job"))
            if (!job.equals("Director", ignoreCase = true)) {
                continue
            }
            val name = nonBlank(member.optString("name")) ?: continue
            output += name
        }
        return output.distinct()
    }

    private fun parseSeriesCreators(details: JSONObject): List<String> {
        val creators = details.optJSONArray("created_by") ?: return emptyList()
        val output = mutableListOf<String>()
        for (index in 0 until creators.length()) {
            val name = nonBlank(creators.optJSONObject(index)?.optString("name")) ?: continue
            output += name
        }
        return output.distinct()
    }

    private fun parseRecommendations(recommendations: JSONObject?): List<String> {
        val results = recommendations?.optJSONArray("results") ?: return emptyList()
        val output = mutableListOf<String>()
        for (index in 0 until minOf(results.length(), 20)) {
            val entry = results.optJSONObject(index) ?: continue
            val id = entry.optInt("id", -1)
            if (id > 0) {
                output += "tmdb:$id"
            }
        }
        return output
    }

    private fun parseMovieCollection(details: JSONObject): List<String> {
        val collection = details.optJSONObject("belongs_to_collection") ?: return emptyList()
        val collectionId = collection.optInt("id", -1)
        return if (collectionId > 0) {
            listOf("tmdb:collection:$collectionId")
        } else {
            emptyList()
        }
    }

    private fun parseSeriesSeasons(details: JSONObject, tmdbId: Int): List<MetadataSeason> {
        val seasons = details.optJSONArray("seasons") ?: return emptyList()
        val output = mutableListOf<MetadataSeason>()
        for (index in 0 until seasons.length()) {
            val seasonObject = seasons.optJSONObject(index) ?: continue
            val seasonNumber = seasonObject.optInt("season_number", -1)
            if (seasonNumber <= 0) {
                continue
            }
            val episodeCount = seasonObject.optInt("episode_count", -1)
            if (episodeCount <= 0) {
                continue
            }

            output += MetadataSeason(
                id = "tmdb:$tmdbId:season:$seasonNumber",
                name = nonBlank(seasonObject.optString("name")) ?: "Season $seasonNumber",
                overview = nonBlank(seasonObject.optString("overview")) ?: "",
                seasonNumber = seasonNumber,
                episodeCount = episodeCount,
                airDate = nonBlank(seasonObject.optString("air_date"))
            )
        }
        return output
    }

    private fun mediaPath(mediaType: MetadataLabMediaType): String {
        return if (mediaType == MetadataLabMediaType.SERIES) "tv" else "movie"
    }

    private fun getJson(path: String, query: Map<String, String>): JSONObject? {
        val uriBuilder = Uri.parse("https://api.themoviedb.org/3/$path").buildUpon()
        query.forEach { (key, value) ->
            uriBuilder.appendQueryParameter(key, value)
        }
        return httpGetJson(uriBuilder.build().toString())
    }
}

private fun httpGetJson(url: String): JSONObject? {
    return runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            val body =
                (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { reader -> reader.readText() }
                    .orEmpty()
            if (statusCode !in 200..299 || body.isBlank()) {
                return null
            }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun MetadataLabMediaType.asIdKind(): String {
    return if (this == MetadataLabMediaType.SERIES) "series" else "movie"
}
