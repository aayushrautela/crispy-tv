package com.crispy.tv.addons.streams

import android.content.Context
import android.util.Log
import com.crispy.tv.addons.registry.AddonManifestSeed
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val TAG = "CrispyAddonSubs"

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

private fun MetadataLabMediaType.asApiPath(): String =
    when (this) {
        MetadataLabMediaType.MOVIE -> "movie"
        MetadataLabMediaType.SERIES -> "series"
        MetadataLabMediaType.ANIME -> "series"
    }

private fun String.encodeAddonPathSegment(): String =
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
    val isSelected: Boolean = false,
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

class AddonStreamsService(
    context: Context,
    addonManifestUrlsCsv: String,
    private val httpClient: CrispyHttpClient,
) {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)
    private val manifestFetchSemaphore = Semaphore(6)
    private val endpointsCacheLock = Any()

    @Volatile
    private var endpointsCache: EndpointsCache? = null

    suspend fun loadStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        preferredProviderId: String? = null,
        onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)? = null,
        onProviderResult: ((ProviderStreamsResult) -> Unit)? = null,
    ): List<ProviderStreamsResult> {
        val normalizedLookupId = lookupId.trim()
        if (normalizedLookupId.isBlank()) return emptyList()

        val candidates =
            orderedEndpoints(resolveEndpoints(), preferredProviderId)
                .filter { endpoint -> endpoint.supports(mediaType, normalizedLookupId) }
        onProvidersResolved?.invoke(
            candidates.map { endpoint ->
                StreamProviderDescriptor(
                    providerId = endpoint.providerId,
                    providerName = endpoint.providerName,
                )
            }
        )
        if (candidates.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            coroutineScope {
                val channel = Channel<Pair<Int, ProviderStreamsResult>>(capacity = candidates.size)
                candidates.forEachIndexed { index, endpoint ->
                    launch {
                        channel.send(index to fetchProviderStreams(endpoint, mediaType, normalizedLookupId))
                    }
                }

                val completed = ArrayList<Pair<Int, ProviderStreamsResult>>(candidates.size)
                repeat(candidates.size) {
                    val indexedResult = channel.receive()
                    completed += indexedResult
                    onProviderResult?.invoke(indexedResult.second)
                }
                channel.close()

                completed
                    .sortedBy { it.first }
                    .map { it.second }
            }
        }
    }

    suspend fun loadProviderStreams(
        mediaType: MetadataLabMediaType,
        lookupId: String,
        providerId: String,
    ): ProviderStreamsResult? {
        val normalizedLookupId = lookupId.trim()
        if (normalizedLookupId.isBlank()) return null

        val endpoint =
            resolveEndpoints().firstOrNull { candidate ->
                candidate.providerId.equals(providerId, ignoreCase = true)
            } ?: return null
        if (!endpoint.supports(mediaType, normalizedLookupId)) {
            return ProviderStreamsResult(
                providerId = endpoint.providerId,
                providerName = endpoint.providerName,
                streams = emptyList(),
                errorMessage = "This provider does not support ${mediaType.asApiPath()} streams."
            )
        }

        return withContext(Dispatchers.IO) {
            fetchProviderStreams(endpoint, mediaType, normalizedLookupId)
        }
    }

    private suspend fun resolveEndpoints(): List<AddonEndpoint> {
        val seeds = addonRegistry.orderedSeeds()
        val fingerprint =
            seeds.joinToString("|") { seed ->
                listOf(
                    seed.installationId,
                    seed.manifestUrl,
                    seed.baseUrl,
                    seed.encodedQuery,
                    seed.cachedManifestJson.orEmpty().hashCode().toString(),
                ).joinToString("#")
            }

        synchronized(endpointsCacheLock) {
            val cached = endpointsCache
            if (cached != null && cached.fingerprint == fingerprint) {
                return cached.endpoints
            }
        }

        val resolved =
            coroutineScope {
                seeds
                    .mapIndexed { index, seed ->
                        async(Dispatchers.IO) {
                            index to resolveEndpoint(seed)
                        }
                    }.awaitAll()
                    .sortedBy { it.first }
                    .mapNotNull { it.second }
            }

        synchronized(endpointsCacheLock) {
            endpointsCache = EndpointsCache(fingerprint = fingerprint, endpoints = resolved)
        }
        return resolved
    }

    private suspend fun resolveEndpoint(seed: AddonManifestSeed): AddonEndpoint? {
        val manifest = resolveManifest(seed)
        val providerId = nonBlank(manifest?.optString("id")) ?: seed.addonIdHint
        val providerName = nonBlank(manifest?.optString("name")) ?: providerId

        val streamSupport = parseStreamSupport(manifest)
        if (!streamSupport.supported) return null

        return AddonEndpoint(
            providerId = providerId,
            providerName = providerName,
            baseUrl = seed.baseUrl,
            encodedQuery = seed.encodedQuery.orEmpty(),
            supportedTypes = streamSupport.types,
            idPrefixes = streamSupport.idPrefixes,
        )
    }

    private suspend fun resolveManifest(seed: AddonManifestSeed): JSONObject? {
        val networkManifest =
            manifestFetchSemaphore.withPermit {
                httpClient.getJsonObject(seed.manifestUrl, MANIFEST_REQUEST_POLICY)
            }

        if (networkManifest != null) {
            addonRegistry.cacheManifest(seed, networkManifest)
            return networkManifest
        }

        val cachedJson = seed.cachedManifestJson ?: return null
        return runCatching { JSONObject(cachedJson) }.getOrNull()
    }

    private fun parseStreamSupport(manifest: JSONObject?): StreamSupport {
        val defaultTypes =
            parseMediaTypes(manifest?.optJSONArray("types"))
                .ifEmpty { setOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES, MetadataLabMediaType.ANIME) }
        val defaultPrefixes = parseStringList(manifest?.optJSONArray("idPrefixes"))
        if (manifest == null) {
            return StreamSupport(supported = true, types = defaultTypes, idPrefixes = defaultPrefixes.toSet())
        }

        val resources = manifest.optJSONArray("resources")
        if (resources == null || resources.length() == 0) {
            return StreamSupport(supported = true, types = defaultTypes, idPrefixes = defaultPrefixes.toSet())
        }

        var streamDeclared = false
        val supportedTypes = linkedSetOf<MetadataLabMediaType>()
        val idPrefixes = linkedSetOf<String>()

        for (index in 0 until resources.length()) {
            when (val resource = resources.opt(index)) {
                is String -> {
                    if (resource.equals("stream", ignoreCase = true)) {
                        streamDeclared = true
                        supportedTypes += defaultTypes
                        idPrefixes += defaultPrefixes
                    }
                }

                is JSONObject -> {
                    val name = nonBlank(resource.optString("name")) ?: continue
                    if (!name.equals("stream", ignoreCase = true)) continue
                    streamDeclared = true

                    val types = parseMediaTypes(resource.optJSONArray("types")).ifEmpty { defaultTypes }
                    supportedTypes += types
                    idPrefixes += parseStringList(resource.optJSONArray("idPrefixes")).ifEmpty { defaultPrefixes }
                }
            }
        }

        if (!streamDeclared) {
            return StreamSupport(supported = false, types = emptySet(), idPrefixes = emptySet())
        }

        val finalTypes = if (supportedTypes.isEmpty()) defaultTypes else supportedTypes
        return StreamSupport(supported = true, types = finalTypes, idPrefixes = idPrefixes)
    }

    private suspend fun fetchProviderStreams(
        endpoint: AddonEndpoint,
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): ProviderStreamsResult {
        val formattedLookupId = endpoint.formatLookupId(lookupId)
        if (formattedLookupId == null) {
            return ProviderStreamsResult(
                providerId = endpoint.providerId,
                providerName = endpoint.providerName,
                streams = emptyList(),
                errorMessage = "This provider does not accept this title id format.",
            )
        }

        val requestUrl = buildResourceUrl(endpoint, mediaType, formattedLookupId)
        val payload = httpClient.getJsonObject(requestUrl, STREAM_REQUEST_POLICY)

        if (payload == null) {
            return ProviderStreamsResult(
                providerId = endpoint.providerId,
                providerName = endpoint.providerName,
                streams = emptyList(),
                errorMessage = "Failed to load streams.",
                attemptedUrl = requestUrl,
            )
        }

        val streams = parseStreams(payload, endpoint.providerId, endpoint.providerName)
        return ProviderStreamsResult(
            providerId = endpoint.providerId,
            providerName = endpoint.providerName,
            streams = streams,
            errorMessage = null,
            attemptedUrl = requestUrl,
        )
    }

    private fun parseStreams(
        payload: JSONObject,
        providerId: String,
        providerName: String,
    ): List<AddonStream> {
        val array = payload.optJSONArray("streams") ?: JSONArray()
        if (array.length() == 0) return emptyList()

        val dedupe = LinkedHashSet<String>()
        val out = ArrayList<AddonStream>(array.length())

        for (index in 0 until array.length()) {
            val streamObject = array.optJSONObject(index) ?: continue
            val name = nonBlank(streamObject.optString("name"))
            val title = nonBlank(streamObject.optString("title"))
            val description = nonBlank(streamObject.optString("description")) ?: title
            val url = nonBlank(streamObject.optString("url"))
            val infoHash = nonBlank(streamObject.optString("infoHash"))
            val externalUrl = nonBlank(streamObject.optString("externalUrl"))
            val fileIdx = parseIntOrNull(streamObject, "fileIdx")
            val sources = parseStringList(streamObject.optJSONArray("sources"))
            val clientResolveObject = streamObject.optJSONObject("clientResolve")
            if (url == null && infoHash == null && externalUrl == null && clientResolveObject == null) continue

            val dedupeKey =
                listOf(
                    url.orEmpty(),
                    externalUrl.orEmpty(),
                    infoHash.orEmpty(),
                    name.orEmpty(),
                    title.orEmpty(),
                ).joinToString("|")
            if (!dedupe.add(dedupeKey)) continue

            val hintsObj = streamObject.optJSONObject("behaviorHints")
            val proxyHeaders = hintsObj?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            val requestHeaders = parseRequestHeaders(proxyHeaders)
            val behaviorHints =
                StreamBehaviorHints(
                    bingeGroup = nonBlank(hintsObj?.optString("bingeGroup")),
                    notWebReady = (hintsObj?.optBoolean("notWebReady") ?: false) || proxyHeaders != null,
                    videoHash = nonBlank(hintsObj?.optString("videoHash")),
                    videoSize = hintsObj?.optLong("videoSize")?.takeIf { it > 0L },
                    filename = nonBlank(hintsObj?.optString("filename")),
                    proxyRequestHeaders = requestHeaders.ifEmpty { null },
                )
            val stableKey = buildStableKey(providerId, dedupeKey)
            val subtitles = parseStreamSubtitles(streamObject.optJSONArray("subtitles"))
            val clientResolve = parseClientResolve(clientResolveObject)

            out +=
                AddonStream(
                    providerId = providerId,
                    providerName = providerName,
                    name = name,
                    title = title,
                    description = description,
                    url = url,
                    infoHash = infoHash,
                    fileIdx = fileIdx,
                    externalUrl = externalUrl,
                    sources = sources,
                    requestHeaders = requestHeaders,
                    cached = hintsObj?.optBoolean("cached", false) ?: false,
                    stableKey = stableKey,
                    subtitles = subtitles,
                    behaviorHints = behaviorHints,
                    clientResolve = clientResolve,
                )
        }

        return out
    }

    private fun parseClientResolve(obj: JSONObject?): StreamClientResolve? {
        if (obj == null) return null
        return StreamClientResolve(
            type = nonBlank(obj.optString("type")),
            infoHash = nonBlank(obj.optString("infoHash")),
            fileIdx = parseIntOrNull(obj, "fileIdx"),
            magnetUri = nonBlank(obj.optString("magnetUri")),
            sources = parseStringList(obj.optJSONArray("sources")),
            torrentName = nonBlank(obj.optString("torrentName")),
            filename = nonBlank(obj.optString("filename")),
            mediaType = nonBlank(obj.optString("mediaType")),
            mediaId = nonBlank(obj.optString("mediaId")),
            mediaOnlyId = nonBlank(obj.optString("mediaOnlyId")),
            title = nonBlank(obj.optString("title")),
            season = parseIntOrNull(obj, "season"),
            episode = parseIntOrNull(obj, "episode"),
            service = nonBlank(obj.optString("service")),
            serviceIndex = parseIntOrNull(obj, "serviceIndex"),
            serviceExtension = nonBlank(obj.optString("serviceExtension")),
            isCached = if (obj.has("isCached")) obj.optBoolean("isCached") else null,
            stream = parseClientResolveStream(obj.optJSONObject("stream")),
        )
    }

    private fun parseClientResolveStream(obj: JSONObject?): StreamClientResolveStream? {
        if (obj == null) return null
        return StreamClientResolveStream(raw = parseClientResolveRaw(obj.optJSONObject("raw")))
    }

    private fun parseClientResolveRaw(obj: JSONObject?): StreamClientResolveRaw? {
        if (obj == null) return null
        return StreamClientResolveRaw(
            torrentName = nonBlank(obj.optString("torrentName")),
            filename = nonBlank(obj.optString("filename")),
            size = obj.optLong("size").takeIf { it > 0L },
            folderSize = obj.optLong("folderSize").takeIf { it > 0L },
            tracker = nonBlank(obj.optString("tracker")),
            indexer = nonBlank(obj.optString("indexer")),
            network = nonBlank(obj.optString("network")),
            parsed = parseClientResolveParsed(obj.optJSONObject("parsed")),
        )
    }

    private fun parseClientResolveParsed(obj: JSONObject?): StreamClientResolveParsed? {
        if (obj == null) return null
        return StreamClientResolveParsed(
            rawTitle = nonBlank(obj.optString("raw_title")),
            parsedTitle = nonBlank(obj.optString("parsed_title")),
            year = parseIntOrNull(obj, "year"),
            resolution = nonBlank(obj.optString("resolution")),
            seasons = parseIntList(obj.optJSONArray("seasons")),
            episodes = parseIntList(obj.optJSONArray("episodes")),
            quality = nonBlank(obj.optString("quality")),
            hdr = parseStringList(obj.optJSONArray("hdr")),
            codec = nonBlank(obj.optString("codec")),
            audio = parseStringList(obj.optJSONArray("audio")),
            channels = parseStringList(obj.optJSONArray("channels")),
            languages = parseStringList(obj.optJSONArray("languages")),
            group = nonBlank(obj.optString("group")),
            network = nonBlank(obj.optString("network")),
            edition = nonBlank(obj.optString("edition")),
            duration = obj.optLong("duration").takeIf { it > 0L },
            bitDepth = nonBlank(obj.optString("bit_depth")),
            extended = if (obj.has("extended")) obj.optBoolean("extended") else null,
            theatrical = if (obj.has("theatrical")) obj.optBoolean("theatrical") else null,
            remastered = if (obj.has("remastered")) obj.optBoolean("remastered") else null,
            unrated = if (obj.has("unrated")) obj.optBoolean("unrated") else null,
        )
    }

    private fun parseIntOrNull(obj: JSONObject, name: String): Int? {
        val raw = obj.opt(name) ?: return null
        return when (raw) {
            is Int -> raw.takeIf { it >= 0 }
            is String -> raw.toIntOrNull()?.takeIf { it >= 0 }
            else -> null
        }
    }

    private fun parseStringList(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) return emptyList()
        val out = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = nonBlank(array.optString(index)) ?: continue
            out += value
        }
        return out
    }

    private fun parseIntList(array: JSONArray?): List<Int> {
        if (array == null || array.length() == 0) return emptyList()
        val out = ArrayList<Int>(array.length())
        for (index in 0 until array.length()) {
            val raw = array.opt(index) ?: continue
            val value =
                when (raw) {
                    is Int -> raw
                    is String -> raw.toIntOrNull()
                    else -> null
                } ?: continue
            out += value
        }
        return out
    }

    private fun orderedEndpoints(
        endpoints: List<AddonEndpoint>,
        preferredProviderId: String?,
    ): List<AddonEndpoint> {
        val preferred = preferredProviderId?.trim()?.takeIf { it.isNotBlank() } ?: return endpoints
        return endpoints.sortedWith(
            compareBy<AddonEndpoint> { endpoint ->
                if (endpoint.providerId.equals(preferred, ignoreCase = true)) 0 else 1
            }
        )
    }

    private fun buildResourceUrl(
        endpoint: AddonEndpoint,
        mediaType: MetadataLabMediaType,
        formattedLookupId: String,
    ): String {
        val encodedId = formattedLookupId.encodeAddonPathSegment()
        val base = "${endpoint.baseUrl}/stream/${mediaType.asApiPath()}/$encodedId.json"
        return if (endpoint.encodedQuery.isBlank()) base else "$base?${endpoint.encodedQuery}"
    }

    private fun parseMediaTypes(values: JSONArray?): Set<MetadataLabMediaType> {
        if (values == null || values.length() == 0) return emptySet()

        val out = LinkedHashSet<MetadataLabMediaType>()
        for (index in 0 until values.length()) {
            val value = nonBlank(values.optString(index)) ?: continue
            when (value.lowercase(Locale.US)) {
                "movie" -> out += MetadataLabMediaType.MOVIE
                "series", "show", "tv" -> out += MetadataLabMediaType.SERIES
                "anime" -> out += MetadataLabMediaType.ANIME
            }
        }
        return out
    }

    private fun parseManifestStringArray(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) return emptyList()

        val out = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = nonBlank(array.optString(index)) ?: continue
            out += value
        }
        return out
    }

    private fun parseRequestHeaders(headersObject: JSONObject?): Map<String, String> {
        if (headersObject == null || headersObject.length() == 0) return emptyMap()

        val out = linkedMapOf<String, String>()
        val iterator = headersObject.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()?.trim().orEmpty()
            if (key.isBlank()) continue
            val value = headersObject.optString(key).trim()
            if (value.isBlank()) continue
            out[key] = value
        }
        return out
    }

    private fun parseStreamSubtitles(subtitlesArray: org.json.JSONArray?): List<StreamSubtitle> {
        if (subtitlesArray == null || subtitlesArray.length() == 0) return emptyList()
        val out = ArrayList<StreamSubtitle>(subtitlesArray.length())
        for (i in 0 until subtitlesArray.length()) {
            val item = subtitlesArray.optJSONObject(i) ?: continue
            val url = nonBlank(item.optString("url")) ?: continue
            val lang =
                nonBlank(item.optString("lang"))
                    ?: nonBlank(item.optString("language"))
                    ?: nonBlank(item.optString("languageCode"))
            val name = nonBlank(item.optString("name")) ?: nonBlank(item.optString("title"))
            out += StreamSubtitle(url = url, lang = lang, name = name)
        }
        return out
    }

    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun CrispyHttpClient.getJsonObject(
        url: String,
        requestPolicy: JsonRequestPolicy,
    ): JSONObject? {
        var attempt = 0
        var backoffMs = requestPolicy.initialBackoffMs

        while (true) {
            when (
                val result =
                    getJsonObjectOnce(
                        url = url,
                        requestPolicy = requestPolicy,
                    )
            ) {
                is JsonFetchResult.Success -> return result.payload
                is JsonFetchResult.HttpFailure -> {
                    if (!result.shouldRetry || attempt >= requestPolicy.maxRetries) {
                        return null
                    }
                }

                JsonFetchResult.EmptyBody,
                JsonFetchResult.InvalidUrl,
                JsonFetchResult.ParseFailure,
                JsonFetchResult.RequestFailure,
                -> {
                    if (attempt >= requestPolicy.maxRetries) {
                        return null
                    }
                }
            }

            attempt += 1
            if (backoffMs > 0) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            }
        }
    }

    private suspend fun CrispyHttpClient.getJsonObjectOnce(
        url: String,
        requestPolicy: JsonRequestPolicy,
    ): JsonFetchResult {
        val httpUrl = url.toHttpUrlOrNull() ?: return JsonFetchResult.InvalidUrl
        val response =
            runCatching {
                get(
                    url = httpUrl,
                    headers = requestPolicy.headers,
                    callTimeoutMs = requestPolicy.callTimeoutMs,
                )
            }.getOrElse {
                return JsonFetchResult.RequestFailure
            }

        if (response.code !in 200..299) {
            return JsonFetchResult.HttpFailure(
                code = response.code,
                shouldRetry = response.code != 404,
            )
        }
        val body = response.body.trim()
        if (body.isEmpty()) return JsonFetchResult.EmptyBody

        return runCatching { JSONObject(body) }
            .fold(
                onSuccess = { JsonFetchResult.Success(it) },
                onFailure = { JsonFetchResult.ParseFailure },
            )
    }

    private fun buildStableKey(providerId: String, dedupeKey: String): String {
        val hash = dedupeKey.hashCode().toUInt().toString(16)
        return "$providerId-$hash"
    }

    suspend fun fetchAddonSubtitles(
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): List<AddonSubtitle> {
        val normalizedId = lookupId.trim()
        if (normalizedId.isBlank()) return emptyList()

        val endpoints = resolveSubtitleEndpoints()
        Log.d(TAG, "fetchAddonSubtitles mediaType=$mediaType id=$normalizedId endpoints=${endpoints.size}")
        if (endpoints.isEmpty()) return emptyList()

        val out = ArrayList<AddonSubtitle>()
        val seen = LinkedHashSet<String>()
        withContext(Dispatchers.IO) {
            for (endpoint in endpoints) {
                if (!endpoint.supports(mediaType, normalizedId)) {
                    Log.d(TAG, "subtitle endpoint skipped (type/prefix mismatch): ${endpoint.providerName}")
                    continue
                }
                val url = buildSubtitleResourceUrl(endpoint, mediaType, normalizedId)
                Log.d(TAG, "subtitle GET ${endpoint.providerName} -> $url")
                val json = httpClient.getJsonObject(url, SUBTITLE_REQUEST_POLICY)
                if (json == null) {
                    Log.d(TAG, "subtitle GET ${endpoint.providerName} -> null (failed/empty)")
                    continue
                }
                Log.d(TAG, "subtitle GET ${endpoint.providerName} -> ok(${json.length()})")
                val subtitles = parseAddonSubtitles(json, endpoint.providerId, endpoint.providerName)
                Log.d(TAG, "subtitle parsed ${endpoint.providerName} count=${subtitles.size}")
                for (subtitle in subtitles) {
                    if (seen.add(subtitle.id)) out += subtitle
                }
            }
        }
        Log.d(TAG, "fetchAddonSubtitles total=${out.size}")
        return out
    }

    private suspend fun resolveSubtitleEndpoints(): List<SubtitleEndpoint> {
        val seeds = addonRegistry.orderedSeeds()
        return coroutineScope {
            seeds
                .map { seed ->
                    async(Dispatchers.IO) {
                        val manifest = resolveManifest(seed) ?: return@async null
                        val resource = subtitleResourceFor(manifest) ?: run {
                            Log.d(TAG, "subtitle endpoint dropped (no 'subtitles' resource): ${seed.addonIdHint}")
                            return@async null
                        }
                        val providerId = nonBlank(manifest.optString("id")) ?: seed.addonIdHint
                        val providerName = nonBlank(manifest.optString("name")) ?: providerId
                        Log.d(TAG, "subtitle endpoint kept: $providerName")
                        SubtitleEndpoint(
                            providerId = providerId,
                            providerName = providerName,
                            baseUrl = seed.baseUrl,
                            encodedQuery = seed.encodedQuery.orEmpty(),
                            types = resource.types,
                            idPrefixes = resource.idPrefixes,
                        )
                    }
                }.awaitAll()
        }.filterNotNull()
    }

    private fun subtitleResourceFor(manifest: JSONObject): SubtitleResourceInfo? {
        val defaultTypes = parseStringList(manifest.optJSONArray("types"))
        val defaultPrefixes = parseStringList(manifest.optJSONArray("idPrefixes"))
        val resources = manifest.optJSONArray("resources") ?: return null
        for (index in 0 until resources.length()) {
            when (val resource = resources.opt(index)) {
                is String -> {
                    if (resource.equals("subtitles", ignoreCase = true) || resource.equals("subtitle", ignoreCase = true)) {
                        return SubtitleResourceInfo(
                            types = defaultTypes.toSet(),
                            idPrefixes = defaultPrefixes.toSet(),
                        )
                    }
                }

                is JSONObject -> {
                    val name = nonBlank(resource.optString("name")) ?: continue
                    if (!name.equals("subtitles", ignoreCase = true) && !name.equals("subtitle", ignoreCase = true)) continue
                    val types = parseStringList(resource.optJSONArray("types")).ifEmpty { defaultTypes }.toSet()
                    val idPrefixes = parseStringList(resource.optJSONArray("idPrefixes")).ifEmpty { defaultPrefixes }.toSet()
                    return SubtitleResourceInfo(types = types, idPrefixes = idPrefixes)
                }
            }
        }
        return null
    }

    private fun buildSubtitleResourceUrl(
        endpoint: SubtitleEndpoint,
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): String {
        val encodedId = lookupId.encodeAddonPathSegment()
        val base = "${endpoint.baseUrl}/subtitles/${mediaType.asApiPath()}/$encodedId.json"
        return if (endpoint.encodedQuery.isBlank()) base else "$base?${endpoint.encodedQuery}"
    }

    private fun parseAddonSubtitles(
        json: JSONObject,
        providerId: String,
        providerName: String,
    ): List<AddonSubtitle> {
        val array = json.optJSONArray("subtitles") ?: return emptyList()
        if (array.length() == 0) return emptyList()
        val out = ArrayList<AddonSubtitle>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = nonBlank(item.optString("url")) ?: continue
            val language =
                nonBlank(item.optString("lang"))
                    ?: nonBlank(item.optString("language"))
                    ?: nonBlank(item.optString("languageCode"))
                    ?: nonBlank(item.optString("locale"))
                    ?: nonBlank(item.optString("label"))
                    ?: "unknown"
            val name = nonBlank(item.optString("label")) ?: nonBlank(item.optString("name")) ?: language
            out +=
                AddonSubtitle(
                    id = "$providerId-${nonBlank(item.optString("id")) ?: index}",
                    url = url,
                    language = language,
                    display = "$language - $providerName",
                    addonName = providerName,
                )
        }
        return out
    }

    private data class SubtitleEndpoint(
        val providerId: String,
        val providerName: String,
        val baseUrl: String,
        val encodedQuery: String,
        val types: Set<String> = emptySet(),
        val idPrefixes: Set<String> = emptySet(),
    ) {
        fun supports(mediaType: MetadataLabMediaType, lookupId: String): Boolean {
            val canonical = mediaType.asApiPath()
            val typeMatches = types.isEmpty() || types.any { it.equals(canonical, ignoreCase = true) }
            if (!typeMatches) return false
            return idPrefixes.isEmpty() || idPrefixes.any { prefix -> lookupId.startsWith(prefix) }
        }
    }

    private data class SubtitleResourceInfo(
        val types: Set<String>,
        val idPrefixes: Set<String>,
    )

    private data class EndpointsCache(
        val fingerprint: String,
        val endpoints: List<AddonEndpoint>,
    )

    private data class StreamSupport(
        val supported: Boolean,
        val types: Set<MetadataLabMediaType>,
        val idPrefixes: Set<String> = emptySet(),
    )

    private data class JsonRequestPolicy(
        val callTimeoutMs: Long,
        val maxRetries: Int,
        val initialBackoffMs: Long,
        val headers: Headers,
    )

    private sealed interface JsonFetchResult {
        data class Success(
            val payload: JSONObject,
        ) : JsonFetchResult

        data class HttpFailure(
            val code: Int,
            val shouldRetry: Boolean,
        ) : JsonFetchResult

        data object InvalidUrl : JsonFetchResult

        data object EmptyBody : JsonFetchResult

        data object ParseFailure : JsonFetchResult

        data object RequestFailure : JsonFetchResult
    }

    private data class AddonEndpoint(
        val providerId: String,
        val providerName: String,
        val baseUrl: String,
        val encodedQuery: String,
        val supportedTypes: Set<MetadataLabMediaType>,
        val idPrefixes: Set<String> = emptySet(),
    ) {
        fun supports(mediaType: MetadataLabMediaType, lookupId: String): Boolean =
            supportedTypes.contains(mediaType) &&
                (idPrefixes.isEmpty() || idPrefixes.any { prefix -> lookupId.startsWith(prefix) })

        fun formatLookupId(lookupId: String): String? {
            val trimmedLookupId = lookupId.trim()
            return trimmedLookupId.takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        private const val INITIAL_RETRY_BACKOFF_MS = 1_000L
        private const val MAX_RETRY_BACKOFF_MS = 8_000L
        private const val MANIFEST_TIMEOUT_MS = 6_000L
        private const val STREAM_TIMEOUT_MS = 10_000L
        private const val MANIFEST_MAX_RETRIES = 1
        private const val STREAM_MAX_RETRIES = 5
        private const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        private val JSON_HEADERS =
            Headers.headersOf(
                "Accept",
                "application/json",
                "User-Agent",
                STREAM_USER_AGENT,
            )

        private val MANIFEST_REQUEST_POLICY =
            JsonRequestPolicy(
                callTimeoutMs = MANIFEST_TIMEOUT_MS,
                maxRetries = MANIFEST_MAX_RETRIES,
                initialBackoffMs = INITIAL_RETRY_BACKOFF_MS,
                headers = JSON_HEADERS,
            )

        private val STREAM_REQUEST_POLICY =
            JsonRequestPolicy(
                callTimeoutMs = STREAM_TIMEOUT_MS,
                maxRetries = STREAM_MAX_RETRIES,
                initialBackoffMs = INITIAL_RETRY_BACKOFF_MS,
                headers = JSON_HEADERS,
            )

        private val SUBTITLE_REQUEST_POLICY =
            JsonRequestPolicy(
                callTimeoutMs = STREAM_TIMEOUT_MS,
                maxRetries = STREAM_MAX_RETRIES,
                initialBackoffMs = INITIAL_RETRY_BACKOFF_MS,
                headers = JSON_HEADERS,
            )
    }
}
