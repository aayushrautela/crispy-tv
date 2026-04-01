package com.crispy.tv.backend

import com.crispy.tv.backend.CrispyBackendClient.MediaLookupInput
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.putLibraryResolveInput(input: MediaLookupInput) {
    val effectiveProvider =
        input.provider?.trim()?.takeIf { it.isNotBlank() }
            ?: input.tvdbId?.toString()?.let { "tvdb" }
            ?: input.tmdbId?.toString()?.let { "tmdb" }
    val effectiveProviderId =
        input.providerId?.trim()?.takeIf { it.isNotBlank() }
            ?: input.tvdbId?.toString()
            ?: input.tmdbId?.toString()
    if (!input.id.isNullOrBlank()) put("id", input.id.trim())
    if (!input.imdbId.isNullOrBlank()) put("imdbId", input.imdbId.trim())
    if (!input.mediaType.isNullOrBlank()) put("mediaType", input.mediaType.trim())
    if (!effectiveProvider.isNullOrBlank()) put("provider", effectiveProvider)
    if (!effectiveProviderId.isNullOrBlank()) put("providerId", effectiveProviderId)
    if (!input.parentProvider.isNullOrBlank()) put("parentProvider", input.parentProvider.trim())
    if (!input.parentProviderId.isNullOrBlank()) put("parentProviderId", input.parentProviderId.trim())
    if (input.seasonNumber != null) put("seasonNumber", input.seasonNumber)
    if (input.episodeNumber != null) put("episodeNumber", input.episodeNumber)
}

internal fun Map<String, Any?>.toJsonObject(): JSONObject {
    val json = JSONObject()
    for ((key, value) in this) {
        json.put(key, value.toJsonValue())
    }
    return json
}

internal fun Any?.toJsonValue(): Any? {
    return when (this) {
        null -> JSONObject.NULL
        JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> this
        is JSONArray -> this
        is Map<*, *> -> {
            val json = JSONObject()
            for ((key, value) in this) {
                if (key is String) {
                    json.put(key, value.toJsonValue())
                }
            }
            json
        }

        is Iterable<*> -> JSONArray().apply { this@toJsonValue.forEach { put(it.toJsonValue()) } }
        is Array<*> -> JSONArray().apply { this@toJsonValue.forEach { put(it.toJsonValue()) } }
        else -> this
    }
}

internal fun JSONObject?.toAnyMap(): Map<String, Any?> {
    val obj = this ?: return emptyMap()
    val keys = obj.keys()
    val result = linkedMapOf<String, Any?>()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = obj.opt(key).toKotlinValue()
    }
    return result
}

internal fun JSONArray?.toAnyList(): List<Any?> {
    val array = this ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            add(array.opt(index).toKotlinValue())
        }
    }
}

internal fun Any?.toKotlinValue(): Any? {
    return when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> this.toAnyMap()
        is JSONArray -> this.toAnyList()
        else -> this
    }
}

internal fun JSONObject?.optNullableString(key: String): String? {
    return this?.optString(key)?.trim().orEmpty().ifBlank { null }
}

internal fun JSONObject.optIntOrNull(key: String): Int? {
    return when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
}

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    return when (val value = opt(key)) {
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

internal fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.opt(index)?.toString()?.trim().orEmpty()
            if (item.isNotBlank()) {
                add(item)
            }
        }
    }
}

internal fun JSONObject.optIntList(key: String): List<Int> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is Number -> add(item.toInt())
                is String -> item.trim().toIntOrNull()?.let(::add)
            }
        }
    }
}

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
