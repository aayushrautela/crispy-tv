package com.crispy.tv.sync

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.addons.registry.CloudAddonRow
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.accounts.Session
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.backend.AddonDto
import com.crispy.tv.discover.FakeAccountApi
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.testing.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The household addon sync is the diff between two row sets, and every decision it makes is
 * either "is this URL already known" or "must this row never be touched". Those are exactly the
 * decisions a recorded double can hold open, so [HouseholdAddonsCloudSync] names interfaces
 * rather than the concrete `CrispyBackendClient` it happens to be wired with -- see that class's
 * KDoc. Nothing here fakes the addon registry: it is the production class over a
 * [FakeKeyValueStore], because the identity it computes (an installation id over the *normalised*
 * manifest URL) is part of what these tests assert.
 *
 * **Two things about the registry are worth knowing before reading a case.**
 *
 * The first is that a registry always contains a default OpenSubtitles addon. A fresh store
 * seeds it, so after any reconcile `exportCloudAddons()` answers with that row plus the rows
 * that were passed in. Every assertion about local rows therefore goes through
 * [Fixture.localAddonUrls], which subtracts it by name -- and every push fixture puts it on the
 * *server* too, so it cancels out of the diff. A raw row count would silently include it.
 *
 * The second is a fact this suite deliberately does **not** assert. `pullToLocal` gives each row
 * the DTO's index as its `sortOrder`, not a running count over the rows that survived filtering,
 * so a dropped row leaves a gap in the numbering. `exportCloudAddons()` rewrites `sortOrder` to
 * the order index on the way out, so the gap is not observable through any public member: both
 * choices produce the same registry. It is recorded here rather than tested, because a test that
 * could not see the difference would be decoration.
 */
class HouseholdAddonsCloudSyncTest {

    @Test
    fun aSessionRefreshThatThrowsAbortsBothOperationsWithoutTouchingTheBackend() = runTest {
        val boom = IllegalStateException("refresh exploded")
        val fixture = Fixture(account = SessionRefreshFailure(boom))

        val pull = fixture.sync.pullToLocal()
        val push = fixture.sync.pushFromLocal()

        assertSame(boom, pull.exceptionOrNull(), "pull must propagate the refresh failure unchanged")
        assertSame(boom, push.exceptionOrNull(), "push must propagate the refresh failure unchanged")
        assertEquals(
            emptyList(),
            fixture.backend.listAddonsCalls,
            "the backend must not be reached before a session exists",
        )
        assertEquals(
            listOf(
                "pull aborted: session refresh failed: refresh exploded",
                "push aborted: session refresh failed: refresh exploded",
            ),
            fixture.logger.warnedMessages(),
            "each operation warns about its own abort, so two calls means two warnings",
        )
    }

    @Test
    fun noActiveSessionIsASkipAndNotAFailure() = runTest {
        val fixture = Fixture(account = FakeAccountApi(session = null))

        val pull = fixture.sync.pullToLocal()
        val push = fixture.sync.pushFromLocal()

        assertTrue(pull.isSuccess, "a user who is not signed in has nothing to fail at")
        assertTrue(push.isSuccess, "a user who is not signed in has nothing to fail at")
        assertEquals(
            emptyList(),
            fixture.backend.listAddonsCalls,
            "a skip must not reach the backend",
        )
        assertEquals(
            listOf(
                "pull skipped: no active session",
                "push skipped: no active session",
            ),
            fixture.logger.infos(),
            "a skip returns before logOutcome, so it logs exactly one line and not a completion",
        )
    }

    @Test
    fun pullKeepsOnlyStremioRowsWithANonBlankManifestUrl() = runTest {
        val fixture = Fixture()
        fixture.backend.serverAddons = listOf(
            serverAddon("a", "https://example.com/a.json"),
            serverAddon("b", "https://example.com/b.json", type = "plugin"),
            serverAddon("c", "   "),
            serverAddon("d", "https://example.com/d.json"),
        )

        fixture.sync.pullToLocal()

        assertEquals(
            listOf("https://example.com/a.json", "https://example.com/d.json"),
            fixture.localAddonUrls(),
            "only stremio rows with a real url reach the registry, in server order",
        )
    }

    @Test
    fun pullHandsThePluginBridgeTheUnfilteredServerRows() = runTest {
        val fixture = Fixture()
        val server = listOf(
            serverAddon("a", "https://example.com/a.json"),
            serverAddon("b", "https://example.com/b.json", type = "plugin"),
            serverAddon("c", "   "),
        )
        fixture.backend.serverAddons = server

        fixture.sync.pullToLocal()

        assertEquals(
            listOf(server),
            fixture.bridge.pullCalls,
            "the bridge owns plugin records, so it is given every row including the ones filtered out",
        )
    }

    @Test
    fun pushInstallsNothingWhenEveryLocalRowIsAlreadyOnTheServer() = runTest {
        val fixture = Fixture()
        fixture.seedLocal("https://example.com/a.json")
        fixture.backend.serverAddons = listOf(
            serverAddon("os", OPENSUBTITLES),
            serverAddon("a", "https://example.com/a.json"),
        )

        fixture.sync.pushFromLocal()

        assertEquals(
            emptyList(),
            fixture.backend.installAddonCalls.map { it.manifestUrl },
            "a row already on the server must not be installed again",
        )
        assertEquals(
            emptyList(),
            fixture.backend.uninstallAddonCalls.map { it.addonId },
            "a row already local must not be uninstalled",
        )
    }

    @Test
    fun pushInstallsExactlyTheLocalRowsTheServerDoesNotHave() = runTest {
        val fixture = Fixture()
        fixture.seedLocal("https://example.com/a.json", "https://example.com/b.json")
        fixture.backend.serverAddons = listOf(
            serverAddon("os", OPENSUBTITLES),
            serverAddon("a", "https://example.com/a.json"),
        )

        fixture.sync.pushFromLocal()

        assertEquals(
            listOf("https://example.com/b.json"),
            fixture.backend.installAddonCalls.map { it.manifestUrl },
            "b is the only local row the server is missing",
        )
        assertEquals(
            listOf(InstallIdentity("token-1", "profile-1")),
            fixture.backend.installAddonCalls.map { InstallIdentity(it.accessToken, it.profileId) },
            "the call carries the session's token and the store's profile id, not either id",
        )
    }

    @Test
    fun pushUninstallsExactlyTheStremioServerRowsThatAreGoneLocally() = runTest {
        val fixture = Fixture()
        fixture.seedLocal("https://example.com/keep.json")
        fixture.backend.serverAddons = listOf(
            serverAddon("os", OPENSUBTITLES),
            serverAddon("gone", "https://example.com/gone.json"),
            serverAddon("gone-plugin", "https://example.com/plugin.json", type = "plugin"),
            serverAddon("keep", "https://example.com/keep.json"),
        )

        fixture.sync.pushFromLocal()

        assertEquals(
            listOf("gone"),
            fixture.backend.uninstallAddonCalls.map { it.addonId },
            "a row of another type is never this class's business, so it is never uninstalled",
        )
    }

    @Test
    fun twoUrlsThatDifferOnlyInCaseAreOneIdentity() = runTest {
        val fixture = Fixture()
        fixture.seedLocal("https://Example.com/a.json")
        fixture.backend.serverAddons = listOf(
            serverAddon("os", OPENSUBTITLES),
            serverAddon("a", "https://example.com/A.json"),
        )

        fixture.sync.pushFromLocal()

        assertEquals(
            emptyList(),
            fixture.backend.installAddonCalls.map { it.manifestUrl },
            "the local row is already on the server under different case",
        )
        assertEquals(
            emptyList(),
            fixture.backend.uninstallAddonCalls.map { it.addonId },
            "the server row is already local under different case",
        )
    }

    @Test
    fun pushWithNoActiveProfileIdWarnsAndStillProceeds() = runTest {
        val fixture = Fixture(activeProfileId = null)
        fixture.seedLocal("https://example.com/a.json")
        fixture.backend.serverAddons = listOf(
            serverAddon("os", OPENSUBTITLES),
            serverAddon("gone", "https://example.com/gone.json"),
        )

        fixture.sync.pushFromLocal()

        assertEquals(
            listOf("push: no active profile id; server will reject installs"),
            fixture.logger.warnedMessages(),
            "the warning is the whole of the difference between a blank id and one",
        )
        assertEquals(
            listOf("gone"),
            fixture.backend.uninstallAddonCalls.map { it.addonId },
            "the uninstall is still attempted, with the blank profile id -- dropping it would " +
                "silently skip every uninstall and leave the server growing",
        )
        assertEquals(
            listOf(""),
            fixture.backend.uninstallAddonCalls.map { it.profileId },
            "and the blank id is the one handed over, not the one the warning was about",
        )
    }

    @Test
    fun aPluginBridgeFailureFailsTheOperationAfterTheAddonWorkAlreadyHappened() = runTest {
        val bridgeFailure = IllegalArgumentException("bridge said no")
        val fixture = Fixture(bridge = RecordingBridge(pushResult = Result.failure(bridgeFailure)))
        fixture.seedLocal("https://example.com/a.json")
        fixture.backend.serverAddons = listOf(serverAddon("os", OPENSUBTITLES))

        val result = fixture.sync.pushFromLocal()

        assertSame(bridgeFailure, result.exceptionOrNull(), "the bridge's failure is the result")
        assertEquals(
            listOf("https://example.com/a.json"),
            fixture.backend.installAddonCalls.map { it.manifestUrl },
            "the addon work runs before the bridge, so it has already happened",
        )
    }

    @Test
    fun aBridgeFailureOnPullFailsAfterTheRegistryHasAlreadyBeenReconciled() = runTest {
        val bridgeFailure = IllegalStateException("bridge down")
        val fixture = Fixture(bridge = RecordingBridge(pullResult = Result.failure(bridgeFailure)))
        fixture.backend.serverAddons = listOf(serverAddon("a", "https://example.com/a.json"))

        val result = fixture.sync.pullToLocal()

        assertSame(bridgeFailure, result.exceptionOrNull(), "the bridge's failure is the result")
        assertEquals(
            listOf("https://example.com/a.json"),
            fixture.localAddonUrls(),
            "the registry write happens before the bridge and is not rolled back",
        )
    }

    @Test
    fun aCompletedOperationIsLoggedAtInfoAndAFailedOneAtWarn() = runTest {
        val ok = Fixture()
        ok.sync.pullToLocal()

        val bad = Fixture(bridge = RecordingBridge(pullResult = Result.failure(IllegalStateException("no"))))
        bad.backend.serverAddons = listOf(serverAddon("a", "https://example.com/a.json"))
        bad.sync.pullToLocal()

        assertEquals(
            listOf("pull: 0 server addon row(s)", "pull completed"),
            ok.logger.infos(),
            "a pull that worked logs the row count and then ends on one completion line",
        )
        assertEquals(
            listOf("pull failed: no"),
            bad.logger.warnedMessages(),
            "a pull that failed ends on one warn line carrying the reason",
        )
    }
}

// --- fixtures -------------------------------------------------------------------------------

private const val NOW = "2026-01-01T00:00:00Z"
private const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"
private const val PROFILE_ID = "profile-1"
private const val USER_ID = "user-7"

private val SESSION = Session(
    accessToken = "token-1",
    refreshToken = "refresh-1",
    expiresAtEpochSec = null,
    userId = USER_ID,
    email = null,
    anonymous = false,
)

/**
 * `FakeAccountApi` with no session cannot produce the "the backend is down" answer, which is the
 * one case that is a failure rather than a skip. It is `open` for exactly this.
 */
private class SessionRefreshFailure(private val cause: Throwable) : FakeAccountApi() {
    override suspend fun ensureValidSession(): Session? = throw cause
}

/** A call's two arguments, so a case can assert both without repeating the lambda. */
private data class InstallIdentity(val accessToken: String, val profileId: String)

private fun serverAddon(id: String, manifestUrl: String, type: String = "stremio") =
    AddonDto(id = id, manifestUrl = manifestUrl, createdAt = NOW, type = type)

/**
 * One fixture per case, and nothing shared: the registry caches its parsed state and the
 * backend records its calls, so a suite-level instance would make every case depend on the order
 * they ran in.
 */
private class Fixture(
    val account: AccountApi = FakeAccountApi(SESSION),
    val bridge: RecordingBridge = RecordingBridge(),
    activeProfileId: String? = PROFILE_ID,
) {
    val backend = RecordingBackendApi()
    val logger = RecordingLogger()
    val registry = MetadataAddonRegistry(FakeKeyValueStore(), nowMs = { 0L })
    val sync = HouseholdAddonsCloudSync(
        supabase = account,
        backend = backend,
        addonRegistry = registry,
        activeProfileStore = ActiveProfileStore(
            profileStore(activeProfileId),
        ),
        logger = logger,
        pluginSyncBridge = bridge,
    )

    /** Every local row except the OpenSubtitles addon the registry seeds for itself. */
    fun localAddonUrls(): List<String> =
        registry.exportCloudAddons().map { it.manifestUrl }.filterNot { it == OPENSUBTITLES }

    /** Put rows in the registry the way a previous pull would have. */
    fun seedLocal(vararg manifestUrls: String) {
        registry.reconcileCloudAddons(
            manifestUrls.mapIndexed { index, url -> CloudAddonRow(manifestUrl = url, sortOrder = index) },
        )
    }

    private fun profileStore(activeProfileId: String?): KeyValueStore =
        FakeKeyValueStore(
            buildMap {
                if (activeProfileId != null) {
                    put("active_profile_id:$USER_ID", activeProfileId)
                }
            },
        )
}

private class RecordingBridge(
    private val pullResult: Result<Unit> = Result.success(Unit),
    private val pushResult: Result<Unit> = Result.success(Unit),
) : PluginAddonsSyncBridge {
    val pullCalls = mutableListOf<List<AddonDto>>()
    val pushCalls = mutableListOf<PushCall>()

    override suspend fun reconcilePull(serverAddons: List<AddonDto>): Result<Unit> {
        pullCalls += serverAddons
        return pullResult
    }

    override suspend fun reconcilePush(
        accessToken: String,
        profileId: String,
        serverAddons: List<AddonDto>,
    ): Result<Unit> {
        pushCalls += PushCall(accessToken, profileId, serverAddons)
        return pushResult
    }

    data class PushCall(val accessToken: String, val profileId: String, val serverAddons: List<AddonDto>)
}

private data class LogCall(val level: String, val tag: String, val message: String)

/**
 * `:app`'s own copy, because a `commonTest` compilation unit is not published and the one in
 * `:addons` cannot be named from here.
 */
private class RecordingLogger : AppLogger {
    val calls = mutableListOf<LogCall>()

    fun infos(): List<String> = calls.filter { it.level == "info" }.map { it.message }

    fun warnedMessages(): List<String> = calls.filter { it.level == "warn" }.map { it.message }

    override fun debug(tag: String, message: String) {
        calls += LogCall("debug", tag, message)
    }

    override fun info(tag: String, message: String) {
        calls += LogCall("info", tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        calls += LogCall("warn", tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        calls += LogCall("error", tag, message)
    }
}