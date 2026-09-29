package com.crispy.tv.home

import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.TimeSource

/**
 * The collaborators `CalendarService` and `UpNextService` take, in doubles.
 *
 * All three are interfaces already, which is what makes these two services
 * testable at all. Each double here is the smallest thing that can be asked a
 * question: the resolver answers from a value and counts, the logger records
 * what it was told, the clock is a constant. Nothing here is a mock framework --
 * a silent default would let a test pass without the code under test running.
 */

/** A [BackendContextResolver] that answers from a value and counts its calls. */
internal class FixedBackendContextResolver(
    private var context: BackendContext?,
) : BackendContextResolver {
    var resolveCalls = 0
        private set

    fun answerWith(accessToken: String, profileId: String) = apply {
        context = BackendContext(accessToken = accessToken, profileId = profileId)
    }

    fun answerWithNothing() = apply {
        context = null
    }

    override suspend fun resolve(): BackendContext? {
        resolveCalls++
        return context
    }

    override fun clear() {
        context = null
    }
}

/** An [AppLogger] that records every line, so a test can assert on the failure path. */
internal class RecordingAppLogger : AppLogger {
    val lines = mutableListOf<String>()
    val warnings = mutableListOf<Triple<String, String, Throwable?>>()

    override fun debug(tag: String, message: String) {
        lines += "D/$tag $message"
    }

    override fun info(tag: String, message: String) {
        lines += "I/$tag $message"
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        lines += "W/$tag $message"
        warnings += Triple(tag, message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        lines += "E/$tag $message"
        warnings += Triple(tag, message, throwable)
    }
}

/** A [TimeSource] pinned to a reading, so a "now" fallback is deterministic. */
internal class FixedTimeSource(private val nowMs: Long) : TimeSource {
    override fun nowMs(): Long = nowMs
}
