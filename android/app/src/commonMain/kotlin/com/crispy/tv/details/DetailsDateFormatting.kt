package com.crispy.tv.details

import com.crispy.tv.domain.watch.formatIso8601LongDate

/**
 * Renders a release date for display, or null when there is nothing to render.
 *
 * The portable replacement for
 * `LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))`,
 * with the JVM's own parsing and the formatting contract preserved exactly:
 *
 * - the value is trimmed first, and a blank one is null;
 * - anything at least ten characters long is truncated to its date, so a
 *   release date carrying a time is read from the date part;
 * - a value that does not parse is returned **unchanged and untrimmed**, not
 *   null. A backend that sends an unexpected shape should still have it shown
 *   to the user rather than have the row disappear, which is what the previous
 *   `catch (_: Throwable) { date }` did.
 *
 * The formatting itself lives in `:core-domain` as [formatIso8601LongDate],
 * pinned to the JVM's output. What stays here is the fallback policy, which is
 * a presentation decision about an unparseable value rather than a property of
 * a date, and the two are separated for that reason.
 */
internal fun formatLongDate(date: String?): String? {
    val raw = date?.trim().orEmpty()
    if (raw.isBlank()) return null

    val iso = if (raw.length >= 10) raw.take(10) else raw
    return formatIso8601LongDate(iso) ?: date
}
