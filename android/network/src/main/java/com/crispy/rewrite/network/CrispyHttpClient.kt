package com.crispy.rewrite.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class CrispyHttpResponse(
    val url: HttpUrl,
    val code: Int,
    val headers: Headers,
    val body: String,
) {
    fun header(name: String): String? = headers[name]
}

class CrispyHttpClient(private val okHttpClient: OkHttpClient) {
    suspend fun execute(request: Request, callTimeoutMs: Long? = null): CrispyHttpResponse =
        withContext(Dispatchers.IO) {
            val call = okHttpClient.newCall(request)
            if (callTimeoutMs != null && callTimeoutMs > 0) {
                call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            }
            call.await().use { response ->
                CrispyHttpResponse(
                    url = response.request.url,
                    code = response.code,
                    headers = response.headers,
                    body = response.body?.string().orEmpty(),
                )
            }
        }

    suspend fun get(
        url: HttpUrl,
        headers: Headers = Headers.headersOf(),
        callTimeoutMs: Long? = null,
    ): CrispyHttpResponse {
        val request = Request.Builder().url(url).headers(headers).get().build()
        return execute(request, callTimeoutMs = callTimeoutMs)
    }

    suspend fun postJson(
        url: HttpUrl,
        jsonBody: String,
        headers: Headers = Headers.headersOf(),
        callTimeoutMs: Long? = null,
    ): CrispyHttpResponse {
        val request =
            Request.Builder()
                .url(url)
                .headers(headers)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return execute(request, callTimeoutMs = callTimeoutMs)
    }

    suspend fun delete(
        url: HttpUrl,
        headers: Headers = Headers.headersOf(),
        callTimeoutMs: Long? = null,
    ): CrispyHttpResponse {
        val request = Request.Builder().url(url).headers(headers).delete().build()
        return execute(request, callTimeoutMs = callTimeoutMs)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
