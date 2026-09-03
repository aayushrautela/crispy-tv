package com.crispy.tv.plugins

internal object PluginInputJson {

    fun encode(input: PluginStreamInput): String {
        return buildString {
            append('{')
            append("\"tmdbId\":").append(input.tmdbId)
            input.imdbId?.let { append(",\"imdbId\":").append(quote(it)) }
            append(",\"mediaType\":").append(quote(input.mediaType))
            input.season?.let { append(",\"season\":").append(it) }
            input.episode?.let { append(",\"episode\":").append(it) }
            append(",\"title\":").append(quote(input.title))
            input.year?.let { append(",\"year\":").append(it) }
            if (input.settings.isNotEmpty()) {
                append(",\"settings\":{")
                input.settings.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    append(quote(key)).append(':').append(quote(value))
                }
                append('}')
            }
            append('}')
        }
    }

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
                '' -> append("\\f")
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
