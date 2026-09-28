package com.crispy.tv.home

/**
 * A home list opts into the numbered "TOP 10" billboard treatment by its raw
 * machine list key (`kind`) matching the `top10[-_]*` convention, e.g.
 * `top10-movies`. Matching is by list key only, never by display title, which
 * is user-facing/localized and may not match. Mirrors the web client's
 * `lib/top10.ts`.
 */
private val TOP10_LIST_KEY_REGEX = Regex("^top10(?:[-_].*)?$", RegexOption.IGNORE_CASE)

fun isTop10ListKey(listKey: String): Boolean {
    return TOP10_LIST_KEY_REGEX.matches(listKey.trim())
}