package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.PluginExecutionResult
import com.crispy.tv.plugins.PluginSetting
import com.crispy.tv.plugins.PluginStream
import com.crispy.tv.plugins.PluginSubtitle

internal object PluginResultMapper {

    fun mapStreams(value: Any?): List<PluginStream> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { entry -> mapStream(entry) }
    }

    private fun mapStream(value: Any?): PluginStream? {
        if (value !is Map<*, *>) return null
        val url = stringOrNull(value["url"]) ?: return null
        if (url.isBlank()) return null

        return PluginStream(
            name = stringOrNull(value["name"]) ?: "Unknown",
            url = url,
            quality = stringOrNull(value["quality"]),
            headers = mapStrings(value["headers"]),
            referer = stringOrNull(value["referer"]),
            subtitles = mapSubtitles(value["subtitles"]),
            sizeBytes = (value["size"] as? Number)?.toLong(),
            audio = stringOrNull(value["audio"]),
            filename = stringOrNull(value["filename"]),
        )
    }

    private fun mapSubtitles(value: Any?): List<PluginSubtitle> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { entry ->
            if (entry !is Map<*, *>) return@mapNotNull null
            val url = stringOrNull(entry["url"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PluginSubtitle(
                url = url,
                lang = stringOrNull(entry["lang"]) ?: "Unknown",
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
}
