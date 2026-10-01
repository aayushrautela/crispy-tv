package com.crispy.tv.addons.sources

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.accounts.Session
import com.crispy.tv.accounts.SignUpResult
import com.crispy.tv.player.SupabaseSyncAuthState
import com.crispy.tv.player.SupabaseSyncLabResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [RemoteSupabaseSyncLabService], which had no test at all until it moved to
 * `commonMain`. Two things are worth pinning here and they are not the same kind
 * of thing.
 *
 * **The disabled messages are product decisions, and eight of them are distinct.**
 * A copy-paste that made `signOut` answer with `pushAllLocalData`'s message compiles,
 * ships, and tells a user the wrong reason a sync did not run -- and the closest
 * pair in the file differs by a single word: `initialize` says the lab is
 * "temporarily disabled until" addon sync moves, while `syncNow` says it "is
 * disabled until". That is why the suite asserts the messages as a set *and*
 * separately asserts which two members are allowed to share one. Asserting the set
 * alone would pass if any two merged; asserting them one at a time would miss a
 * member nobody covered.
 *
 * **The dispatcher asymmetry is the second decision.** Eight members enter
 * `ioDispatcher` and `syncNow` does not. That is not a tidy-up opportunity, so both
 * halves are pinned: a recording dispatcher counts entries, and the count is `0` for
 * `syncNow` and `1` for every other member. The count is taken as a delta around
 * each call rather than read once at the end, because a list built eagerly is
 * already cumulative by the time a loop inspects its first member.
 *
 * A dispatcher that *runs* its block inline is used rather than one that throws:
 * it counts the same entries without leaving a coroutine that never dispatches
 * waiting to be resumed.
 */
class RemoteSupabaseSyncLabServiceTest {

    /**
     * The nine suspend members, each paired with its own name. One list, because
     * three tests below need it and a second copy is a second thing to forget.
     */
    private fun members(
        service: RemoteSupabaseSyncLabService,
    ): List<Pair<String, suspend () -> SupabaseSyncLabResult>> = listOf(
        "initialize" to { service.initialize() },
        "signUpWithEmail" to { service.signUpWithEmail("a@b.c", "pw") },
        "signInWithEmail" to { service.signInWithEmail("a@b.c", "pw") },
        "signOut" to { service.signOut() },
        "pushAllLocalData" to { service.pushAllLocalData() },
        "pullAllToLocal" to { service.pullAllToLocal() },
        "syncNow" to { service.syncNow() },
        "generateSyncCode" to { service.generateSyncCode("1234") },
        "claimSyncCode" to { service.claimSyncCode("1234", "1234") },
    )

    @Test
    fun everyMemberAnswersWithItsOwnMessageAndReportsNoProgress() = runTest {
        val results = members(RemoteSupabaseSyncLabService(StubAccountApi(), UnconfinedTestDispatcher()))
            .map { (member, call) -> member to call() }

        assertEquals(
            8,
            results.map { it.second.statusMessage }.toSet().size,
            "nine members carry eight distinct messages; only the two sync-code members share one",
        )

        for ((member, result) in results) {
            assertTrue(result.statusMessage.isNotBlank(), "$member must not answer with a blank message")
            assertNull(result.syncCode, "$member must not carry a sync code")
            assertEquals(0, result.pushedAddons, "$member pushed addons")
            assertEquals(0, result.pushedWatchedItems, "$member pushed watched items")
            assertEquals(0, result.pulledAddons, "$member pulled addons")
            assertEquals(0, result.pulledWatchedItems, "$member pulled watched items")
        }
    }

    @Test
    fun theOnlySharedMessageBelongsToTheTwoSyncCodeMembers() = runTest {
        val results = members(RemoteSupabaseSyncLabService(StubAccountApi(), UnconfinedTestDispatcher()))
            .map { (member, call) -> member to call() }

        val shared = results.groupingBy { it.second.statusMessage }.eachCount().filterValues { it > 1 }

        assertEquals(1, shared.size, "exactly one message is answered by more than one member")
        val (message, count) = shared.entries.single()
        assertEquals(2, count, "the shared message is answered twice, not three or four times")
        assertEquals(
            setOf("generateSyncCode", "claimSyncCode"),
            results.filter { it.second.statusMessage == message }.map { it.first }.toSet(),
            "the shared message belongs to the sync-code pair and to nothing else",
        )
    }

    @Test
    fun initializeAndSyncNowDifferByOneWordAndNotByAccident() = runTest {
        val results = members(RemoteSupabaseSyncLabService(StubAccountApi(), UnconfinedTestDispatcher()))
            .associate { (member, call) -> member to call() }

        val initialize = results.getValue("initialize").statusMessage
        val syncNow = results.getValue("syncNow").statusMessage

        assertTrue(
            initialize.startsWith("Sync Lab is temporarily ") && syncNow.startsWith("Sync Lab is "),
            "both whole-lab messages open the same way",
        )
        assertEquals(
            "Sync Lab is temporarily " + syncNow.removePrefix("Sync Lab is "),
            initialize,
            "`temporarily` is the entire difference between the two whole-lab messages",
        )
    }

    @Test
    fun eightMembersEnterTheIoDispatcherAndSyncNowDoesNot() = runTest {
        val dispatcher = RecordingDispatcher()
        val service = RemoteSupabaseSyncLabService(StubAccountApi(), dispatcher)

        for ((member, call) in members(service)) {
            val before = dispatcher.dispatches
            call()
            val entered = dispatcher.dispatches - before
            val expected = if (member == "syncNow") 0 else 1
            assertEquals(expected, entered, "$member entered the io dispatcher $entered time(s), expected $expected")
        }
    }

    @Test
    fun anUnconfiguredApiReportsNothingEvenWhenASessionIsHeld() {
        val api = StubAccountApi(isConfigured = false, session = session(accessToken = "token"))

        val state = RemoteSupabaseSyncLabService(api, UnconfinedTestDispatcher()).authState()

        assertFalse(state.configured, "the api reports itself unconfigured")
        assertFalse(
            state.authenticated,
            "a held session must not read as authenticated while the api is unconfigured -- " +
                "this is the half of the rule that `isConfigured() &&` adds",
        )
        assertFalse(state.anonymous, "the session is not anonymous")
    }

    @Test
    fun aConfiguredApiWithANonBlankTokenIsAuthenticated() {
        val api = StubAccountApi(
            isConfigured = true,
            session = session(accessToken = "token", userId = "u-1", email = "a@b.c"),
        )

        val state = RemoteSupabaseSyncLabService(api, UnconfinedTestDispatcher()).authState()

        assertTrue(state.configured, "the api reports itself configured")
        assertTrue(state.authenticated, "a configured api with a non-blank token is authenticated")
        assertEquals("u-1", state.userId, "the user id passes through")
        assertEquals("a@b.c", state.email, "the email passes through")
        assertFalse(state.anonymous, "the session is not anonymous")
    }

    @Test
    fun aBlankAccessTokenIsNotAuthenticated() {
        val api = StubAccountApi(isConfigured = true, session = session(accessToken = "   "))

        val state = RemoteSupabaseSyncLabService(api, UnconfinedTestDispatcher()).authState()

        assertTrue(state.configured, "the api reports itself configured")
        assertFalse(
            state.authenticated,
            "a whitespace-only token is not a token -- the two worlds differ in exactly this respect",
        )
    }

    @Test
    fun noSessionIsNotAnonymous() {
        val api = StubAccountApi(isConfigured = true, session = null)

        val state = RemoteSupabaseSyncLabService(api, UnconfinedTestDispatcher()).authState()

        assertTrue(state.configured, "the api reports itself configured")
        assertFalse(state.authenticated, "no session means no authentication")
        assertFalse(
            state.anonymous,
            "an absent session answers false rather than staying null -- `== true`, not `?: false`",
        )
        assertNull(state.userId, "an absent session has no user id")
        assertNull(state.email, "an absent session has no email")
    }

    @Test
    fun anAnonymousSessionIsBothAnonymousAndAuthenticated() {
        val api = StubAccountApi(
            isConfigured = true,
            session = session(accessToken = "token", anonymous = true, email = null),
        )

        val state = RemoteSupabaseSyncLabService(api, UnconfinedTestDispatcher()).authState()

        assertTrue(state.anonymous, "the session says it is anonymous")
        assertTrue(state.authenticated, "an anonymous session still holds a token")
        assertNull(state.email, "an anonymous session has no email to report")
    }

    @Test
    fun authStateIsTheDefaultWhenTheApiIsNeitherConfiguredNorHoldingASession() {
        val state = RemoteSupabaseSyncLabService(
            StubAccountApi(isConfigured = false, session = null),
            UnconfinedTestDispatcher(),
        ).authState()

        assertEquals(SupabaseSyncAuthState(), state, "an absent api and an absent session are the default")
    }

    @Test
    fun everyResultCarriesTheAuthStateAsItIsAtTheTimeItIsBuilt() = runTest {
        val api = StubAccountApi(isConfigured = true, session = session(accessToken = "token", userId = "u-1"))
        val service = RemoteSupabaseSyncLabService(api, UnconfinedTestDispatcher())

        val before = service.authState()
        val first = service.initialize()
        api.session = session(accessToken = "token", userId = "u-2")
        val second = service.initialize()

        assertEquals(before, first.authState, "the first result carries the state it was built with")
        assertEquals("u-1", first.authState.userId, "the first result was built before the swap")
        assertEquals("u-2", second.authState.userId, "the second result reads the session afresh")
    }

    private fun session(
        accessToken: String,
        userId: String? = null,
        email: String? = null,
        anonymous: Boolean = false,
    ) = Session(
        accessToken = accessToken,
        refreshToken = "refresh",
        expiresAtEpochSec = null,
        userId = userId,
        email = email,
        anonymous = anonymous,
    )

    /** Counts entries and runs the block inline, so nothing is left waiting. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatches: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }

        override fun toString(): String = "RecordingDispatcher(dispatches=$dispatches)"
    }

    /**
     * All six [AccountApi] members are declared, not the two this class calls.
     *
     * That is deliberate and it is not the rule the repo uses elsewhere: `:backend`
     * owns the interface and keeps the exhaustive double in its own `commonTest`,
     * which nothing else can see, because a `commonTest` is not published. Six
     * members is small enough to stay exhaustive by hand, so this fake is exhaustive
     * today and **a seventh member will not fail this compilation.** If `AccountApi`
     * grows one, this is the file that silently stops being a stand-in for it.
     */
    private class StubAccountApi(
        private val isConfigured: Boolean,
        var session: Session?,
    ) : AccountApi {
        constructor() : this(isConfigured = false, session = null)

        override fun isConfigured(): Boolean = isConfigured

        override fun currentSession(): Session? = session

        override suspend fun ensureValidSession(): Session =
            throw IllegalStateException("ensureValidSession is not used by this service")

        override suspend fun signInWithEmail(email: String, password: String): Session =
            throw IllegalStateException("signInWithEmail is not used by this service")

        override suspend fun signUpWithEmail(
            email: String,
            password: String,
            metadata: Map<String, String?>,
        ): SignUpResult = throw IllegalStateException("signUpWithEmail is not used by this service")

        override suspend fun signOut() {
            throw IllegalStateException("signOut is not used by this service")
        }
    }
}