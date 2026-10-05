package com.crispy.tv.person

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the epoch-millisecond UTC midnight of a birth date as `September 4, 1981`.
 *
 * This is the `jvmMain` half of `PersonBody`'s `formatBirthday` slot, and it
 * exists because the *pattern* follows the device's language. `LocaleDateFormatters`
 * says this once for its own three renderings and the reason generalises: `:core-domain`'s
 * `formatIso8601LongDate` is portable and locale-invariant, so routing this through it
 * would print a different string than the user has learned to read — a behaviour change
 * dressed as a port. It is `jvmMain` rather than `commonMain` because `Locale` and
 * `java.time` are JVM types, and `jvmMain` is the source set both the Android and the
 * desktop target compile.
 *
 * **It takes a `Long`, not a `LocalDate`.** A `java.time` type in this signature would
 * be the pin the whole split exists to discharge, so the calendar date crosses as an
 * epoch-millisecond number and the `java.time` work happens here. The unit is epoch
 * *millis*, the same unit `LocaleDateFormatters.date` and `.time` already use, so the
 * three `(Long) -> String` slots in this codebase do not need to be told apart; epoch
 * days would have been the smaller number and the easier mistake to make.
 *
 * **The zone is UTC, and it has to be.** The value handed in is midnight UTC *on the
 * birthday* (see `parseIso8601DateToEpochMillis` in `:core-domain`, whose own KDoc
 * records that reading such a date in the device's zone would move a release date to the
 * previous day west of UTC). The original code had no zone at all — `LocalDate.parse(raw)`
 * yields calendar fields — so formatting through UTC reproduces exactly the date that
 * arrived, while formatting through the device zone would be a second, invisible shift.
 */
fun formatBirthdayDate(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))