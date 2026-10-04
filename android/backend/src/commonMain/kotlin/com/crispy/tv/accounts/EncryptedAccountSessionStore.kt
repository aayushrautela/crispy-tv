package com.crispy.tv.accounts

import com.crispy.tv.backend.optBooleanOrNull
import com.crispy.tv.backend.optLongOrNull
import com.crispy.tv.backend.optNullableString
import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.platform.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * An [AccountSessionStore] that encrypts the session through a [SecretStore] and keeps
 * the ciphertext in a [KeyValueStore], so the auth tokens have somewhere to live on a
 * platform whose keystore is not Android's.
 *
 * [SecureTokenStore] is the same contract and is deliberately **not** reused or
 * merged: it reaches `android.security.keystore` and `SharedPreferences`, and its
 * `org.json` codec answers differently from `kotlinx.serialization`'s on a value that
 * [Session] really produces (the table in [parse] has all three rows). Merging the two
 * would be a behaviour change to a shipping Android class, not a move.
 *
 * Everything this class needs is already portable: `SecretStore`, `KeyValueStore` and
 * `SecretFormat` are `:platform-core` `commonMain`, so the keystore and the file
 * backing both arrive as ports and this class holds no platform type of its own.
 * Android hands it the keystore-backed store, the desktop build hands it
 * `DesktopSecretStore`.
 *
 * ## The store's name and this class's key are the composition root's business
 *
 * The *name* of the [KeyValueStore] is chosen by whoever builds the services — Android
 * keeps it inside `SecureTokenStore` as the `auth_tokens_secure` preferences file, and
 * the desktop build names its own file — so the name is not a cross-platform contract
 * and the two platforms' files are not interchangeable. [KEY_SESSION] is this class's
 * own key inside whichever store it is given, and it matches `SecureTokenStore`'s so a
 * file written by either codec shape reads the same key.
 *
 * ## No in-memory cache, and that is a decision
 *
 * `SecureTokenStore` answers every read from a `MutableStateFlow`, so it pays no crypto
 * per call. This class decrypts on every [current], and the reason is the port rather
 * than the performance: `AccountSessionStore` has no invalidation event, so a cache here
 * would be correct for the one instance that wrote it and stale for any second instance
 * over the same file, and the only way to notice the second instance is a bug report.
 * [SecureTokenStore]'s cache is safe because its reactive surface is public — its own
 * `session: StateFlow` is how Android callers learn about a sign-in, and that surface is
 * deliberately **not** in this port (see [AccountSessionStore]).
 */
class EncryptedAccountSessionStore(
    private val secretStore: SecretStore,
    private val store: KeyValueStore,
) : AccountSessionStore {
    /**
     * The session, or `null` for three different reasons that all mean "there is no
     * session": nothing stored, a stored value this store cannot decrypt, and a stored
     * value whose `access_token` is blank. The third is the one that matters — an entry
     * that decrypts but names no token is not a session, and returning one would hand
     * the client an empty credential to fail with later.
     *
     * Every failure is a `null` rather than a throw, because [SecureTokenStore] answers
     * them that way and a corrupt entry on disk must not take the app down on launch.
     */
    override fun current(): Session? = runCatching {
        val stored = store.getString(KEY_SESSION, null) ?: return null
        val plaintext = secretStore.decrypt(stored) ?: return null
        parse(plaintext)
    }.getOrNull()

    override suspend fun save(session: Session) {
        val json =
            buildJsonObject {
                put("access_token", session.accessToken)
                put("refresh_token", session.refreshToken)
                // `put(key, String?)` and `put(key, JsonElement?)` both turn a null into
                // `JsonNull`, but they only do it on the *builder*, where the receiver is
                // `JsonObjectBuilder` — so the `?:` is spelled out here rather than left to
                // an overload that a reader has to know. `SecureTokenStore` writes
                // `JSONObject.NULL` for exactly these three, so an absent field and a null
                // field are the same two events on both codecs.
                put("expires_at", session.expiresAtEpochSec?.let(::JsonPrimitive) ?: JsonNull)
                put("user_id", session.userId?.let(::JsonPrimitive) ?: JsonNull)
                put("email", session.email?.let(::JsonPrimitive) ?: JsonNull)
                put("anonymous", session.anonymous)
            }.toString()
        store.putString(KEY_SESSION, secretStore.encrypt(json))
    }

    /**
     * `remove` rather than [KeyValueStore.clear], which is what `SecureTokenStore` calls.
     *
     * The two are equivalent while the store is dedicated to this one key — Android's is,
     * because the whole `auth_tokens_secure` preferences file holds the session and
     * nothing else — but `clear` is a statement about every key in the store rather than
     * about this session, and this class is handed a store it does not own. Removing one
     * key cannot destroy a sibling that a future key rotation adds.
     */
    override suspend fun clear() {
        runCatching { store.remove(KEY_SESSION) }
    }

    /**
     * The rules [SecureTokenStore.parse] applies, over `kotlinx.serialization` rather
     * than `org.json`, and the three places the two disagree:
     *
     * | stored value | `SecureTokenStore` (AOSP `org.json`) | here |
     * |---|---|---|
     * | `access_token` absent or blank | `null` | `null` |
     * | `expires_at: null` | `null` — `optLong` falls back to `-1` for a non-`Number` | `null` |
     * | `user_id: null` | **`"null"`, the literal string** | `null` |
     * | `email: null` | **`"null"`, the literal string** | `null` |
     * | `anonymous` absent | `false` | `false` |
     *
     * **The `user_id` and `email` rows are a defect in [SecureTokenStore], not a
     * difference of taste, and this landing does not touch it.** AOSP's
     * `optString` runs the value through `String.valueOf`, and `JSONObject.NULL.toString()`
     * is the four characters `null`, so `.trim().ifBlank { null }` never fires and a
     * session stored without an email reads back with `email = "null"`. The reference
     * `org.json` answers `""` there instead, which is why a JVM test standing in for
     * `org.json` would report the reference's answer and hide this.
     *
     * The last row of that pair is not a bug either way: `optNullableString` also rejects
     * the literal string `"null"` because backend payloads really do carry it, so a
     * *genuine* value of `"null"` is dropped here and kept by `SecureTokenStore`. This
     * class reuses `:backend`'s existing helpers rather than writing a fourth set of JSON
     * policies, which is also why the `refreshToken` below is trimmed by the helper and
     * never gets an `ifBlank`: `SecureTokenStore` keeps an empty refresh token as an
     * empty string, and so does this.
     */
    private fun parse(plaintext: String): Session? {
        val json = Json.parseToJsonElement(plaintext).jsonObject
        val accessToken = json.optNullableString("access_token") ?: return null
        return Session(
            accessToken = accessToken,
            refreshToken = json.optNullableString("refresh_token").orEmpty(),
            expiresAtEpochSec = json.optLongOrNull("expires_at")?.takeIf { it > 0L },
            userId = json.optNullableString("user_id"),
            email = json.optNullableString("email"),
            anonymous = json.optBooleanOrNull("anonymous") ?: false,
        )
    }

    private companion object {
        private const val KEY_SESSION = "session"
    }
}