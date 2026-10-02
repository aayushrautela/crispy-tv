package com.crispy.tv.playerui

import kotlin.math.roundToInt

/**
 * The video rectangle a picture-in-picture window should crop to, as four integers.
 *
 * This is `android.graphics.Rect`'s four fields and nothing else. The type it replaced was the
 * last thing standing between [PlayerHost] -- a port, with an implementation per platform -- and
 * `commonMain`: the interface named a parameter whose type carried two `android.jar` classes, and
 * a type named in a member signature pins the file that declares it whether or not the signature
 * mentions an Android type anywhere else.
 *
 * `android.graphics.Rect`'s own `equals` compares the four ints, so a data class does the same
 * work and [PlayerPipController]'s `config == lastAppliedConfig` guard keeps its meaning.
 *
 * `width()` and `height()` are reproduced as **functions**, not properties, even though Kotlin
 * would prefer a property for a zero-argument getter. `android.graphics.Rect` declares them as
 * methods and the one call site reads `it.width()`, so keeping the shape means the producer side
 * of this port is a byte-identical diff. The first version declared them as properties and the
 * compiler answered "None of the following candidates is applicable because of a receiver type
 * mismatch" -- which is the port working, just not in the direction I wrote it.
 */
data class PictureInPictureSourceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
}

/**
 * A picture-in-picture aspect ratio as an **unreduced** numerator/denominator pair.
 *
 * Unreduced on purpose. [PlayerRoute] builds these from a rounded float (`17778/10000` for 16:9)
 * and from raw pixel bounds (`1920/1080`), so two of them can describe the same ratio and still
 * differ as pairs -- and `android.util.Rational`, which this replaced, also compares its two raw
 * fields rather than a reduced form. Storing them raw is therefore the behaviour-preserving
 * choice: the only divergence it could ever cause is a redundant window transaction, which is the
 * harmless direction.
 *
 * The platform type is rebuilt at the far end, in `PlayerPipController.buildParams`.
 */
data class PictureInPictureAspectRatio(
    val numerator: Int,
    val denominator: Int,
)

data class PictureInPictureConfig(
    val enabled: Boolean = false,
    val sourceRect: PictureInPictureSourceRect? = null,
    val aspectRatio: PictureInPictureAspectRatio? = null,
)

/** Android rejects a PiP window wider than 2.39:1, so a wider video is letterboxed to this. */
internal const val MAX_PICTURE_IN_PICTURE_ASPECT_RATIO = 2.39

/** And a narrower one is pillarboxed to this, which is the reciprocal of the maximum. */
internal const val MIN_PICTURE_IN_PICTURE_ASPECT_RATIO = 1.0 / MAX_PICTURE_IN_PICTURE_ASPECT_RATIO

/**
 * The aspect ratio to hand the platform, or null when there is nothing to hand it.
 *
 * This is the whole of the previous `aspectRatio?.takeIf { it.numerator > 0 && it.denominator > 0 }
 * ?.let(::clampAspectRatio)` chain in `PlayerPipController.buildParams`, which was a private
 * function behind two levels of nullable receiver inside a `PictureInPictureParams.Builder` call.
 * Nothing about it was reachable from a test: `PlayerPipController` cannot be constructed off
 * Android, and the decision was never named anywhere in production.
 *
 * The nullable receiver is deliberate and is what lets the whole chain collapse here -- the
 * `?:` and `?.let` this replaces were a filter and a map, with no fallback of their own, so
 * folding them together adds no answer that was not already reachable.
 *
 * **Null is a distinct answer from "clamped".** A non-positive numerator or denominator is
 * dropped rather than clamped: `0/10` is not an extremely tall window, it is a window the engine
 * has not been told the shape of yet, and clamping it would advertise a 2.39:1 letterbox for a
 * video whose geometry is still unknown.
 */
internal fun PictureInPictureAspectRatio?.clampedAspectRatio(): PictureInPictureAspectRatio? {
    val candidate = this ?: return null
    if (candidate.numerator <= 0 || candidate.denominator <= 0) {
        return null
    }
    val ratio = candidate.numerator.toDouble() / candidate.denominator
    return when {
        ratio > MAX_PICTURE_IN_PICTURE_ASPECT_RATIO ->
            PictureInPictureAspectRatio((MAX_PICTURE_IN_PICTURE_ASPECT_RATIO * 100).roundToInt(), 100)
        ratio < MIN_PICTURE_IN_PICTURE_ASPECT_RATIO ->
            // The denominator is the *maximum's* hundreds, not the minimum's, and that reads like
            // a copy-paste slip. It is correct: MAX is 2.39, so round(MAX * 100) is 239, and
            // 100/239 is precisely 1/2.39 -- which is what MIN is defined as. The two constants
            // are reciprocals by construction, so the arithmetic works out; it is pinned by a
            // test because "correct by coincidence" is exactly the kind of thing a future edit
            // to MAX silently breaks.
            PictureInPictureAspectRatio(100, (MAX_PICTURE_IN_PICTURE_ASPECT_RATIO * 100).roundToInt())
        else -> candidate
    }
}