package com.crispy.tv.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The JSON policies [ProfileDataShadowStore] persists a profile shadow with,
 * lifted out of the class so a test can name them. See
 * `com.crispy.tv.library.LibraryDiskCacheJsonAccessors` for why they are
 * top-level `internal` rather than `private` members: a `private` member is
 * unnameable from a test in the same module too, and the class needs a
 * `Context` that these pure functions do not.
 *
 * ## This is a copy of `:backend`'s `toStringMap`, and it is not the same function
 *
 * `com.crispy.tv.backend.toStringMap` is the one `public` declaration in
 * `CrispyBackendJsonExtensions.kt`, and `:home` imports it. `:app` has its own
 * copy, and the two disagree on three inputs:
 *
 * | input | `:backend` | here |
 * |---|---|---|
 * | `"  x  "` | `"x"` -- trimmed | `"  x  "` -- not trimmed |
 * | `""` | dropped | kept |
 * | `"   "` | dropped | kept |
 *
 * `:backend` also takes a **nullable** receiver and returns a `linkedMapOf`;
 * this takes a non-null receiver and returns a `mutableMapOf`.
 *
 * [toJsonObject] has no counterpart in `:backend` -- the `:backend` function of
 * that name takes a `Map<String, Any?>` and does not sort -- so these are not
 * overloads of one function but two functions with one name, and the key order
 * that reaches the `SharedPreferences` entry is sorted by this one only.
 *
 * **Nothing here decides which copy is right.** The two are pinned from both
 * sides in the suite and the duplication stays until consolidating it is a
 * deliberate act.
 *
 * ## What the node-type port preserved here, deliberately
 *
 * Two of this file's three behaviours look like they would be "tidied" by the
 * port and are not:
 *
 * - **It does not trim, and it does not drop blanks.** `:backend` does both.
 *   Trimming here would silently rewrite what is persisted.
 * - **A container is stringified and kept.** The old body was
 *   `result[key] = rawValue.toString()` with only a null/JSON-null skip, so a
 *   nested object arrived as its JSON text and stayed. `:backend`'s copy, ported
 *   to `JsonElement`, drops containers instead -- `(element as? JsonPrimitive)`
 *   cannot match one. **That is the same choice `:backend` made and this copy
 *   does not share it**, which is precisely the divergence the table above is
 *   for.
 *
 * The one behaviour that *did* change is inherited from the node type and is a
 * fix: `org.json` answered the four characters `"null"` for a stored JSON null,
 * where `contentOrNull` answers `null`, so a shadow value that is genuinely
 * JSON-null now persists as absent rather than as the string `"null"`. A
 * fractional number also keeps its literal text -- `org.json` parsed `1.50` to a
 * `Double` printing `1.5`, and `contentOrNull` returns `"1.50"`. **Choosing
 * `JsonElement` preserved a JSON null versus an absent key; it did not preserve
 * the platform's rendering of a number.** Those are two different properties and
 * only one of them was the reason to change the node type.
 */
internal fun JsonObject.toStringMap(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for ((key, element) in this) {
        if (element is JsonNull) {
            continue
        }
        val text = if (element is JsonPrimitive) {
            element.contentOrNull ?: continue
        } else {
            element.toString()
        }
        result[key] = text
    }
    return result
}

/**
 * **The sorted key order is this function's whole reason for existing**, and it
 * is not preserved by `:backend`'s same-named function at all -- that one takes
 * a `Map<String, Any?>` and iterates it as given. So the order that reaches the
 * `SharedPreferences` entry is decided here and nowhere else, and dropping
 * `.sorted()` would change what is written without changing anything that
 * compiles.
 *
 * `JSONObject()` + `put` is **mutable**, where `JsonObject` has no mutating
 * members -- the second direction a node-type port changes behaviour in.
 */
internal fun Map<String, String>.toJsonObject(): JsonObject {
    return buildJsonObject {
        for (key in keys.sorted()) {
            put(key, getValue(key))
        }
    }
}
