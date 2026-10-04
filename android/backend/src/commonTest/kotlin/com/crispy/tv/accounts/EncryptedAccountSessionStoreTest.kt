package com.crispy.tv.accounts

import com.crispy.tv.platform.SecretStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EncryptedAccountSessionStore] over two doubles.
 *
 * **Neither double can reach a real keystore, which is the point.** The production
 * encryption is `SecureTokenStore`'s AndroidKeyStore AES key on Android and
 * `DesktopSecretStore`'s AES-GCM-over-a-file on the desktop build, and neither is
 * reachable from a `commonTest`. What is left for this suite is everything the class
 * itself decides: the JSON codec, the three `null` answers, the expiry rule, the
 * `remove`-not-`clear` decision and the delegation boundary (the token must not be
 * readable in the store).
 *
 * **The `null`-versus-`"null"` case is the one worth having.** `SecureTokenStore`'s
 * equivalent row is a defect on AOSP — `optString` of `JSONObject.NULL` is the four
 * characters `null` — and it is invisible to a JVM test standing in for `org.json`,
 * because the reference implementation answers `""` there. This assertion is the
 * portable half of that finding, and its failure message names the value it is about
 * so a reader does not have to work out which of six fields moved.
 */
class EncryptedAccountSessionStoreTest {
    @Test
    fun everyFieldSurvivesTheRoundTrip() = runTest {
        val store = FakeKeyValueStore()
        val sessions = EncryptedAccountSessionStore(FakeSecretStore(), store)
        val session = Session(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAtEpochSec = 1_700_000_000L,
            userId = "profile-1",
            email = "someone@example.test",
            anonymous = true,
        )

        sessions.save(session)

        assertEquals(session, sessions.current())
    }

    @Test
    fun aSessionOfNullsAndBlanksSurvivesTheRoundTrip() = runTest {
        val store = FakeKeyValueStore()
        val sessions = EncryptedAccountSessionStore(FakeSecretStore(), store)
        val session = Session(
            accessToken = "access-token",
            refreshToken = "",
            expiresAtEpochSec = null,
            userId = null,
            email = null,
            anonymous = false,
        )

        sessions.save(session)

        assertEquals(session, sessions.current())
    }

    /**
     * The delegation, and the most this suite can honestly say about it.
     *
     * **"The token is not readable in the stored value" is not assertable here, and an
     * earlier draft of this test asserted it anyway.** [FakeSecretStore] is reversible on
     * purpose -- it has to be, for a suite that writes entries by hand to reach the
     * codec -- so the stored value is `prefix + plaintext` and the token *is* in it.
     * A double that produced unreadable bytes would either be a real cipher (which
     * cannot be asserted in `commonTest`, because the production ones are AndroidKeyStore
     * and a desktop key file) or a fake whose opacity proves nothing. So what is pinned
     * is the claim a reversible double can carry: the class stores whatever the
     * [SecretStore] returns and never writes a session itself. That the real
     * implementations produce ciphertext is [SecureTokenStore]'s and `DesktopSecretStore`'s
     * own business, each with its own suite.
     */
    @Test
    fun whatIsStoredIsWhateverTheSecretStoreReturned() = runTest {
        val store = FakeKeyValueStore()
        val secretStore = FakeSecretStore()
        val sessions = EncryptedAccountSessionStore(secretStore, store)

        sessions.save(
            Session(
                accessToken = "an-access-token",
                refreshToken = "a-refresh-token",
                expiresAtEpochSec = null,
                userId = null,
                email = "someone@example.test",
                anonymous = false,
            ),
        )

        val raw = store.getString(KEY_SESSION, null)
        assertTrue(raw != null, "save() wrote no value under the session key")
        assertEquals(
            listOf(secretStore.encryptedValues.single()),
            listOf(raw.removePrefix(FakeSecretStore.PREFIX)),
            "the stored value is not what the SecretStore returned for the session JSON",
        )
        assertEquals(
            1,
            secretStore.encryptedValues.size,
            "save() must encrypt exactly once -- once per save, not once per field",
        )
    }

    /**
     * The row in [EncryptedAccountSessionStore.parse]'s table that is a defect in
     * `SecureTokenStore`: a JSON null has to read back as `null`, not as the four
     * characters `"null"`.
     */
    @Test
    fun aNullUserIdAndEmailReadBackAsNullAndNotAsTheStringNull() = runTest {
        val store = FakeKeyValueStore()
        val secretStore = FakeSecretStore()
        store.putString(
            KEY_SESSION,
            secretStore.encrypt(
                """{"access_token":"a","refresh_token":"r","expires_at":null,""" +
                    """"user_id":null,"email":null}""",
            ),
        )
        val sessions = EncryptedAccountSessionStore(secretStore, store)

        val session = sessions.current()

        assertTrue(session != null, "a session with an access token was not read back at all")
        assertNull(session.userId, "user_id was null and must not read back as a value")
        assertNull(session.email, "email was null and must not read back as a value")
        assertNull(
            session.expiresAtEpochSec,
            "expires_at was null and must not read back as a value",
        )
        assertFalse(session.anonymous, "an absent anonymous flag is false, not true")
    }

    @Test
    fun theLiteralStringNullIsAlsoDropped() = runTest {
        val store = FakeKeyValueStore()
        val secretStore = FakeSecretStore()
        store.putString(
            KEY_SESSION,
            secretStore.encrypt(
                """{"access_token":"a","refresh_token":"r","user_id":"null","email":"NULL"}""",
            ),
        )
        val sessions = EncryptedAccountSessionStore(secretStore, store)

        val session = sessions.current()

        assertTrue(session != null, "a session with an access token was not read back at all")
        assertNull(
            session.userId,
            "the literal string \"null\" is dropped, because backend payloads carry it",
        )
        assertNull(session.email, "the check is case-insensitive, as optNullableString's is")
    }

    /**
     * An entry that decrypts but names no access token is not a session, and returning
     * one would hand the client an empty credential to fail with later.
     */
    @Test
    fun anEntryWithNoAccessTokenIsNotASession() = runTest {
        val store = FakeKeyValueStore()
        val secretStore = FakeSecretStore()
        store.putString(
            KEY_SESSION,
            secretStore.encrypt("""{"access_token":"  ","refresh_token":"r"}"""),
        )
        val sessions = EncryptedAccountSessionStore(secretStore, store)

        assertNull(
            sessions.current(),
            "a blank access token is not a session, and must not read back as one",
        )
    }

    @Test
    fun anExpiryAtOrBeforeZeroIsNotAnExpiry() = runTest {
        val store = FakeKeyValueStore()
        val secretStore = FakeSecretStore()
        val sessions = EncryptedAccountSessionStore(secretStore, store)
        val session = Session(
            accessToken = "a",
            refreshToken = "r",
            expiresAtEpochSec = 1L,
            userId = null,
            email = null,
            anonymous = false,
        )

        for (stored in listOf(0L, -1L)) {
            store.putString(
                KEY_SESSION,
                secretStore.encrypt("""{"access_token":"a","expires_at":$stored}"""),
            )

            val read = sessions.current()

            assertTrue(read != null, "the session was not read back for expires_at=$stored")
            assertNull(
                read.expiresAtEpochSec,
                "expires_at=$stored is not after the epoch and must not read back as an expiry",
            )
        }

        sessions.save(session)

        assertEquals(1L, sessions.current()?.expiresAtEpochSec, "one second is after the epoch")
    }

    @Test
    fun aValueThisStoreCannotDecryptIsNotASession() = runTest {
        val store = FakeKeyValueStore()
        val sessions = EncryptedAccountSessionStore(FakeSecretStore(), store)
        store.putString(KEY_SESSION, "a plaintext token left behind by something else")

        assertNull(
            sessions.current(),
            "an undecryptable entry is no session, and must not throw either",
        )
    }

    @Test
    fun anEmptyStoreIsNotASession() = runTest {
        val sessions = EncryptedAccountSessionStore(FakeSecretStore(), FakeKeyValueStore())

        assertNull(sessions.current(), "an empty store holds no session")
    }

    @Test
    fun aMalformedStoredPayloadIsNotASession() = runTest {
        val store = FakeKeyValueStore()
        val secretStore = FakeSecretStore()
        store.putString(KEY_SESSION, secretStore.encrypt("not json at all"))
        val sessions = EncryptedAccountSessionStore(secretStore, store)

        assertNull(sessions.current(), "a corrupt entry must not take the app down on launch")
    }

    /**
     * `remove`, not `clear` — the store is handed in rather than owned, so the claim is
     * about one key rather than about every key in it.
     */
    @Test
    fun clearRemovesTheSessionAndLeavesTheRestOfTheStoreAlone() = runTest {
        val store = FakeKeyValueStore()
        val sessions = EncryptedAccountSessionStore(FakeSecretStore(), store)
        sessions.save(
            Session(
                accessToken = "a",
                refreshToken = "r",
                expiresAtEpochSec = null,
                userId = null,
                email = null,
                anonymous = false,
            ),
        )
        store.putString("a_sibling_key", "a sibling value")

        sessions.clear()

        assertNull(sessions.current(), "the session is still readable after clear()")
        assertTrue(
            store.contains("a_sibling_key"),
            "clear() emptied a store it does not own",
        )
    }

    private companion object {
        /** `SecureTokenStore`'s key, and this class's own. */
        private const val KEY_SESSION = "session"
    }
}

/**
 * Reversible and fake on purpose: the suite needs to see that a value was *handed* to
 * the [SecretStore] and came back, not that a real cipher round-tripped.
 *
 * [SecretStore] has no fake in this module and does not deserve one outside this file:
 * the production implementations are AndroidKeyStore on Android and an AES-GCM key
 * file on the desktop build, and neither is reachable from a `commonTest`. What is
 * worth pinning is the delegation -- that the class stores nothing in the clear -- and
 * [EncryptedAccountSessionStoreTest.whatIsStoredIsWhateverTheSecretStoreReturned] does
 * that against this double. The store itself is [FakeKeyValueStore], the module's
 * existing in-memory one, reused rather than re-declared: an earlier draft of this
 * file declared its own, and being in the same package it shadowed the shared class
 * for `BackendContextResolverTest`, which imports that name across packages.
 */
private class FakeSecretStore : SecretStore {
    val encryptedValues = mutableListOf<String>()

    override fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    override fun encrypt(plaintext: String): String {
        encryptedValues += plaintext
        return PREFIX + plaintext
    }

    override fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return null
        return stored.removePrefix(PREFIX)
    }

    companion object {
        const val PREFIX = "fake_encrypted:"
    }
}
