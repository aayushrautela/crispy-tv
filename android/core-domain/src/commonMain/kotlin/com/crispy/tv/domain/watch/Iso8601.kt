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
