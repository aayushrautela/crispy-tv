package com.crispy.tv.playerui

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [AndroidPlayerGestureController], the androidMain implementation of the
 * [PlayerGestureController] port.
 *
 * ## Why this suite exists at all
 *
 * The port moved to `commonMain` in the landing that created it, and the
 * implementation did not move with it, because it reaches `Activity.window`,
 * `WindowManager.LayoutParams`, `AudioManager` and `Settings.System`. The suite
 * written alongside the port covered only the common side — the two formatting
 * functions and the episode grouping — and a mutation pass found the
 * implementation by turning the class `abstract` and reporting `SURVIVED`. That
 * was correct about the *driver*: `:android:app:desktopTest` builds the desktop
 * target, and `androidMain` is not on it, so nothing in `desktopTest` can see
 * that file whatever it contains. It was not correct about the code, which had
 * no verification at all.
 *
 * **`:android:app:testAndroidHostTest` is `:app`'s only test compilation that
 * sees `androidMain`**, and this is where it belongs. Robolectric is here for a
 * `Context` and an `Activity` and nothing else — no view is inflated and no
 * resource is read — so `@Config(manifest = Config.NONE)` is enough and
 * `isIncludeAndroidResources` is unnecessary.
 *
 * `sdk = [35]` is not optional. With `Config.NONE` there is no manifest for
 * Robolectric to read `targetSdk` from, so it silently falls back to **SDK 21**,
 * which is below the app's `minSdk` of 26; a class added after API 21 is then
 * absent from `android-all` and the failure reads `Method ... not mocked`, which
 * names a real app class and blames nothing about Robolectric.
 *
 * ## Every expected number here was measured
 *
 * The probe that found these values ran on JDK 21 with SDK 35 and printed them
 * before any of them was written into an assertion. In particular: the stream
 * maximum is **15**, the default `screenBrightness` on a fresh window is
 * **`-1.0f`** (`BRIGHTNESS_OVERRIDE_NONE`), and `Settings.System.getInt` for
 * `SCREEN_BRIGHTNESS` **throws** when the setting has never been written — which
 * is what the implementation's `runCatching { … }.getOrDefault(127)` is for.
 * The seeded case uses 51 because `51 / 255` is exactly `0.2`, so the assertion
 * is exact in binary floating point rather than nearly equal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AndroidPlayerGestureControllerTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private val audioManager: AudioManager = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager

    private fun controller() = AndroidPlayerGestureController(activity, audioManager)

    private fun seedSystemBrightness(value: Int) {
        Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    // region construction

    @Test
    fun aNullActivityYieldsNoController() {
        // The caller passes `context.findActivity()`, which is nullable, and the
        // player screen has no activity on a pre-attach composition. Returning a
        // controller here would mean the gestures silently do nothing instead.
        assertNull(tryCreateGestureController(null))
    }

    @Test
    fun aRealActivityYieldsAControllerThroughThePort() {
        val created = tryCreateGestureController(activity)
        assertNotNull(created)
        // And it is reached through the interface, not the implementation: the
        // function's declared return type is `PlayerGestureController?`, so this
        // only compiles because the port is what commonMain names.
        val asPort: PlayerGestureController? = created
        assertNotNull(asPort)
    }

    @Test
    fun theFactoryBuildsTheAndroidImplementation() {
        assertEquals(
            "com.crispy.tv.playerui.AndroidPlayerGestureController",
            tryCreateGestureController(activity)!!.javaClass.name,
        )
    }

    // endregion

    // region brightness

    @Test
    fun setBrightnessReturnsTheClampedValueItApplied() {
        val controller = controller()
        assertEquals(1.0f, controller.setBrightness(5f), 0f)
        assertEquals(0.02f, controller.setBrightness(-3f), 0f)
        // A value already inside the range is returned unchanged.
        assertEquals(0.4f, controller.setBrightness(0.4f), 0f)
    }

    @Test
    fun setBrightnessIsVisibleOnTheWindow() {
        // The implementation mutates the `LayoutParams` it was handed and reassigns
        // it, so this pins that the write is not dropped: Robolectric's
        // `activity.window.attributes` returns the same object every call, which is
        // what makes a write-then-read assertion meaningful here.
        controller().setBrightness(0.75f)
        assertEquals(0.75f, activity.window.attributes.screenBrightness, 0f)
    }

    @Test
    fun currentBrightnessReadsTheSystemSettingWhenTheWindowHasNoOverride() {
        // A fresh window's `screenBrightness` is `-1.0f`, which is outside `0f..1f`,
        // so `currentBrightness` must fall through to `Settings.System`. 51/255 is
        // exactly 0.2 in binary floating point.
        seedSystemBrightness(51)
        assertEquals(0.2f, controller().currentBrightness(), 0f)
    }

    @Test
    fun currentBrightnessPrefersAWindowOverrideInsideTheRange() {
        val controller = controller()
        controller.setBrightness(0.6f)
        assertEquals(0.6f, controller.currentBrightness(), 0f)
    }

    @Test
    fun restoreBrightnessReturnsAWindowWithNoOverrideToHavingNoOverride() {
        // `originalBrightness` is captured at construction, and a fresh window is at
        // `-1.0f`, so the restore writes `BRIGHTNESS_OVERRIDE_NONE` back rather than
        // `coerceIn(0f, 1f)` — which would have pinned the window at black.
        val controller = controller()
        controller.setBrightness(0.3f)
        controller.restoreBrightness()
        assertEquals(-1.0f, activity.window.attributes.screenBrightness, 0f)
    }

    @Test
    fun restoreBrightnessIsAOneShotLatch() {
        // The screen calls this when the user touches the screen mid-gesture, which
        // can happen more than once. The second call is a no-op, so a gesture that
        // re-applies brightness after the restore is not undone.
        val controller = controller()
        controller.setBrightness(0.3f)
        controller.restoreBrightness()
        controller.setBrightness(0.9f)
        controller.restoreBrightness()
        assertEquals(0.9f, activity.window.attributes.screenBrightness, 0f)
    }

    @Test
    fun restoreBrightnessCapturedAnOriginalInsideTheRange() {
        // The other half of the original-brightness rule, and the only way to reach
        // it: the controller captures `screenBrightness` **at construction**, so the
        // window has to already carry an override before this one is built. It then
        // gets that exact value back — not `coerceIn(0f, 1f)`, and not
        // `BRIGHTNESS_OVERRIDE_NONE`. Without this case the latch is only ever
        // tested against the `-1.0f` arm and the `else` could be deleted.
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.42f }
        val controller = controller()
        controller.setBrightness(0.9f)
        controller.restoreBrightness()
        assertEquals(0.42f, activity.window.attributes.screenBrightness, 0f)
    }

    // endregion

    // region volume

    @Test
    fun currentVolumeReadsTheStreamAsAFractionOfItsMaximum() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        assertTrue("expected a non-zero stream maximum, was $max", max > 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val level = controller().currentVolume()
        assertEquals(3f / max.toFloat(), level.fraction, 1e-6f)
        assertEquals(false, level.isMuted)
    }

    @Test
    fun currentVolumeIsMutedOnlyAtZero() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val level = controller().currentVolume()
        assertEquals(0f, level.fraction, 0f)
        assertEquals(true, level.isMuted)
    }

    @Test
    fun setVolumeReturnsTheLevelItActuallyApplied() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val controller = controller()
        // 0.5 of 15 is 7.5, which rounds to 8 -- the return value is the applied
        // step, not the requested fraction, so the gesture overlay cannot drift from
        // the stream.
        val half = controller.setVolume(0.5f)
        assertEquals(8f / max.toFloat(), half.fraction, 1e-6f)
        assertEquals(8, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun setVolumeClampsTheRequestedFractionAtBothEnds() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val controller = controller()
        assertEquals(0f, controller.setVolume(-4f).fraction, 0f)
        assertEquals(0, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(1f, controller.setVolume(9f).fraction, 1e-6f)
        assertEquals(max, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun setVolumeNeverReportsMutedForAnAudibleStream() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = controller().setVolume(0.5f)
        assertEquals(false, level.isMuted)
        assertTrue(level.fraction > 0f)
        assertTrue(max > 0)
    }

    // endregion
}
