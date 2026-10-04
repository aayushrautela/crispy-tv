package com.crispy.tv.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The JSON policies the disk caches read their payloads with, lifted out of
 * [DiskHomeCatalogSnapshotCache] so that [RecommendationCatalogDiskCacheStore] shares them
 * rather than carrying its own copy.
 *
 * **Every one of these reproduces an `org.json` answer, and the reason is written on each
 * one.** This file used to be a single extension on `JSONObject?` in `androidMain`; the
 * two classes that used it now share these five, and both are `internal` rather than
 * private to a file for the same reason [cacheFileName] is: every policy here is a decision
 * about what a hand-edited or half-written cache file is allowed to contain, and a
 * decision no test can call is a decision no test can cover.
 */

/**
 * `optString(key)`, whose three answers all have to be reproduced: `org.json` answers `""`
 * for an absent key *and* for a `JSONObject.NULL`, and it answers a container's JSON text
 * for a field documented as a string. The last of those is not defensive clutter —
 * `org.json` produced it, and a naive `as? JsonPrimitive` port turns a readable value into
 * a crash.
 *
 * This is `:backend`'s `optStringOrEmpty` and `:app`'s two copies of the same policy,
 * transcribed because all three are `internal` in their own modules. `:backend`'s copy
 * carries the measurement behind `contentOrNull`, so it is the one worth reading before
 * changing this.
 */
internal fun JsonObject.optStringOrEmpty(key: String): String =
    when (val element = this[key]) {
        null, JsonNull -> ""
        else -> element.asCacheString()
    }

/**
 * `JSON.toString(opt(key))` for one element: a primitive's content, and a container's JSON
 * text. `JSONObject.NULL` is a `JsonPrimitive` whose `contentOrNull` is `null`, so it
 * answers `""` here rather than the four characters `null` — which is the whole reason
 * `optStringOrEmpty` tests for `JsonNull` separately as well.
 */
internal fun JsonElement.asCacheString(): String =
    if (this is JsonPrimitive) contentOrNull ?: "" else toString()

/**
 * `optJSONObject(key)`: absent, JSON null, or a non-object is `null`, which is what every
 * caller here already relied on — each one is `?: emptyMap()`. A `JsonArray` is not a
 * `JsonObject`, and neither is a primitive, so the cast is the whole rule.
 */
internal fun JsonObject.optJsonObject(key: String): JsonObject? = this[key] as? JsonObject

/**
 * `optJSONArray(key)`: absent, JSON null, or a non-array is `null`, and both callers walked
 * the result with `?: JSONArray()`. [orEmpty] is that, on the nullable receiver rather than
 * at the use site, which is why an absent array and an empty one cannot drift apart at the
 * four places that read one.
 */
internal fun JsonObject.optJsonArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/**
 * This is [com.crispy.tv.backend]'s old `toStringMap`, moved here byte for byte, then
 * ported off `org.json` along with everything else that reached it.
 *
 * **It was `public` in `:backend` for exactly one reason: this one caller.**
 * `:backend` reached a `commonMain` by porting off `org.json`, so its copy now takes a
 * `JsonObject?` and cannot serve a receiver of `org.json`'s `JSONObject?`. `:home` was still
 * `org.json`-based in `androidMain`, so the honest choices were to port `:home`'s whole JSON
 * layer (which this landing did, so the reason is retired) or to let `:home` own the helper
 * it was borrowing. **A cross-module `public` that exists for a single caller and whose
 * signature a node-type change invalidates is a coupling with no other content**, so
 * `:backend`'s went back to `internal` and this copy lives here. `:app` has since made the
 * same copy twice, in `ProfileDataShadowJsonAccessors` and `LibraryDiskCacheJsonAccessors`,
 * with its own divergence table — three copies in three modules is the current state of this
 * repository, not an oversight in any one of them.
 *
 * **The copy `:backend` and the copies `:app` all keep the same answer on containers**, and
 * this is the one divergence that remains: this copy keeps `org.json`'s
 * `else -> value.toString()`, so a nested object or array under a string-map key reads as
 * its JSON text and is **kept**; `:backend`'s `commonMain` copy casts to `JsonPrimitive` and
 * **drops** a container instead. That difference used to be forced — they were two node
 * types — and it is now a choice, kept here because it is the behaviour `:home`'s cache
 * already stored on disk and changing it would be a different landing.
 *
 * The rest of the policy is `org.json`'s and was measured against it: `JSONObject.NULL` is
 * dropped rather than read as a value, a `Number` or `Boolean` becomes its text, everything
 * is trimmed, and a key whose value is blank **or whitespace only** is not kept.
 */
internal fun JsonElement?.toStringMap(): Map<String, String> {
    val obj = this as? JsonObject ?: return emptyMap()
    val result = linkedMapOf<String, String>()
    for ((key, element) in obj) {
        if (element is JsonNull) {
            continue
        }
        val normalized = element.asCacheString().trim()
        if (normalized.isNotBlank()) {
            result[key] = normalized
        }
    }
    return result
}