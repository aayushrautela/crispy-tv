package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.PluginExecutionResult
import com.crispy.tv.plugins.PluginSetting
import com.crispy.tv.plugins.PluginStream
import com.crispy.tv.plugins.PluginSubtitle

internal object PluginResultMapper {

    fun mapStreams(value: Any?): List<PluginStream> {
        if (value !is List<*>) {
            android.util.Log.w(
                LOG_TAG,
                "unexpected getStreams result type: ${value?.let { it::class.simpleName } ?: "null"}",
            )
            return emptyList()
        }
        return value.mapNotNull { entry -> mapStream(entry) }
    }

    private fun mapStream(value: Any?): PluginStream? {
        if (value !is Map<*, *>) {
            android.util.Log.w(LOG_TAG, "dropped non-object result row")
            return null
        }
        val url = extractUrl(value["url"])
        val infoHash = stringOrNull(value["infoHash"])
        if (url.isNullOrBlank() && infoHash.isNullOrBlank()) {
            android.util.Log.w(
                LOG_TAG,
                "dropped row without url/infoHash: name=${stringOrNull(value["name"])} title=${stringOrNull(value["title"])}",
            )
            return null
        }

        val title = stringOrNull(value["title"])
        return PluginStream(
            name = stringOrNull(value["name"]) ?: title ?: "Unknown",
            title = title,
            url = url?.takeIf { it.isNotBlank() },
            quality = stringOrNull(value["quality"]),
            headers = mapStrings(value["headers"]),
            referer = stringOrNull(value["referer"]),
            subtitles = mapSubtitles(value["subtitles"]),
            sizeBytes = (value["size"] as? Number)?.toLong(),
            sizeLabel = (value["size"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            language = stringOrNull(value["language"]),
            provider = stringOrNull(value["provider"]),
            type = stringOrNull(value["type"]),
            seeders = (value["seeders"] as? Number)?.toInt(),
            peers = (value["peers"] as? Number)?.toInt(),
            infoHash = infoHash,
            audio = stringOrNull(value["audio"]),
            filename = stringOrNull(value["filename"]),
        )
    }

    private fun extractUrl(value: Any?): String? {
        stringOrNull(value)?.let { return it }
        if (value is Map<*, *>) return stringOrNull(value["url"])
        return null
    }

    private fun mapSubtitles(value: Any?): List<PluginSubtitle> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            val url = stringOrNull(entry["url"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lang = stringOrNull(entry["language"]) ?: stringOrNull(entry["lang"]) ?: "Unknown"
            PluginSubtitle(
                url = url,
                lang = lang,
                name = stringOrNull(entry["name"]),
                headers = mapStrings(entry["headers"]),
            )
        }
    }

    private fun mapStrings(value: Any?): Map<String, String> {
        if (value !is Map<*, *>) return emptyMap()
        return buildMap {
            value.forEach { (key, entry) ->
                val name = key?.toString() ?: return@forEach
                val headerValue = entry?.toString() ?: return@forEach
                put(name, headerValue)
            }
        }
    }

    private fun stringOrNull(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }

    fun result(streams: List<PluginStream>, settings: List<PluginSetting>) =
        PluginExecutionResult(streams = streams, settings = settings)

    private const val LOG_TAG = "CrispyPlugins"
}
