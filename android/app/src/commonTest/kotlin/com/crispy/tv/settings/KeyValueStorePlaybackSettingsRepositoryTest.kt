package com.crispy.tv.settings

import com.crispy.tv.nativeengine.playback.NativePlaybackEnginePreference
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The read / normalise / write rules of [KeyValueStorePlaybackSettingsRepository].
 *
 * They were previously reachable only through `SharedPreferences`, so none of
 * them had a test and the file sat in `androidMain` with no way to be given
 * one. Every case here is a decision the code makes rather than a delegation:
 * a fallback, a trim, a short-circuit, or a conversion that can fail.
 */
class KeyValueStorePlaybackSettingsRepositoryTest {

    @Test
    fun anEmptyStoreYieldsEveryDefault() {
        val settings = repository().settings.value

        assertEquals(PlaybackSettings(), settings)
    }

    @Test
    fun everySetterUpdatesTheLiveSnapshot() {
        val repository = repository()

        repository.setSkipIntroEnabled(false)
        repository.setTrailerAutoplayEnabled(false)
        repository.setTrailerMuted(true)
        repository.setPlaybackSpeed(1.5f)
        repository.setMuted(true)
        repository.setDefaultAudioLanguage("eng")
        repository.setDefaultSubtitleLanguage("deu")
        repository.setUseLibass(true)
        repository.setLibassRenderType("SL")
        repository.setResizeMode(PlayerResizeMode.Zoom)
        repository.setAutoSelectStream(true)
        repository.setPlaybackEnginePreference(NativePlaybackEnginePreference.Libmpv)

        val settings = repository.settings.value
        assertEquals(
            PlaybackSettings(
                skipIntroEnabled = false,
                trailerAutoplayEnabled = false,
                trailerMuted = true,
                playbackSpeed = 1.5f,
                muted = true,
                defaultAudioLanguage = "eng",
                defaultSubtitleLanguage = "deu",
                useLibass = true,
                libassRenderType = "SL",
                resizeMode = PlayerResizeMode.Zoom,
                autoSelectStream = true,
                playbackEnginePreference = NativePlaybackEnginePreference.Libmpv,
            ),
            settings,
        )
    }

    /**
     * The live snapshot updating is the whole reason the
     * `OnSharedPreferenceChangeListener` could be deleted: a second writer used
     * to write straight into the preferences file, and the listener was the only
     * thing that carried it into the snapshot. The cloud sync now writes through
     * this repository, so a caller that reads `settings` immediately after a
     * setter must see its own write, with nothing observing a file behind its
     * back.
     */
    @Test
    fun aSetterIsVisibleToTheNextReader() {
        val repository = repository()
        assertEquals(false, repository.settings.value.trailerMuted)

        repository.setTrailerMuted(true)

        assertEquals(true, repository.settings.value.trailerMuted)
    }

    @Test
    fun whatWasWrittenIsReadBackByAFreshRepository() {
        val store = FakeKeyValueStore()
        KeyValueStorePlaybackSettingsRepository(store).apply {
            setTrailerMuted(false)
            setPlaybackSpeed(2f)
            setResizeMode(PlayerResizeMode.Zoom)
            setPlaybackEnginePreference(NativePlaybackEnginePreference.ExoPlayer)
            setDefaultAudioLanguage("fra")
        }

        val reread = KeyValueStorePlaybackSettingsRepository(store).settings.value

        assertEquals(false, reread.trailerMuted)
        assertEquals(2f, reread.playbackSpeed)
        assertEquals(PlayerResizeMode.Zoom, reread.resizeMode)
        assertEquals(NativePlaybackEnginePreference.ExoPlayer, reread.playbackEnginePreference)
        assertEquals("fra", reread.defaultAudioLanguage)
    }

    /**
     * Every setter, called with the value it already holds.
     *
     * All twelve share the same short-circuit, so a test that picked a few of
     * them was a test of the few, not of the rule: deleting the guard from an
     * untested setter changed nothing observable. The full list is also the
     * cheapest way to notice a setter added later without one.
     */
    @Test
    fun settingAValueThatIsAlreadySetLeavesTheStoreAlone() {
        val store = FakeKeyValueStore()
        val repository = KeyValueStorePlaybackSettingsRepository(store)
        val current = repository.settings.value

        repository.setSkipIntroEnabled(current.skipIntroEnabled)
        repository.setTrailerAutoplayEnabled(current.trailerAutoplayEnabled)
        repository.setTrailerMuted(current.trailerMuted)
        repository.setPlaybackSpeed(current.playbackSpeed)
        repository.setMuted(current.muted)
        repository.setDefaultAudioLanguage(current.defaultAudioLanguage)
        repository.setDefaultSubtitleLanguage(current.defaultSubtitleLanguage)
        repository.setUseLibass(current.useLibass)
        repository.setLibassRenderType(current.libassRenderType)
        repository.setAutoSelectStream(current.autoSelectStream)
        repository.setResizeMode(current.resizeMode)
        repository.setPlaybackEnginePreference(current.playbackEnginePreference)

        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun clearingALanguageRemovesTheKeyRatherThanStoringABlank() {
        val store = FakeKeyValueStore(mapOf("default_audio_language" to "eng"))
        val repository = KeyValueStorePlaybackSettingsRepository(store)

        repository.setDefaultAudioLanguage(null)

        assertNull(KeyValueStorePlaybackSettingsRepository(store).settings.value.defaultAudioLanguage)
        assertEquals(false, store.contains("default_audio_language"))
    }

    @Test
    fun aBlankLanguageIsNormalisedToNoSelection() {
        val repository = repository()

        repository.setDefaultAudioLanguage("   ")

        assertNull(repository.settings.value.defaultAudioLanguage)
    }

    @Test
    fun aLanguageIsTrimmedBeforeItIsStored() {
        val store = FakeKeyValueStore()
        val repository = KeyValueStorePlaybackSettingsRepository(store)

        repository.setDefaultSubtitleLanguage("  spa \n")

        assertEquals("spa", KeyValueStorePlaybackSettingsRepository(store).settings.value.defaultSubtitleLanguage)
    }

    @Test
    fun aBlankLibassRenderTypeFallsBackToTheDefault() {
        val store = FakeKeyValueStore()
        val repository = KeyValueStorePlaybackSettingsRepository(store)

        repository.setLibassRenderType("   ")

        assertEquals("OVERLAY_OPEN_GL", repository.settings.value.libassRenderType)
        assertEquals("OVERLAY_OPEN_GL", KeyValueStorePlaybackSettingsRepository(store).settings.value.libassRenderType)
    }

    @Test
    fun anUnusableSpeedFallsBackToTheDefault() {
        val store = FakeKeyValueStore()
        val repository = KeyValueStorePlaybackSettingsRepository(store)

        repository.setPlaybackSpeed(0f)
        repository.setPlaybackSpeed(-2f)
        repository.setPlaybackSpeed(Float.NaN)
        repository.setPlaybackSpeed(Float.POSITIVE_INFINITY)

        assertEquals(1f, repository.settings.value.playbackSpeed)
        assertEquals(emptyList(), store.writes)
    }

    /**
     * A stored enum name that no longer exists -- an install that shipped a
     * value this build has since renamed, or a value written by hand. Falling
     * back to the default is what keeps the settings screen from crashing on
     * launch; the `runCatching` is the code under test, not defensive noise.
     */
    @Test
    fun anUnknownStoredEnumNameFallsBackToTheDefault() {
        val settings = KeyValueStorePlaybackSettingsRepository(
            FakeKeyValueStore(
                mapOf(
                    "resize_mode" to "Stretch",
                    "playback_engine" to "VLC",
                ),
            ),
        ).settings.value

        assertEquals(PlayerResizeMode.Fit, settings.resizeMode)
        assertEquals(NativePlaybackEnginePreference.Auto, settings.playbackEnginePreference)
    }

    @Test
    fun aStoredBlankEnumNameFallsBackToTheDefault() {
        val settings = KeyValueStorePlaybackSettingsRepository(
            FakeKeyValueStore(mapOf("resize_mode" to "  ")),
        ).settings.value

        assertEquals(PlayerResizeMode.Fit, settings.resizeMode)
    }

    @Test
    fun aStoredBlankLanguageReadsBackAsNoSelection() {
        val settings = KeyValueStorePlaybackSettingsRepository(
            FakeKeyValueStore(mapOf("default_audio_language" to "  ")),
        ).settings.value

        assertNull(settings.defaultAudioLanguage)
    }

    @Test
    fun aStoredBlankLibassRenderTypeFallsBackToTheDefault() {
        val settings = KeyValueStorePlaybackSettingsRepository(
            FakeKeyValueStore(mapOf("libass_render_type" to "")),
        ).settings.value

        assertEquals("OVERLAY_OPEN_GL", settings.libassRenderType)
    }

    private fun repository() = KeyValueStorePlaybackSettingsRepository(FakeKeyValueStore())
}
