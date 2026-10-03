package com.crispy.tv.sync

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.accounts.Session
import com.crispy.tv.backend.ProfileSettings
import com.crispy.tv.discover.FakeAccountApi
import com.crispy.tv.nativeengine.playback.NativePlaybackEnginePreference
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
import com.crispy.tv.platform.RecordingKeyValueStore
import com.crispy.tv.settings.PlaybackSettings
import com.crispy.tv.settings.PlaybackSettingsRepository
import com.crispy.tv.testing.FakeKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SKIP_INTRO = "playback.skip_intro_enabled"
private const val TRAILER_AUTOPLAY = "playback.trailer_autoplay_enabled"
private const val TRAILER_MUTED = "playback.trailer_muted"

private fun session(
    accessToken: String = "tok",
    userId: String? = "user-42",
) = Session(
    accessToken = accessToken,
    refreshToken = "refresh",
    expiresAtEpochSec = null,
    userId = userId,
    email = null,
    anonymous = false,
)

/**
 * The decision surface of [ProfileDataCloudSync], which had **no coverage in any source
 * set** before it moved to `commonMain`.
 *
 * That it had none is the finding, not an oversight: the class was `androidMain` only
 * because a `Context` sat in two default arguments and the backend parameter named a
 * client a `commonTest` cannot construct. Once both were discharged -- the `Context`
 * became two named stores from the factory, and the client became [BackendApi][com.crispy.tv.backend.BackendApi],
 * which the client already implements -- every rule below became reachable from here.
 *
 * The three collaborators a test needs are all ports, which is the only reason this is
 * a suite and not a wish: [AccountApi], `PlaybackSettingsRepository` and the shadow store
 * are interfaces or constructors over a [com.crispy.tv.platform.KeyValueStore], so the
 * real code runs unmodified. The backend is [RecordingBackendApi] subclassed for the two
 * members this class calls, rather than a second 52-member double.
 */
class ProfileDataCloudSyncTest {

    // --- who is asked to sync at all ---------------------------------------

    @Test
    fun aSignedOutUserIsANoOpRatherThanAFailure() = runTest {
        val harness = harness(account = FakeAccountApi(null), profileId = "p1")

        val result = harness.sync.pullForActiveProfile()

        // Not a failure: there is nothing to sync and nothing went wrong, which is the
        // distinction that keeps a signed-out launch from showing an error.
        assertTrue(result.isSuccess, "a signed-out user must not be reported as an error")
        assertEquals(emptyList(), harness.backend.getCalls)
        assertEquals(emptyList(), harness.backend.patchCalls)
        assertEquals(emptyList(), harness.playback.calls)
    }

    @Test
    fun aSignedInUserWithNoActiveProfileIsAlsoANoOp() = runTest {
        val harness = harness(profileId = null)

        val result = harness.sync.pullForActiveProfile()

        assertTrue(result.isSuccess)
        assertEquals(emptyList(), harness.backend.getCalls)
    }

    @Test
    fun aSessionLookupFailureIsReportedRatherThanSwallowed() = runTest {
        val boom = IllegalStateException("supabase is down")
        val harness = harness(account = ThrowingAccountApi(boom), profileId = "p1")

        val result = harness.sync.pullForActiveProfile()

        assertTrue(result.isFailure)
        // Identity, not equality: a wrapped copy would lose the type the UI renders.
        assertSame(boom, result.exceptionOrNull())
        assertEquals(emptyList(), harness.backend.getCalls)
    }

    @Test
    fun aBackendFailureIsReportedRatherThanSwallowed() = runTest {
        val boom = IllegalStateException("non-2xx")
        val harness = harness(profileId = "p1", remote = mapOf(SKIP_INTRO to "false"))
        harness.backend.getThrows = boom

        val result = harness.sync.pullForActiveProfile()

        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())
    }

    // --- the profile id ------------------------------------------------------

    @Test
    fun aBlankProfileIdIsRefusedBeforeAnyRequestIsMade() = runTest {
        val harness = harness(profileId = "   ")

        assertTrue(harness.sync.pullForActiveProfile().isSuccess)
        assertTrue(harness.sync.pushForActiveProfile().isSuccess)

        // The discriminator: the guard runs before the request, so a rewrite that moved
        // the `isBlank` check after the fetch would still "succeed" and would show a call.
        assertEquals(emptyList(), harness.backend.getCalls)
        assertEquals(emptyList(), harness.backend.patchCalls)
    }

    @Test
    fun theProfileIdIsTrimmedBeforeItReachesTheBackendOrTheShadow() = runTest {
        val harness = harness(profileId = "  p1  ", remote = mapOf(SKIP_INTRO to "true"))

        harness.sync.pullForActiveProfile()

        assertEquals(listOf("tok/p1"), harness.backend.getCalls)
        // And the shadow is filed under the trimmed id, so a later push finds it.
        assertEquals("p1", harness.shadow.read("p1")?.profileId)
        assertNull(harness.shadow.read("  p1  "))
    }

    // --- pulling -------------------------------------------------------------

    @Test
    fun aPullSavesTheRemoteSettingsAsAShadowAndAppliesThemLocally() = runTest {
        val harness = harness(
            profileId = "p1",
            remote = mapOf(SKIP_INTRO to "false", "some.other.key" to "kept"),
        )

        assertTrue(harness.sync.pullForActiveProfile().isSuccess)

        val snapshot = harness.shadow.read("p1")
        assertEquals(mapOf(SKIP_INTRO to "false", "some.other.key" to "kept"), snapshot?.settings)
        // A pull has no catalog preferences to record and no local change to timestamp, and
        // writing an empty map where the real one lives would erase it on the next push.
        assertEquals(emptyMap(), snapshot?.catalogPrefs)
        assertNull(snapshot?.updatedAt)
        assertEquals(listOf("skipIntroEnabled=false"), harness.playback.calls)
    }

    @Test
    fun onlyTheThreePlaybackKeysReachTheRepositoryAndTheRestStayInTheShadow() = runTest {
        val harness = harness(
            profileId = "p1",
            remote = mapOf(
                SKIP_INTRO to "false",
                TRAILER_AUTOPLAY to "false",
                TRAILER_MUTED to "true",
                "unrelated" to "x",
            ),
        )

        harness.sync.pullForActiveProfile()

        // Asserted as a SET as well as a list: a repository that grew a fourth setter would
        // otherwise pass a subset check by accident, and the point is that nothing else
        // is applied.
        assertEquals(
            setOf("skipIntroEnabled=false", "trailerAutoplayEnabled=false", "trailerMuted=true"),
            harness.playback.calls.toSet(),
        )
        assertEquals(3, harness.playback.calls.size)
        assertEquals("x", harness.shadow.read("p1")?.settings?.get("unrelated"))
    }

    @Test
    fun anUnreadableValueLeavesTheLocalSettingAloneRatherThanGuessing() = runTest {
        // Every one of these is a value the backend could plausibly send and this code has
        // no answer for. The rule is uniform: absent, not false, because `false` would
        // silently switch a feature off.
        val unreadable = listOf("yes", "1", "0", "on", "off", "", "  ", "truthy", "FALSEY")
        for (value in unreadable) {
            val harness = harness(profileId = "p1", remote = mapOf(SKIP_INTRO to value))

            harness.sync.pullForActiveProfile()

            assertEquals(
                emptyList(),
                harness.playback.calls,
                "\"$value\" must not be applied to the local setting",
            )
        }
    }

    @Test
    fun aPulledValueIsTrimmedAndCaseFoldedBeforeItIsApplied() = runTest {
        val harness = harness(
            profileId = "p1",
            remote = mapOf(SKIP_INTRO to " TRUE ", TRAILER_MUTED to " False"),
        )

        harness.sync.pullForActiveProfile()

        assertEquals(
            listOf("skipIntroEnabled=true", "trailerMuted=false"),
            harness.playback.calls,
        )
    }

    // --- pushing -------------------------------------------------------------

    @Test
    fun aPushWithNoLocalBaselineFetchesOneFirstAndKeepsIt() = runTest {
        val harness = harness(profileId = "p1", remote = mapOf(SKIP_INTRO to "true"))
        assertNull(harness.shadow.read("p1"))

        assertTrue(harness.sync.pushForActiveProfile().isSuccess)

        // The fetch first: a push with no baseline would otherwise send a payload holding
        // only the three keys it knows, wiping every other server-side setting.
        assertEquals(listOf("tok/p1"), harness.backend.getCalls)
        assertEquals(1, harness.backend.patchCalls.size)
        val sent = harness.backend.patchCalls.single().settings
        assertEquals("true", sent[SKIP_INTRO])
        assertTrue(sent.containsKey(TRAILER_AUTOPLAY) && sent.containsKey(TRAILER_MUTED))
        // And the shadow now holds what was sent, so a second push does not fetch again.
        assertEquals(sent, harness.shadow.read("p1")?.settings)
    }

    @Test
    fun aPushWithABaselineAlreadyOnDiskDoesNotReadTheNetworkFirst() = runTest {
        val harness = harness(
            profileId = "p1",
            remote = mapOf(SKIP_INTRO to "true"),
            local = PlaybackSettings(skipIntroEnabled = false),
        )
        harness.shadow.write(
            ProfileDataShadowStore.Snapshot(
                profileId = "p1",
                settings = mapOf(SKIP_INTRO to "false"),
                catalogPrefs = emptyMap(),
                updatedAt = null,
            ),
        )

        harness.sync.pushForActiveProfile()

        // The discriminator against the case above: the baseline is the point of the
        // shadow store, and re-fetching would make every push a read.
        assertEquals(emptyList(), harness.backend.getCalls)
        assertEquals("false", harness.backend.patchCalls.single().settings[SKIP_INTRO])
    }

    @Test
    fun aPushKeepsUnrelatedBaselineKeysAndOverridesOnlyTheThreePlaybackOnes() = runTest {
        val harness = harness(profileId = "p1")
        harness.shadow.write(
            ProfileDataShadowStore.Snapshot(
                profileId = "p1",
                settings = mapOf(
                    // All three baseline values are the OPPOSITE of the local defaults below,
                    // so a push that forwarded the baseline verbatim fails three assertions
                    // instead of passing on the keys it happens to agree about.
                    SKIP_INTRO to "false",
                    TRAILER_AUTOPLAY to "false",
                    TRAILER_MUTED to "true",
                    "catalog.layout" to "grid",
                    "catalog.sort" to "popularity",
                ),
                catalogPrefs = emptyMap(),
                updatedAt = null,
            ),
        )

        harness.sync.pushForActiveProfile()

        val sent = harness.backend.patchCalls.single().settings
        assertEquals("grid", sent["catalog.layout"])
        assertEquals("popularity", sent["catalog.sort"])
        // Server-first, fill-missing-only: the local snapshot wins for the three keys the
        // device owns, and nothing else is touched.
        assertEquals("true", sent[SKIP_INTRO])
        assertEquals("true", sent[TRAILER_AUTOPLAY])
        assertEquals("false", sent[TRAILER_MUTED])
        assertEquals(
            setOf(SKIP_INTRO, TRAILER_AUTOPLAY, TRAILER_MUTED, "catalog.layout", "catalog.sort"),
            sent.keys,
        )
    }

    @Test
    fun thePushedValuesComeFromTheLiveLocalSnapshotRatherThanFromTheShadow() = runTest {
        val harness = harness(
            profileId = "p1",
            local = PlaybackSettings(
                skipIntroEnabled = false,
                trailerAutoplayEnabled = false,
                trailerMuted = true,
            ),
        )
        // A stale shadow claiming the opposite is the case that distinguishes the two.
        harness.shadow.write(
            ProfileDataShadowStore.Snapshot(
                profileId = "p1",
                settings = mapOf(SKIP_INTRO to "true", TRAILER_MUTED to "false"),
                catalogPrefs = emptyMap(),
                updatedAt = null,
            ),
        )

        harness.sync.pushForActiveProfile()

        val sent = harness.backend.patchCalls.single().settings
        assertEquals("false", sent[SKIP_INTRO])
        assertEquals("false", sent[TRAILER_AUTOPLAY])
        assertEquals("true", sent[TRAILER_MUTED])
    }

    // --- the stringly-typed boolean ------------------------------------------

    /**
     * [parseBooleanSetting] was `private` and is now `internal`, which is the whole reason
     * this table exists: a private top-level function cannot be named by any test, so the
     * normalisation rules were untestable rather than merely untested.
     */
    @Test
    fun theAcceptedSpellingsAreTrimmedAndCaseFolded() {
        val cases = mapOf(
            "true" to true,
            "TRUE" to true,
            "TrUe" to true,
            "  true  " to true,
            "false" to false,
            "FALSE" to false,
            "\tFalse\n" to false,
        )
        for ((raw, expected) in cases) {
            assertEquals(expected, parseBooleanSetting(raw), "raw = \"$raw\"")
        }
    }

    @Test
    fun everyOtherSpellingIsAbsentRatherThanFalse() {
        val unreadable = listOf(null, "", "   ", "1", "0", "yes", "no", "on", "off", "t", "f")
        for (raw in unreadable) {
            assertNull(parseBooleanSetting(raw), "raw = $raw")
        }
    }

    @Test
    fun theAbsentAnswerIsWhatLeavesASettingAloneAndFalseIsNotTheSameAnswer() {
        // The two are one line apart in the caller and behave differently in the product:
        // absent means "the server did not say", false means "the server said off".
        assertNull(parseBooleanSetting("unknown"))
        assertEquals(false, parseBooleanSetting("false"))
        assertFalse(parseBooleanSetting("unknown") == parseBooleanSetting("false"))
    }

    // --- harness -------------------------------------------------------------

    private fun harness(
        account: AccountApi = FakeAccountApi(session()),
        profileId: String? = "p1",
        remote: Map<String, String> = emptyMap(),
        local: PlaybackSettings = PlaybackSettings(),
    ): Harness {
        val backend = ProfileSettingsBackend(remote)
        val profileStore = FakeKeyValueStore()
        val activeProfileStore = ActiveProfileStore(profileStore)
        if (profileId != null) {
            activeProfileStore.setActiveProfileId("user-42", profileId)
        }
        val shadow = ProfileDataShadowStore(RecordingKeyValueStore())
        val playback = RecordingPlaybackSettingsRepository(local)
        return Harness(
            sync = ProfileDataCloudSync(
                supabase = account,
                backend = backend,
                playbackSettings = playback,
                activeProfileStore = activeProfileStore,
                shadowStore = shadow,
            ),
            backend = backend,
            playback = playback,
            shadow = shadow,
        )
    }

    private class Harness(
        val sync: ProfileDataCloudSync,
        val backend: ProfileSettingsBackend,
        val playback: RecordingPlaybackSettingsRepository,
        val shadow: ProfileDataShadowStore,
    )
}

/**
 * A session lookup that throws, which no constructor argument can produce: a null session
 * is the "not signed in" answer and nothing else is the "backend is down" one. Subclassing
 * the existing `open` [FakeAccountApi] is the point of it being `open` -- a second copy
 * would rot the moment `AccountApi` grew a member.
 */
private class ThrowingAccountApi(private val cause: Throwable) : FakeAccountApi() {
    override suspend fun ensureValidSession(): Session? = throw cause
}

/**
 * The two [com.crispy.tv.backend.BackendApi] members [ProfileDataCloudSync] calls, answered.
 *
 * Every other member keeps `RecordingBackendApi`'s name-the-member `AssertionError`, so a
 * test that reaches one has called something it did not mean to.
 */
private class ProfileSettingsBackend(
    private var remote: Map<String, String>,
) : RecordingBackendApi() {
    val getCalls = mutableListOf<String>()
    val patchCalls = mutableListOf<PatchCall>()

    var getThrows: Throwable? = null
    var patchThrows: Throwable? = null

    data class PatchCall(
        val accessToken: String,
        val profileId: String,
        val settings: Map<String, String>,
    )

    override suspend fun getProfileSettings(accessToken: String, profileId: String): ProfileSettings {
        getCalls += "$accessToken/$profileId"
        getThrows?.let { throw it }
        return ProfileSettings(remote)
    }

    override suspend fun patchProfileSettings(
        accessToken: String,
        profileId: String,
        settings: Map<String, String>,
    ): ProfileSettings {
        patchCalls += PatchCall(accessToken, profileId, settings)
        patchThrows?.let { throw it }
        return ProfileSettings(settings)
    }
}

/**
 * A [PlaybackSettingsRepository] that records which setters ran, in order.
 *
 * `calls` rather than only a snapshot is the instrument: the decision under test is *which
 * keys the payload could speak for*, and a repository that silently ignored a key would look
 * identical in a snapshot. The unused setters throw, because this class exists only so the
 * three the sync owns are observable.
 */
private class RecordingPlaybackSettingsRepository(
    initial: PlaybackSettings,
) : PlaybackSettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

    val calls = mutableListOf<String>()

    override fun setSkipIntroEnabled(enabled: Boolean) {
        calls += "skipIntroEnabled=$enabled"
        _settings.value = _settings.value.copy(skipIntroEnabled = enabled)
    }

    override fun setTrailerAutoplayEnabled(enabled: Boolean) {
        calls += "trailerAutoplayEnabled=$enabled"
        _settings.value = _settings.value.copy(trailerAutoplayEnabled = enabled)
    }

    override fun setTrailerMuted(muted: Boolean) {
        calls += "trailerMuted=$muted"
        _settings.value = _settings.value.copy(trailerMuted = muted)
    }

    override fun setPlaybackSpeed(speed: Float): Unit = unused("setPlaybackSpeed")
    override fun setMuted(muted: Boolean): Unit = unused("setMuted")
    override fun setDefaultAudioLanguage(language: String?): Unit = unused("setDefaultAudioLanguage")
    override fun setDefaultSubtitleLanguage(language: String?): Unit = unused("setDefaultSubtitleLanguage")
    override fun setUseLibass(enabled: Boolean): Unit = unused("setUseLibass")
    override fun setLibassRenderType(renderType: String): Unit = unused("setLibassRenderType")
    override fun setAutoSelectStream(enabled: Boolean): Unit = unused("setAutoSelectStream")
    override fun setResizeMode(mode: PlayerResizeMode): Unit = unused("setResizeMode")
    override fun setPlaybackEnginePreference(preference: NativePlaybackEnginePreference): Unit =
        unused("setPlaybackEnginePreference")

    private fun unused(name: String): Nothing = throw AssertionError("$name is not stubbed")
}
