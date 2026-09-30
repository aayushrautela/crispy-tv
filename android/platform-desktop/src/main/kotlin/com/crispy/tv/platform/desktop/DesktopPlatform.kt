package com.crispy.tv.platform.desktop

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.TimeSource
import java.io.PrintStream

/**
 * The desktop [TimeSource], [MonotonicClock] and [AppLogger].
 *
 * Grouped in one file because each is a thin adapter over a single platform
 * call, which mirrors `AndroidPlatform.kt` on the other side of the seam.
 */

/**
 * Wall-clock [TimeSource].
 *
 * This is *not* a substitute for [MonotonicClock], and the two answer different
 * questions. A wall clock can jump backwards or forwards when the machine syncs
 * time or crosses a timezone, so it is wrong for throttling and intervals, while
 * a monotonic clock cannot tell you what time it is and is wrong for anything a
 * user sees. Code that throttles injects [MonotonicClock]; code that shows a
 * timestamp injects [TimeSource].
 */
class DesktopTimeSource : TimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * The desktop [MonotonicClock].
 *
 * `System.nanoTime()` is the JVM's monotonic source, and it is the same
 * contract as `SystemClock.elapsedRealtime()` on Android: a value that only ever
 * increases within a process, whose origin is arbitrary, and whose only
 * meaningful use is a difference between two readings. It is **not** a wall
 * clock and must never be formatted as a date.
 */
class DesktopMonotonicClock(
    private val originNanos: Long = System.nanoTime(),
) : MonotonicClock {

    /**
     * Elapsed milliseconds since this clock was constructed.
     *
     * An instance is its own origin, so two instances do not share a timeline.
     * That is deliberate: an elapsed time is only comparable against a reading
     * from the same instance, and an origin recorded in one instance and
     * subtracted in another silently produces a large or negative interval.
     */
    override fun elapsedMs(): Long = (System.nanoTime() - originNanos) / 1_000_000L
}

/**
 * [AppLogger] over the process's own streams.
 *
 * ## Why the debug gate is a constructor flag
 *
 * The Android implementation reads the *application's* `FLAG_DEBUGGABLE`
 * rather than a library `BuildConfig`, because a library's `BuildConfig.DEBUG`
 * reflects how the library itself was built -- a release application linking a
 * debug-built library would emit debug lines, which is backwards.
 *
 * A desktop build has no such flag: `kotlin.jvm` produces one artifact that
 * serves development and release, and the packaging task is not a Kotlin
 * compiler input. So the answer to the same question -- "is this a debug
 * build?" -- is supplied by whoever composes the application, which is exactly
 * the kind of answer a composition root should be handing out. [debug] defaults
 * to false so a logger that is constructed without thought is quiet.
 *
 * ## Why warn and error go to stderr
 *
 * `debug` and `info` go to [out]; `warn` and `error` go to [error]. Both are
 * injectable so a test can capture them, and so an embedding application that
 * already owns a logging pipeline can route these into it rather than have two
 * logging systems interleave.
 */
class DesktopAppLogger(
    private val out: PrintStream = System.out,
    private val error: PrintStream = System.err,
    private val debug: Boolean = false,
) : AppLogger {

    override fun debug(tag: String, message: String) {
        if (debug) out.println("$tag: $message")
    }

    override fun info(tag: String, message: String) {
        out.println("$tag: $message")
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        error.println("$tag: $message")
        throwable?.printStackTrace(error)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        error.println("$tag: $message")
        throwable?.printStackTrace(error)
    }
}
