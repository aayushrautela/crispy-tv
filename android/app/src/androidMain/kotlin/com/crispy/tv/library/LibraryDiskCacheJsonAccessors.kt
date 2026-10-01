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
 * The two copies disagreed, and `optBooleanOrNull` was the row that mattered.
 * It has been fixed, so **one row of the table is left**:
 *
 * | input | `:backend` | here |
 * |---|---|---|
 * | the literal string `"null"` | `null` | `"null"` |
 *
 * **That row stays on purpose, and it is not the platform-rendering bug it
 * looks like.** `:backend`'s branch is
 * `takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }`, and
 * backend payloads really do carry that string, so it is defensive parsing of a
 * real shape. Nothing here filters it, and the case named for it says so
 * explicitly. The two rows that *were* the bug — a present-but-unreadable
 * boolean answering `false` — are gone because this copy now matches
 * `:backend`; the reasoning is on the function.
 *
 * **The suite this file used to name does not exist, and could not have.** It
 * cited `JsonAccessorsDivergenceTest` as the thing that "pins both sides of
 * every row", and cited "three call paths" as the reason to wait. Both claims
 * were wrong. There is no such file in the repository, and there is no source
 * set in the module graph from which one could exist: these two functions are
 * `internal` in two different modules, so `:app`'s tests cannot see `:backend`'s
 * and `:backend`'s cannot see `:app`'s. **A gate that could not be written is
 * not a weak gate, it is no gate** — the divergence was being held by prose
 * alone. The "three call paths" was the count of the three *files* a
 * consolidation would touch; the one defective function had a single call site
 * (`LibraryDiskCacheStore.kt:126`), which is what made the fix cheap.
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

/**
 * `:backend`'s policy, transcribed, because this copy is the one that was
 * wrong.
 *
 * **The `has`/`isNull` guard this used to open with is gone, and its removal
 * is the point rather than a simplification.** `org.json`'s `opt(name)` answers
 * Java `null` for an absent key and `JSONObject.NULL` for a stored JSON null,
 * and `JSONObject.NULL` is neither a `Boolean` nor a `String`, so it reaches
 * the `else` arm and answers `null` like the other two. A guard that decided
 * nothing is a statement of intent with no state to change.
 *
 * **The `is String` arm is what makes the answer `null` rather than `false`.**
 * `optBoolean` could never do it: it returns the `Boolean` or its `false`
 * default, so a function named `optBooleanOrNull` returned `false` for
 * `"banana"`, for `1`, and for a number that was never a boolean at all. Every
 * consumer here branches on `liked == false` against `liked == true`, and those
 * are different answers — see the case in the suite named for that.
 */
internal fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    return opt(key)?.let { value ->
        when (value) {
            is Boolean -> value
            is String -> value.trim().lowercase().let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }

            else -> null
        }
    }
}
