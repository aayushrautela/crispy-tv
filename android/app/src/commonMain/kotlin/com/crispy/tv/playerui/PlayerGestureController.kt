package com.crispy.tv.playerui

import androidx.compose.runtime.Immutable

/**
 * The device brightness and volume a drag gesture reads and writes.
 *
 * A **top-level** type rather than a nested one inside [PlayerGestureController]:
 * the gesture modifier in `PlayerGestures.kt` names it as the parameter type of
 * `onVolumeChange`, so a nested declaration would pin the file that holds it for
 * the same reason `SignUpResult` pinned `AccountApi` -- and a nested type is as
 * pinned as the file declaring it.
 *
 * `fraction` is 0f..1f of the stream maximum rather than a raw stream step, because
 * the gesture works in fractions and the conversion needs an `AudioManager` that
 * `commonMain` cannot name.
 */
@Immutable
data class AudioLevel(
    val fraction: Float,
    val isMuted: Boolean,
)

/**
 * The platform capabilities the player's drag gestures need: screen brightness and
 * the music stream's volume.
 *
 * ## Why this is an interface
 *
 * The only implementation is `AndroidPlayerGestureController`, in `androidMain`,
 * and it is genuinely pinned: `Activity.window.attributes`, `WindowManager`,
 * `AudioManager` and `Settings.System` cannot be named from a KMP `commonMain`.
 * **The interface keeps the implementation's name**, following the
 * `StreamResolver` -> `CachingStreamResolver` precedent, so the two callers
 * (`PlayerGestures.kt` in `commonMain`, `PlayerRoute.kt` in `androidMain`) import
 * the symbol they already imported and neither needed an edit.
 *
 * ## Why it carries all five members
 *
 * The members are the callers' members, not the implementation's. `PlayerGestures`
 * reads and writes brightness and volume; `PlayerRoute` restores the brightness it
 * found on entry when the user leaves the player. Measuring per caller says four
 * and measuring repo-wide says five, and the fifth is the one that would have been
 * silently dropped -- an interface member omitted from the port is a compile error
 * in the screen and a behaviour change anywhere a type is held as the interface
 * rather than the class.
 */
interface PlayerGestureController {
    /** The current screen brightness as a 0f..1f fraction, never 0f. */
    fun currentBrightness(): Float

    /** Sets the screen brightness and returns the value actually applied. */
    fun setBrightness(level: Float): Float

    /** The music stream's volume as a fraction of its maximum. */
    fun currentVolume(): AudioLevel

    /** Sets the music stream's volume from a fraction and returns the level applied. */
    fun setVolume(level: Float): AudioLevel

    /**
     * Puts back the brightness the player found on entry.
     *
     * Idempotent on the implementation, and deliberately so: a user can leave the
     * player by several routes and each one calls this.
     */
    fun restoreBrightness()
}
