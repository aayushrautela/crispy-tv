package com.crispy.tv.platform

/**
 * A monotonic clock, for intervals, elapsed time and throttling.
 *
 * Deliberately distinct from [TimeSource], and the two must not be merged. They
 * answer different questions:
 *
 * - [TimeSource] is "what time is it now", in UTC epoch milliseconds. It can jump
 *   forwards or backwards when the device syncs its clock or crosses a timezone.
 * - [MonotonicClock] is "how long since boot". It never jumps and never goes
 *   backwards, but it has no relationship to the wall clock and is meaningless
 *   across a reboot.
 *
 * Swapping one for the other's job is a real bug rather than a simplification. A
 * throttle driven by [TimeSource] misbehaves the moment a device's clock is set
 * — if the clock jumps forward an hour, a once-a-minute notification would not
 * fire for an hour; if it jumps back, it would fire continuously. A duration
 * computed from [MonotonicClock] is correct regardless, because the only thing
 * that matters is the difference between two readings.
 *
 * Apple has no exact analogue of `SystemClock.elapsedRealtime()`: the nearest is
 * a continuous clock the system also uses, so the implementations there document
 * what they actually return. That difference is precisely why this is a named
 * contract and not a second method on [TimeSource] — the semantic gap is real and
 * per-platform, and hiding it would let code assume a precision Apple cannot
 * provide.
 */
fun interface MonotonicClock {
    /**
     * Milliseconds from an arbitrary but fixed origin, monotonically
     * non-decreasing and unaffected by wall-clock changes.
     */
    fun elapsedMs(): Long
}
