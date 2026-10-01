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
 * **It was declared in `androidMain` for exactly as long as its three
 * consumers were, and it moved on its own with no import changed.** That file
 * named not one `android.*` type: the source set it sat in *was* the pin, and
 * a pin that is a file's location rather than its contents is invisible to an
 * import scan. The dependency followed the file down
 * (`commonMain.dependencies`, out of `androidMain.dependencies`), because **a
 * file's dependency belongs in the source set the file is in, not where it
 * used to be** -- see `android/addons/build.gradle.kts`.
 *
 * **What the move bought is not the declaration, it is the test source set.**
 * Eight of these accessors had **no coverage at all**, because `androidMain`
 * can only be tested from `androidHostTest` and no one had written one; from
 * `commonMain` the same eight are nameable from `commonTest`, and
 * `JsonAccessorsTest` now drives every policy on the code below directly
 * instead of through whichever of the three consumers happened to reach it.
 *
 * **These are the same shapes `:app` carries in
 * `LibraryDiskCacheJsonAccessors.kt`, and they are duplicated rather than
 * shared** -- `internal` is module-scoped, so `:addons` cannot name a
 * declaration in `:app` and `:app` cannot name one here. The duplication is
 * also *asymmetric*, which is the part worth knowing before attempting a
 * consolidation: **`:app`'s copy is a superset of thirteen, all thirteen of
 * them called, while this one had eight of eleven called.** So the stale copy
 * was this one, and the three accessors deleted when this file moved --
 * `optBooleanOrTrue`, `optStringOrThrow` and `optBooleanOrThrow` -- were live
 * in `:app` and dead here. **That is the shape of the trap, and it nearly went
 * the other way**: grepping those three names repo-wide returns five `:app`
 * call sites and zero `:addons` ones, because `com.crispy.tv.library` declares
 * its own functions under *identical simple names*, so a name-only search
 * crosses a module boundary and answers about the wrong declaration. The
 * count that settles it is scoped to the only compilation unit that can see
 * an `internal` declaration at all. Consolidation is therefore not yet
 * mechanical: `:app` and `:backend` already hold two copies of
 * `optNullableString` that disagree, and a merge is a port only once the
 * bodies have been diffed, never because the names line up.
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
 * `optBooleanOrNull`: the boolean, or `null` for anything `org.json` would not
 * read as one. **`optBoolean` is strict on every implementation**, so a stored
 * non-boolean is `null` here and was `false` there -- which is the difference
 * between "not true" and "not a boolean", and the only reason both this and
 * [optBooleanOrFalse] exist.
 */
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

/** The `?.let`-free read of a nullable receiver, kept because callers rely on `null` for absent. */
internal fun JsonArray.stringAtOrEmpty(index: Int): String =
    (getOrNull(index) as? JsonPrimitive)?.contentOrNull ?: ""

/**
 * `optBoolean(key)` -- the **one-argument** form, which defaults to `false`.
 *
 * This used to sit beside a second accessor of the same behaviour named
 * `optBooleanOrTrue`, whose KDoc claimed the two were *not* negations of each
 * other -- that a present non-boolean answers `true` here and `false` there.
 * **The two bodies were byte-identical** (`jsonPrimitiveOrNull(key)
 * ?.booleanOrNull == true`), and `jsonPrimitiveOrNull` excludes `JsonNull`
 * while `booleanOrNull` is strict, so a present non-boolean answered `false`
 * in both. **The claimed difference was not observable, and the accessor
 * carrying it had no callers in this module**, so it is gone rather than kept
 * as a second name for one answer. Note that it was *not* dead everywhere:
 * `com.crispy.tv.library` in `:app` declares its own `optBooleanOrThrow`, and
 * a name-only grep cannot tell the two apart. See the class KDoc.
 */
internal fun JsonObject.optBooleanOrFalse(key: String): Boolean =
    jsonPrimitiveOrNull(key)?.booleanOrNull == true
