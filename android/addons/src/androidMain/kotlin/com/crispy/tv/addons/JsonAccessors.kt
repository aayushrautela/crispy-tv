package com.crispy.tv.addons

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The JSON accessors this module's three `androidMain` files share, on
 * `kotlinx.serialization.json.JsonElement` rather than `org.json`.
 *
 * **This file exists because a node type does not respect a module boundary.**
 * `AddonsSettingsScreen.kt` in `:app` was ported first, and the moment it was,
 * `MetadataAddonRegistry.cacheManifest(seed, manifest: JSONObject)` in this
 * module stopped compiling -- the node type was in a *cross-module signature*,
 * so the port had to follow the signature rather than stop at the file. The
 * caller that forced it was not the one that motivated it.
 *
 * **These are deliberately the same eleven shapes `:app` now carries in
 * `LibraryDiskCacheJsonAccessors.kt`, and they are duplicated rather than
 * shared** -- `internal` is module-scoped, so `:addons` cannot name a
 * declaration in `:app` and `:app` cannot name one here. Only one of the
 * original `org.json` policies is transcribed, and it is transcribed rather
 * than consolidated: `:app` and `:backend` already hold two copies of
 * `optNullableString` that disagree, and picking a winner is a product act
 * rather than a port.
 */

/**
 * The `opt(key)` arm every `opt*OrNull` below shares: absent or JSON null is
 * `null`, a container is `null`, and a primitive is returned for the parsers
 * to try.
 */
internal fun JsonObject.jsonPrimitiveOrNull(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

/**
 * `optString`: a missing key and a JSON null both answer `""`, where
 * `org.json` answered the four characters `"null"` for the latter. That was
 * never a deliberate answer -- it was a platform rendering of a null leaking
 * through a string accessor -- so the new answer is a fix rather than a change.
 */
internal fun JsonObject.optStringOrEmpty(key: String): String =
    when (val element = this[key]) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull ?: ""
        // A container is stringified, which is what `optString` did.
        else -> element.toString()
    }

/** `optJSONObject`: absent, JSON null, or a non-object is `null`. */
internal fun JsonObject.optJsonObject(key: String): JsonObject? = this[key] as? JsonObject

/** `optJSONArray`: absent, JSON null, or a non-array is `null`. */
internal fun JsonObject.optJsonArray(key: String): JsonArray? = this[key] as? JsonArray

/**
 * `optBoolean(k, false)` read as an "is it true" question.
 *
 * The old form was `optBoolean(k, false)`, so `!= true` is the same answer on
 * a stored boolean; on a stored non-boolean `org.json` answered `false` and
 * this answers `false` too, because `null != true`. **`optBoolean` is strict
 * on every implementation**, so nothing here is a reinterpretation.
 */
internal fun JsonObject.optBooleanOrTrue(key: String): Boolean = jsonPrimitiveOrNull(key)?.booleanOrNull == true

internal fun JsonObject.optBooleanOrNull(key: String): Boolean? = jsonPrimitiveOrNull(key)?.booleanOrNull

/**
 * `optInt`: the number, or a numeric string, or `null`.
 *
 * **`longOrNull` and `intOrNull` parse where `optLong` and `optInt`
 * truncated.** For the whole numbers this module writes the two are
 * indistinguishable; a fractional value would answer `null` here and a
 * truncated integer under `org.json`.
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

/** `getString`: the literal, or **a throw** -- `org.json`'s `getString` raised. */
internal fun JsonObject.optStringOrThrow(key: String): String =
    jsonPrimitiveOrNull(key)?.contentOrNull
        ?: error("Expected a string at \"$key\" but it is absent or not a string.")

/** `getBoolean`: the boolean, or **a throw**. See [optStringOrThrow]. */
internal fun JsonObject.optBooleanOrThrow(key: String): Boolean =
    jsonPrimitiveOrNull(key)?.booleanOrNull
        ?: error("Expected a boolean at \"$key\" but it is absent or not a boolean.")

/** The `?.let`-free read of a nullable receiver, kept because callers rely on `null` for absent. */
internal fun JsonArray.stringAtOrEmpty(index: Int): String =
    (getOrNull(index) as? JsonPrimitive)?.contentOrNull ?: ""

/**
 * `optBoolean(key)` -- the **one-argument** form, which defaults to `false`.
 *
 * **`optBooleanOrTrue` is not its negation**, and the difference is a present
 * non-boolean: `optBooleanOrTrue` answers `true` there and this answers
 * `false`, because `org.json`'s `optBoolean(key)` answered `false` for
 * anything it could not read as a Boolean. Both are `internal` to this module,
 * so nothing in `:app` or `:backend` sees either.
 */
internal fun JsonObject.optBooleanOrFalse(key: String): Boolean =
    jsonPrimitiveOrNull(key)?.booleanOrNull == true
