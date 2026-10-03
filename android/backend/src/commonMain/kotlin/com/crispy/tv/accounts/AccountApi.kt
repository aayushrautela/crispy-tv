package com.crispy.tv.accounts

/**
 * Supabase auth, as `commonMain` code sees it.
 *
 * ## Why this exists
 *
 * The account surface is a sibling of [com.crispy.tv.backend.BackendApi] and
 * exists for the same reason. Every type it exchanges is already portable --
 * [Session] and [SignUpResult] are plain data in `commonMain`, and the only
 * strings in between are strings. This interface was introduced because the one
 * thing that was *not* portable was the implementation: `SupabaseAccountClient`
 * spoke OkHttp and `org.json` and was therefore `androidMain`.
 *
 * **That reason is gone.** The client is in `commonMain` now, behind the
 * [com.crispy.tv.network.CrispyHttpClient] port and answering `JsonObject`, and
 * [SecureTokenStore] is the only file this module still keeps in `androidMain`
 * because Android Keystore has no portable form. The interface is still the
 * right thing for `commonMain` callers to name, for the same reason as its
 * sibling: **it is what a `commonTest` can stand in for.**
 *
 * `SupabaseAccountClient` implements this with its body unchanged, so introducing
 * it was a type-level port, not a transport port. No request or response
 * handling moved.
 *
 * ## What this is not
 *
 * The transport is [com.crispy.tv.network.CrispyHttpClient], one level down, and
 * it is a genuine port. **This KDoc previously claimed there was no
 * `HttpClientPort` here because that port "leaks `okhttp3.HttpUrl` and `Headers`
 * in its own signature", and that was false** -- the same invented reason as on
 * [com.crispy.tv.backend.BackendApi], which is how one false premise ended up
 * load-bearing in three sibling KDocs. It leaked nothing, and no `commonMain`
 * file in this repository imports `okhttp3`. What stays true: no `okhttp3` or
 * `org.json` type reaches this interface.
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
