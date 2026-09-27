package com.crispy.tv.domain.watch

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND
private const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR

/**
 * Portable ISO-8601 handling for [findNextEpisode], replacing `java.time`.
 *
 * Only what the release-date contract exercises is supported: a calendar date
 * (`YYYY-MM-DD`) and an instant (`YYYY-MM-DDTHH:mm[:ss[.fff]]` closed by `Z` or
 * a numeric offset). Anything else returns null, which the caller treats exactly
 * as it treated the previous `Instant.parse` / `LocalDate.parse` failure.
 *
 * Instants resolve to epoch milliseconds. The old comparison ran at nanosecond
 * precision, but `findNextEpisode` receives its clock as epoch milliseconds, so
 * a release instant finer than a millisecond was never distinguishable in that
 * comparison. The fraction is therefore truncated, not rounded.
 */
internal fun parseIso8601InstantToEpochMillis(value: String): Long? {
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
