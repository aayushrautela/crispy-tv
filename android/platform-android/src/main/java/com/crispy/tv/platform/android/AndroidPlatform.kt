package com.crispy.tv.platform.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.TimeSource

/**
 * The Android implementations of [TimeSource], [MonotonicClock] and [AppLogger].
 *
 * Grouped in one file because each is a thin adapter over a platform call. They
 * live in this module rather than in `:android:app` so library modules can depend
 * on them without depending on the application, which is the dependency direction
 * the KMP migration is undoing.
 */

/**
 * Wall-clock [TimeSource].
 *
 * Note this is *not* a substitute for [MonotonicClock]. The two answer different
 * questions and conflating them is a real bug rather than a simplification: a wall
 * clock can jump backwards or forwards when a device syncs time or crosses a
 * timezone, so it is wrong for throttling and intervals, while
 * `SystemClock.elapsedRealtime()` cannot tell you what time it is and is wrong for
 * anything a user sees. `WatchProgressStore` throttles, so it injects
 * [MonotonicClock]; code that shows a timestamp injects [TimeSource].
 */
class AndroidTimeSource : TimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}

// `MonotonicClock` is a *contract*, so it is declared in :android:platform-core
// next to `TimeSource` and only implemented here. Declaring it in this module
// would have inverted the dependency: the portable side that needs to inject a
// clock would have to depend on the Android library to name the type.

/** The Android [MonotonicClock]. */
class AndroidMonotonicClock : MonotonicClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * [AppLogger] over `android.util.Log`.
 *
 * `debug` is gated on the *application's* `FLAG_DEBUGGABLE` rather than on a
 * `BuildConfig.DEBUG` of its own. A library's `BuildConfig.DEBUG` reflects how that
 * library was built, not how the app that links it was built, so a release app
 * linking a debug-built library would emit debug lines — which is backwards.
 * There is no `BuildConfig` here to use anyway: AGP 9 does not generate one for
 * Kotlin Multiplatform library modules, and this module is deliberately not one.
 *
 * The caller's `tag` is passed through unchanged. The existing call sites each have
 * a per-file `TAG` constant naming the subsystem, and prefixing it here would stop
 * those tags matching the source.
 */
class AndroidAppLogger(context: Context) : AppLogger {

    private val debuggable: Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun debug(tag: String, message: String) {
        if (debuggable) Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}

/** Convenience for call sites that already hold an [Application]. */
fun Application.appLogger(): AndroidAppLogger = AndroidAppLogger(this)
