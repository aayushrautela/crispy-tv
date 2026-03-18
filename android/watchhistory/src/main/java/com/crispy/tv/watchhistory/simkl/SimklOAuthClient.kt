package com.crispy.tv.watchhistory.simkl

import com.crispy.tv.player.ProviderAuthActionResult
import com.crispy.tv.player.ProviderAuthStartResult
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.watchhistory.auth.ProviderSessionStore
import com.crispy.tv.watchhistory.oauth.OAuthCallbackParseResult
import com.crispy.tv.watchhistory.oauth.OAuthCallbackParser
import com.crispy.tv.watchhistory.oauth.OAuthStateStore
import com.crispy.tv.watchhistory.oauth.Pkce
import com.crispy.tv.watchhistory.oauth.SupabaseFunctionExchangeClient
import org.json.JSONObject

internal class SimklOAuthClient(
    private val simklClientId: String,
    private val simklRedirectUri: String,
    private val oauthExchangeClient: SupabaseFunctionExchangeClient,
    private val simklService: SimklService,
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
        val codeVerifier = pkce.generateUrlSafeToken(64)
        val codeChallenge = pkce.codeChallengeFromVerifier(codeVerifier)
        stateStore.saveSimkl(state = state, codeVerifier = codeVerifier)

        val authorizationUrl = simklService.authorizeUrl(state = state, codeChallenge = codeChallenge)
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
        if (!oauthExchangeClient.isConfigured()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Provider OAuth requires SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY.",
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

        val expected = stateStore.loadSimkl()
        if (expected.state.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl OAuth state mismatch.",
                authState = sessionStore.authState(),
            )
        }
        if (payload.state.isBlank() || payload.state != expected.state) {
            stateStore.clearSimkl()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl OAuth state mismatch.",
                authState = sessionStore.authState(),
            )
        }

        if (expected.codeVerifier.isBlank()) {
            stateStore.clearSimkl()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Simkl OAuth code verifier missing.",
                authState = sessionStore.authState(),
            )
        }

        val tokenPayload =
            JSONObject()
                .put("code", payload.code)
                .put("codeVerifier", expected.codeVerifier)
                .put("redirectUri", simklRedirectUri)
        val exchange = oauthExchangeClient.post(functionName = "simkl-oauth-exchange", payload = tokenPayload)
        val tokenObject = exchange.payload
        if (tokenObject == null) {
            stateStore.clearSimkl()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = exchange.errorMessage ?: "Simkl token exchange failed.",
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

        val userHandle =
            tokenObject.optString("providerUsername").trim().ifBlank {
                tokenObject.optString("providerUserId").trim()
            }.ifBlank { null }
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
