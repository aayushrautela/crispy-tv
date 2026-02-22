package com.crispy.rewrite.watchhistory.simkl

import com.crispy.rewrite.player.ProviderAuthActionResult
import com.crispy.rewrite.player.ProviderAuthStartResult
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.watchhistory.auth.ProviderSessionStore
import com.crispy.rewrite.watchhistory.oauth.OAuthCallbackParseResult
import com.crispy.rewrite.watchhistory.oauth.OAuthCallbackParser
import com.crispy.rewrite.watchhistory.oauth.OAuthStateStore
import com.crispy.rewrite.watchhistory.oauth.Pkce

internal class SimklOAuthClient(
    private val simklClientId: String,
    private val simklClientSecret: String,
    private val simklRedirectUri: String,
    private val simklApi: SimklApi,
    private val sessionStore: ProviderSessionStore,
    private val stateStore: OAuthStateStore,
    private val callbackParser: OAuthCallbackParser,
    private val pkce: Pkce,
) {
    suspend fun begin(): ProviderAuthStartResult? {
        if (simklClientId.isBlank()) {
            return ProviderAuthStartResult(
                authorizationUrl = "",
                statusMessage = "Simkl client ID missing. Set SIMKL_CLIENT_ID in gradle.properties.",
            )
        }
        if (simklRedirectUri.isBlank()) {
            return ProviderAuthStartResult(
                authorizationUrl = "",
                statusMessage = "Simkl redirect URI missing. Set SIMKL_REDIRECT_URI in gradle.properties.",
            )
        }

        val state = pkce.generateUrlSafeToken(16)
        stateStore.saveSimkl(state)

        val authorizationUrl = simklApi.authorizeUrl(state)
        return ProviderAuthStartResult(
            authorizationUrl = authorizationUrl,
            statusMessage = "Opening Simkl OAuth.",
        )
    }

    suspend fun complete(callbackUri: String): ProviderAuthActionResult {
        if (simklClientId.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl client ID missing. Set SIMKL_CLIENT_ID in gradle.properties.",
                authState = sessionStore.authState(),
            )
        }
        if (simklRedirectUri.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl redirect URI missing. Set SIMKL_REDIRECT_URI in gradle.properties.",
                authState = sessionStore.authState(),
            )
        }

        val parsed = callbackParser.parse(provider = WatchProvider.SIMKL, callbackUri = callbackUri)
        if (parsed is OAuthCallbackParseResult.Invalid) {
            return ProviderAuthActionResult(success = false, statusMessage = "Invalid Simkl callback URI.", authState = sessionStore.authState())
        }

        val payload = (parsed as OAuthCallbackParseResult.Parsed).payload
        if (payload.error.isNotEmpty()) {
            stateStore.clearSimkl()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl OAuth rejected: ${payload.error}",
                authState = sessionStore.authState(),
            )
        }
        if (payload.code.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Missing Simkl OAuth authorization code.",
                authState = sessionStore.authState(),
            )
        }

        val expectedState = stateStore.loadSimklState()
        if (expectedState.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl OAuth state mismatch.",
                authState = sessionStore.authState(),
            )
        }
        if (payload.state.isBlank() || payload.state != expectedState) {
            stateStore.clearSimkl()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl OAuth state mismatch.",
                authState = sessionStore.authState(),
            )
        }

        val tokenObject = simklApi.exchangeToken(code = payload.code)
        if (tokenObject == null) {
            stateStore.clearSimkl()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl token exchange failed.",
                authState = sessionStore.authState(),
            )
        }

        stateStore.clearSimkl()

        val accessToken = tokenObject.optString("access_token").trim()
        if (accessToken.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl token response missing access token.",
                authState = sessionStore.authState(),
            )
        }

        val userHandle = simklApi.fetchUserHandle(accessToken)
        sessionStore.connectProvider(
            provider = WatchProvider.SIMKL,
            accessToken = accessToken,
            refreshToken = null,
            expiresAtEpochMs = null,
            userHandle = userHandle,
        )

        return ProviderAuthActionResult(
            success = true,
            statusMessage = "Connected Simkl OAuth.",
            authState = sessionStore.authState(),
        )
    }
}
