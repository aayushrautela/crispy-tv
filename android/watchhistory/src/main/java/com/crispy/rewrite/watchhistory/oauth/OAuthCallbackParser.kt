package com.crispy.rewrite.watchhistory.oauth

import android.net.Uri
import com.crispy.rewrite.player.WatchProvider

internal data class OAuthCallbackPayload(
    val provider: WatchProvider,
    val code: String,
    val state: String,
    val error: String,
)

internal sealed interface OAuthCallbackParseResult {
    data class Parsed(val payload: OAuthCallbackPayload) : OAuthCallbackParseResult

    data class Invalid(val statusMessage: String) : OAuthCallbackParseResult
}

internal class OAuthCallbackParser {
    fun parse(provider: WatchProvider, callbackUri: String): OAuthCallbackParseResult {
        val callback = callbackUri.trim()
        if (callback.isBlank()) {
            return OAuthCallbackParseResult.Invalid("OAuth callback is empty.")
        }

        val uri = runCatching { Uri.parse(callback) }.getOrNull()
            ?: return OAuthCallbackParseResult.Invalid("OAuth callback URI is invalid.")

        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        val state = uri.getQueryParameter("state")?.trim().orEmpty()
        val error = uri.getQueryParameter("error")?.trim().orEmpty()

        return OAuthCallbackParseResult.Parsed(
            OAuthCallbackPayload(
                provider = provider,
                code = code,
                state = state,
                error = error,
            )
        )
    }
}
