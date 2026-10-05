package com.crispy.tv.library

/**
 * The device's UTC offset in milliseconds, read fresh on every call.
 *
 * This is the `jvmMain` half of the library graph's `deviceUtcOffsetMillis` slot, and it
 * is `jvmMain` rather than `commonMain` because `java.time` has no common spelling. The
 * `dependsOn` edges in `build.gradle.kts` are what let both the Android and the desktop
 * target compile this file instead of each writing its own copy.
 *
 * `java.time`'s `ZoneId.systemDefault()` was called once per history entry and once
 * per watchlist entry, and this is a function rather than a value for exactly that
 * reason: an offset remembered at composition time is silently wrong for the hours
 * either side of a daylight-saving change, and a screen that groups rows by month
 * is exactly where being an hour out shows.
 *
 * It lives here rather than in the shared formatter bundle on purpose. A formatter
 * that applied its own zone would shift the epoch value a second time -- the
 * timestamps are already instants, and the caller chose the zone deliberately.
 */
fun deviceUtcOffsetMillis(): Long =
    java.time.ZoneId.systemDefault()
        .rules
        .getOffset(java.time.Instant.now())
        .totalSeconds * 1_000L
