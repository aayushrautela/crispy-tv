package com.crispy.tv.plugins.repo

import android.util.Log
import com.crispy.tv.plugins.bridge.PluginExecutionBlockedException
import com.crispy.tv.plugins.bridge.SsrfGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

internal class PluginManifestClient(private val okHttpClient: OkHttpClient) {

    suspend fun fetchManifest(repoUrl: String): PluginManifest {
        val body = fetch(repoUrl)
        return PluginManifestParser.parse(body)
    }

    suspend fun fetchCode(scraperUrl: String): String {
        return fetch(scraperUrl)
    }

    fun resolveScraperUrl(repoUrl: String, filename: String): String {
        return runCatching { URI(repoUrl).resolve(filename).toString() }
            .getOrDefault(filename)
    }

    private suspend fun fetch(url: String): String {
        return withContext(Dispatchers.IO) {
            try {
                SsrfGuard.validate(url)
                Log.i(TAG, "Fetching $url")
                okHttpClient
                    .newCall(Request.Builder().url(url).get().build())
                    .execute()
                    .use { response ->
                        Log.i(TAG, "Response ${response.code} for $url")
                        if (!response.isSuccessful) {
                            throw PluginRepositoryException("HTTP ${response.code} fetching $url")
                        }
                        val bytes = response.body.bytes()
                        if (bytes.size > MAX_FETCH_BYTES) {
                            throw PluginRepositoryException("Manifest/code too large: ${bytes.size} bytes")
                        }
                        bytes.toString(Charsets.UTF_8)
                    }
            } catch (error: PluginExecutionBlockedException) {
                Log.w(TAG, "Request blocked for $url: ${error.message}")
                throw error
            } catch (error: java.net.UnknownHostException) {
                Log.w(TAG, "DNS failed for $url", error)
                throw PluginRepositoryException(
                    "Could not resolve host \"${error.message?.substringAfter('"')?.substringBefore('"') ?: url}\". " +
                        "Check the URL and the device's internet connection.",
                )
            } catch (error: java.io.IOException) {
                Log.w(TAG, "Network error fetching $url", error)
                throw PluginRepositoryException("Network error fetching $url: ${error.message}")
            }
        }
    }

    private companion object {
        const val TAG = "CrispyPlugins"
        const val MAX_FETCH_BYTES = 2 * 1024 * 1024
    }
}
