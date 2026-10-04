package com.crispy.tv.accounts

import com.crispy.tv.backend.AccountSettings
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.ImportJob
import com.crispy.tv.backend.ImportProvider
import com.crispy.tv.backend.ProviderState
import com.crispy.tv.backend.StartImportResult
import com.crispy.tv.testing.FakeKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AccountViewModel`s reached `commonMain` in the accounts/search landing, and this is the
 * first coverage of the one behaviour that changed in the move: `AccountSettingsViewModel`
 * used to hold a `Context` and call `startActivity` itself, and now takes
 * `openUrl: (String) -> Unit`.
 *
 * Every production string is asserted verbatim rather than paraphrased. Four of them are
 * user-facing sentences (`"Not signed in."`, `"Complete trakt sign-in in your browser."`,
 * `"Failed to start trakt connection."`, `"Failed to disconnect sync."`) and one is a
 * server-contract constant (`OAUTH_RETURN_TO`), which is why the literals are copied here
 * rather than interpolated from the code.
 *
 * The viewmodel reads `uiState.value.syncProvider` *inside* its own `launch`, so the
 * connect-vs-reconnect decision is made against whatever `load()` last published. Every
 * dispatcher is therefore unconfined, so the launch completes before the assertion, and
 * the reconnect case runs `load()` first with a connected provider answered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelsTest {

    private val backend = RecordingBackendApi()
    private val resolver = FixedBackendContextResolver(BackendContext(accessToken = "token", profileId = "profile-1"))
    private val bootstrap = FakeAccountBootstrapRepository()
    private val keyValueStore = FakeKeyValueStore()

    private val openedUrls = mutableListOf<String>()

    private fun viewModel(): AccountSettingsViewModel =
        AccountSettingsViewModel(
            openUrl = { url -> openedUrls += url },
            bootstrapRepository = bootstrap,
            accountSettingsRepository = AccountSettingsRepository(backend),
            syncProviderRepository = SyncProviderRepository(resolver, backend),
            pendingProviderAuthStore = PendingProviderAuthStore(keyValueStore),
        )

    // ------------------------------------------------------------------ fixtures

    private fun signedIn() {
        bootstrap.answerWith(
            BootstrapResult(
                signedIn = true,
                anonymous = false,
                onboardingComplete = true,
                session = Session(
                    accessToken = "access-1",
                    refreshToken = "refresh-1",
                    expiresAtEpochSec = 9_999_999_999L,
                    userId = "user-1",
                    email = "a@b.c",
                    anonymous = false,
                ),
            ),
        )
    }

    private fun answerSettings(
        pricingTier: String? = "premium",
        hasMdbListAccess: Boolean = true,
    ) {
        backend.answerAccountSettings(
            AccountSettings(pricingTier = pricingTier, hasMdbListAccess = hasMdbListAccess),
        )
    }

    private fun providerState(
        provider: String,
        connectionState: String,
    ) = ProviderState(
        provider = provider,
        connectionState = connectionState,
        accountStatus = null,
        primaryAction = "open_browser",
        canImport = false,
        canReconnect = false,
        canDisconnect = false,
        externalUsername = null,
        statusLabel = "",
        statusMessage = null,
        lastImportCompletedAt = null,
    )

    private fun importJob() =
        ImportJob(
            id = "job-1",
            profileId = "profile-1",
            provider = "trakt",
            mode = "full",
            status = "awaiting_oauth",
            requestedByUserId = "user-1",
            errorMessage = null,
            createdAt = null,
            startedAt = null,
            finishedAt = null,
            updatedAt = null,
        )

    private fun answerConnectedProvider(provider: String) {
        backend.withProviderStates(providerState(provider = provider, connectionState = "connected"))
    }

    private fun answerStartImport(authUrl: String?) {
        backend.startImportResult =
            StartImportResult(
                job = importJob(),
                providerState = providerState(provider = "trakt", connectionState = "pending"),
                authUrl = authUrl,
                nextAction = "open_browser",
            )
    }

    // ------------------------------------------------------- the openUrl slot

    @Test
    fun `starting an import hands the provider's auth url to the openUrl slot unchanged`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerStartImport("https://trakt.tv/oauth/authorize?token=abc")
            val viewModel = viewModel()

            viewModel.startImport("trakt")

            assertEquals(listOf("https://trakt.tv/oauth/authorize?token=abc"), openedUrls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a blank auth url opens nothing but still reports success`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerStartImport("   ")
            val viewModel = viewModel()

            viewModel.startImport("trakt")

            assertTrue("a blank url must not be opened", openedUrls.isEmpty())
            assertEquals("Complete trakt sign-in in your browser.", viewModel.uiState.value.statusMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a null auth url opens nothing`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerStartImport(null)
            val viewModel = viewModel()

            viewModel.startImport("trakt")

            assertTrue(openedUrls.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------------ the request

    @Test
    fun `the import request carries the manifest's oauth return uri verbatim`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerStartImport(null)
            val viewModel = viewModel()

            viewModel.startImport("trakt")

            assertEquals(
                "crispytv://oauth-callback",
                backend.startImportCalls.single().returnTo,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a provider name is trimmed and lowercased before it is looked up`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerStartImport(null)
            val viewModel = viewModel()

            viewModel.startImport("  Trakt  ")

            assertEquals(ImportProvider.TRAKT, backend.startImportCalls.single().provider)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an unknown provider is ignored without reaching the backend`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            val viewModel = viewModel()

            viewModel.startImport("kodi")

            assertTrue(backend.startImportCalls.isEmpty())
            assertTrue(openedUrls.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `importing the already-connected provider reconnects instead of connecting`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerSettings()
            answerConnectedProvider("trakt")
            answerStartImport(null)
            val viewModel = viewModel()
            viewModel.load()

            viewModel.startImport("trakt")

            assertEquals("reconnect", backend.startImportCalls.single().action)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `importing a different provider connects`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerSettings()
            answerConnectedProvider("trakt")
            answerStartImport(null)
            val viewModel = viewModel()
            viewModel.load()

            viewModel.startImport("simkl")

            assertEquals("connect", backend.startImportCalls.single().action)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ---------------------------------------------------------------- the load

    @Test
    fun `load publishes the pricing tier plus the list access and the connected provider`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerSettings(pricingTier = "family", hasMdbListAccess = false)
            answerConnectedProvider("simkl")
            val viewModel = viewModel()

            viewModel.load()

            val state = viewModel.uiState.value
            assertEquals("family", state.pricingTier)
            assertEquals(false, state.hasMdbListAccess)
            assertEquals("simkl", state.syncProvider)
            assertNull(state.error)
            assertEquals(false, state.isBusy)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `load without a session reports the not-signed-in message`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel()

            viewModel.load()

            assertEquals("Not signed in.", viewModel.uiState.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------- the pending OAuth park

    @Test
    fun `consuming a parked provider auth reloads the state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerSettings()
            answerConnectedProvider("trakt")
            val viewModel = viewModel()
            viewModel.load()
            val loadsBefore = backend.getAccountSettingsCalls.size

            PendingProviderAuthStore(keyValueStore).put("trakt", "token-123")
            viewModel.consumePendingProviderAuth()

            assertEquals(loadsBefore + 1, backend.getAccountSettingsCalls.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a parked auth with a blank provider does not reload`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerSettings()
            val viewModel = viewModel()
            viewModel.load()
            val loadsBefore = backend.getAccountSettingsCalls.size

            PendingProviderAuthStore(keyValueStore).put("   ", "token-123")
            viewModel.consumePendingProviderAuth()

            assertEquals(loadsBefore, backend.getAccountSettingsCalls.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `nothing parked means nothing to consume`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerSettings()
            val viewModel = viewModel()
            viewModel.load()
            val loadsBefore = backend.getAccountSettingsCalls.size

            viewModel.consumePendingProviderAuth()

            assertEquals(loadsBefore, backend.getAccountSettingsCalls.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // --------------------------------------------------------- the disconnect

    @Test
    fun `disconnecting clears the connected provider from the state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            answerSettings()
            answerConnectedProvider("trakt")
            val viewModel = viewModel()
            viewModel.load()

            viewModel.disconnectSyncProvider()

            assertEquals(1, backend.disconnectCalls.size)
            assertEquals(ImportProvider.TRAKT, backend.disconnectCalls.single().third)
            assertNull(viewModel.uiState.value.syncProvider)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `disconnecting with nothing connected does nothing`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            signedIn()
            val viewModel = viewModel()

            viewModel.disconnectSyncProvider()

            assertTrue(backend.disconnectCalls.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
