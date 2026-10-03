package com.crispy.tv.person

import com.crispy.tv.domain.watch.parseIso8601DateToEpochMillis

/**
 * The `BORN` row's value: the birth date rendered for this device, or null when the
 * person has none worth showing.
 *
 * This is the whole of what used to be `PersonDetailsRoute`'s private `formatBirthday`,
 * split at the platform boundary. The rules — trim, treat blank as absent, parse, and
 * **fall back to the raw text** — are here because they are decisions and they are
 * testable from `commonTest`; only the rendering crosses, as a no-default `format`.
 * `LocaleDateFormatters` states the same division of labour for the three dates
 * `DetailsHeader` shows, and the reason is the same one: `DateTimeFormatter.ofPattern(
 * "MMMM d, yyyy", Locale.getDefault())` follows the device's **language**, so it cannot
 * be replaced by `:core-domain`'s locale-invariant `formatIso8601LongDate` without
 * printing a string the user has not learned to read.
 *
 * ## The fallback is a decision, not an error path
 *
 * An unparseable value renders **verbatim**, and that is user-visible: a backend that
 * stored `"unknown"`, or `"circa 1912"`, shows exactly that rather than an empty row.
 * So the fallback is not "give up", it is "we could not do better than the text we were
 * given", and the only thing that must not happen is a crash or an empty cell.
 *
 * ## One input is answered differently than it used to be
 *
 * The old body was `LocalDate.parse(raw).format(...)`, and `LocalDate.parse` reads an
 * ISO date with a **sign-prefixed or over-long year** (`"+1981-09-04"`, `"12345-01-01"`),
 * because `ISO_LOCAL_DATE` gives its year field `SignStyle.EXCEEDS_PAD` over 4 to 10
 * digits. `parseIso8601DateToEpochMillis` requires exactly ten characters, so those
 * inputs now take the fallback and render as the raw text instead of being reformatted.
 *
 * This is a narrowing, and it is recorded rather than papered over: no birth date is
 * signed or five-digit, and the alternative was carrying a branch for a shape nothing
 * sends. `aSignedOrOverlongYearNowTakesTheFallbackRatherThanBeingReformatted` pins it,
 * so the difference is a decision on the record instead of an accident nobody noticed.
 */
internal fun birthdayText(
    value: String?,
    format: (epochMillis: Long) -> String,
): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) {
        return null
    }
    val epochMillis = parseIso8601DateToEpochMillis(raw) ?: return raw
    return format(epochMillis)
}