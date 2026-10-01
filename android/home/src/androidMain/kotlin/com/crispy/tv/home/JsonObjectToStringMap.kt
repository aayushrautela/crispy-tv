package com.crispy.tv.home

import org.json.JSONObject

/**
 * This is [com.crispy.tv.backend]'s old `toStringMap`, moved here byte for byte.
 *
 * **It was `public` in `:backend` for exactly one reason: this one caller.**
 * `:backend` reached a `commonMain` by porting off `org.json`, so its copy now
 * takes a `JsonObject?` and cannot serve a receiver of `org.json`'s `JSONObject?`.
 * `:home` is still `org.json`-based in `androidMain`, so the honest choices were
 * to port `:home`'s whole JSON layer (the rest of the port's plan, not this
 * change's business) or to let `:home` own the helper it was borrowing. **A
 * cross-module `public` that exists for a single caller and whose signature a
 * node-type change invalidates is a coupling with no other content**, so
 * `:backend`'s went back to `internal` and this copy came here.
 *
 * **The two copies differ in one answer, and it is deliberate.** This one keeps
 * `org.json`'s `else -> value.toString()`, so a nested object or array under a
 * string-map key reads as its JSON text and is **kept**; `:backend`'s
 * `commonMain` copy casts to `JsonPrimitive` and **drops** a container instead.
 * They cannot be one function because they are two node types, and making them
 * agree would mean either teaching `JsonElement` to stringify containers or
 * teaching `org.json` to drop them — and the second is the behaviour `:home`'s
 * cache already stores on disk.
 */
internal fun JSONObject?.toStringMap(): Map<String, String> {
    val obj = this ?: return emptyMap()
    val keys = obj.keys()
    val result = linkedMapOf<String, String>()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.opt(key) ?: continue
        val normalized =
            when (value) {
                JSONObject.NULL -> null
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> value.toString()
            }?.trim()
        if (!normalized.isNullOrBlank()) {
            result[key] = normalized
        }
    }
    return result
}
