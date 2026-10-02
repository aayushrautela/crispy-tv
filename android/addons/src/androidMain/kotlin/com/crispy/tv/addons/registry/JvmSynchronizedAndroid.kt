package com.crispy.tv.addons.registry

/**
 * The Android half of [JvmSynchronized]: on this target the annotation *is*
 * `kotlin.jvm.Synchronized`, so the five synchronized methods on
 * `MetadataAddonRegistry` keep the mutual exclusion they had when the class was
 * `androidMain`. No other target actualizes it, which is what
 * `@OptionalExpectation` is for, and what the expectation's KDoc says.
 */
actual typealias JvmSynchronized = kotlin.jvm.Synchronized