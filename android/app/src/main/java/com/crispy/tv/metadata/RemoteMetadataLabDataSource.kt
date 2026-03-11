package com.crispy.tv.metadata

import android.content.Context
import com.crispy.tv.domain.metadata.AddonMetadataCandidate
import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.domain.metadata.formatIdForIdPrefixes
import com.crispy.tv.metadata.tmdb.TmdbMetadataRecordRepository
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabDataSource
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.MetadataLabPayload
import com.crispy.tv.player.MetadataLabRequest
import com.crispy.tv.player.MetadataTransportStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RemoteMetadataLabDataSource(
    context: Context,
    addonManifestUrlsCsv: String,
    private val tmdbRepository: TmdbMetadataRecordRepository,
    private val httpClient: CrispyHttpClient,
) : MetadataLabDataSource {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)
    private val addonClient = AddonMetadataClient(addonRegistry, httpClient)

    override suspend fun load(
        request: MetadataLabRequest,
        normalizedId: com.crispy.tv.domain.metadata.NuvioMediaId
    ): MetadataLabPayload = withContext(Dispatchers.IO) {
        val contentId = normalizedId.contentId
        val streamLookupId = normalizedId.videoId ?: contentId
        val subtitleLookupId = normalizedId.videoId ?: contentId
        val tmdbResult = tmdbRepository.fetchMeta(request.mediaType, contentId)
        val tmdbMeta = tmdbResult?.record
        var transportStats = addonClient.fetchTransportStats(
            mediaType = request.mediaType,
            contentId = contentId,
            streamLookupId = streamLookupId,
            subtitleLookupId = subtitleLookupId,
            preferredAddonId = request.preferredAddonId
        )

        if (transportStats.isEmpty() && contentId.startsWith("tmdb:", ignoreCase = true)) {
            val bridgedImdb = tmdbMeta?.imdbId?.takeIf { it.startsWith("tt", ignoreCase = true) }
            if (bridgedImdb != null) {
                transportStats = addonClient.fetchTransportStats(
                    mediaType = request.mediaType,
                    contentId = bridgedImdb,
                    streamLookupId = rewriteLookupBase(streamLookupId, contentId, bridgedImdb),
                    subtitleLookupId = rewriteLookupBase(subtitleLookupId, contentId, bridgedImdb),
                    preferredAddonId = request.preferredAddonId
                )
            }
        }

        val primaryMeta = tmdbMeta ?: fallbackMetadata(contentId)
        val primaryCandidate =
            if (tmdbResult != null) {
                AddonMetadataCandidate(
                    addonId = "tmdb",
                    mediaId = primaryMeta.id,
                    title = tmdbResult.title
                )
            } else {
                AddonMetadataCandidate(
                    addonId = "fallback.local",
                    mediaId = primaryMeta.id,
                    title = "Unavailable"
                )
            }

        MetadataLabPayload(
            addonResults = listOf(primaryCandidate),
            addonMeta = primaryMeta,
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

private class AddonMetadataClient(
    private val addonRegistry: MetadataAddonRegistry,
    private val httpClient: CrispyHttpClient,
) {
    @Volatile
    private var resolvedEndpoints: List<AddonEndpoint>? = null
    @Volatile
    private var resolvedFingerprint: String? = null

    suspend fun fetchTransportStats(
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

    private suspend fun resolveEndpoints(): List<AddonEndpoint> {
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

        val resolved = mutableListOf<AddonEndpoint>()
        for (seed in seeds) {
            resolved += resolveEndpoint(seed = seed)
        }
        resolvedFingerprint = fingerprint
        resolvedEndpoints = resolved
        return resolved
    }

    private suspend fun resolveEndpoint(seed: AddonManifestSeed): AddonEndpoint {
        val networkManifest = httpClient.getJsonObject(seed.manifestUrl)
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

    private suspend fun fetchResourceCount(
        endpoint: AddonEndpoint,
        resourceKind: AddonResourceKind,
        mediaType: MetadataLabMediaType,
        lookupId: String
    ): Int {
        val response = fetchResource(endpoint, resourceKind, mediaType, lookupId) ?: return 0
        val arrayKey = when (resourceKind) {
            AddonResourceKind.STREAM -> "streams"
            AddonResourceKind.SUBTITLES -> "subtitles"
        }
        return response.optJSONArray(arrayKey)?.length() ?: 0
    }

    private suspend fun fetchResource(
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
        return httpClient.getJsonObject(url)
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
            value.equals("stream", ignoreCase = true) -> AddonResourceKind.STREAM
            value.equals("subtitles", ignoreCase = true) -> AddonResourceKind.SUBTITLES
            else -> null
        }
    }

    private fun MetadataLabMediaType.asApiPath(): String {
        return if (this == MetadataLabMediaType.SERIES) "series" else "movie"
    }
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun MetadataLabMediaType.asIdKind(): String {
    return if (this == MetadataLabMediaType.SERIES) "series" else "movie"
}
