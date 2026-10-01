package com.crispy.tv.library

import org.json.JSONObject

/**
 * The JSON accessor policies [LibraryDiskCacheStore] reads a cached catalog page
 * with, lifted out of the class so a test can name them.
 *
 * ## Why they are top-level and not `private` members
 *
 * They were `private fun` members, which makes them unnameable from a test
 * **in this module as well as any other** -- `private` is a property of the
 * class, not of the file. The repo's own rule is that a private decision is an
 * untestable decision, and the precedent is `:tv`'s `CrispyTvDarkColors`, which
 * was widened to `internal` for exactly this reason.
 *
 * Lifting them rather than widening them in place is what makes the test
 * possible without a `Context`: `LibraryDiskCacheStore` is a `Context` holder,
 * so a test of its members would have to construct one, while these four
 * functions are pure functions over a JSON tree.
 *
 * ## This is a copy of `:backend`'s policies, and it is not the same function
 *
 * `com.crispy.tv.backend` declares the same two names. Its `optNullableString`
 * is `internal`, and `:app` is a different module, so it was never visible here
 * and these two were written instead. `:home` *does* import the `:backend`
 * copy -- so the policy is reachable from one module and unreachable from
 * another, decided by an `internal` keyword nobody revisited.
 *
 * The two copies disagree, and the disagreements are behavioural:
 *
 * | input | `:backend` | here |
 * |---|---|---|
 * | the literal string `"null"` | `null` | `"null"` |
 * | `"banana"` as a boolean | `null` | `false` |
 * | `1` as a boolean | `null` | `false` |
 * | `"  x  "` as a string value | `"x"` | `"x"` |
 *
 * The second row is the sharp one: **the `:backend` copy can return `null` for
 * a present-but-unparseable value and this one cannot, so a function named
 * `optBooleanOrNull` returns `false`.** `org.json`'s `optBoolean` never returns
 * `null` -- it returns the `Boolean` itself or its `false` default.
 *
 * **Nothing here decides which copy is right.** Consolidating them is a
 * behaviour change on three call paths, so `JsonAccessorsDivergenceTest` in
 * `androidHostTest` pins both sides of every row above, and the duplication
 * stays until it is a deliberate act.
 *
 * These tests must live in `androidHostTest`, not `commonTest`: `org.json` is a
 * class of the Android platform supplied by `android.jar`, so it is absent from
 * every other target's classpath. Robolectric supplies the real AOSP
 * implementation -- see `:backend`'s `JsonAccessorPolicyHostTest` for why the
 * Maven `org.json:json` artifact is not a substitute.
 */
internal fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() }
}

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return optBoolean(key)
}
