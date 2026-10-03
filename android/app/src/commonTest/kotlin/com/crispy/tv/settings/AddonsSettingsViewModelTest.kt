package com.crispy.tv.settings

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.Session
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.discover.FakeAccountApi
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import com.crispy.tv.network.HttpRequest
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import com.crispy.tv.testing.FakeKeyValueStore
import com.crispy.tv.platform.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AddonsSettingsViewModel]'s install/uninstall state machine, which had no coverage at
 * all before it reached `commonMain`.
 *
 * ## Why a real collaborator rather than a new double
 *
 * Two of the three constructor arguments are concrete classes, and the rule about that
 * is usually "the file cannot be tested". It does not apply here, and the reason is
 * worth stating because the rule is often quoted as absolute: **the wall is scoped to
 * the module that owns the type.** `MetadataAddonRegistry` takes `(KeyValueStore,
 * nowMs)` and `HouseholdAddonsCloudSync` takes six already-doubled collaborators, so
 * both are constructible from a `commonTest` without any new interface. The only
 * genuinely new double needed is `CrispyHttpClient`, which is already an interface.
 *
 * So this suite needed **no port, no `open` class and no second exhaustive double** --
 * which is the whole difference between "unreachable" and "merely untested", and those
 * two look identical in a build log.
 *
 * ## Why `Dispatchers.setMain` is installed before the ViewModel exists
 *
 * `viewModelScope` captures `Dispatchers.Main` **at construction**, so a `setMain` in
 * `@BeforeTest` is load-bearing rather than hygiene: install it afterwards and the
 * launched bodies simply never run, and every assertion below sees the initial state
 * with no error to explain why.
 *
 * ## Why `UnconfinedTestDispatcher` for `ioDispatcher`
 *
 * The port takes a no-default dispatcher precisely so it cannot be defaulted to
 * `Dispatchers.Default` -- which compiles on every target and silently puts blocking
 * HTTP on a CPU-sized pool. In a test it is also what makes `advanceUntilIdle()`
 * meaningful: a real `Dispatchers.IO` would run the fetch body on a thread this
 * coroutine never waits for, so the state after `advanceUntilIdle()` would be whatever
 * the fetch happened to have published, which is a race dressed as a result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonsSettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val http = ManifestHttpClient()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun restoreMain() {
        Dispatchers.resetMain()
    }

    // ---- prepareInstall: the four rejections, and each one is before the fetch -----

    @Test
    fun aBlankDraftIsRejectedWithoutStartingAFetch() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.setDraftUrl("   ")
        viewModel.prepareInstall()
        advanceUntilIdle()

        assertEquals(
            "Enter an addon manifest URL first.",
            viewModel.uiState.value.errorMessage,
        )
        // The state alone cannot tell "returned early" from "launched and failed", so
        // the transport is asked directly. This is the assertion that pins the *absence*
        // of a coroutine rather than the presence of a message.
        assertEquals(emptyList(), http.requestedUrls)
        assertTrue(!viewModel.uiState.value.isCheckingAddon)
    }

    @Test
    fun aUrlWithNoHostIsRejectedBeforeAnyFetch() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.setDraftUrl("https://")
        viewModel.prepareInstall()
        advanceUntilIdle()

        assertEquals(
            "The addon URL is invalid. Try a full manifest URL.",
            viewModel.uiState.value.errorMessage,
        )
        assertEquals(emptyList(), http.requestedUrls)
    }

    @Test
    fun anAlreadyInstalledAddonIsRefusedBeforeTheManifestIsFetched() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        // Already present: the registry seeds the OpenSubtitles addon for itself, and
        // `installedAddons` is read off the registry, so this is a real installed row
        // rather than one this test invented.
        val installed = assertNotNull(viewModel.uiState.value.installedAddons.firstOrNull())
        viewModel.setDraftUrl(installed.manifestUrl.uppercase())
        viewModel.prepareInstall()
        advanceUntilIdle()

        assertEquals("That addon is already installed.", viewModel.uiState.value.errorMessage)
        // Case-insensitive in both directions: the draft is uppercased and it is still
        // recognised as installed, so this pins `manifestUrlsMatch` rather than an
        // exact-string lookup.
        assertEquals(emptyList(), http.requestedUrls)
    }

    @Test
    fun aManifestThatCannotBeLoadedIsReportedRatherThanInstalled() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        http.responses[NEW_ADDON_URL] = CrispyHttpResponse(code = 404, body = "not found")
        viewModel.setDraftUrl(NEW_ADDON_URL)
        viewModel.prepareInstall()
        advanceUntilIdle()

        assertEquals(
            "Unable to load addon manifest from that URL.",
            viewModel.uiState.value.errorMessage,
        )
        assertNull(viewModel.uiState.value.pendingInstall)
        assertEquals(
            emptyList(),
            installedUrlsOf(viewModel),
            "nothing but the registry's own seed, so no request may have added a row",
        )
    }

    // ---- prepareInstall: the success shape is a PREVIEW, not an install ----------

    @Test
    fun aReachableManifestBecomesAPendingInstallAndNotAnInstalledAddon() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        val installedBefore = viewModel.uiState.value.installedAddons.map { it.manifestUrl }
        http.responses[NEW_ADDON_URL] = CrispyHttpResponse(code = 200, body = NEW_ADDON_MANIFEST)
        viewModel.setDraftUrl(NEW_ADDON_URL)
        viewModel.prepareInstall()
        advanceUntilIdle()

        val pending = assertNotNull(viewModel.uiState.value.pendingInstall)
        assertEquals("Example Addon", pending.name)
        assertEquals("org.example.addon", pending.addonId)
        assertEquals("1.2.3", pending.version)
        assertEquals(
            listOf("catalog", "meta"),
            pending.resources,
            "resource names are read out of the `resources` array, de-duplicated in order",
        )
        assertEquals(
            listOf("movie", "series"),
            pending.types,
        )
        assertEquals(
            "https://addon.example.com/logo.png",
            pending.logoUrl,
            "a rooted logo resolves against the manifest's own base, not the draft",
        )
        // The preview is the point: nothing is installed, and the draft is kept so the
        // user can confirm against what they typed.
        assertEquals(
            installedBefore,
            viewModel.uiState.value.installedAddons.map { it.manifestUrl },
        )
        assertEquals(NEW_ADDON_URL, viewModel.uiState.value.draftUrl)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    /**
     * The four warnings are four independent decisions in [buildPendingInstall], and a
     * manifest that trips all of them at once is what makes them separable: each one is
     * present, so a rule that stopped firing for any single reason fails this case.
     */
    @Test
    fun aManifestThatIsMissingEverythingSaysSoInTheWarnings() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        http.responses[INSECURE_ADDON_URL] =
            CrispyHttpResponse(code = 200, body = """{"id":"","name":"  "}""")
        viewModel.setDraftUrl(INSECURE_ADDON_URL)
        viewModel.prepareInstall()
        advanceUntilIdle()

        val pending = assertNotNull(viewModel.uiState.value.pendingInstall)
        assertEquals("Unknown addon", pending.name, "a blank name falls back to the literal")
        assertNull(pending.addonId, "a blank id is null, not empty -- the warning keys off blank")
        assertEquals(
            listOf(
                "This addon uses an insecure HTTP URL.",
                "Manifest is missing a stable addon id.",
                "Manifest does not list addon resources.",
                "Manifest does not declare supported media types.",
            ),
            pending.warnings,
            "warning order is the order they are appended in, which is the order they read in",
        )
    }

    @Test
    fun aPendingInstallCanBeDismissedAndConfirmingNothingIsNotAnError() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        http.responses[NEW_ADDON_URL] = CrispyHttpResponse(code = 200, body = NEW_ADDON_MANIFEST)
        viewModel.setDraftUrl(NEW_ADDON_URL)
        viewModel.prepareInstall()
        advanceUntilIdle()
        viewModel.dismissPendingInstall()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.pendingInstall)

        // The early return: no pending install means `confirmInstall()` does nothing at
        // all, and "nothing" must not be reported as a success or a failure.
        viewModel.confirmInstall()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.statusMessage)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    // ---- confirmInstall ----------------------------------------------------------

    @Test
    fun confirmingInstallsTheRowAndClearsTheDraft() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        http.responses[NEW_ADDON_URL] = CrispyHttpResponse(code = 200, body = NEW_ADDON_MANIFEST)
        viewModel.setDraftUrl(NEW_ADDON_URL)
        viewModel.prepareInstall()
        advanceUntilIdle()
        viewModel.confirmInstall()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(
            NEW_ADDON_URL in state.installedAddons.map { it.manifestUrl },
            "the confirmed addon is in the installed list; got ${state.installedAddons.map { it.manifestUrl }}",
        )
        assertEquals("", state.draftUrl, "the draft is cleared only on a confirmed install")
        assertNull(state.pendingInstall)
        assertEquals("Installed Example Addon.", state.statusMessage)
        assertNull(state.errorMessage)
        assertTrue(!state.isInstallingAddon)
    }

    @Test
    fun anInstallWhoseSyncFailedStillInstallsAndSaysTheSyncFailed() = runTest(dispatcher) {
        val fixture = newFixture(syncFails = true)
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        http.responses[NEW_ADDON_URL] = CrispyHttpResponse(code = 200, body = NEW_ADDON_MANIFEST)
        viewModel.setDraftUrl(NEW_ADDON_URL)
        viewModel.prepareInstall()
        advanceUntilIdle()
        viewModel.confirmInstall()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(
            NEW_ADDON_URL in state.installedAddons.map { it.manifestUrl },
            "a failed push must not roll the local install back",
        )
        assertEquals(
            "Installed Example Addon (sync failed).",
            state.statusMessage,
            "the suffix is the only difference, which is what makes this a pair with the " +
                "successful case rather than a variant of it",
        )
        assertNull(state.errorMessage)
    }

    // ---- removeAddon -------------------------------------------------------------

    @Test
    fun removingAnAddonDropsTheRowAndSaysWhichOne() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        http.responses[NEW_ADDON_URL] = CrispyHttpResponse(code = 200, body = NEW_ADDON_MANIFEST)
        viewModel.setDraftUrl(NEW_ADDON_URL)
        viewModel.prepareInstall()
        advanceUntilIdle()
        viewModel.confirmInstall()
        advanceUntilIdle()

        val installed = assertNotNull(
            viewModel.uiState.value.installedAddons.firstOrNull { it.manifestUrl == NEW_ADDON_URL }
        )
        viewModel.removeAddon(installed)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            emptyList(),
            state.installedAddons.filter { it.manifestUrl == NEW_ADDON_URL },
        )
        assertEquals("Removed Example Addon.", state.statusMessage)
        assertNull(state.errorMessage)
    }

    /**
     * The `ifBlank { addonIdFromUrl(...) }` fallback, reached by a row whose cached
     * manifest named no id -- which is what the ported helper is actually for, and the
     * reason it needed widening out of `private` to be covered at all.
     */
    @Test
    fun anAddonWithNoCachedIdIsRemovedByTheIdInItsUrl() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        val row = viewModel.uiState.value.installedAddons.first()
        assertTrue(row.addonId.isNotBlank(), "the seeded row has an id, so blank it for the fallback")

        val blanked = row.copy(addonId = "")
        viewModel.removeAddon(blanked)
        advanceUntilIdle()

        // `https://addon.example.com/manifest.json` has host `addon.example.com`, and
        // that host is the id the registry is asked to remove.
        assertEquals(
            emptyList(),
            installedUrlsOf(viewModel),
            "the row's manifest url is gone, which can only happen if the id resolved",
        )
    }

    /**
     * The refusal arm, and it is the one case that pins the *new* `addonIdFromUrl`
     * answer end to end: an unparseable url now yields an empty id rather than adopting
     * the whole string, so this row is refused instead of being "removed" under a
     * garbage id.
     */
    @Test
    fun anAddonWhoseIdCannotBeResolvedIsRefusedWithoutChangingAnything() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        val before = viewModel.uiState.value.installedAddons
        viewModel.removeAddon(before.first().copy(addonId = "", manifestUrl = "not a url"))
        advanceUntilIdle()

        assertEquals(
            "Could not resolve addon id for removal.",
            viewModel.uiState.value.errorMessage,
        )
        assertEquals(
            before,
            viewModel.uiState.value.installedAddons,
            "a refused removal leaves the list untouched",
        )
        assertNull(viewModel.uiState.value.statusMessage)
    }

    // ---- the draft and the two sync outcomes -------------------------------------

    @Test
    fun editingTheDraftClearsBothMessagesAtOnce() = runTest(dispatcher) {
        val fixture = newFixture()
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.setDraftUrl("   ")
        viewModel.prepareInstall()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.setDraftUrl("https://addon.example.com/manifest.json")
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.statusMessage)
    }

    /**
     * A signed-out pull is a **skip**, and the code answers it as success -- so no error
     * appears. The case beside it ([aFailedPullIsReportedOnTheUiState]) is what makes
     * this one mean something: the two differ only in whether the session refresh throws,
     * and `FakeAccountApi(null)` versus the throwing subclass is that difference.
     */
    @Test
    fun aSignedOutSyncIsASkipRatherThanAFailure() = runTest(dispatcher) {
        val fixture = newFixture(signedIn = false)
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
        assertTrue(!viewModel.uiState.value.isLoading, "the list still finished loading")
    }

    @Test
    fun aFailedPullIsReportedOnTheUiState() = runTest(dispatcher) {
        val fixture = newFixture(syncFails = true)
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        assertEquals(
            "Addons sync failed: backend down",
            viewModel.uiState.value.errorMessage,
            "the message carries the failure's own text, so the `orEmpty()` never hides it",
        )
    }

    // ---- fixtures ----------------------------------------------------------------

    /**
     * Every collaborator is built here rather than in each case, so a case reads as
     * "what this does" and not "what this had to construct".
     *
     * [syncFails] is the one lever with real work behind it: `HouseholdAddonsCloudSync`
     * turns a **throwing** `ensureValidSession` into `Result.failure`, and
     * `FakeAccountApi`'s own KDoc says it is `open` so a test can override exactly that
     * one member rather than writing a second double. `signedIn = false` takes the
     * *other* branch -- a null session is a skip -- so the two cases differ by one
     * boolean rather than by two unrelated fixtures.
     */
    /**
     * The one way a test builds a [Fixture].
     *
     * It exists so the http client and the io dispatcher are supplied *here*, once,
     * from the class-level instances the assertions read. The first draft let [Fixture]
     * construct its own client, which made every seeded response unread and every
     * "the guard runs before the fetch" assertion true for the wrong reason -- the
     * client was never asked. A fixture double that is not the same object as the one
     * the case asserts on is a fixture that agrees with everything.
     */
    /**
     * The installed urls, minus the one row `MetadataAddonRegistry` seeds for itself.
     *
     * `MetadataAddonRegistry` always contains `https://opensubtitles-v3.strem.io/manifest.json`
     * whether or not anything put it there -- the same fact `HouseholdAddonsCloudSyncTest`
     * records, where `localAddonUrls()` filters that url out. Two cases here asserted an
     * *empty* installed list and failed with
     * `[https://opensubtitles-v3.strem.io/manifest.json]`, and the production code was
     * right in both: the question each case is really asking is "is there anything
     * *other than the seed*", and `emptyList()` asked a different one.
     */
    private fun installedUrlsOf(viewModel: AddonsSettingsViewModel): List<String> =
        viewModel.uiState.value.installedAddons.map { it.manifestUrl }
            .filterNot { manifestUrl -> manifestUrl == OPENSUBTITLES_URL }

    private fun newFixture(
        syncFails: Boolean = false,
        signedIn: Boolean = true,
    ): Fixture = Fixture(
        http = http,
        dispatcher = dispatcher,
        syncFails = syncFails,
        signedIn = signedIn,
    )

    private class Fixture(
        private val http: ManifestHttpClient,
        private val dispatcher: CoroutineDispatcher,
        syncFails: Boolean,
        signedIn: Boolean = true,
    ) {
        private val userId = "user-7"

        val registry = MetadataAddonRegistry(FakeKeyValueStore(), nowMs = { 0L })

        private val sync =
            HouseholdAddonsCloudSync(
                supabase =
                    if (syncFails) {
                        object : FakeAccountApi(SESSION) {
                            override suspend fun ensureValidSession(): Session =
                                throw IllegalStateException("backend down")
                        }
                    } else {
                        FakeAccountApi(if (signedIn) SESSION else null)
                    },
                backend = RecordingBackendApi(),
                addonRegistry = registry,
                activeProfileStore =
                    ActiveProfileStore(
                        FakeKeyValueStore(buildMap { put("active_profile_id:$userId", "profile-1") })
                    ),
                logger = SilentLogger,
            )

        // The same unconfined dispatcher the test runs on, so the blocking `httpGetJson`
        // the screen wraps in `withContext(ioDispatcher)` shares one scheduler with
        // `advanceUntilIdle()`. The discarded draft reached the dispatcher out of
        // `scope.coroutineContext[CoroutineDispatcher]!!` and then threw it away for a
        // fresh `UnconfinedTestDispatcher()` -- which has its OWN private scheduler, so
        // `advanceUntilIdle()` would never have driven the fetch.
        fun viewModel(): AddonsSettingsViewModel =
            AddonsSettingsViewModel(
                addonRegistry = registry,
                httpClient = http,
                householdAddonsCloudSync = sync,
                ioDispatcher = dispatcher,
            )
    }

    /**
     * Answers a fixed manifest per url and **records every request**, because "the guard
     * is before the fetch" is not a claim this suite can make from `uiState` alone --
     * a rejection and a failed fetch both end with an error message.
     *
     * Every member the add-on screen does not call throws, naming itself, so a new call
     * site fails as a wrong assumption about which verb this surface uses rather than as
     * a silent success.
     */
    private class ManifestHttpClient : CrispyHttpClient {
        val responses = mutableMapOf<String, CrispyHttpResponse>()
        val requestedUrls = mutableListOf<String>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            callTimeoutMs: Long?,
            query: List<Pair<String, String>>,
        ): CrispyHttpResponse {
            requestedUrls += url
            return responses[url] ?: CrispyHttpResponse(code = 404, body = "")
        }

        override suspend fun getOrNull(
            url: String,
            headers: Map<String, String>,
            callTimeoutMs: Long?,
            query: List<Pair<String, String>>,
        ): CrispyHttpResponse? = get(url, headers, callTimeoutMs, query)

        override suspend fun execute(
            request: HttpRequest,
            callTimeoutMs: Long?,
        ): CrispyHttpResponse = error("AddonsSettingsViewModel never calls execute.")

        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
            callTimeoutMs: Long?,
        ): CrispyHttpResponse = error("AddonsSettingsViewModel never calls postJson.")

        override suspend fun delete(
            url: String,
            headers: Map<String, String>,
            callTimeoutMs: Long?,
        ): CrispyHttpResponse = error("AddonsSettingsViewModel never calls delete.")

        // No `patch` override, and its absence is the point worth recording: the first
        // draft of this double declared one, from memory, and the compiler answered
        // `'patch' overrides nothing`. `CrispyHttpClient` has five members -- execute,
        // get, getOrNull, postJson, delete -- and this is now all five. A double's shape
        // must be READ rather than recalled, because the whole reason a double declares
        // a member is to mirror one; a member it invents is a claim about the interface
        // that nothing checks until the compiler happens to be run.
    }

    /**
     * `:app`'s commonTest has no shared `AppLogger` double -- two files each carry a
     * private one -- so this is a third rather than a widening, and that duplication is
     * recorded rather than fixed here: the two existing copies are in files this
     * landing does not otherwise touch, and an `AppLogger` double that grows a member
     * is a compile error in all three at once.
     */
    private object SilentLogger : AppLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        private val SESSION =
            Session(
                accessToken = "token-1",
                refreshToken = "refresh-1",
                expiresAtEpochSec = null,
                userId = "user-7",
                email = null,
                anonymous = false,
            )

        /** The row `MetadataAddonRegistry` seeds for itself; see [installedUrlsOf]. */
    private const val OPENSUBTITLES_URL =
        "https://opensubtitles-v3.strem.io/manifest.json"

    private const val NEW_ADDON_URL = "https://addon.example.com/manifest.json"
        private const val INSECURE_ADDON_URL = "http://addon.example.com/manifest.json"

        /**
         * A manifest with an id, a name, a version, a rooted logo, two `catalog`
         * resources and two `type` entries -- so the preview assertions read about the
         * *mapping* and not about defaults. `duplicate` is in `resources` deliberately:
         * `parseManifestResources` collects into a `LinkedHashSet`, so the duplicate
         * proves both the de-duplication and the order.
         */
        private val NEW_ADDON_MANIFEST =
            """
            {
              "id": "org.example.addon",
              "name": "Example Addon",
              "version": "1.2.3",
              "description": "An example.",
              "logo": "/logo.png",
              "resources": [
                {"name": "catalog", "types": ["movie", "series"]},
                {"name": "catalog"},
                {"name": "meta"}
              ],
              "types": ["movie", "series"]
            }
            """.trimIndent()
    }
}