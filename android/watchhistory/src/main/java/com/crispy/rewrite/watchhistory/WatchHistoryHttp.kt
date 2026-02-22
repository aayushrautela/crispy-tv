package com.crispy.rewrite.watchhistory

import android.util.Log
import com.crispy.rewrite.network.CrispyHttpClient
import com.crispy.rewrite.network.CrispyHttpResponse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

internal class WatchHistoryHttp(
    private val httpClient: CrispyHttpClient,
    private val tag: String
) {
    suspend fun getRaw(url: String, headers: Map<String, String>): CrispyHttpResponse? {
        return runCatching {
            httpClient.get(url = url.toHttpUrl(), headers = toHeaders(headers))
        }.onFailure { e ->
            Log.w(tag, "HTTP GET failed: $url", e)
        }.getOrNull()
    }

    suspend fun postJsonRaw(url: String, headers: Map<String, String>, payload: JSONObject): CrispyHttpResponse? {
        return runCatching {
            httpClient.postJson(url = url.toHttpUrl(), jsonBody = payload.toString(), headers = toHeaders(headers))
        }.onFailure { e ->
            Log.w(tag, "HTTP POST failed: $url", e)
        }.getOrNull()
    }

    suspend fun deleteRaw(url: String, headers: Map<String, String>): CrispyHttpResponse? {
        return runCatching {
            httpClient.delete(url = url.toHttpUrl(), headers = toHeaders(headers))
        }.onFailure { e ->
            Log.w(tag, "HTTP DELETE failed: $url", e)
        }.getOrNull()
    }

    suspend fun postJson(url: String, headers: Map<String, String>, payload: JSONObject): Int? {
        return runCatching {
            val response = httpClient.postJson(url = url.toHttpUrl(), jsonBody = payload.toString(), headers = toHeaders(headers))
            response.code
        }.onFailure { e ->
            Log.w(tag, "HTTP POST failed: $url", e)
        }.getOrNull()
    }

    suspend fun delete(url: String, headers: Map<String, String>): Int? {
        return runCatching {
            val response = httpClient.delete(url = url.toHttpUrl(), headers = toHeaders(headers))
            response.code
        }.onFailure { e ->
            Log.w(tag, "HTTP DELETE failed: $url", e)
        }.getOrNull()
    }

    suspend fun postJsonForObject(url: String, headers: Map<String, String>, payload: JSONObject): JSONObject? {
        return runCatching {
            val response = httpClient.postJson(url = url.toHttpUrl(), jsonBody = payload.toString(), headers = toHeaders(headers))
            if (response.code !in 200..299) {
                Log.w(tag, "HTTP POST non-2xx: ${response.code} $url body=${compactForLog(response.body)}")
                return@runCatching null
            }
            JSONObject(response.body)
        }.onFailure { e ->
            Log.w(tag, "HTTP POST/JSON failed: $url", e)
        }.getOrNull()
    }

    suspend fun getJsonArray(url: String, headers: Map<String, String>): JSONArray? {
        return runCatching {
            val response = httpClient.get(url = url.toHttpUrl(), headers = toHeaders(headers))
            if (response.code !in 200..299) {
                Log.w(tag, "HTTP GET non-2xx: ${response.code} $url body=${compactForLog(response.body)}")
                return@runCatching null
            }
            JSONArray(response.body)
        }.onFailure { e ->
            Log.w(tag, "HTTP GET/JSONArray failed: $url", e)
        }.getOrNull()
    }

    suspend fun getJsonAny(url: String, headers: Map<String, String>): Any? {
        return runCatching {
            val response = httpClient.get(url = url.toHttpUrl(), headers = toHeaders(headers))
            if (response.code !in 200..299) {
                Log.w(tag, "HTTP GET non-2xx: ${response.code} $url body=${compactForLog(response.body)}")
                return@runCatching null
            }

            val body = response.body.trim()
            when {
                body.startsWith('[') -> JSONArray(body)
                body.startsWith('{') -> JSONObject(body)
                else -> body
            }
        }.onFailure { e ->
            Log.w(tag, "HTTP GET/JSON failed: $url", e)
        }.getOrNull()
    }

    private fun toHeaders(headers: Map<String, String>): Headers {
        if (headers.isEmpty()) return Headers.Builder().build()
        val builder = Headers.Builder()
        for ((k, v) in headers) {
            builder.add(k, v)
        }
        return builder.build()
    }

    private fun compactForLog(body: String, maxLength: Int = 240): String {
        val compact = body.replace(Regex("\\s+"), " ").trim()
        if (compact.isEmpty()) return "<empty>"
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
    }
}
