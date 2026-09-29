package com.crispy.tv.accounts

/**
 * Supabase auth, as `commonMain` code sees it.
 *
 * ## Why this exists
 *
 * The account surface is a sibling of [com.crispy.tv.backend.BackendApi] and
 * exists for the same reason. Every type it exchanges is already portable --
 * [Session] and [SignUpResult] are plain data in `commonMain`, and the only
 * strings in between are strings. The one thing that is *not* portable is the
 * transport: `SupabaseAccountClient` speaks OkHttp and `org.json` and is
 * therefore `androidMain`.
 *
 * `SupabaseAccountClient` implements this with its body unchanged, so this is a
 * type-level port, not a transport port. No request or response handling moved.
 *
 * ## What this is not
 *
 * There is no `HttpClientPort` here, for the same reason there is none on
 * [com.crispy.tv.backend.BackendApi]: [com.crispy.tv.network.CrispyHttpClient]
 * leaks `okhttp3.HttpUrl` and `Headers` in its own signature, so it is a wrapper
 * around OkHttp rather than an abstraction of it. Every OkHttp and `org.json`
 * type in the client is `private`, so none of them reach this interface.
 *
 * ## Errors
 *
 * Implementations throw [IllegalStateException] when Supabase is not configured
 * or when a non-2xx response carries no usable message, and the caller is
 * expected to treat the thrown message as user-facing. There is no typed
 * failure type here because there is none on the client either; introducing one
 * would be a behaviour change on the error boundary, not plumbing.
 */
interface AccountApi {

    /** False when no Supabase URL or publishable key was configured. */
    fun isConfigured(): Boolean

    /** The stored session, or null. Does not refresh and does not touch the network. */
    fun currentSession(): Session?

    /**
     * The stored session, refreshed first if it is expired or close to expiry.
     *
     * Returns null when there is no session, when the session cannot be
     * refreshed, or when the refresh token is rejected -- a rejected refresh
     * clears the store. A *transient* network failure keeps the old session and
     * returns it, because a session that is merely stale is still usable.
     */
    suspend fun ensureValidSession(): Session?

    /** Throws [IllegalStateException] when not configured, or on a non-2xx response. */
    suspend fun signInWithEmail(email: String, password: String): Session

    /**
     * Throws [IllegalStateException] when not configured, or on a non-2xx response.
     *
     * A [SignUpResult] with no session is the normal outcome when the account
     * needs email confirmation; see [SignUpResult].
     */
    suspend fun signUpWithEmail(
        email: String,
        password: String,
        metadata: Map<String, String?> = emptyMap(),
    ): SignUpResult

    /**
     * Revokes the session server-side when one is held.
     *
     * Clearing the local store is the caller's job, so that server-side
     * revocation and the local wipe run under a single lock.
     */
    suspend fun signOut()
}
