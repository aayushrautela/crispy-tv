package com.crispy.tv.settings

import com.crispy.tv.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the player's app-drawn subtitles look.
 *
 * The colours are stored as the web app's `#AARRGGBB` hex strings, under the
 * web app's own key names (`subtitleColor`, `subtitleBackColor`,
 * `subtitleBorderColor`, `subtitleSize`, `subtitlePosition`), so what survives a
 * sync is the shared *format* and not merely the values: the parse lives here,
 * in the one place both writers use, and a setting the app has never touched
 * still reads the web app's choice rather than a default.
 *
 * [sizePercent] is a multiplier over the cue's base font size (100 = the base),
 * and [positionPercent] is how far above the picture's bottom edge the cue sits
 * -- both the same units the web app's renderer uses.
 */
data class SubtitleAppearance(
    val textColorHex: String = DEFAULT_TEXT_COLOR_HEX,
    val backgroundHex: String = DEFAULT_BACKGROUND_HEX,
    val borderColorHex: String = DEFAULT_BORDER_COLOR_HEX,
    val sizePercent: Int = DEFAULT_SIZE_PERCENT,
    val positionPercent: Int = DEFAULT_POSITION_PERCENT,
) {
    /** The box is drawn only when its background carries a non-zero alpha. */
    val hasBackground: Boolean
        get() = SubtitleHex.hasVisibleBackground(backgroundHex)

    /**
     * The outline is drawn only when there is no visible box, because the box
     * already supplies the contrast and the white (inverted) box would grow a
     * halo -- the web app's own edge rule.
     */
    val hasOutline: Boolean
        get() = !hasBackground

    val textColor: Long
        get() = SubtitleHex.parse(textColorHex, DEFAULT_TEXT_COLOR)

    val background: Long
        get() = SubtitleHex.parse(backgroundHex, DEFAULT_BACKGROUND_COLOR)

    val borderColor: Long
        get() = SubtitleHex.parse(borderColorHex, DEFAULT_BORDER_COLOR)

    /**
     * Whether [box] is the box currently drawn.
     *
     * The comparison goes through [SubtitleHex.parse] so a stored 6-digit hex
     * and an enum's 8-digit one can still match: a settings screen that read
     * the raw string would show no box selected at all, with no way to tell
     * why.
     */
    fun matchesBox(box: SubtitleBoxStyle): Boolean =
        SubtitleHex.toHex(background) == box.backgroundHex

    fun matchesTextColor(color: SubtitleTextColor): Boolean =
        SubtitleHex.toHex(textColor) == SubtitleHex.toHex(color.argb)

    companion object {
        const val DEFAULT_TEXT_COLOR_HEX = "#FFFFFFFF"
        const val DEFAULT_BACKGROUND_HEX = "#00000000"
        const val DEFAULT_BORDER_COLOR_HEX = "#FF000000"
        const val DEFAULT_SIZE_PERCENT = 100
        const val DEFAULT_POSITION_PERCENT = 5
        const val MIN_SIZE_PERCENT = 50
        const val MAX_SIZE_PERCENT = 200
        const val MIN_POSITION_PERCENT = 0
        const val MAX_POSITION_PERCENT = 50
        const val SIZE_STEP = 10
        const val POSITION_STEP = 5

        /** The fallbacks a stored hex is rejected in favour of. */
        const val DEFAULT_TEXT_COLOR = 0xFFFFFFFFL
        const val DEFAULT_BACKGROUND_COLOR = 0x00000000L
        const val DEFAULT_BORDER_COLOR = 0xFF000000L
    }
}

/**
 * The box styles the web app ships as `BOX_STYLES`, plus the "no box" entry the
 * web app keeps as its `clean` base preset.
 *
 * [textColorHex] travels with the style because the web app's own `selectBox`
 * writes all four fields: the inverted box's white background would hide white
 * text, so the style that changes the background has to answer what colour the
 * text is on top of it.
 */
enum class SubtitleBoxStyle(
    val backgroundHex: String,
    val borderHex: String,
    val textColorHex: String,
    val label: String,
) {
    None("#00000000", "#FF000000", "#FFFFFFFF", "None"),
    Soft("#80000000", "#FF000000", "#FFFFFFFF", "Soft"),
    Solid("#FF000000", "#FF000000", "#FFFFFFFF", "Solid"),
    Inverted("#BFFFFFFF", "#BFFFFFFF", "#FF000000", "Inverted"),
}

/** The three text colours the web app ships as `TEXT_COLORS`. */
enum class SubtitleTextColor(val argb: Long, val label: String) {
    White(0xFFFFFFFFL, "White"),
    Yellow(0xFFFFFF00L, "Yellow"),
    Cyan(0xFF00FFFFL, "Cyan"),
}

interface SubtitleSettingsRepository {
    val appearance: StateFlow<SubtitleAppearance>

    /** Picks a box, which also sets the text colour that reads on it. */
    fun selectBoxStyle(box: SubtitleBoxStyle)

    fun selectTextColor(color: SubtitleTextColor)

    fun setSizePercent(percent: Int)

    fun setPositionPercent(percent: Int)
}

/**
 * [SubtitleSettingsRepository] over any [KeyValueStore].
 *
 * There is no change listener, for the same reason as
 * [KeyValueStoreImageSettingsRepository]: a second writer on these preferences
 * would be the only thing that could need one, and there is none.
 */
class KeyValueStoreSubtitleSettingsRepository(private val store: KeyValueStore) :
    SubtitleSettingsRepository {
    private val _appearance = MutableStateFlow(read())
    override val appearance: StateFlow<SubtitleAppearance> = _appearance.asStateFlow()

    override fun selectBoxStyle(box: SubtitleBoxStyle) {
        update(
            _appearance.value.copy(
                backgroundHex = box.backgroundHex,
                borderColorHex = box.borderHex,
                textColorHex = box.textColorHex,
            ),
        )
    }

    override fun selectTextColor(color: SubtitleTextColor) {
        update(_appearance.value.copy(textColorHex = SubtitleHex.toHex(color.argb)))
    }

    override fun setSizePercent(percent: Int) {
        update(
            _appearance.value.copy(
                sizePercent =
                    percent.coerceIn(
                        SubtitleAppearance.MIN_SIZE_PERCENT,
                        SubtitleAppearance.MAX_SIZE_PERCENT,
                    ),
            ),
        )
    }

    override fun setPositionPercent(percent: Int) {
        update(
            _appearance.value.copy(
                positionPercent =
                    percent.coerceIn(
                        SubtitleAppearance.MIN_POSITION_PERCENT,
                        SubtitleAppearance.MAX_POSITION_PERCENT,
                    ),
            ),
        )
    }

    private fun update(next: SubtitleAppearance) {
        if (next == _appearance.value) return
        _appearance.value = next
        store.putString(KEY_TEXT_COLOR, next.textColorHex)
        store.putString(KEY_BACKGROUND, next.backgroundHex)
        store.putString(KEY_BORDER_COLOR, next.borderColorHex)
        store.putInt(KEY_SIZE, next.sizePercent)
        store.putInt(KEY_POSITION, next.positionPercent)
    }

    private fun read(): SubtitleAppearance =
        SubtitleAppearance(
            textColorHex =
                store.getString(KEY_TEXT_COLOR) ?: SubtitleAppearance.DEFAULT_TEXT_COLOR_HEX,
            backgroundHex =
                store.getString(KEY_BACKGROUND) ?: SubtitleAppearance.DEFAULT_BACKGROUND_HEX,
            borderColorHex =
                store.getString(KEY_BORDER_COLOR) ?: SubtitleAppearance.DEFAULT_BORDER_COLOR_HEX,
            sizePercent =
                store.getInt(KEY_SIZE, SubtitleAppearance.DEFAULT_SIZE_PERCENT)
                    .coerceAtLeast(SubtitleAppearance.MIN_SIZE_PERCENT),
            positionPercent =
                store.getInt(KEY_POSITION, SubtitleAppearance.DEFAULT_POSITION_PERCENT)
                    .coerceIn(
                        SubtitleAppearance.MIN_POSITION_PERCENT,
                        SubtitleAppearance.MAX_POSITION_PERCENT,
                    ),
        )

    companion object {
        private const val KEY_TEXT_COLOR = "subtitleColor"
        private const val KEY_BACKGROUND = "subtitleBackColor"
        private const val KEY_BORDER_COLOR = "subtitleBorderColor"
        private const val KEY_SIZE = "subtitleSize"
        private const val KEY_POSITION = "subtitlePosition"
    }
}

/**
 * The `#AARRGGBB` hex format the web app reads and writes.
 *
 * The interesting part is what a malformed value answers: a hex that does not
 * parse, or that has the wrong digit count, falls back to the default rather
 * than to a transparent box, because a silently invisible subtitle is a
 * failure nobody reports and an opaque one is at least visible.
 */
internal object SubtitleHex {
    /**
     * `true` when the hex names a colour with a non-zero alpha, the web app's
     * own `hasBackground`: a 6-digit hex is fully opaque, an 8-digit one is
     * visible unless its alpha is `00`.
     */
    fun hasVisibleBackground(hex: String): Boolean {
        val digits = normalize(hex) ?: return false
        if (digits.length == 6) return true
        if (digits.length != 8) return false
        return !digits.startsWith("00")
    }

    fun parse(hex: String, fallback: Long): Long {
        val digits = normalize(hex) ?: return fallback
        return when (digits.length) {
            6 -> 0xFF000000L or digits.toLong(16)
            8 -> digits.toLong(16)
            else -> fallback
        }
    }

    fun toHex(argb: Long): String {
        // Long.toString(16) has no padding, so a colour with leading zeroes
        // (red is 0xFFFF0000 but green is 0xFF00FF00) would shorten the string
        // and lose its alpha digit.
        return "#" + (argb and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()
    }

    /** Uppercased, `#`-stripped, or `null` when the text is not hex at all. */
    private fun normalize(hex: String): String? {
        val trimmed = hex.trim().removePrefix("#")
        if (trimmed.isEmpty()) return null
        return trimmed.uppercase().takeIf { value -> value.all { it in "0123456789ABCDEF" } }
    }
}
