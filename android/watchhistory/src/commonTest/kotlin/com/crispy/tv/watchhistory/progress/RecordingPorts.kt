package com.crispy.tv.watchhistory.progress

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.KeyValueStore

/**
 * The `:platform-core` ports, faked.
 *
 * ## Why these are in the test's own source set
 *
 * All four are `commonMain` interfaces of `:android:platform-core`, which is a
 * **different module** from `:android:watchhistory`, so none of them is
 * `internal` and a test here can implement them directly. There is nothing to
 * widen and no port to add: the repo's recorded rule is that a `commonMain`
 * class three repositories depend on must be an interface precisely so a test
 * can hand its caller something, and these already are.
 *
 * ## Why the keys are sorted, which is a decision and not a detail
 *
 * [RecordingKeyValueStore.keys] returns a `TreeSet` rather than whatever map
 * backs it. `WatchProgressStore.getAllWatchProgress` calls `store.keys().sorted()`
 * itself, so it does not depend on this — **but two of its cases here do**,
 * because a suite that asserts an order has to control the order it is given
 * rather than inherit it. `HashMap` key order on the JVM is deterministic for a
 * given set of keys but is not the order anyone would predict, and it is
 * specified by nothing.
 *
 * ## Why [RecordingAppLogger] records instead of asserting
 *
 * `getWatchProgress` logs a warning and returns `null` when a stored blob is
 * malformed, and that is the behaviour under test. Asserting on the returned
 * `null` is the assertion; the log is *how* the class says it, and a test that
 * asserted the message text would be asserting a string the class invented.
 * [RecordingAppLogger.warnings] exists so a case can say the malformed blob was
 * logged **and** not care what it said.
 */
class RecordingKeyValueStore(
    initial: Map<String, String> = emptyMap(),
) : KeyValueStore {
    private val values = LinkedHashMap(initial)

    /** Every key ever written, in order. A write of the same value still counts. */
    val writes: MutableList<String> = mutableListOf()

    override fun getString(key: String, defaultValue: String?): String? =
        values[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        values[key] = value
        writes += key
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
    override fun putBoolean(key: String, value: Boolean) = Unit
    override fun getInt(key: String, defaultValue: Int): Int = defaultValue
    override fun putInt(key: String, value: Int) = Unit
    override fun getFloat(key: String, defaultValue: Float): Float = defaultValue
    override fun putFloat(key: String, value: Float) = Unit
    override fun contains(key: String): Boolean = key in values

    override fun keys(): Set<String> = values.keys.toSortedSet()

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() {
        values.clear()
    }

    /** Direct access for arranging state the port has no setter for. */
    fun seed(key: String, value: String) {
        values[key] = value
    }

    fun snapshot(): Map<String, String> = values.toMap()
}

class RecordingAppLogger : AppLogger {
    val warnings: MutableList<String> = mutableListOf()

    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        warnings += message
    }

    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

/**
 * A [MonotonicClock] whose reading moves by a fixed step on every call.
 *
 * The repo's recorded rule: a fixed clock makes two readings of the same call
 * identical, so any assertion about a *gap* between them is vacuous. The only
 * place `WatchProgressStore` reads it twice in one operation is the debounce
 * gate, and a step is what makes the gate's two branches reachable at all.
 */
class TestMonotonicClock(
    private val startMs: Long = 0L,
    private val stepMs: Long = 0L,
) : com.crispy.tv.platform.MonotonicClock {
    private var current = startMs

    override fun elapsedMs(): Long {
        val reading = current
        current += stepMs
        return reading
    }
}

/** A [TimeSource] that returns exactly what a case sets. */
class FixedTimeSource(var now: Long = 0L) : com.crispy.tv.platform.TimeSource {
    override fun nowMs(): Long = now
}
