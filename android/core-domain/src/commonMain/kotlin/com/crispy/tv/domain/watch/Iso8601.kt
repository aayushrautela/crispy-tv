package com.crispy.tv.domain.watch

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND
private const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR

/**
 * Portable ISO-8601 handling, replacing `java.time`.
 *
 * Parsing covers only what the release-date contract exercises: a calendar date
 * (`YYYY-MM-DD`) and an instant (`YYYY-MM-DDTHH:mm[:ss[.fff]]` closed by `Z` or
 * a numeric offset). Anything else returns null, which the caller treats exactly
 * as it treated the previous `Instant.parse` / `LocalDate.parse` failure.
 *
 * Instants resolve to epoch milliseconds. The old comparison ran at nanosecond
 * precision, but `findNextEpisode` receives its clock as epoch milliseconds, so
 * a release instant finer than a millisecond was never distinguishable in that
 * comparison. The fraction is therefore truncated, not rounded.
 *
 * Formatting goes the other way, for callers that send an instant to the backend
 * as text. That is a separate concern from the release-date contract, so it is a
 * separate top-level function rather than a mode of this one.
 */
fun parseIso8601InstantToEpochMillis(value: String): Long? {
    if (value.length < 19) return null

    val year = value.readDigits(0, 4) ?: return null
    if (value[4] != '-') return null
    val month = value.readDigits(5, 7) ?: return null
    if (value[7] != '-') return null
    val day = value.readDigits(8, 10) ?: return null
    if (!isValidDate(year, month, day)) return null

    if (value[10] != 'T' && value[10] != 't' && value[10] != ' ') return null
    val hour = value.readDigits(11, 13) ?: return null
    if (value[13] != ':') return null
    val minute = value.readDigits(14, 16) ?: return null

    var cursor = 16
    var second = 0
    if (cursor < value.length && value[cursor] == ':') {
        second = value.readDigits(cursor + 1, cursor + 3) ?: return null
        cursor += 3
    }

    var fractionMillis = 0
    if (cursor < value.length && value[cursor] == '.') {
        cursor++
        val fractionStart = cursor
        while (cursor < value.length && value[cursor].isDigit()) {
            cursor++
        }
        if (cursor == fractionStart) return null
        fractionMillis = value.readFractionAsMillis(fractionStart, cursor)
    }

    if (hour > 23 || minute > 59 || second > 59) return null

    val offsetMinutes = value.readUtcOffsetMinutes(cursor) ?: return null

    val localMillis = epochDayOf(year, month, day) * MILLIS_PER_DAY +
        hour * MILLIS_PER_HOUR +
        minute * MILLIS_PER_MINUTE +
        second * MILLIS_PER_SECOND +
        fractionMillis
    return localMillis - offsetMinutes * MILLIS_PER_MINUTE
}

/**
 * Parses a strict `YYYY-MM-DD` calendar date into days since 1970-01-01.
 */
internal fun parseIso8601DateToEpochDay(value: String): Long? {
    if (value.length != 10) return null
    if (value[4] != '-' || value[7] != '-') return null

    val year = value.readDigits(0, 4) ?: return null
    val month = value.readDigits(5, 7) ?: return null
    val day = value.readDigits(8, 10) ?: return null
    if (!isValidDate(year, month, day)) return null

    return epochDayOf(year, month, day)
}

/**
 * Formats [epochMillis] as a UTC ISO-8601 instant, byte-for-byte as
 * `java.time.Instant.ofEpochMilli(ms).toString()` renders it.
 *
 * ## Why byte-for-byte
 *
 * This value goes to the backend as `occurredAt`, so the previous JVM
 * implementation is the wire contract. Emitting `1970-01-01T00:00:00.000Z` where
 * the old code sent `1970-01-01T00:00:00Z` would be a silent format change on a
 * field the server reads, and one that only shows up in production data. The
 * omitted-zero-fraction rule is therefore reproduced deliberately, not
 * incidentally:
 *
 * - millisecond precision 0 renders no fraction: `2000-02-29T00:00:00Z`
 * - otherwise exactly three digits: `2000-02-29T00:00:00.001Z`
 *
 * `Instant` also renders 6 or 9 fraction digits, but it only has those to offer
 * when the instant actually carries that precision. An instant built from epoch
 * milliseconds never does, so three digits is the only fraction this can produce.
 *
 * Pre-epoch instants work because the day and the time-of-day are computed with
 * floor semantics separately: `utcEpochDayOf` floors, so the remainder
 * `epochMillis - day * MILLIS_PER_DAY` is always in `0 until 86400000` and
 * formats as a positive time rather than a negative one.
 */
/**
 * The UTC midnight at the start of the calendar date in [value], in epoch
 * milliseconds, or null if [value] is not a `YYYY-MM-DD` date.
 *
 * The portable replacement for `LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC)
 * .toInstant().toEpochMilli()`, which is the shape the calendar code used. A release
 * date with no time on it is read as midnight **UTC**, not in the device's zone, and
 * that choice is load-bearing: reading it locally would move a release to the
 * previous day for anyone west of UTC. Preserved exactly rather than tidied.
 *
 * Accepts a full instant and truncates to its date, because the callers pass
 * `value.take(10)` today and a backend may send either shape.
 */
fun parseIso8601DateToEpochMillis(value: String): Long? {
    val epochDay = parseIso8601DateToEpochDay(value) ?: return null
    return epochDay * MILLIS_PER_DAY
}

/**
 * The three-letter month label for a `YYYY-MM-DD` date: `Jan`, `Feb`, …, `Dec`.
 *
 * The portable replacement for `LocalDate.parse(s).month.name.take(3).lowercase()
 * .replaceFirstChar { it.uppercase() }`, which is how a calendar badge reads "Mar 14".
 * Returns null for anything that is not a valid date, matching the `try`/`catch` the
 * old expression sat in.
 */
fun iso8601MonthLabel(value: String): String? {
    val month = parseIso8601MonthNumber(value) ?: return null
    return MONTH_LABELS.getOrNull(month - 1)
}

/** The 1-12 month number of a `YYYY-MM-DD` date, or null if it is not a valid date. */
private fun parseIso8601MonthNumber(value: String): Int? {
    if (value.length < 10) return null
    val year = value.readDigits(0, 4) ?: return null
    // The separators are checked, unlike a bare "ten digits is a date" reading.
    // Without this, "2024010526" parsed as 2024-01-05 and rendered a label for a
    // string that LocalDate rejects. The length check stays a lower bound rather
    // than equality because the one caller passes a release date that may carry
    // an instant after the date, and reads the month off the first ten
    // characters -- the same truncation its sibling performs.
    if (value[4] != '-' || value[7] != '-') return null
    val month = value.readDigits(5, 7) ?: return null
    val day = value.readDigits(8, 10) ?: return null
    if (month !in 1..12) return null
    // Validate the day as well as the month, so "2024-02-31" is rejected the way
    // LocalDate rejects it rather than labelling it "Feb".
    if (!isValidDate(year, month, day)) return null
    return month
}

/**
 * Renders a `YYYY-MM-DD` date the way `java.time` renders it under the
 * `MMM d, yyyy` pattern with `Locale.US`: `Jan 5, 2026`.
 *
 * ## Why byte-for-byte
 *
 * This is the portable replacement for
 * `LocalDate.parse(s).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))`,
 * which is a user-visible label, so a drift here is a visible text change rather
 * than a wrong number. It is nevertheless pinned to the JVM's output, verified on
 * JDK 21 for `2026-01-05`, `2024-02-29`, `2026-12-31` and `9999-12-31`.
 *
 * Two details are not obvious and both come from the JVM:
 *
 * - **The year is a year-of-era, not a year.** `0000-01-01` formats as
 *   `Jan 1, 0001`, not `Jan 1, 0000`, because year 0 belongs to the era before
 *   the common one and its year-of-era is 1. [formatYearOfEra] reproduces that.
 * - **A year needing more than four digits renders with a sign**: `+10000-01-01`
 *   formats as `Jan 1, +10000`. This parser accepts exactly four digits, so that
 *   input is rejected rather than rendered — a deliberate difference, since the
 *   input cannot reach here through a caller that truncates to ten characters.
 *
 * [MONTH_LABELS] is the same table [iso8601MonthLabel] uses, and it is the
 * `Locale.US` set: `Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec`, with no
 * abbreviated forms carrying a period.
 */
fun formatIso8601LongDate(value: String): String? {
    val epochDay = parseIso8601DateToEpochDay(value) ?: return null
    val (year, month, dayOfMonth) = civilFromEpochDay(epochDay)
    val label = MONTH_LABELS[month - 1]
    return "$label $dayOfMonth, ${formatYearOfEra(year)}"
}

/**
 * The month and day of [value], as `MMM d` renders it: `Jan 5`, `Dec 31`.
 *
 * The same [MONTH_LABELS] table and the same unpadded day of month that
 * [formatIso8601LongDate] uses, without the year. It exists because a badge on an
 * episode row wants the day a user recognises and not the year they already know,
 * and because the alternative was a `java.time` call in a `commonMain` file -- which
 * does not compile for a Kotlin/Native target, and cannot be seen by an import scan
 * when it is written fully qualified.
 *
 * A value this cannot read is `null` rather than rendered, exactly as
 * [formatIso8601LongDate] does. It does **not** truncate: callers that hold a
 * longer ISO value are expected to pass the first ten characters, which is the
 * arrangement `formatIso8601LongDate` documents for itself.
 */
fun formatIso8601MonthDay(value: String): String? {
    val epochDay = parseIso8601DateToEpochDay(value) ?: return null
    val (_, month, dayOfMonth) = civilFromEpochDay(epochDay)
    return "${MONTH_LABELS[month - 1]} $dayOfMonth"
}

/**
 * The year-of-era, zero-padded to four digits, as `MMM d, yyyy` renders it.
 *
 * A proleptic year at or before 0 sits in the era before the common one, where
 * the year-of-era is one greater, so year 0 prints as 1 and never as 0. Years
 * that need more than four digits carry an explicit sign, which is how `+10000`
 * comes out of the same pattern.
 */
private fun formatYearOfEra(year: Int): String {
    val yearOfEra = if (year > 0) year else year + 1
    val sign = if (yearOfEra < 0) "-" else ""
    return sign + yearOfEra.toString().padStart(4, '0')
}

/**
 * English three-letter month names.
 *
 * A table rather than a derivation. The label is user-visible text, so deriving it
 * from a locale-sensitive source would make it drift per device — and this file
 * already hard-codes its grammar for the same reason: the output is a contract.
 */
private val MONTH_LABELS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/**
 * Formats [epochMillis] to a UTC ISO-8601 instant, byte-for-byte as
 * `java.time.Instant.ofEpochMilli(ms).toString()` renders it.
 */
fun formatIso8601Instant(epochMillis: Long): String {
    val day = utcEpochDayOf(epochMillis)
    val millisIntoDay = epochMillis - day * MILLIS_PER_DAY
    val (year, month, dayOfMonth) = civilFromEpochDay(day)
    val hour = millisIntoDay / MILLIS_PER_HOUR
    val minute = (millisIntoDay / MILLIS_PER_MINUTE) % 60L
    val second = (millisIntoDay / MILLIS_PER_SECOND) % 60L
    val millisOfSecond = millisIntoDay % MILLIS_PER_SECOND
    val fraction = if (millisOfSecond == 0L) "" else ".${millisOfSecond.toString().padStart(3, '0')}"
    return buildString(24) {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(dayOfMonth.toString().padStart(2, '0'))
        append('T')
        append(hour.toString().padStart(2, '0'))
        append(':')
        append(minute.toString().padStart(2, '0'))
        append(':')
        append(second.toString().padStart(2, '0'))
        append(fraction)
        append('Z')
    }
}

/**
 * The `"yyyy-MM"` civil month [epochMillis] falls in, at a fixed UTC offset.
 *
 * Replaces `YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone)).toString()`.
 * The zone arrives as its offset in milliseconds rather than as a `ZoneId`,
 * because the device's zone is the one part of that expression which cannot be
 * pure: two devices reading the same instant on either side of a month boundary
 * must produce different keys, and a caller which cannot state its offset cannot
 * say which month it meant. So the caller decides, at the composition root, where
 * the platform's answer is available -- and the decision is then testable.
 */
fun civilMonthKeyFromEpochMillis(epochMillis: Long, utcOffsetMillis: Long): String {
    val (year, month, _) = civilFromEpochDay(utcEpochDayOf(epochMillis + utcOffsetMillis))
    return monthKeyOf(year, month)
}

/**
 * The `"yyyy-MM"` civil month an ISO-8601 instant falls in, at a fixed UTC offset.
 *
 * Replaces `YearMonth.from(Instant.parse(value).atZone(zone)).toString()`. A value
 * this file's parser rejects returns null, which the caller treats exactly as it
 * treated the old `Instant.parse` failure -- the convention
 * [parseIso8601InstantToEpochMillis] already documents.
 */
fun civilMonthKey(value: String, utcOffsetMillis: Long): String? =
    civilMonthKeyFromEpochMillis(
        parseIso8601InstantToEpochMillis(value) ?: return null,
        utcOffsetMillis,
    )

/**
 * The month before [monthKey], with the year decremented across January.
 *
 * Replaces `YearMonth.parse(key).minusMonths(1).toString()`. A key which is not
 * `"yyyy-MM"` with a month in `1..12` returns null rather than throwing, because
 * the caller only ever holds a key this file produced or a literal it recognises,
 * and a library function that throws on a value it might itself have been handed
 * is worse than one that says "not a month". January of year `0000` has no
 * predecessor for the same reason and also returns null.
 */
fun previousMonthKey(monthKey: String): String? {
    if (monthKey.length != 7 || monthKey[4] != '-') return null
    val year = monthKey.readDigits(0, 4) ?: return null
    val month = monthKey.readDigits(5, 7) ?: return null
    if (month !in 1..12) return null
    // The one case the padding cannot express: December of year -1, which would need
    // a sign and a five-digit field, and so could not be read back by this function.
    // Returning null says "there is no previous month" rather than minting a key
    // nothing can parse.
    if (month == 1 && year == 0) return null
    return if (month == 1) monthKeyOf(year - 1, 12) else monthKeyOf(year, month - 1)
}

/**
 * A `"yyyy-MM"` key.
 *
 * The year handling is deliberately not shared with [formatYearOfEra]: a month key
 * is a grouping token which is compared for equality and parsed back again, so it
 * has to round-trip through [previousMonthKey], and an era-shifted or signed year
 * would not. [parseIso8601InstantToEpochMillis] reads exactly four digits, so every
 * key it can produce is `0000`-`9999` and plain padding is the whole job; the one
 * value outside that range is refused by [previousMonthKey] rather than minted.
 */
private fun monthKeyOf(year: Int, month: Int): String =
    year.toString().padStart(4, '0') + "-" + month.toString().padStart(2, '0')

/**
 * The proleptic Gregorian date for a day count from 1970-01-01.
 *
 * Howard Hinnant's `civil_from_days`, the inverse of [epochDayOf]. Exact for the
 * full range this module deals in, including negative day counts.
 */
private fun civilFromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
    val shifted = epochDay + 719_468L
    val era = (if (shifted >= 0L) shifted else shifted - 146_096L) / 146_097L
    val dayOfEra = shifted - era * 146_097L
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val shiftedMonth = (5L * dayOfYear + 2L) / 153L
    val dayOfMonth = (dayOfYear - (153L * shiftedMonth + 2L) / 5L + 1L).toInt()
    val month = (if (shiftedMonth < 10L) shiftedMonth + 3L else shiftedMonth - 9L).toInt()
    return Triple((if (month <= 2) year + 1L else year).toInt(), month, dayOfMonth)
}

/** The UTC calendar day containing [epochMillis]. */
internal fun utcEpochDayOf(epochMillis: Long): Long {
    val quotient = epochMillis / MILLIS_PER_DAY
    val hadRemainder = epochMillis % MILLIS_PER_DAY != 0L
    return if (hadRemainder && epochMillis < 0L) quotient - 1 else quotient
}

/**
 * Days from 1970-01-01 to the given proleptic Gregorian date.
 *
 * Howard Hinnant's `days_from_civil`, which is exact for the full Int range
 * this module deals in.
 */
private fun epochDayOf(year: Int, month: Int, day: Int): Long {
    val shiftedYear = if (month <= 2) year - 1 else year
    val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
    val yearOfEra = shiftedYear - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
}

private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
    if (month !in 1..12) return false
    return day in 1..daysInMonth(year, month)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> 0
}

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

/**
 * Reads a decimal run of exactly [end] - [start] digits, or null if any
 * character in the range is not a digit.
 */
private fun String.readDigits(start: Int, end: Int): Int? {
    if (end > length) return null
    var result = 0
    for (index in start until end) {
        val char = this[index]
        if (char < '0' || char > '9') return null
        result = result * 10 + (char - '0')
    }
    return result
}

/**
 * Reads the fraction between [start] and [endExclusive] as milliseconds,
 * truncating rather than rounding.
 */
private fun String.readFractionAsMillis(start: Int, endExclusive: Int): Int {
    var millis = 0
    var place = 100
    for (index in start until endExclusive) {
        if (place == 0) break
        millis += (this[index] - '0') * place
        place /= 10
    }
    return millis
}

/**
 * Reads the UTC offset that closes an instant, returning minutes to subtract.
 *
 * Accepts `Z`, and `±HH`, `±HHMM` and `±HH:MM` / `±HH:MM:SS`, which is the set
 * `Instant.parse` accepted. A missing offset is rejected, matching it too.
 */
private fun String.readUtcOffsetMinutes(cursor: Int): Int? {
    if (cursor >= length) return null

    if (this[cursor] == 'Z' || this[cursor] == 'z') {
        return if (cursor + 1 == length) 0 else null
    }

    val sign = when (this[cursor]) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    var position = cursor + 1
    if (position + 2 > length) return null

    val hours = readDigits(position, position + 2) ?: return null
    position += 2

    var minutes = 0
    var seconds = 0
    if (position < length) {
        if (this[position] == ':') position++
        minutes = readDigits(position, position + 2) ?: return null
        position += 2
        if (position < length) {
            if (this[position] == ':') position++
            seconds = readDigits(position, position + 2) ?: return null
            position += 2
        }
    }

    if (position != length) return null
    if (hours > 18 || minutes > 59 || seconds > 59) return null
    return sign * (hours * 60 + minutes)
}
