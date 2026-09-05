package com.crispy.tv.plugins

/**
 * Encodes the positional arguments for the `getStreams(tmdbId, mediaType, season, episode)`
 * call site. Strings are JSON-quoted; null season/episode become the `undefined` literal,
 * exactly like the Nuvio runtime builds its call code.
 */
internal object PluginJsArgs {

    fun callArguments(input: PluginStreamInput): String {
        val tmdbId = string(input.tmdbId)
        val mediaType = string(input.mediaType)
        val season = numberOrUndefined(input.season)
        val episode = numberOrUndefined(input.episode)
        return "$tmdbId, $mediaType, $season, $episode"
    }

    /** Lookup context exposed on the `crispy` global: both ids, equal citizens. */
    fun lookupObjectJson(input: PluginStreamInput): String {
        val tmdbId = string(input.tmdbId)
        val imdbId = string(input.imdbId)
        val mediaType = string(input.mediaType)
        return """{"tmdbId":$tmdbId,"imdbId":$imdbId,"mediaType":$mediaType,"season":${jsonNumberOrNull(input.season)},"episode":${jsonNumberOrNull(input.episode)}}"""
    }

    fun string(value: String): String = quote(value)

    fun numberOrUndefined(value: Int?): String = value?.toString() ?: "undefined"

    private fun jsonNumberOrNull(value: Int?): String = value?.toString() ?: "null"

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    if (char < ' ') {
                        append("\\u%04x".format(char.code))
                    } else {
                        append(char)
                    }
            }
        }
        append('"')
    }
}
