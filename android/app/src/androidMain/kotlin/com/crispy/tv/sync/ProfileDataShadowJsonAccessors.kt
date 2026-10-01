package com.crispy.tv.sync

import org.json.JSONObject

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
 * sides in `androidHostTest` and the duplication stays until consolidating it is
 * a deliberate act.
 */
internal fun JSONObject.toStringMap(): Map<String, String> {
    val iter = keys()
    val result = mutableMapOf<String, String>()
    while (iter.hasNext()) {
        val key = iter.next()
        val rawValue = opt(key)
        if (rawValue == null || rawValue == JSONObject.NULL) continue
        result[key] = rawValue.toString()
    }
    return result
}

internal fun Map<String, String>.toJsonObject(): JSONObject {
    val obj = JSONObject()
    for (key in keys.sorted()) {
        obj.put(key, getValue(key))
    }
    return obj
}
