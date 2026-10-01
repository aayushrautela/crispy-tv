package com.crispy.tv.library

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON policies [LibraryDiskCacheStore] reads its cache entries with, lifted
 * out of the class so a test can name them. They are top-level `internal` rather
 * than `private` members because a `private` member is unnameable from a test in
 * the same module too -- the precedent is `:tv`'s `CrispyTvDarkColors`, widened
 * to `internal` for exactly this. Lifting them is also what makes the test
 * possible without a `Context`, because `LibraryDiskCacheStore` is a `Context`
 * holder and these functions are not.
 *
 * ## This is a copy of `:backend`'s policies, and it is not the same function
 *
 * `com.crispy.tv.backend.optNullableString` and `optBooleanOrNull` are
 * `internal` in `:backend`, and `:app` is a different module, so that copy was
 * never visible here -- while `:home` *does* import `:backend`'s one `public`
 * export, `toStringMap`. **The same policy is reachable from one module and
 * unreachable from another, and the deciding token is an `internal` nobody
 * revisited.** That is why these are transcribed rather than imported, and it is
 * why a divergence between the two copies is not a bug in either.
 *
 * ### The surviving divergence row, and it stays
 *
 * | input | `:backend` | here |
 * |---|---|---|
 * | the literal string `"null"` | `null` | `"null"` |
 *
 * `:backend`'s branch is `takeUnless { it.isBlank() || it.equals("null",
 * ignoreCase = true) }`, and backend payloads really do carry that string, so
 * that is defensive parsing of a real shape rather than a guess. This copy
 * leaves it alone.
 *
 * ### `JsonAccessorsDivergenceTest` does not exist, and could not have
 *
 * An earlier KDoc deferred consolidating these on the grounds that such a suite
 * *"pins both sides of every row"*, and cited *"three call paths"* as the
 * reason to wait. Both were wrong: there is no such file in the repository, and
 * there is no source set from which one could exist, because the two functions
 * are `internal` in two different modules. **A gate that could not be written is
 * not a weak gate, it is no gate.** And "three call paths" was the count of the
 * three *files* a consolidation would touch, while the one function that had
 * actually been defective had a single call site.
 *
 * ## Where these tests now live, and why the answer changed
 *
 * An earlier paragraph here said these tests *must* live in `androidHostTest`,
 * because `org.json` is a class of the Android platform supplied by `android.jar`
 * and is absent from every other target's classpath. **That is no longer the
 * reason, and keeping it would have been the wrong documentation.** These
 * functions are pure over `kotlinx.serialization.json.JsonElement`, which is a
 * real KMP dependency, so the suite belongs in `commonTest` and runs on desktop
 * and both Apple targets. `:watchhistory`'s `WatchProgressStoreHostTest` and
 * `:backend`'s `JsonAccessorPolicyHostTest` already made this move for exactly
 * the same reason. **A KDoc paragraph that exists only to explain why a gate
 * could not be written is the first thing to rewrite when the gate becomes
 * writable.**
 *
 * ## The node type changed; the policies did not
 *
 * `org.json` became `JsonElement`, and the one difference that reaches these
 * functions is worth stating plainly: **`org.json`'s `optString` answers the
 * four characters `"null"` for a stored JSON null**, where `contentOrNull`
 * answers `null`. Every `optString` site that reached a field could therefore
 * receive a literal string where the document said null. That is a fix, not a
 * regression, and the two behaviour changes this repository records for the
 * node-type migration are both instances of it. **Note that this file's
 * `optNullableString` was already immune** -- its `has`/`isNull` guard returned
 * before `optString` could render anything -- so its answer is unchanged, and
 * the guard now simply disappears into the node type.
 */
internal fun JsonObject.optNullableString(key: String): String? {
    return optStringOrEmpty(key).trim().takeIf { it.isNotEmpty() }
}

/**
 * `:backend`'s policy, transcribed, because this copy is the one that was
 * wrong.
 *
 * **The `has`/`isNull` guard this used to open with is gone, and its removal is
 * the point rather than a simplification.** `org.json`'s `opt(name)` answers
 * Java `null` for an absent key and `JSONObject.NULL` for a stored JSON null,
 * and `JSONObject.NULL` is neither a `Boolean` nor a `String`, so it reached the
 * `else` arm and answered `null` like the other two. A guard that decided
 * nothing is a statement of intent with no state to change.
 *
 * **The `is String` arm is what makes the answer `null` rather than `false`.**
 * `optBoolean` could never do it: it returns the `Boolean` or its `false`
 * default, so a function named `optBooleanOrNull` returned `false` for
 * `"banana"`, for `1`, and for a number that was never a boolean at all. Every
 * consumer here branches on `liked == false` against `liked == true`, and those
 * are different answers -- see the case in the suite named for that.
 */
internal fun JsonObject.optBooleanOrNull(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) {
        return null
    }
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/**
 * The `org.json` `optString(key)` this module's call sites used, expressed over
 * the node type.
 *
 * **A missing key and a JSON null both answer `""`, where `org.json` answered
 * `"null"` for the latter.** That is the port's one behaviour change at every
 * keyed string read, and it is the fix the node type was adopted for.
 *
 * A container stringifies, which is what `optString` did: a field documented as
 * a string and delivered as an object reads as that object's JSON rather than
 * as nothing at all. `JsonElement.jsonPrimitive` *throws* on a container, so the
 * `as? JsonPrimitive` here is load-bearing rather than defensive.
 *
 * Declared once for the module because it is the node-type replacement for one
 * `org.json` member rather than a policy with an opinion in it -- the policies
 * above are the ones that deliberately disagree with `:backend`'s.
 */
internal fun JsonObject.optStringOrEmpty(key: String): String {
    return when (val element = this[key]) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull ?: ""
        else -> element.toString()
    }
}

/**
 * `JSONObject()` + `put` is how the store wrote an entry, and it is **mutable**,
 * where `JsonObject` has no mutating members at all -- the second of the three
 * directions a node-type port can change behaviour in. Building through
 * `buildJsonObject` is the equivalent, not a rewrite.
 */
/**
 * `optJSONObject(key)`: absent, JSON null, or a non-object answers `null`,
 * which is what every caller here relies on -- each is a `?: emptyMap()` or a
 * `?: return null`.
 *
 * **`org.json`'s `optJSONObject` answered `null` for all three, and a
 * `JsonArray` is not a `JsonObject` and neither is a primitive, so the cast is
 * the whole rule.** Like [optStringOrEmpty] this replaces one `org.json`
 * *member* rather than a policy, so it is shared by every caller in the module
 * rather than copied per package the way [optNullableString] and
 * [optBooleanOrNull] deliberately are.
 */
internal fun JsonObject.optJsonObject(key: String): JsonObject? = this[key] as? JsonObject

/** `optJSONArray(key)`, with the same three-way `null` answer. */
internal fun JsonObject.optJsonArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun Map<String, String>.toJsonObject(): JsonObject {
    return buildJsonObject {
        for (key in keys.sorted()) {
            put(key, getValue(key))
        }
    }
}


/**
 * The `opt(key)` arm every `opt*OrNull` below shares: absent or JSON null is
 * `null`, a container is `null` (the `else` arm the `org.json` copies reached),
 * and a primitive is returned for the numeric and boolean parsers to try.
 *
 * **A node-type replacement for one `org.json` *member*, so it is shared by
 * every caller in this module** -- unlike [optNullableString] and
 * [optBooleanOrNull], which are *policies* that deliberately disagree with
 * `:backend`'s and are therefore copied per package.
 */
internal fun JsonObject.jsonPrimitiveOrNull(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

/**
 * `optInt`: the number, or a numeric string, or `null`.
 *
 * **`longOrNull` parses where `optLong` truncated**, and that difference is
 * invisible for a whole number -- which is all this store writes. It is
 * recorded here rather than left implicit because the reverse holds too: a
 * fractional value would answer `null` here and `42` under `org.json`.
 */
internal fun JsonObject.optIntOrNull(key: String): Int? {
    val primitive = jsonPrimitiveOrNull(key) ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
}

/** [optIntOrNull]'s long form. See the note there about parsing versus truncation. */
internal fun JsonObject.optLongOrNull(key: String): Long? {
    val primitive = jsonPrimitiveOrNull(key) ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull()
}

/**
 * `getBoolean`: the boolean, or **a throw** for anything else.
 *
 * `org.json`'s `getBoolean` raised `JSONException` on an absent or
 * uncoercible value, and three call sites here depend on the raise rather than
 * on a default -- so this replaces the throw with a different exception type
 * instead of quietly answering `false`. **A defaulted read would be a silent
 * behaviour change on exactly the inputs a reader cannot distinguish from a
 * stored `false`.**
 */
internal fun JsonObject.optBooleanOrThrow(key: String): Boolean =
    jsonPrimitiveOrNull(key)?.booleanOrNull
        ?: error("Expected a boolean at \"$key\" but it is absent or not a boolean.")

/** `getString`: the literal, or **a throw**. See [optBooleanOrThrow]. */
internal fun JsonObject.optStringOrThrow(key: String): String =
    jsonPrimitiveOrNull(key)?.contentOrNull
        ?: error("Expected a string at \"$key\" but it is absent or not a string.")


/**
 * [optStringOrEmpty], but `null` for absent, JSON null, or a container.
 *
 * `obj.optString(key)` on an absent key already answered `""`, so the nullable
 * form is not needed for that; it is needed where the *old* code branched on
 * `isNull(key)`, which asked "is there a value here" rather than "is there a
 * string here" -- so a non-string used to render as its JSON text and now
 * answers `null`. **The caller decides whether that is a change; the accessor
 * only makes it visible.**
 */
internal fun JsonObject.optStringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull


/**
 * `optDouble`: the number, or a numeric string, or `null`.
 *
 * **No truncation question here, unlike [optLongOrNull]**, because a `Double`
 * is the widest numeric type `org.json` answered for and `doubleOrNull` parses
 * a literal rather than narrowing one -- `1.5` is `1.5` in both.
 */
internal fun JsonObject.optDoubleOrNull(key: String): Double? {
    val primitive = jsonPrimitiveOrNull(key) ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
}


/**
 * [optStringOrEmpty]'s index-based twin, for a `JsonArray` element.
 *
 * **`org.json`'s `JSONArray.optString(index)` is the member this replaces, so it
 * is shared module-wide** for the same reason [optStringOrEmpty] is.
 */
internal fun JsonArray.stringAtOrEmpty(index: Int): String {
    val element = getOrNull(index) ?: return ""
    return when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull ?: ""
        else -> element.toString()
    }
}
