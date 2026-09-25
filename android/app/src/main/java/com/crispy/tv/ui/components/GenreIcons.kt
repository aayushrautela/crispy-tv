package com.crispy.tv.ui.components

import androidx.annotation.DrawableRes
import com.crispy.tv.ui.assets.R

/**
 * Single source of truth for genre glyphs, kept in sync with the web client's
 * `src/lib/genreIcons.tsx` mapping so home hero metadata and the discover genre
 * picker render the same icon for a given genre key.
 *
 * Only use this for vector glyphs. `SearchGenreSuggestion.imageResId` points at
 * raster artwork instead, which must be drawn with `Image`, never `Icon`
 * (tinting flattens the photo into a solid block).
 */
@DrawableRes
internal fun genreIcon(genre: String): Int {
    return when (genre.trim().lowercase()) {
        "action" -> R.drawable.ic_bolt
        "adventure" -> R.drawable.ic_map
        "animated", "animation" -> R.drawable.ic_theaters
        "comedy" -> R.drawable.ic_sentiment_very_satisfied
        "crime" -> R.drawable.ic_gavel
        "documentary" -> R.drawable.ic_videocam
        "drama" -> R.drawable.ic_masks
        "family" -> R.drawable.ic_group
        "fantasy" -> R.drawable.ic_auto_awesome
        "horror" -> R.drawable.ic_skull
        "history" -> R.drawable.ic_account_balance
        "music" -> R.drawable.ic_music_note
        "mystery" -> R.drawable.ic_search
        "reality" -> R.drawable.ic_live_tv
        "romance" -> R.drawable.ic_favorite
        "scifi", "sci-fi", "science fiction", "sci-fi & fantasy", "sci fi & fantasy",
        "sci-fi and fantasy" -> R.drawable.ic_rocket
        "sport" -> R.drawable.ic_emoji_events
        "thriller" -> R.drawable.ic_flash_on
        "tv movie" -> R.drawable.ic_live_tv
        "war" -> R.drawable.ic_shield
        "western" -> R.drawable.ic_movie
        else -> R.drawable.ic_movie
    }
}
