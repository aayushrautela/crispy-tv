package com.crispy.tv.addons.sources

import android.content.Context
import com.crispy.tv.domain.metadata.AddonMetadataCandidate
import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabDataSource
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.MetadataLabPayload
import com.crispy.tv.player.MetadataLabRequest
import com.crispy.tv.player.MetadataTransportStat
import com.crispy.tv.addons.lookup.parseLookupId
import com.crispy.tv.addons.registry.AddonManifestSeed
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.addons.streams.asApiPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.crispy.tv.addons.optJsonArray
import com.crispy.tv.addons.optJsonObject
import com.crispy.tv.addons.optStringOrEmpty
import com.crispy.tv.addons.stringAtOrEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RemoteMetadataLabDataSource(
    context: Context,
    private val httpClient: CrispyHttpClient,
) : MetadataLabDataSource {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext)
    private val addonClient = AddonMetadataClient(addonRegistry, httpClient)

    override suspend fun load(
        request: MetadataLabRequest
    ): MetadataLabPayload = withContext(Dispatchers.IO) {
        val parsedLookupId = parseLookupId(request.rawId)
        val contentId = parsedLookupId.baseId
        val streamLookupId = buildLookupId(contentId, parsedLookupId.season, parsedLookupId.episode)
        val subtitleLookupId = streamLookupId
        val transportStats = addonClient.fetchTransportStats(
            mediaType = request.mediaType,
            contentId = contentId,
            streamLookupId = streamLookupId,
            subtitleLookupId = subtitleLookupId,
            preferredAddonId = request.preferredAddonId
        )

        val primaryMeta = fallbackMetadata(contentId)
        val primaryCandidate = AddonMetadataCandidate(
            addonId = "fallback.local",
            mediaId = primaryMeta.id,
            title = "Unavailable"
        )

        MetadataLabPayload(
            addonResults = listOf(primaryCandidate),
            addonMeta = primaryMeta,
            tmdbMeta = null,
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
) {
    fun supports(kind: AddonResourceKind, mediaType: MetadataLabMediaType): Boolean {
        return supportedTypes[kind]?.contains(mediaType) == true
    }

    fun formatLookupId(lookupId: String): String? {
        val trimmed = lookupId.trim()
        return trimmed.takeIf { it.isNotBlank() }
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

            val formattedStreamLookupId = endpoint.formatLookupId(streamLookupId)
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
                endpoint.formatLookupId(subtitleLookupId)
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
            )
        }

        val addonId = nonBlank(manifest.optStringOrEmpty("id")) ?: seed.addonIdHint
        val resources = manifest.optJsonArray("resources")

        val supportedTypes = mutableMapOf<AddonResourceKind, MutableSet<MetadataLabMediaType>>()

        if (resources == null) {
            val defaults = setOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES)
            supportedTypes[AddonResourceKind.STREAM] = defaults.toMutableSet()
            supportedTypes[AddonResourceKind.SUBTITLES] = defaults.toMutableSet()
        } else {
            for (resource in resources) {
            when (resource) {

                is JsonPrimitive -> {
                    // `is String` is not a type a JSON string has: a JSON *number*
                    // is a `JsonPrimitive` too, so widening this arm would admit
                    // values `org.json` dropped, and `contentOrNull` cannot tell
                    // `1234` from `"1234"`. `isString` is the only discriminator,
                    // and a `when` arm cannot express it -- so it becomes a guard.
                    val text = resource.contentOrNull
                    if (resource.isString && text != null) {
                        val kind = toResourceKindOrNull(text) ?: continue
                        supportedTypes.getOrPut(kind) { mutableSetOf() }.add(MetadataLabMediaType.MOVIE)
                        supportedTypes.getOrPut(kind) { mutableSetOf() }.add(MetadataLabMediaType.SERIES)
                    }
                }

                    is JsonObject -> {
                        val kind = toResourceKindOrNull(nonBlank(resource.optStringOrEmpty("name"))) ?: continue
                        val types = parseManifestStringArray(resource.optJsonArray("types"))
                        val targets =
                            if (types.isEmpty()) {
                                listOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES)
                            } else {
                                types.mapNotNull { toMediaTypeOrNull(it) }
                            }

                        for (target in targets) {
                            supportedTypes.getOrPut(kind) { mutableSetOf() }.add(target)
                        }
                    }
                    // `JsonElement` is SEALED, so this `when` is exhaustiveness-checked
                    // where `org.json`'s `Any?` was not, and a JSON *array* element was
                    // neither a `String` nor a `JSONObject` -- so the old `when` fell
                    // through and did nothing, and so does this arm. It has to be LAST:
                    // the compiler rejects an `else` entry in any other position.
                    else -> Unit
                }
            }
        }

        val frozenSupportedTypes = supportedTypes.mapValues { (_, values) -> values.toSet() }

        return AddonEndpoint(
            addonId = addonId,
            baseUrl = seed.baseUrl,
            encodedQuery = seed.encodedQuery,
            supportedTypes = frozenSupportedTypes,
        )
    }

    private fun parseCachedManifest(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) {
            return null
        }
        // `Json.parseToJsonElement` raises `SerializationException` where
        // `JSONObject(String)` raised `JSONException`; the `runCatching`
        // absorbs either and nothing in `:addons` names either type.
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun fallbackManifestFor(seed: AddonManifestSeed): JsonObject? {
        val looksLikeCinemeta =
            seed.addonIdHint.contains("cinemeta", ignoreCase = true) ||
                seed.manifestUrl.contains("cinemeta", ignoreCase = true)
        if (looksLikeCinemeta) {
            return buildJsonObject {
                put("id", "com.linvo.cinemeta")
                put("types", buildJsonArray { add("movie"); add("series") })
            }
        }

        val looksLikeOpenSubtitles =
            seed.addonIdHint.contains("opensubtitles", ignoreCase = true) ||
                seed.manifestUrl.contains("opensubtitles", ignoreCase = true)
        if (looksLikeOpenSubtitles) {
            return buildJsonObject {
                put("id", "org.stremio.opensubtitlesv3")
                put("types", buildJsonArray { add("movie"); add("series") })
            }
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
        return response.optJsonArray(arrayKey)?.size ?: 0
    }

    private suspend fun fetchResource(
        endpoint: AddonEndpoint,
        resourceKind: AddonResourceKind,
        mediaType: MetadataLabMediaType,
        lookupId: String
    ): JsonObject? {
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

    private fun parseCastWithDetails(castWithDetails: JsonArray?): List<String> {
        if (castWithDetails == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (item in castWithDetails) {
        when (item) {

            is JsonPrimitive -> {
                    // `is String` is not a type a JSON string has: a JSON *number*
                    // is a `JsonPrimitive` too, so widening this arm would admit
                    // values `org.json` dropped, and `contentOrNull` cannot tell
                    // `1234` from `"1234"`. `isString` is the only discriminator,
                    // and a `when` arm cannot express it -- so it becomes a guard.
                    val text = item.contentOrNull
                    if (item.isString && text != null) {
                        output += text
                    }
                }

                is JsonObject -> {
                    val name = nonBlank(item.optStringOrEmpty("name"))
                    val character = nonBlank(item.optStringOrEmpty("character"))
                    if (name != null && character != null) {
                        output += "$name as $character"
                    } else if (name != null) {
                        output += name
                    }
                }
                // `JsonElement` is SEALED, so this `when` is exhaustiveness-checked
                // where `org.json`'s `Any?` was not, and a JSON *array* element was
                // neither a `String` nor a `JSONObject` -- so the old `when` fell
                // through and did nothing, and so does this arm. It has to be LAST:
                // the compiler rejects an `else` entry in any other position.
                else -> Unit
            }
        }
        return output
    }

    private fun parseSimilarIds(similar: JsonArray?): List<String> {
        if (similar == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (item in similar) {
        when (item) {

            is JsonPrimitive -> {
                    // `is String` is not a type a JSON string has: a JSON *number*
                    // is a `JsonPrimitive` too, so widening this arm would admit
                    // values `org.json` dropped, and `contentOrNull` cannot tell
                    // `1234` from `"1234"`. `isString` is the only discriminator,
                    // and a `when` arm cannot express it -- so it becomes a guard.
                    val text = item.contentOrNull
                    if (item.isString && text != null) {
                    val normalized = nonBlank(text)
                    if (normalized != null) {
                        output += normalized
                    }
                    }
                }

                is JsonObject -> {
                    val id = nonBlank(item.optStringOrEmpty("id"))
                    if (id != null) {
                        output += id
                    }
                }
                // `JsonElement` is SEALED, so this `when` is exhaustiveness-checked
                // where `org.json`'s `Any?` was not, and a JSON *array* element was
                // neither a `String` nor a `JSONObject` -- so the old `when` fell
                // through and did nothing, and so does this arm. It has to be LAST:
                // the compiler rejects an `else` entry in any other position.
                else -> Unit
            }
        }
        return output
    }

    private fun parseCollectionItems(collection: JsonObject?): List<String> {
        val items = collection?.optJsonArray("items") ?: return emptyList()
        val output = mutableListOf<String>()
        for (item in items) {
        when (item) {

            is JsonPrimitive -> {
                    // `is String` is not a type a JSON string has: a JSON *number*
                    // is a `JsonPrimitive` too, so widening this arm would admit
                    // values `org.json` dropped, and `contentOrNull` cannot tell
                    // `1234` from `"1234"`. `isString` is the only discriminator,
                    // and a `when` arm cannot express it -- so it becomes a guard.
                    val text = item.contentOrNull
                    if (item.isString && text != null) {
                    val value = nonBlank(text)
                    if (value != null) {
                        output += value
                    }
                    }
                }

                is JsonObject -> {
                    val value = nonBlank(item.optStringOrEmpty("id"))
                    if (value != null) {
                        output += value
                    }
                }
                // `JsonElement` is SEALED, so this `when` is exhaustiveness-checked
                // where `org.json`'s `Any?` was not, and a JSON *array* element was
                // neither a `String` nor a `JSONObject` -- so the old `when` fell
                // through and did nothing, and so does this arm. It has to be LAST:
                // the compiler rejects an `else` entry in any other position.
                else -> Unit
            }
        }
        return output
    }

    private fun parseStringArray(values: JsonArray?): List<String> {
        if (values == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (index in 0 until values.size) {
            val value = nonBlank(values.stringAtOrEmpty(index))
            if (value != null) {
                output += value
            }
        }
        return output
    }

    private fun parseManifestStringArray(values: JsonArray?): List<String> {
        if (values == null) {
            return emptyList()
        }

        val output = mutableListOf<String>()
        for (index in 0 until values.size) {
            val raw = values.getOrNull(index)
            // A JSON number is a `JsonPrimitive` too, so `isString` is the only
            // thing that can keep this arm to what `is String` accepted.
            if (raw is JsonPrimitive && raw.isString) {
                nonBlank(raw.contentOrNull)?.let { output += it }
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
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun buildLookupId(contentId: String, season: Int?, episode: Int?): String {
    return if (season != null && season > 0 && episode != null && episode > 0) {
        "$contentId:$season:$episode"
    } else {
        contentId
    }
}

private suspend fun CrispyHttpClient.getJsonObject(url: String): JsonObject? {
    val response =
        runCatching {
            get(
                url = url,
                headers = mapOf("Accept" to "application/json"),
            )
        }.getOrNull() ?: return null

    if (response.code !in 200..299) {
        return null
    }

    val body = response.body.trim()
    if (body.isBlank()) {
        return null
    }

    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}
