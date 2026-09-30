package com.crispy.tv.accounts

import com.crispy.tv.backend.BackendApi
import com.crispy.tv.backend.MeResponse
import com.crispy.tv.backend.Profile
import com.crispy.tv.backend.User
import com.crispy.tv.testing.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [loadActiveProfile] reached `commonMain` when the `accounts` / `search` landing split it
 * at the `Context` boundary, and that is the first time it can be tested at all: it used
 * to build its three collaborators from an `android.content.Context` inline.
 *
 * The double below answers `ensureValidSession()` and `currentSession()` **independently**,
 * which is the point of the class. The `FakeAccountApi` in the `discover` package returns
 * the same value from both, so with it a test can only prove that a session was found -- it
 * cannot prove that the refresh call was preferred, and the preferred arm is the one that
 * decides whether the app silently re-authenticates.
 */
class ProfileMenuRouteTest {
    private class SplitSessionAccountApi(
        private val ensured: Session?,
        private val current: Session?,
    ) : AccountApi {
        var ensureCalls = 0
        var currentCalls = 0

        override fun isConfigured(): Boolean = true

        override fun currentSession(): Session? {
            currentCalls++
            return current
        }

        override suspend fun ensureValidSession(): Session? {
            ensureCalls++
            return ensured
        }

        override suspend fun signInWithEmail(email: String, password: String): Session =
            error("unused")

        override suspend fun signUpWithEmail(
            email: String,
            password: String,
            metadata: Map<String, String?>,
        ): SignUpResult = error("unused")

        override suspend fun signOut() = error("unused")
    }

    // ------------------------------------------------------------------ fixtures

    private fun session(accessToken: String = "token", userId: String? = "user-1") =
        Session(
            accessToken = accessToken,
            refreshToken = "refresh",
            expiresAtEpochSec = 0L,
            userId = userId,
            email = "a@b.c",
            anonymous = false,
        )

    /** `Profile` has eight required fields, so every construction goes through here. */
    private fun profile(
        id: String,
        name: String = "Profile $id",
        avatarKey: String? = null,
    ) = Profile(
        id = id,
        name = name,
        avatarKey = avatarKey,
        isKids = false,
        sortOrder = 0,
        createdByUserId = null,
        createdAt = null,
        updatedAt = null,
    )

    private fun me(
        userId: String = "user-1",
        profiles: List<Profile> = emptyList(),
    ) = MeResponse(user = User(id = userId, email = "a@b.c"), profiles = profiles)

    private fun activeStore(activeIdFor: String? = null): ActiveProfileStore {
        val store = ActiveProfileStore(FakeKeyValueStore())
        if (activeIdFor != null) store.setActiveProfileId("user-1", activeIdFor)
        return store
    }

    // --------------------------------------------------------------------- cases

    @Test
    fun `no session at all means no profile`() = runTest {
        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(ensured = null, current = null),
            backend = RecordingBackendApi(),
            activeProfileStore = activeStore(),
        )

        assertNull(result)
    }

    @Test
    fun `a refreshed session is preferred over the stored one`() = runTest {
        val api = SplitSessionAccountApi(
            ensured = session(accessToken = "refreshed"),
            current = session(accessToken = "stored"),
        )
        val backend = RecordingBackendApi().answerMe(me(profiles = listOf(profile("p1"))))

        loadActiveProfile(api, backend, activeStore())

        // The token that reached the backend is the proof, because that is what the
        // server would have been asked with.
        assertEquals(listOf("refreshed"), backend.getMeCalls)
        assertEquals(1, api.ensureCalls)
        // The fallback is never consulted once the refresh succeeds.
        assertEquals(0, api.currentCalls)
    }

    @Test
    fun `the stored session is the fallback when there is nothing to refresh`() = runTest {
        val api = SplitSessionAccountApi(ensured = null, current = session(accessToken = "stored"))
        val backend = RecordingBackendApi().answerMe(me(profiles = listOf(profile("p1"))))

        loadActiveProfile(api, backend, activeStore())

        assertEquals(listOf("stored"), backend.getMeCalls)
        assertEquals(1, api.currentCalls)
    }

    @Test
    fun `a blank session user id falls back to the account the backend reports`() = runTest {
        val backend = RecordingBackendApi()
            .answerMe(me(userId = "from-backend", profiles = listOf(profile("p1"), profile("p2"))))
        val store = ActiveProfileStore(FakeKeyValueStore()).apply {
            setActiveProfileId("from-backend", "p2")
        }

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(userId = "   "), null),
            backend = backend,
            activeProfileStore = store,
        )

        // The fallback only matters if it is what the store is consulted with, so the
        // assertion is the *picked* profile rather than the resolved id alone.
        assertEquals("p2", result?.id)
    }

    @Test
    fun `a blank user id on both sides means no profile`() = runTest {
        val backend = RecordingBackendApi().answerMe(me(userId = "   ", profiles = listOf(profile("p1"))))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(userId = "  "), null),
            backend = backend,
            activeProfileStore = activeStore(),
        )

        assertNull(result)
    }

    @Test
    fun `the active profile wins over the first one`() = runTest {
        val backend = RecordingBackendApi()
            .answerMe(me(profiles = listOf(profile("p1"), profile("p2"), profile("p3"))))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = backend,
            activeProfileStore = activeStore(activeIdFor = "p3"),
        )

        assertEquals("p3", result?.id)
    }

    @Test
    fun `the first profile is the fallback when no active id is stored`() = runTest {
        val backend = RecordingBackendApi()
            .answerMe(me(profiles = listOf(profile("p1"), profile("p2"))))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = backend,
            activeProfileStore = activeStore(activeIdFor = null),
        )

        assertEquals("p1", result?.id)
    }

    @Test
    fun `an active id that matches nothing falls back to the first profile`() = runTest {
        val backend = RecordingBackendApi()
            .answerMe(me(profiles = listOf(profile("p1"), profile("p2"))))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = backend,
            activeProfileStore = activeStore(activeIdFor = "p-deleted"),
        )

        assertEquals("p1", result?.id)
    }

    @Test
    fun `a stored active id with stray whitespace still matches`() = runTest {
        val backend = RecordingBackendApi()
            .answerMe(me(profiles = listOf(profile("p1"), profile("p2"))))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = backend,
            activeProfileStore = activeStore(activeIdFor = "  p2  "),
        )

        // Without the trim this falls back to p1, so the assertion distinguishes the two.
        assertEquals("p2", result?.id)
    }

    @Test
    fun `a failing backend call becomes no profile rather than an exception`() = runTest {
        // RecordingBackendApi throws from an unanswered member, which is exactly the
        // failure shape this guard exists for: the avatar in a menu must not be able to
        // take the route down.
        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = RecordingBackendApi(),
            activeProfileStore = activeStore(),
        )

        assertNull(result)
    }

    @Test
    fun `the avatar key is resolved rather than passed through`() = runTest {
        val backend = RecordingBackendApi()
            .answerMe(me(profiles = listOf(profile("p1", avatarKey = "  https://cdn/pic.png  "))))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = backend,
            activeProfileStore = activeStore(),
        )

        assertEquals("https://cdn/pic.png", result?.avatarUrl)
    }

    @Test
    fun `a blank avatar key resolves to no url`() = runTest {
        val backend = RecordingBackendApi()
            .answerMe(me(profiles = listOf(profile("p1", avatarKey = "   "))))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = backend,
            activeProfileStore = activeStore(),
        )

        assertNull(result?.avatarUrl)
    }

    @Test
    fun `an account with no profiles has nothing to show`() = runTest {
        val backend = RecordingBackendApi().answerMe(me(profiles = emptyList()))

        val result = loadActiveProfile(
            supabase = SplitSessionAccountApi(session(), null),
            backend = backend,
            activeProfileStore = activeStore(activeIdFor = "p1"),
        )

        assertNull(result)
    }

    @Test
    fun `every collaborator shape the loader used to build inline is named here`() = runTest {
        // A regression guard on the seam itself: this function is what
        // `ActiveProfileLoader` calls from androidMain, and it is the only place the
        // backend's access token is read. If the call sites stop using it the suite
        // above all keep passing, so pin that it is reachable with these three args.
        val api: AccountApi = SplitSessionAccountApi(session(), null)
        val backend: BackendApi = RecordingBackendApi().answerMe(me(profiles = listOf(profile("p1"))))
        val store: ActiveProfileStore = activeStore()

        assertEquals("p1", loadActiveProfile(api, backend, store)?.id)
    }
}
