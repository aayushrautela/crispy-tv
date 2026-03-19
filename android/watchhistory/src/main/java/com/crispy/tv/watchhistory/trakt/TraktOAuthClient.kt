package com.crispy.tv.watchhistory.trakt

import com.crispy.tv.player.ProviderAuthActionResult
import com.crispy.tv.player.ProviderAuthStartResult
import com.crispy.tv.player.ProviderSessionBackend
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.watchhistory.TRAKT_AUTHORIZE_BASE
import com.crispy.tv.watchhistory.auth.ProviderSessionStore
import com.crispy.tv.watchhistory.oauth.OAuthCallbackParseResult
import com.crispy.tv.watchhistory.oauth.OAuthCallbackParser
import com.crispy.tv.watchhistory.oauth.OAuthStateStore
import com.crispy.tv.watchhistory.oauth.Pkce
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class TraktOAuthClient(
    private val traktClientId: String,
    private val traktRedirectUri: String,
    private val sessionBackend: ProviderSessionBackend,
    private val sessionStore: ProviderSessionStore,
    private val stateStore: OAuthStateStore,
    private val callbackParser: OAuthCallbackParser,
    private val pkce: Pkce,
) {
    suspend fun begin(): ProviderAuthStartResult? {
        if (traktClientId.isBlank()) {
            return ProviderAuthStartResult(
                authorizationUrl = "",
                statusMessage = "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties.",
            )
        }
        if (traktRedirectUri.isBlank()) {
            return ProviderAuthStartResult(
                authorizationUrl = "",
                statusMessage = "Trakt redirect URI missing. Set TRAKT_REDIRECT_URI in gradle.properties.",
            )
        }

        val state = pkce.generateUrlSafeToken(16)
        val codeVerifier = pkce.generateUrlSafeToken(64)
        val codeChallenge = pkce.codeChallengeFromVerifier(codeVerifier)

        stateStore.saveTrakt(state = state, codeVerifier = codeVerifier)

        val authorizationUrl =
            TRAKT_AUTHORIZE_BASE.toHttpUrl().newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", traktClientId)
                .addQueryParameter("redirect_uri", traktRedirectUri)
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", codeChallenge)
                .addQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()

        return ProviderAuthStartResult(
            authorizationUrl = authorizationUrl,
            statusMessage = "Opening Trakt OAuth.",
        )
    }

    suspend fun complete(callbackUri: String): ProviderAuthActionResult {
        if (traktClientId.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Trakt client ID missing. Set TRAKT_CLIENT_ID in gradle.properties.",
                authState = sessionStore.authState(),
            )
        }
        if (traktRedirectUri.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Trakt redirect URI missing. Set TRAKT_REDIRECT_URI in gradle.properties.",
                authState = sessionStore.authState(),
            )
        }
        if (!sessionBackend.isConfigured()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Provider OAuth requires SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY.",
                authState = sessionStore.authState(),
            )
        }

        val parsed = callbackParser.parse(provider = WatchProvider.TRAKT, callbackUri = callbackUri)
        if (parsed is OAuthCallbackParseResult.Invalid) {
            return ProviderAuthActionResult(success = false, statusMessage = "Invalid Trakt callback URI.", authState = sessionStore.authState())
        }

        val payload = (parsed as OAuthCallbackParseResult.Parsed).payload
        val code = payload.code
        val returnedState = payload.state
        val oauthError = payload.error

        if (oauthError.isNotEmpty()) {
            stateStore.clearTrakt()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Trakt OAuth rejected: $oauthError",
                authState = sessionStore.authState(),
            )
        }
        if (code.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Missing OAuth authorization code.",
                authState = sessionStore.authState(),
            )
        }

        val expected = stateStore.loadTrakt()
        if (expected.state.isBlank()) {
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Trakt OAuth state mismatch.",
                authState = sessionStore.authState(),
            )
        }
        if (returnedState.isBlank() || returnedState != expected.state) {
            stateStore.clearTrakt()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Trakt OAuth state mismatch.",
                authState = sessionStore.authState(),
            )
        }
        if (expected.codeVerifier.isBlank()) {
            stateStore.clearTrakt()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = "Missing PKCE verifier for Trakt OAuth.",
                authState = sessionStore.authState(),
            )
        }

        val exchange =
            sessionBackend.exchangeProviderSession(
                provider = WatchProvider.TRAKT,
                code = code,
                redirectUri = traktRedirectUri,
                codeVerifier = expected.codeVerifier,
            )

        val session = exchange.session
        if (session == null) {
            stateStore.clearTrakt()
            return ProviderAuthActionResult(
                success = false,
                statusMessage = exchange.errorMessage ?: "Trakt token exchange failed.",
                authState = sessionStore.authState(),
            )
        }

        stateStore.clearTrakt()

        sessionStore.connectProvider(
            provider = WatchProvider.TRAKT,
            accessToken = session.accessToken,
            expiresAtEpochMs = session.expiresAtEpochMs,
            userHandle = session.userHandle,
        )

        return ProviderAuthActionResult(
            success = true,
            statusMessage = "Connected Trakt OAuth.",
            authState = sessionStore.authState(),
        )
    }
}
