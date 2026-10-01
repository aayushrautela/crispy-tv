package com.crispy.tv.accounts

/**
 * The three things a Supabase account client needs from wherever auth tokens live.
 *
 * **`SecureTokenStore` is not portable — it reaches `android.security.keystore` and
 * `SharedPreferences` — so naming it as a constructor parameter pinned
 * `SupabaseAccountClient` to `androidMain` for exactly the same reason 39 of the
 * backend parsers were pinned by `CrispyBackendClient`.** Typing the parameter as
 * this interface is the whole fix, and it is the same fix: a file whose receiver, or
 * whose constructor parameter, is pinned cannot be freed by changing its arguments.
 *
 * **It is a third contract, not a second one on [SecureTokenStore].** That class
 * already implements `com.crispy.tv.platform.SecretStore` — `isEncrypted` /
 * `encrypt` / `decrypt` — and **none of those three members is anything
 * `SupabaseAccountClient` calls**, so typing the parameter to `SecretStore` would
 * have failed to compile and named three unrelated members rather than the real
 * shape. One class, two contracts, and this is the second.
 *
 * **`session: StateFlow<Session?>` is deliberately absent.** `SecureTokenStore`
 * exposes it, and building this port from the class's public surface rather than from
 * its callers' calls would have carried it along for nothing: the union of every
 * caller is exactly these three members, and the reactive surface is consumed
 * elsewhere by callers that stay on the concrete type.
 */
interface AccountSessionStore {
    fun current(): Session?

    suspend fun save(session: Session)

    suspend fun clear()
}