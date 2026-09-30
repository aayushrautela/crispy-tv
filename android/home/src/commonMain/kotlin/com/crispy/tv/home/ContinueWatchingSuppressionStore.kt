package com.crispy.tv.home

import com.crispy.tv.platform.KeyValueStore

/**
 * Remembers which continue-watching entries the user dismissed, so a refresh
 * does not resurrect them until the underlying content actually updates.
 *
 * It used to own a `SharedPreferences` file and parse it with `org.json`.
 * Both are Android-only -- the store is a class of the platform and `org.json`
 * a class of `android.jar` -- so it now persists through [KeyValueStore] and
 * reads and writes the payload with the small codec below.
 *
 * The on-disk format is unchanged: one flat JSON object mapping content key to
 * suppression timestamp, `{"tmdb:123":1717000000000}`. Keeping the exact bytes
 * means an upgrade neither resurrects every dismissal nor strands the file,
 * and the codec round-trips through `SuppressionStoreTest` rather than through
 * `org.json` on any target.
 */
class ContinueWatchingSuppressionStore(
    private val store: KeyValueStore,
) {
    fun read(): MutableMap<String, Long> = parseSuppressions(store.getString(KEY_ITEM_SUPPRESSIONS))

    fun write(value: Map<String, Long>) {
        if (value.isEmpty()) {
            store.remove(KEY_ITEM_SUPPRESSIONS)
            return
        }
        store.putString(KEY_ITEM_SUPPRESSIONS, renderSuppressions(value))
    }

    companion object {
        /**
         * The key inside the store. The *file* name lives with the Android
         * factory: renaming it would strand every install's dismissals, while
         * this key is namespaced by the store itself.
         */
        const val KEY_ITEM_SUPPRESSIONS = "suppressed_items"
    }
}

/**
 * Parses the flat `{key: timestamp}` payload. Anything malformed -- a blank
 * file, a truncated write, a value that is not a number -- reads as no
 * suppressions rather than failing the whole home load.
 */
internal fun parseSuppressions(raw: String?): MutableMap<String, Long> {
    val map = mutableMapOf<String, Long>()
    val text = raw?.trim() ?: return map
    if (!text.startsWith("{") || !text.endsWith("}")) return map
    var index = 1
    val end = text.length - 1
    while (index < end) {
        index = skipSeparators(text, index, end)
        if (index >= end) break
        if (text[index] != '"') return mutableMapOf()
        val keyResult = readQuoted(text, index, end) ?: return mutableMapOf()
        index = skipSeparators(text, keyResult.next, end)
        if (index >= end || text[index] != ':') return mutableMapOf()
        index = skipSeparators(text, index + 1, end)
        val numberResult = readLong(text, index, end) ?: return mutableMapOf()
        if (numberResult.value > 0L) {
            map[keyResult.value] = numberResult.value
        }
        index = skipSeparators(text, numberResult.next, end)
        if (index < end && text[index] == ',') index++
    }
    return map
}

private data class ParsedText(
    val value: String,
    val next: Int,
)

private data class ParsedLong(
    val value: Long,
    val next: Int,
)

private fun skipSeparators(
    text: String,
    from: Int,
    end: Int,
): Int {
    var index = from
    while (index < end && (text[index].isWhitespace() || text[index] == ',')) index++
    return index
}

private fun readQuoted(
    text: String,
    from: Int,
    end: Int,
): ParsedText? {
    val builder = StringBuilder()
    var index = from + 1
    while (index < end) {
        val char = text[index]
        if (char == '"') return ParsedText(builder.toString(), index + 1)
        if (char == '\\' && index + 1 < end) {
            val escaped = text[index + 1]
            builder.append(
                when (escaped) {
                    '"', '\\', '/' -> escaped
                    'n' -> '\n'
                    't' -> '\t'
                    'u' -> {
                        if (index + 5 >= end) return null
                        val hex = text.substring(index + 2, index + 6)
                        val code = hex.toIntOrNull(16) ?: return null
                        index += 4
                        code.toChar()
                    }
                    else -> return null
                },
            )
            index += 2
        } else {
            builder.append(char)
            index++
        }
    }
    return null
}

private fun readLong(
    text: String,
    from: Int,
    end: Int,
): ParsedLong? {
    var index = from
    var negative = false
    if (index < end && (text[index] == '-' || text[index] == '+')) {
        negative = text[index] == '-'
        index++
    }
    var value = 0L
    var digits = 0
    while (index < end && text[index].isDigit()) {
        value = value * 10L + (text[index] - '0')
        digits++
        index++
    }
    if (digits == 0) return null
    return ParsedLong(if (negative) -value else value, index)
}

private fun StringBuilder.appendUnicodeEscape(code: Int) {
    // `String.format` is JVM-only and invisible to the import-based purity gate
    // (`"".format(x)` is `kotlin.*`, so only an Apple compile catches it). A
    // `\uXXXX` escape is a fixed 4-hex-digit pad and nothing else.
    append("\\u")
    append(code.toString(16).padStart(4, '0'))
}

/**
 * Renders the flat `{key: timestamp}` payload. Keys escape `"`, `\` and
 * control characters so the output stays valid JSON no matter what a content
 * key contains; [parseSuppressions] reads it back.
 */
internal fun renderSuppressions(value: Map<String, Long>): String =
    buildString {
        append('{')
        value.entries.forEachIndexed { entryIndex, (key, timestamp) ->
            if (entryIndex > 0) append(',')
            append('"')
            key.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') appendUnicodeEscape(char.code) else append(char)
                }
            }
            append("\":")
            append(timestamp)
        }
        append('}')
    }
