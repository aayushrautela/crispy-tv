package com.crispy.tv.nativeengine.playback

/**
 * Turns an engine's raw subtitle cue ([NativePlaybackSnapshot.currentSubtitle],
 * mpv's `sub-text`) into text a plain overlay can render, or `null` when the
 * cue carries no visible text.
 *
 * This is deliberately *not* a renderer: ASS positioning, fonts and colours
 * are a follow-up that needs libass rasterization. What it removes is the
 * markup the engine passes through verbatim -- ASS override blocks (`{\i1}`),
 * residual HTML-ish tags (`<i>`, `<font ...>`) and ASS line-break/hard-space
 * escapes (`\N`, `\n`, `\h`) -- because a `Text` composable would print those
 * literally. Blank lines are dropped so a cue that was only markup answers
 * `null` rather than an invisible box.
 */
fun sanitizeSubtitleCue(raw: String): String? {
    if (raw.isBlank()) return null
    var text = raw
    text = ASS_OVERRIDE_BLOCK_REGEX.replace(text, "")
    text = MARKUP_TAG_REGEX.replace(text, "")
    text = text
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ")
    val joined = text
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    return joined.takeIf { it.isNotEmpty() }
}

/**
 * `{\...}` — ASS/SSA override blocks, matched up to the closing brace.
 * Both braces are escaped: ICU (Android's `java.util.regex`) requires `{` and `}`
 * to be quoted to be treated as literals, and a bare `}` is a syntax error there.
 * The JVM accepts an unescaped `}`, which is why this crashed `<clinit>` on device
 * while the tests passed.
 */
private val ASS_OVERRIDE_BLOCK_REGEX = Regex("""\{[^}]*\}""")

/** `<i>`, `</b>`, `<font color=...>` — residual markup some containers leave in the text. */
private val MARKUP_TAG_REGEX = Regex("""</?[a-zA-Z][^>]*>""")
