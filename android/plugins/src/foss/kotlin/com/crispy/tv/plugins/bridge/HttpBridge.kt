package com.crispy.tv.plugins.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class PluginFetchResponse(
    val status: Int,
    val statusText: String,
    val url: String,
    val headers: Map<String, String>,
    val bodyBase64: String,
    val bodyText: String,
)

internal class HttpBridge(private val okHttpClient: OkHttpClient) {

    suspend fun fetch(requestJson: String): String {
        val request = PluginRequestJson.parse(requestJson)
        SsrfGuard.validate(request.url)
        val client = if (request.followRedirects) {
            okHttpClient
        } else {
            okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        val response =
            withContext(Dispatchers.IO) {
                client
                    .newCall(buildRequest(request))
                    .execute()
                    .use { httpResponse ->
                        val bytes = httpResponse.body.bytes()
                        if (bytes.size > MAX_RESPONSE_BYTES) {
                            throw PluginExecutionBlockedException("Response too large: ${bytes.size} bytes")
                        }
                        PluginFetchResponse(
                            status = httpResponse.code,
                            statusText = httpResponse.message,
                            url = httpResponse.request.url.toString(),
                            headers = buildMap {
                                for (name in httpResponse.headers.names()) {
                                    httpResponse.headers[name]?.let { put(name.lowercase(), truncate(it)) }
                                }
                            },
                            bodyBase64 = Base64Codec.encode(bytes),
                            bodyText = bytes.toString(Charsets.UTF_8),
                        )
                    }
            }
        return PluginResponseJson.write(response)
    }

    private fun truncate(value: String): String {
        if (value.length <= MAX_HEADER_VALUE_CHARS) return value
        return value.substring(0, MAX_HEADER_VALUE_CHARS - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
    }

    private fun buildRequest(request: PluginRequestJson): Request {
        val builder = Request.Builder().url(request.url)
        var hasUserAgent = false
        request.headers.forEach { (name, value) ->
            if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            builder.header(name, value)
        }
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)
        when (request.method.uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(request.bodyText.toRequestBody(request.mediaType))
            "PUT" -> builder.put(request.bodyText.toRequestBody(request.mediaType))
            "PATCH" -> builder.patch(request.bodyText.toRequestBody(request.mediaType))
            else -> throw PluginExecutionBlockedException("Unsupported method: ${request.method}")
        }
        return builder.build()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_HEADER_VALUE_CHARS = 8 * 1024
        const val TRUNCATION_SUFFIX = "\n...[truncated]"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}

internal data class PluginRequestJson(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val bodyText: String,
    val followRedirects: Boolean,
) {
    val mediaType: okhttp3.MediaType?
        get() = headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()

    companion object {
        fun parse(json: String): PluginRequestJson {
            val root =
                try {
                    org.json.JSONObject(json)
                } catch (error: Exception) {
                    throw PluginExecutionBlockedException("Invalid fetch request JSON")
                }
            val url = root.optString("url").trim()
            if (url.isEmpty()) {
                throw PluginExecutionBlockedException("fetch url is required")
            }
            val headers = LinkedHashMap<String, String>()
            root.optJSONObject("headers")?.let { headersJson ->
                headersJson.keys().forEach { key ->
                    headersJson.optString(key).takeIf { it.isNotEmpty() }?.let { headers[key] = it }
                }
            }
            return PluginRequestJson(
                url = url,
                method = root.optString("method", "GET").trim().ifEmpty { "GET" },
                headers = headers,
                bodyText = root.optString("body"),
                followRedirects = root.optBoolean("followRedirects", true),
            )
        }
    }
}

internal object PluginResponseJson {
    fun write(response: PluginFetchResponse): String {
        val headersJson = org.json.JSONObject()
        response.headers.forEach { (name, value) -> headersJson.put(name, value) }
        return org.json.JSONObject()
            .put("status", response.status)
            .put("statusText", response.statusText)
            .put("url", response.url)
            .put("headers", headersJson)
            .put("bodyBase64", response.bodyBase64)
            .put("body", response.bodyText)
            .toString()
    }
}

internal object Base64Codec {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
