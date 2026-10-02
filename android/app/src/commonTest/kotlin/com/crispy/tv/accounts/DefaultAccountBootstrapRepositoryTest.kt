package com.crispy.tv.accounts

import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.platform.KeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions in [DefaultAccountBootstrapRepository] that only a test can pin, all of
 * which became reachable when the class moved into `commonMain` with the keystore behind
 * the [AccountSessionStore] port and the Coil call behind a slot.
 *
 * The one that matters is `signOut()`, and the way it is pinned here is deliberate.
 * **Every collaborator is asked "did you get called" rather than "what did you compute",
 * because the decision is an ordering, and no per-collaborator assertion can see one.**
 * A test that asserted only that the profile id was cleared would pass against a
 * version that cleared it *before* `signOut()` read the session -- which is the exact
 * bug the capture-first line prevents, and the exact bug that let a stale session
 * survive. The order is observed by having each double append its own name to one
 * shared list, and by making `supabase.signOut()` null the session on the way past.
 */
class DefaultAccountBootstrapRepositoryTest {

    // --- The double that makes the ordering observable ---------------------------
    //
    // One list, shared by every collaborator, is the whole instrument: each double
    // appends a name and nothing else. A collaborator that was never expected to be
    // reached appends nothing, so a test asserting a prefix of the list also asserts
    // that nothing later ran.

    private class RecordingAccountApi(
        private var session: Session? = null,
        private val order: MutableList<String>,
        private val signOutThrows: Boolean = false,
    ) : AccountApi {
        override fun isConfigured(): Boolean = true

        override fun currentSession(): Session? = session

        override suspend fun ensureValidSession(): Session? = session

        override suspend fun signInWithEmail(email: String, password: String): Session =
            error("signInWithEmail")

        // `metadata` is `Map<String, String?>` and the return type is `SignUpResult`,
        // not `Session`. Both were written from memory first time and the compile
        // rejected them; read the interface, do not recall it.
        override suspend fun signUpWithEmail(
            email: String,
            password: String,
            metadata: Map<String, String?>,
        ): SignUpResult = error("signUpWithEmail")

        /**
         * Signs out by *clearing the session*, which is the part that makes the
         * capture-first rule observable. A double that only recorded the call would
         * let a version that reads the id after sign-out pass.
         */
        override suspend fun signOut() {
            order += "supabase.signOut"
            if (signOutThrows) error("network down")
            session = null
        }
    }

    private class RecordingSessionStore(private val order: MutableList<String>) : AccountSessionStore {
        var clearCalls = 0
            private set

        override fun current(): Session? = null

        override suspend fun save(session: Session) {
            error("save")
        }

        override suspend fun clear() {
            clearCalls++
            order += "tokenStore.clear"
        }
    }

    private class RecordingContextResolver(private val order: MutableList<String>) : BackendContextResolver {
        var clearCalls = 0
            private set
        var resolveCalls = 0
            private set
        private var answer: BackendContext? = null

        fun answering(value: BackendContext?) = apply { answer = value }

        override suspend fun resolve(): BackendContext? {
            resolveCalls++
            return answer
        }

        override fun clear() {
            clearCalls++
            order += "backendContextResolver.clear"
        }
    }

    /**
     * In memory, and it records nothing extra, because [ActiveProfileStore] is the thing
     * under test here. `putString` takes a non-null `String` and removal is
     * [remove] — which is the reason [ActiveProfileStore] branches on a null profile id
     * instead of storing one.
     */
    private class FakeKeyValueStore : KeyValueStore {
        private val entries = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? = entries[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            entries[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun getInt(key: String, defaultValue: Int): Int = defaultValue
        override fun putInt(key: String, value: Int) = Unit
        override fun getFloat(key: String, defaultValue: Float): Float = defaultValue
        override fun putFloat(key: String, value: Float) = Unit
        override fun contains(key: String): Boolean = entries.containsKey(key)
        override fun keys(): Set<String> = entries.keys.toSet()
        override fun remove(key: String) {
            entries.remove(key)
        }

        override fun clear() {
            entries.clear()
        }
    }

    private fun session(userId: String?): Session = Session(
        accessToken = "access",
        refreshToken = "refresh",
        expiresAtEpochSec = null,
        userId = userId,
        email = "someone@example.test",
        anonymous = false,
    )

    private fun harness(
        session: Session? = null,
        signOutThrows: Boolean = false,
        order: MutableList<String> = mutableListOf(),
    ): Harness {
        val keyValue = FakeKeyValueStore()
        return Harness(
            repository = DefaultAccountBootstrapRepository(
                clearImageCache = { order += "clearImageCache" },
                supabase = RecordingAccountApi(session, order, signOutThrows),
                backendContextResolver = RecordingContextResolver(order),
                backendClient = RecordingBackendApi(),
                activeProfileStore = ActiveProfileStore(keyValue),
                tokenStore = RecordingSessionStore(order),
            ),
            keyValue = keyValue,
            order = order,
        )
    }

    private class Harness(
        val repository: DefaultAccountBootstrapRepository,
        val keyValue: FakeKeyValueStore,
        val order: MutableList<String>,
    )

    // --- signOut: the ordering is the decision -------------------------------------

    /**
     * The user id is read from the session **before** `signOut()` clears it. A version
     * that moved the capture below the revoke would leave the active profile id on disk
     * forever, because after the revoke there is no user id left to key the removal on.
     *
     * The fake clears the session inside `signOut()` precisely so that a swapped pair of
     * lines fails: the removal is then keyed on null and nothing is written.
     */
    @Test
    fun theUserIdIsCapturedBeforeSigningOutRatherThanAfterIt() = runTest {
        val h = harness(session = session("user-42"))
        h.keyValue.putString("active_profile_id:user-42", "profile-7")

        h.repository.signOut()

        assertNull(
            h.keyValue.getString("active_profile_id:user-42"),
            "the captured id is what keys the removal; read after the revoke there is none",
        )
        assertEquals(
            listOf("supabase.signOut", "tokenStore.clear", "backendContextResolver.clear", "clearImageCache"),
            h.order,
            "the whole sequence, in order, with nothing else reached",
        )
    }

    /**
     * Local state is wiped even when the server-side revoke throws. The `runCatching`
     * around it is the decision: a sign-out that leaves a token on disk because the
     * network was down is the worse of the two failures, and the class says so.
     */
    @Test
    fun aFailedRevokeStillWipesEveryPieceOfLocalState() = runTest {
        val h = harness(session = session("user-9"), signOutThrows = true)
        h.keyValue.putString("active_profile_id:user-9", "profile-3")

        h.repository.signOut()

        assertNull(h.keyValue.getString("active_profile_id:user-9"))
        assertTrue(
            h.order.contains("tokenStore.clear") && h.order.contains("backendContextResolver.clear"),
            "both local wipes ran despite the throw: ${h.order}",
        )
        assertTrue("clearImageCache" in h.order, "the cache slot ran too: ${h.order}")
    }

    /**
     * With no session there is no user id, so there is no profile key to remove -- and
     * that is different from clearing an id that happens to be blank. Asserted by the
     * store being *untouched*, not merely by a null read: a `clear(null)` would remove
     * the `guest` key, which is a real key and would be a real bug.
     */
    @Test
    fun aSignedOutUserHasNoProfileEntryToRemoveAndNoneIsInvented() = runTest {
        val h = harness(session = null)
        h.keyValue.putString("active_profile_id:guest", "someone-elses-profile")

        h.repository.signOut()

        assertEquals(
            "someone-elses-profile",
            h.keyValue.getString("active_profile_id:guest"),
            "a null user id must not be normalised to the guest key and deleted",
        )
    }

    /**
     * A session whose `userId` is blank is treated as *no* user id. `takeIf { it.isNotBlank() }`
     * is a decision with a visible consequence, and blank is the input a half-migrated
     * row actually produces.
     */
    @Test
    fun aBlankUserIdIsTreatedAsNoUserIdRatherThanAsAKey() = runTest {
        val h = harness(session = session("   "))
        h.keyValue.putString("active_profile_id:guest", "keep-me")

        h.repository.signOut()

        assertEquals("keep-me", h.keyValue.getString("active_profile_id:guest"))
    }

    // --- bootstrap: what the three states are --------------------------------------

    /**
     * The three answers are genuinely different states, not one "signed out" answer.
     * Collapsing them would tell a signed-in user with no profile to sign in again.
     */
    @Test
    fun theThreeBootstrapStatesAreDistinguishableAndNoneIsGuessed() = runTest {
        val signedOut = harness(session = null)
        val result = signedOut.repository.bootstrap()
        assertEquals(false, result.signedIn)
        assertEquals(false, result.anonymous)
        assertEquals(false, result.onboardingComplete)
        assertNull(result.session)

        val withProfile = harness(session = session("u")).apply {
            keyValue.putString("unused", "x")
        }
        // resolve() answers null on this harness, so the state is "signed in, no profile yet".
        val second = withProfile.repository.bootstrap()
        assertEquals(true, second.signedIn)
        assertEquals(false, second.onboardingComplete, "no resolved backend context means not onboarded")
    }

    /** A resolved backend context is what onboardingComplete means -- not merely a session. */
    @Test
    fun aResolvedBackendContextIsWhatMarksTheAccountOnboarded() = runTest {
        val order = mutableListOf<String>()
        val keyValue = FakeKeyValueStore()
        val resolver = RecordingContextResolver(order).answering(BackendContext("t", "p"))
        val repository = DefaultAccountBootstrapRepository(
            clearImageCache = { },
            supabase = RecordingAccountApi(session("u"), order),
            backendContextResolver = resolver,
            backendClient = RecordingBackendApi(),
            activeProfileStore = ActiveProfileStore(keyValue),
            tokenStore = RecordingSessionStore(order),
        )

        val result = repository.bootstrap()

        assertEquals(true, result.onboardingComplete)
        assertEquals(1, resolver.resolveCalls)
    }
}
