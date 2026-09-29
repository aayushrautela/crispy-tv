package com.crispy.tv.backend

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.FakeAccountApi
import com.crispy.tv.accounts.FakeKeyValueStore
import com.crispy.tv.accounts.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [BackendContextResolver] is the last piece of the backend module to lose its
 * Android types, and it lost them by taking [com.crispy.tv.accounts.AccountApi]
 * and [BackendApi] instead of the two concrete clients. That makes its behaviour
 * testable for the first time: it needs a `KeyValueStore`, a session and a
 * configured flag, and none of those exist off Android.
 *
 * What is pinned here is the caching contract, because that is the part that is
 * not visible in a code review. The resolver memoises on `(userId, accessToken)`
 * so a token refresh invalidates the cache, and it refuses to pick a profile on
 * the user's behalf -- that decision is what `ProfileSelectorRoute` exists for,
 * and a resolver that guessed would silently log a new account into the first
 * profile.
 *
 * The subject is [CachingBackendContextResolver], the only implementation of the
 * [BackendContextResolver] interface. The interface exists so the account
 * repositories in `:android:app` can be handed one; this suite drives the real
 * implementation against fakes rather than a substitute, because the caching is
 * the behaviour being pinned.
 */
class BackendContextResolverTest {

    @Test
    fun `a session and an active profile resolve to a context`() = runTest {
        val store = FakeKeyValueStore()
        val profiles = ActiveProfileStore(store)
        profiles.setActiveProfileId(USER_ID, PROFILE_ID)

        val resolver = resolver(store, session(USER_ID), configured = true)

        assertEquals(
            BackendContext(accessToken = TOKEN, profileId = PROFILE_ID),
            resolver.resolve(),
        )
    }

    @Test
    fun `no active profile resolves to null rather than picking the first one`() = runTest {
        val store = FakeKeyValueStore()
        val resolver = resolver(store, session(USER_ID), configured = true)

        assertNull(resolver.resolve(), "a fresh account must land on the profile selector")
    }

    @Test
    fun `a blank stored profile id is treated as no selection`() = runTest {
        val store = FakeKeyValueStore()
        ActiveProfileStore(store).setActiveProfileId(USER_ID, "   ")

        val resolver = resolver(store, session(USER_ID), configured = true)

        assertNull(resolver.resolve(), "a whitespace-only id is not a profile")
    }

    @Test
    fun `a repeated resolve is served from the cache`() = runTest {
        val store = FakeKeyValueStore()
        ActiveProfileStore(store).setActiveProfileId(USER_ID, PROFILE_ID)
        val resolver = resolver(store, session(USER_ID), configured = true)

        assertNotNull(resolver.resolve())
        val readsAfterFirstResolve = store.getStringCalls
        assertNotNull(resolver.resolve())

        assertEquals(
            readsAfterFirstResolve,
            store.getStringCalls,
            "the second resolve must not re-read the profile store",
        )
    }

    @Test
    fun `a refreshed access token invalidates the cache`() = runTest {
        val store = FakeKeyValueStore()
        val profiles = ActiveProfileStore(store)
        profiles.setActiveProfileId(USER_ID, PROFILE_ID)
        val account = FakeAccountApi().withSession(session(USER_ID))
        val resolver = CachingBackendContextResolver(account, profiles, UnusedBackendApi())

        assertEquals(PROFILE_ID, resolver.resolve()?.profileId)

        // Same user, new token: the cached entry no longer matches, so the
        // resolver must go back to the store rather than hand back the old one.
        account.withSession(session(USER_ID, accessToken = "$TOKEN-2"))
        profiles.setActiveProfileId(USER_ID, OTHER_PROFILE_ID)

        assertEquals(
            OTHER_PROFILE_ID,
            resolver.resolve()?.profileId,
            "a new access token must not be served the previous profile",
        )
    }

    @Test
    fun `clear drops the cached context`() = runTest {
        val store = FakeKeyValueStore()
        val profiles = ActiveProfileStore(store)
        profiles.setActiveProfileId(USER_ID, PROFILE_ID)
        val resolver = resolver(store, session(USER_ID), configured = true)

        assertNotNull(resolver.resolve())
        resolver.clear()
        profiles.setActiveProfileId(USER_ID, OTHER_PROFILE_ID)

        assertEquals(
            OTHER_PROFILE_ID,
            resolver.resolve()?.profileId,
            "clear() must force the next resolve back to the store",
        )
    }

    @Test
    fun `an unconfigured client resolves to null without asking for a session`() = runTest {
        val store = FakeKeyValueStore()
        ActiveProfileStore(store).setActiveProfileId(USER_ID, PROFILE_ID)
        val account = FakeAccountApi(configured = false).withSession(session(USER_ID))
        val resolver = CachingBackendContextResolver(account, ActiveProfileStore(store), UnusedBackendApi(configured = true))

        assertNull(resolver.resolve())
        assertEquals(0, account.ensureValidSessionCalls, "an unconfigured client must not be used")
    }

    @Test
    fun `an unconfigured backend resolves to null without asking for a session`() = runTest {
        val store = FakeKeyValueStore()
        ActiveProfileStore(store).setActiveProfileId(USER_ID, PROFILE_ID)
        val account = FakeAccountApi().withSession(session(USER_ID))
        val resolver = CachingBackendContextResolver(account, ActiveProfileStore(store), UnusedBackendApi(configured = false))

        assertNull(resolver.resolve())
        assertEquals(0, account.ensureValidSessionCalls, "an unconfigured backend must not be used")
    }

    @Test
    fun `no session resolves to null`() = runTest {
        val store = FakeKeyValueStore()
        ActiveProfileStore(store).setActiveProfileId(USER_ID, PROFILE_ID)

        assertNull(resolver(store, session = null, configured = true).resolve())
    }

    @Test
    fun `a session with a blank access token resolves to null`() = runTest {
        val store = FakeKeyValueStore()
        ActiveProfileStore(store).setActiveProfileId(USER_ID, PROFILE_ID)

        val session = session(USER_ID, accessToken = "  ")

        assertNull(resolver(store, session, configured = true).resolve())
    }

    @Test
    fun `a session with no user id resolves to null`() = runTest {
        val store = FakeKeyValueStore()

        val session = session(userId = null)

        assertNull(resolver(store, session, configured = true).resolve())
    }

    @Test
    fun `concurrent resolves agree and consult the store once`() = runTest {
        val store = FakeKeyValueStore()
        val profiles = ActiveProfileStore(store)
        profiles.setActiveProfileId(USER_ID, PROFILE_ID)
        val resolver = resolver(store, session(USER_ID), configured = true)

        val results = List(8) { async { resolver.resolve() } }.awaitAll()

        assertEquals(
            List(8) { BackendContext(accessToken = TOKEN, profileId = PROFILE_ID) },
            results,
            "every concurrent resolve must see the same context",
        )
        assertEquals(
            1,
            store.getStringCalls,
            "the mutex must collapse the racing resolves into one store read",
        )
    }

    private fun resolver(
        store: FakeKeyValueStore,
        session: Session?,
        configured: Boolean,
    ): BackendContextResolver = CachingBackendContextResolver(
        supabaseAccountClient = FakeAccountApi(configured = configured).withSession(session),
        activeProfileStore = ActiveProfileStore(store),
        backendClient = UnusedBackendApi(configured = true),
    )

    private fun session(userId: String?, accessToken: String = TOKEN) = Session(
        accessToken = accessToken,
        refreshToken = "refresh",
        expiresAtEpochSec = null,
        userId = userId,
        email = "someone@example.com",
        anonymous = false,
    )

    private companion object {
        const val USER_ID = "user-1"
        const val TOKEN = "access-token"
        const val PROFILE_ID = "profile-1"
        const val OTHER_PROFILE_ID = "profile-2"
    }
}
