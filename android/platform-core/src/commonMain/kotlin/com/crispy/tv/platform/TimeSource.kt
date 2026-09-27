package com.crispy.tv.platform

/**
 * Wall-clock seam. Inject this so contract tests can pin `nowMs` and stay
 * deterministic; never read the system clock directly from domain code.
 */
fun interface TimeSource {
    fun nowMs(): Long
}
