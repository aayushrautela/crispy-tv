package com.crispy.tv.streams

import android.content.Context
import androidx.compose.runtime.Immutable
import com.crispy.tv.domain.metadata.formatIdForIdPrefixes
import com.crispy.tv.metadata.AddonManifestSeed
import com.crispy.tv.metadata.MetadataAddonRegistry
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

@Immutable
data class AddonStream(
    val providerId: String,
    val providerName: String,
    val name: String?,
    val title: String?,
    val description: String?,
    val url: String?,
    val externalUrl: String?,
    val cached: Boolean,
    val stableKey: String,
) {
    val playbackUrl: String?
        get() = url ?: externalUrl
}

@Immutable
data class ProviderStreamsResult(
    val providerId: String,
    val providerName: String,
    val streams: List<AddonStream>,
    val errorMessage: String? = null,
    val attemptedUrl: String? = null,
)

@Immutable
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
                .filter { endpoint -> endpoint.supports(mediaType) }
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
        if (!endpoint.supports(mediaType)) {
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
        val addonIdPrefixes = parseManifestStringArray(manifest?.optJSONArray("idPrefixes"))

        val streamSupport = parseStreamSupport(manifest)
        if (!streamSupport.supported) return null

        return AddonEndpoint(
            providerId = providerId,
            providerName = providerName,
            baseUrl = seed.baseUrl,
            encodedQuery = seed.encodedQuery.orEmpty(),
            supportedTypes = streamSupport.types,
            addonIdPrefixes = addonIdPrefixes,
            streamIdPrefixes = streamSupport.idPrefixes,
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
        val defaultTypes = setOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES)
        if (manifest == null) {
            return StreamSupport(supported = true, types = defaultTypes, idPrefixes = emptyMap())
        }

        val resources = manifest.optJSONArray("resources")
        if (resources == null || resources.length() == 0) {
            return StreamSupport(supported = true, types = defaultTypes, idPrefixes = emptyMap())
        }

        var streamDeclared = false
        val supportedTypes = linkedSetOf<MetadataLabMediaType>()
        val idPrefixes = mutableMapOf<MetadataLabMediaType, List<String>>()

        for (index in 0 until resources.length()) {
            when (val resource = resources.opt(index)) {
                is String -> {
                    if (resource.equals("stream", ignoreCase = true)) {
                        streamDeclared = true
                        supportedTypes += defaultTypes
                    }
                }

                is JSONObject -> {
                    val name = nonBlank(resource.optString("name")) ?: continue
                    if (!name.equals("stream", ignoreCase = true)) continue
                    streamDeclared = true

                    val types =
                        parseMediaTypes(resource.optJSONArray("types")).ifEmpty {
                            defaultTypes
                        }
                    val resourcePrefixes =
                        parseManifestStringArray(resource.optJSONArray("idPrefixes")).ifEmpty {
                            nonBlank(resource.optString("idPrefix"))?.let(::listOf).orEmpty()
                        }

                    for (type in types) {
                        supportedTypes += type
                        if (resourcePrefixes.isNotEmpty()) {
                            idPrefixes[type] = resourcePrefixes
                        }
                    }
                }
            }
        }

        if (!streamDeclared) {
            return StreamSupport(supported = false, types = emptySet(), idPrefixes = emptyMap())
        }

        val finalTypes = if (supportedTypes.isEmpty()) defaultTypes else supportedTypes
        return StreamSupport(supported = true, types = finalTypes, idPrefixes = idPrefixes)
    }

    private suspend fun fetchProviderStreams(
        endpoint: AddonEndpoint,
        mediaType: MetadataLabMediaType,
        lookupId: String,
    ): ProviderStreamsResult {
        val formattedLookupId = endpoint.formatLookupId(mediaType, lookupId)
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
            val description = nonBlank(streamObject.optString("description"))
            val url = nonBlank(streamObject.optString("url"))
            val externalUrl = nonBlank(streamObject.optString("externalUrl"))
            if (url == null && externalUrl == null) continue

            val dedupeKey = listOf(url.orEmpty(), externalUrl.orEmpty(), name.orEmpty(), title.orEmpty()).joinToString("|")
            if (!dedupe.add(dedupeKey)) continue

            val cached = streamObject.optJSONObject("behaviorHints")?.optBoolean("cached", false) ?: false
            val stableKey = buildStableKey(providerId, dedupeKey)

            out +=
                AddonStream(
                    providerId = providerId,
                    providerName = providerName,
                    name = name,
                    title = title,
                    description = description,
                    url = url,
                    externalUrl = externalUrl,
                    cached = cached,
                    stableKey = stableKey,
                )
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
        val encodedId = URLEncoder.encode(formattedLookupId, StandardCharsets.UTF_8.name())
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

    private fun MetadataLabMediaType.asApiPath(): String {
        return when (this) {
            MetadataLabMediaType.MOVIE -> "movie"
            MetadataLabMediaType.SERIES -> "series"
        }
    }

    private data class EndpointsCache(
        val fingerprint: String,
        val endpoints: List<AddonEndpoint>,
    )

    private data class StreamSupport(
        val supported: Boolean,
        val types: Set<MetadataLabMediaType>,
        val idPrefixes: Map<MetadataLabMediaType, List<String>>,
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
        val addonIdPrefixes: List<String>,
        val streamIdPrefixes: Map<MetadataLabMediaType, List<String>>,
    ) {
        fun supports(mediaType: MetadataLabMediaType): Boolean = supportedTypes.contains(mediaType)

        fun formatLookupId(mediaType: MetadataLabMediaType, lookupId: String): String? {
            val acceptedPrefixes = streamIdPrefixes[mediaType].orEmpty().ifEmpty { addonIdPrefixes }
            val mediaTypePath =
                when (mediaType) {
                    MetadataLabMediaType.MOVIE -> "movie"
                    MetadataLabMediaType.SERIES -> "series"
                }
            return formatIdForIdPrefixes(
                input = lookupId,
                mediaType = mediaTypePath,
                idPrefixes = acceptedPrefixes,
            )
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
    }
}
