package com.crispy.tv.platform

/**
 * Structured logging seam, so `commonMain` never calls `android.util.Log`.
 *
 * [tag] is the caller's subsystem, matching the existing per-file `TAG` constants.
 */
interface AppLogger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}
