package com.crispy.tv.accounts

import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.ImportProvider
import com.crispy.tv.backend.StartImportResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `SyncProviderRepository` is the one of the three moved repositories with logic
 * of its own: choosing the connected provider out of a list, refusing to start an
 * import with no resolved context, and making disconnect a no-op rather than an
 * error in the same situation. `ProfileRepository` and `AccountSettingsRepository`
 * are delegation, and a test of delegation re-asserts the compiler, so they are
 * deliberately not here.
 */
class SyncProviderRepositoryTest {
    @Test
    fun `the first connected provider is returned`() = runTest {
        val backend = RecordingBackendApi().withProviderStates(
            providerState(provider = "trakt", connectionState = "disconnected"),
            providerState(provider = "simkl", connectionState = "connected"),
        )

        assertEquals("simkl", repository(backend).getConnectedProvider("token"))
    }

    @Test
    fun `connection state is matched without regard to case`() = runTest {
        val backend = RecordingBackendApi().withProviderStates(
            providerState(provider = "trakt", connectionState = "CONNECTED"),
        )

        assertEquals("trakt", repository(backend).getConnectedProvider("token"))
    }

    @Test
    fun `a connected provider name is trimmed`() = runTest {
        val backend = RecordingBackendApi().withProviderStates(
            providerState(provider = "  trakt  ", connectionState = "connected"),
        )

        assertEquals("trakt", repository(backend).getConnectedProvider("token"))
    }

    @Test
    fun `a connected provider named only of whitespace counts as no provider`() = runTest {
        val backend = RecordingBackendApi().withProviderStates(
            providerState(provider = "   ", connectionState = "connected"),
        )

        assertNull(repository(backend).getConnectedProvider("token"))
    }

    @Test
    fun `the first of several connected providers wins`() = runTest {
        val backend = RecordingBackendApi().withProviderStates(
            providerState(provider = "trakt", connectionState = "connected"),
            providerState(provider = "simkl", connectionState = "connected"),
        )

        assertEquals("trakt", repository(backend).getConnectedProvider("token"))
    }

    @Test
    fun `no connected provider resolves to null`() = runTest {
        val backend = RecordingBackendApi().withProviderStates(
            providerState(provider = "trakt", connectionState = "disconnected"),
            providerState(provider = "simkl", connectionState = "error"),
        )

        assertNull(repository(backend).getConnectedProvider("token"))
    }

    @Test
    fun `no resolved profile means no provider and no request`() = runTest {
        val backend = RecordingBackendApi()

        assertNull(repository(backend, context = null).getConnectedProvider("token"))
        assertEquals(
            emptyList(),
            backend.listImportConnectionsCalls,
            "with no profile there is nothing to ask the backend about",
        )
    }

    @Test
    fun `the resolved profile is the one the backend is asked about`() = runTest {
        val backend = RecordingBackendApi()

        repository(backend).getConnectedProvider("token")

        assertEquals(listOf("token" to PROFILE_ID), backend.listImportConnectionsCalls)
    }

    @Test
    fun `starting an import without a resolved profile is refused`() = runTest {
        val backend = RecordingBackendApi()

        assertFailsWith<IllegalStateException> {
            repository(backend, context = null).startImport(
                accessToken = "token",
                provider = ImportProvider.TRAKT,
                action = "connect",
                returnTo = "crispytv://oauth-callback",
            )
        }
        assertEquals(emptyList(), backend.startImportCalls)
    }

    @Test
    fun `an import is started for the android client id and the given return url`() = runTest {
        val backend = RecordingBackendApi().apply {
            startImportResult = StartImportResult(
                job = importJob(),
                providerState = providerState(provider = "trakt", connectionState = "pending"),
                authUrl = "https://trakt.tv/auth",
                nextAction = "open_browser",
            )
        }

        val result = repository(backend).startImport(
            accessToken = "token",
            provider = ImportProvider.TRAKT,
            action = "connect",
            returnTo = "crispytv://oauth-callback",
        )

        assertEquals("https://trakt.tv/auth", result.authUrl)
        assertEquals(
            RecordingBackendApi.StartImportCall(
                accessToken = "token",
                profileId = PROFILE_ID,
                provider = ImportProvider.TRAKT,
                action = "connect",
                clientId = "crispy-android",
                returnTo = "crispytv://oauth-callback",
            ),
            backend.startImportCalls.single(),
            "the client id is the one the backend recognises; a wrong one fails server-side",
        )
    }

    @Test
    fun `disconnecting without a resolved profile is a no-op`() = runTest {
        val backend = RecordingBackendApi()

        repository(backend, context = null).disconnectImportConnection("token", ImportProvider.TRAKT)

        assertEquals(emptyList(), backend.disconnectCalls)
    }

    @Test
    fun `disconnecting addresses the resolved profile and provider`() = runTest {
        val backend = RecordingBackendApi()

        repository(backend).disconnectImportConnection("token", ImportProvider.SIMKL)

        assertEquals(listOf(Triple("token", PROFILE_ID, ImportProvider.SIMKL)), backend.disconnectCalls)
    }

    private fun repository(
        backend: RecordingBackendApi,
        context: BackendContext? = BackendContext(accessToken = "token", profileId = PROFILE_ID),
    ) = SyncProviderRepository(
        backendContextResolver = FixedBackendContextResolver(context),
        backendClient = backend,
    )

    private companion object {
        const val PROFILE_ID = "profile-7"
    }
}
