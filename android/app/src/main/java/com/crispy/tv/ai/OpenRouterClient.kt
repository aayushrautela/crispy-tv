package com.crispy.tv.ai

import com.crispy.tv.network.CrispyHttpClient
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class OpenRouterClient(
    private val httpClient: CrispyHttpClient,
    private val httpReferer: String = "https://crispy-app.com",
    private val xTitle: String = "Crispy Rewrite"
) {
    suspend fun chatCompletionsJsonObject(
        apiKey: String,
        model: String,
        userPrompt: String
    ): String {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw IllegalStateException("AI is not configured yet. Add an OpenRouter key in settings.")
        }

        val body =
            JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt))
                )
                .put("response_format", JSONObject().put("type", "json_object"))
                .toString()

        val headers =
            Headers.Builder()
                .add("Authorization", "Bearer $trimmedKey")
                .add("HTTP-Referer", httpReferer)
                .add("X-Title", xTitle)
                .add("Content-Type", "application/json")
                .add("Accept", "application/json")
                .build()

        val response =
            try {
                httpClient.postJson(
                    url = OPENROUTER_API_URL.toHttpUrl(),
                    jsonBody = body,
                    headers = headers,
                    callTimeoutMs = 25_000L,
                )
            } catch (e: IOException) {
                throw IllegalStateException(
                    "AI is unreachable right now. Check your connection and try again.",
                    e
                )
            } catch (e: Exception) {
                throw e
            }

        if (response.code !in 200..299) {
            val friendly = buildFriendlyApiError(response.code, response.body)
            throw IllegalStateException(friendly)
        }

        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: JSONObject()
        val content =
            json
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()

        if (content.isEmpty()) {
            throw IllegalStateException("AI insights are unavailable right now. Please try again in a moment.")
        }

        return content
    }

    private data class ErrorContext(
        val text: String,
        val retryAfterSeconds: Long?
    )

    private fun buildFriendlyApiError(status: Int, errBody: String): String {
        val ctx = extractErrorContext(errBody)
        val text = ctx.text

        val base =
            when {
                status == 429 || text.contains("rate limit") || text.contains("too many requests") ->
                    "AI rate limited."
                status == 503 ||
                    text.contains("throttl") ||
                    text.contains("capacity_error") ||
                    text.contains("server at capacity") ->
                    "AI is currently throttled."
                status >= 500 -> "AI service is temporarily unavailable."
                else -> "AI insights are unavailable right now."
            }

        val hint =
            ctx.retryAfterSeconds?.let { seconds ->
                " Try again in about $seconds second(s)."
            } ?: " Please try again in a moment."

        return base + hint
    }

    private fun extractErrorContext(errBody: String): ErrorContext {
        val rawLower = errBody.lowercase(Locale.US)
        val json = runCatching { JSONObject(errBody) }.getOrNull()
        if (json == null) return ErrorContext(text = rawLower, retryAfterSeconds = null)

        val error = json.optJSONObject("error")
        val message = error?.optString("message")?.lowercase(Locale.US).orEmpty()
        val providerRaw =
            error
                ?.optJSONObject("metadata")
                ?.optString("raw")
                ?.lowercase(Locale.US)
                .orEmpty()

        val retry = error?.optLong("retry_after_seconds", 0L)?.takeIf { it > 0 }

        val combined = listOf(rawLower, message, providerRaw).joinToString("\n")
        return ErrorContext(text = combined, retryAfterSeconds = retry)
    }

    companion object {
        private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
    }
}
