package com.crispy.tv.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun Map<String, Any?>.toJsonObject(): JsonObject {
    val json = mutableMapOf<String, JsonElement>()
    for ((key, value) in this) {
        json[key] = value.toJsonValue()
    }
    return JsonObject(json)
}

internal fun Any?.toJsonValue(): JsonElement {
    return when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Map<*, *> -> {
            val json = mutableMapOf<String, JsonElement>()
            for ((key, value) in this) {
                if (key is String) {
                    json[key] = value.toJsonValue()
                }
            }
            JsonObject(json)
        }

        is Iterable<*> -> JsonArray(this.map { it.toJsonValue() })
        is Array<*> -> JsonArray(this.map { it.toJsonValue() })
        // `org.json`'s `else -> this` passed the value through *unchanged*, so a
        // Boolean stayed a Boolean and a number stayed a number. `JsonPrimitive`
        // has no `Any?` constructor, and stringifying everything would have been a
        // second silent change on top of the node type itself.
        else -> when (this) {
            is Boolean -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            else -> JsonPrimitive(this.toString())
        }
    }
}

internal fun JsonObject?.toAnyMap(): Map<String, Any?> {
    val obj = this ?: return emptyMap()
    val result = linkedMapOf<String, Any?>()
    for ((key, value) in obj) {
        result[key] = value.toKotlinValue()
    }
    return result
}

internal fun JsonArray?.toAnyList(): List<Any?> {
    val array = this ?: return emptyList()
    return buildList {
        for (element in array) {
            add(element.toKotlinValue())
        }
    }
}

internal fun Any?.toKotlinValue(): Any? {
    return when (this) {
        null, JsonNull -> null
        is JsonObject -> this.toAnyMap()
        is JsonArray -> this.toAnyList()
        // **`else -> this` was right about `org.json` and wrong here.** Under
        // `org.json` a primitive *was* already a `Boolean`, a `String` or a
        // `Number`, so passing it through changed nothing. A `JsonPrimitive` is
        // none of those -- it is one type holding a literal -- so passing it
        // through would have made `toKotlinValue` a function whose name and
        // KDoc ("the neutral model") both claim a conversion it does not do, and
        // `toAnyMap` would hand its callers `JsonElement`s.
        is JsonPrimitive -> this.toKotlinScalar()
        else -> this
    }
}

/**
 * One `JsonPrimitive` to the `org.json` scalar it stood for.
 *
 * **The width is reproduced rather than replaced, because a width is an answer
 * somebody already depends on.** `org.json` widened by size -- `Integer` where
 * it fits, then `Long`, then `Double` -- and the two implementations of it
 * disagreed exactly once: the Maven artifact gave every fractional number a
 * `BigDecimal` while AOSP gave a `Double`. There was no answer correct on both,
 * which is what made the numeric row in that measurement table contested.
 *
 * **AOSP's widths are adopted as the contract**, so `1` is an `Int`, `3000000000`
 * is a `Long` and `1.5` is a `Double`, and the contested case stops being
 * contested: there is no second implementation in this test path any more, and
 * the `BigDecimal` answer is no longer reachable to disagree with. **A whole
 * number that fits neither `Int` nor `Long` still lands on `Double`**, which is
 * also what `org.json` did.
 */
internal fun JsonPrimitive.toKotlinScalar(): Any? {
    booleanOrNull?.let { return it }
    if (isString) return content
    val literal = content
    if (literal.none { it == '.' || it == 'e' || it == 'E' }) {
        literal.toIntOrNull()?.let { return it }
        literal.toLongOrNull()?.let { return it }
    }
    return literal.toDoubleOrNull() ?: literal
}

internal fun JsonObject?.optNullableString(key: String): String? {
    val json = this ?: return null
    val content = json.optStringOrEmpty(key)
    if (content.isBlank() || content.equals("null", ignoreCase = true)) {
        return null
    }
    return content.trim()
}

internal fun JsonObject.optIntOrNull(key: String): Int? {
    return when (val scalar = jsonPrimitiveOrNull(key)?.toKotlinScalar()) {
        is Number -> scalar.toInt()
        is String -> scalar.trim().toIntOrNull()
        else -> null
    }
}

internal fun JsonObject.optDoubleOrNull(key: String): Double? {
    return when (val scalar = jsonPrimitiveOrNull(key)?.toKotlinScalar()) {
        is Number -> scalar.toDouble()
        is String -> scalar.trim().toDoubleOrNull()
        else -> null
    }
}

internal fun JsonObject.optLongOrNull(key: String): Long? {
    return when (val scalar = jsonPrimitiveOrNull(key)?.toKotlinScalar()) {
        is Number -> scalar.toLong()
        is String -> scalar.trim().toLongOrNull()
        else -> null
    }
}

internal fun JsonObject.optBooleanOrNull(key: String): Boolean? {
    val value = jsonPrimitiveOrNull(key) ?: return null
    return value.booleanOrNull ?: value.contentOrNull?.trim()?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

internal fun JsonObject.optStringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return buildList {
        for (element in array) {
            val item = (element as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            if (item.isNotBlank()) {
                add(item)
            }
        }
    }
}

internal fun JsonObject.optIntList(key: String): List<Int> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return buildList {
        for (element in array) {
            when (val scalar = (element as? JsonPrimitive)?.toKotlinScalar()) {
                is Number -> add(scalar.toInt())
                is String -> scalar.trim().toIntOrNull()?.let(::add)
            }
        }
    }
}

internal fun JsonObject?.toStringMap(): Map<String, String> {
    val obj = this ?: return emptyMap()
    val result = linkedMapOf<String, String>()
    for ((key, element) in obj) {
        val normalized = (element as? JsonPrimitive)?.contentOrNull?.trim()
        if (!normalized.isNullOrBlank()) {
            result[key] = normalized
        }
    }
    return result
}

/**
 * A missing key and a JSON null both answer `""`; `org.json` answered `"null"`
 * for the latter.
 *
 * **This one helper carries the port's largest behaviour change, and it is a
 * fix.** Every one of `CrispyBackendParsers.kt`'s 51 `optString` sites reads a
 * field whose document may say null, and under `org.json` such a read returned
 * the four characters `"null"` — which is *not* blank, so the
 * `.trim().ifBlank { null }` that follows at most of those sites never fired and
 * a nullable field received the literal string. `contentOrNull` answers `null`
 * for a `JsonNull`, so those sites now receive a genuinely absent value. Both
 * answers are non-null `String`, so **no site became a compile error** — which
 * is exactly why this is written down here and pinned in
 * `JsonAccessorPolicyHostTest` rather than left to a test diff.
 *
 * **The one thing this does not preserve, and it is not the same property.**
 * Choosing `JsonElement` preserved *a JSON null versus an absent key*, which an
 * untyped tree collapses. It did not preserve *the platform's rendering of a
 * number*: `org.json` parsed `1.50` to a `Double` whose `toString()` is `"1.5"`,
 * while `contentOrNull` returns the literal `"1.50"`. Whole numbers agree. **Two
 * different properties, two different answers, and only one of them was the
 * reason to change the node type.**
 */
internal fun JsonObject.optStringOrEmpty(key: String): String =
    when (val element = this[key]) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull ?: ""   // non-null primitive, non-null content
        // A container stringified, which is what `org.json`'s `optString` did:
        // a field documented as a string and delivered as an object reads as
        // that object's JSON rather than as nothing at all. **`jsonPrimitive`
        // throws on a `JsonObject` or `JsonArray`**, so the `as? JsonPrimitive`
        // in a naive port turns a readable value into a crash.
        else -> element.toString()
    }

/**
 * The `opt(key)` arm every `opt*OrNull` above shares: absent or JSON null is
 * `null`, a container is `null` (the old code reached `else -> null` for those),
 * and a primitive is returned for the numeric and boolean parsers to try.
 */
/**
 * `optJSONObject(key)`: absent, JSON null, or a non-object is `null`.
 *
 * `org.json`'s `optJSONObject` returned `null` for all three, and the callers
 * here rely on that — every one is either `?: JSONObject()` or `?: continue`.
 * **A `JsonArray` is not a `JsonObject`, and neither is a primitive, so the
 * cast is the whole rule** and there is nothing to add.
 */
internal fun JsonObject.optJsonObject(key: String): JsonObject? = this[key] as? JsonObject

/**
 * `optJSONArray(key)`: absent, JSON null, or a non-array is `null`, which is
 * what `optStringList` and `optIntList` already relied on.
 */
internal fun JsonObject.optJsonArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.jsonPrimitiveOrNull(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

/**
 * `optLong(key, default)`.
 *
 * **This reproduces `org.json` rather than adopting `longOrNull`, and the
 * difference is why it exists.** AOSP's `optLong` answers `Long.parseLong` for
 * a *String* -- strict, no exponent and no fraction -- and a truncating cast for
 * a *Number*. `JsonPrimitive.longOrNull` parses either, so a port using it would
 * answer `null` for `"1e3"` where the old code answered the default. A lenient
 * accessor feeding a strict parser is a truncation, not a parse, and those two
 * directions are not the same change.
 *
 * **`contentOrNull` alone cannot do this**: it answers `"1234"` for the number
 * `1234` and for the quoted string `"1234"` alike. Only `isString` separates
 * them.
 */
internal fun JsonObject.optLongOrDefault(key: String, default: Long): Long {
    val primitive = jsonPrimitiveOrNull(key) ?: return default
    val content = primitive.contentOrNull?.trim() ?: return default
    return if (primitive.isString) {
        content.toLongOrNull() ?: default
    } else {
        content.toDoubleOrNull()?.toLong() ?: default
    }
}

/**
 * **Why the numeric policies call [toKotlinScalar] instead of `intOrNull`.**
 *
 * `JsonPrimitive.intOrNull` PARSES a literal: it answers `null` for `"42.9"`.
 * `org.json`'s `optInt` COERCED a `Number`, and `Number.toInt()` **truncates**,
 * so it answered `42`. Those are different operations and the strict one is the
 * wrong translation -- `optIntOrNull("fraction")` over `{"fraction":42.9}` went
 * from `42` to `null`, and `optIntList` dropped an element it used to keep.
 *
 * The first draft of this port reached for `intOrNull` because it looks like the
 * obvious one-for-one replacement, and **the existing suite caught both** -- which
 * is precisely what the suite was characterised for when it was written.
 */