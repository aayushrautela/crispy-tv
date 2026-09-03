package com.crispy.tv.plugins.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class PluginFetchResponse(
    val status: Int,
    val headers: Map<String, String>,
    val bodyBase64: String,
    val bodyText: String,
)

internal class HttpBridge(private val okHttpClient: OkHttpClient) {

    suspend fun fetch(requestJson: String): String {
        val request = PluginRequestJson.parse(requestJson)
        SsrfGuard.validate(request.url)

        val response =
            withContext(Dispatchers.IO) {
                okHttpClient
                    .newCall(buildRequest(request))
                    .execute()
                    .use { httpResponse ->
                        val bytes = httpResponse.body.bytes()
                        if (bytes.size > MAX_RESPONSE_BYTES) {
                            throw PluginExecutionBlockedException("Response too large: ${bytes.size} bytes")
                        }
                        PluginFetchResponse(
                            status = httpResponse.code,
                            headers = buildMap {
                                for (name in httpResponse.headers.names()) {
                                    httpResponse.headers[name]?.let { put(name.lowercase(), it) }
                                }
                            },
                            bodyBase64 = Base64Codec.encode(bytes),
                            bodyText = bytes.toString(Charsets.UTF_8),
                        )
                    }
            }
        return PluginResponseJson.write(response)
    }

    private fun buildRequest(request: PluginRequestJson): Request {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
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
    }
}

internal data class PluginRequestJson(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val bodyText: String,
) {
    val mediaType: okhttp3.MediaType?
        get() = headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()
}

internal object PluginRequestJson {
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
        )
    }
}

internal object PluginResponseJson {
    fun write(response: PluginFetchResponse): String {
        val headersJson = org.json.JSONObject()
        response.headers.forEach { (name, value) -> headersJson.put(name, value) }
        return org.json.JSONObject()
            .put("status", response.status)
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
