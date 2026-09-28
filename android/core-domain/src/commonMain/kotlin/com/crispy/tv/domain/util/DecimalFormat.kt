package com.crispy.tv.domain.util

/**
 * Formats [value] to one decimal place, byte-for-byte as
 * `String.format(Locale.US, "%.1f", value)` does.
 *
 * ## Why not `value * 10` and round
 *
 * Because `%.1f` does not round the binary value, it rounds the *shortest decimal
 * representation* of it. Those differ, and the difference is visible:
 *
 * - `9.95` is really 9.94999999999999928945... in binary. Rounding the binary
 *   value gives `9.9`; the shortest representation is `9.95`, and `%.1f` gives
 *   `10.0`.
 *
 * A rating of 9.95 rendering as 9.9 on one platform and 10.0 on another is exactly
 * the sort of drift the contract suite exists to prevent, so this works on the
 * decimal string instead: it takes the digits `Double.toString` produces, which is
 * the same shortest representation the JVM formatter uses, and rounds those.
 *
 * ## How the digits are laid out
 *
 * `Double.toString` yields a mantissa and a power of ten, e.g. `0.3333333333333333`
 * or `1.0E-5`. Both are reduced to one flat digit run plus a decimal-point position,
 * counted of digits to the left of the point:
 *
 * - `0.3333333333333333` -> digits `03333333333333333`, point at 1
 * - `1.0E-5` -> digits `10`, point at 1 + (-5) = -4
 *
 * The tenths digit is then the digit at `point`, the rounding digit the one after
 * it, and the integer part is everything before `point`. A negative point means the
 * value is below 1 and the integer part is zero.
 *
 * ## Rounding
 *
 * Half-up, matching `%f`. So `2.25` is `2.3` and `8.7499999` is `8.7`, with no
 * float comparison anywhere in the decision. Carrying is explicit: `9.95` must
 * become `10.0`, not a lost integer part.
 */
fun formatOneDecimal(value: Double): String {
    require(value.isFinite()) { "formatOneDecimal requires a finite value, was $value" }

    val negative = value < 0.0
    val magnitude = if (negative) -value else value
    val repr = magnitude.toString()

    val exponentIndex = repr.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (exponentIndex < 0) repr else repr.substring(0, exponentIndex)
    val exponent = if (exponentIndex < 0) 0 else repr.substring(exponentIndex + 1).toInt()

    val dotIndex = mantissa.indexOf('.')
    val integerDigits = if (dotIndex < 0) mantissa else mantissa.substring(0, dotIndex)
    val fractionDigits = if (dotIndex < 0) "" else mantissa.substring(dotIndex + 1)

    val digits = integerDigits + fractionDigits
    val point = integerDigits.length + exponent

    val tenths = (digitAt(digits, point) + if (digitAt(digits, point + 1) >= 5) 1 else 0)
    val carriedTenths = tenths % 10
    val whole =
        if (point <= 0) {
            0L
        } else {
            buildWhole(digits, point) + tenths / 10
        }

    val sign = if (negative && (whole != 0L || carriedTenths != 0)) "-" else ""
    return "$sign$whole.$carriedTenths"
}

/** The digit at [index], or 0 when the position is past the end of the digit run. */
private fun digitAt(digits: String, index: Int): Int =
    if (index < 0 || index >= digits.length) 0 else digits[index] - '0'

/**
 * The integer part: the digits before the decimal point, right-aligned.
 *
 * A positive exponent leaves the point beyond the digits that were printed
 * (`1.0E20` prints only `10`), so trailing zeros are appended rather than the value
 * being truncated.
 */
private fun buildWhole(digits: String, point: Int): Long {
    if (point >= MAX_WHOLE_DIGITS) return Long.MAX_VALUE / 10
    var result = 0L
    for (index in 0 until point) {
        val digit = digitAt(digits, index)
        if (result > (Long.MAX_VALUE - digit) / 10) return Long.MAX_VALUE / 10
        result = result * 10 + digit
    }
    return result
}

/**
 * Beyond this the value cannot be represented as a `Long` of tenths anyway, and
 * `%.1f` would print a number no rating can reach. Clamping keeps the arithmetic
 * defined rather than overflowing into a negative sign.
 */
private const val MAX_WHOLE_DIGITS = 18
