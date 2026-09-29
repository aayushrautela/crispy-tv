package com.crispy.tv.ui.components

import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_account_balance
import com.crispy.tv.ui.resources.ic_auto_awesome
import com.crispy.tv.ui.resources.ic_bolt
import com.crispy.tv.ui.resources.ic_emoji_events
import com.crispy.tv.ui.resources.ic_favorite
import com.crispy.tv.ui.resources.ic_flash_on
import com.crispy.tv.ui.resources.ic_gavel
import com.crispy.tv.ui.resources.ic_group
import com.crispy.tv.ui.resources.ic_live_tv
import com.crispy.tv.ui.resources.ic_map
import com.crispy.tv.ui.resources.ic_masks
import com.crispy.tv.ui.resources.ic_movie
import com.crispy.tv.ui.resources.ic_music_note
import com.crispy.tv.ui.resources.ic_rocket
import com.crispy.tv.ui.resources.ic_search
import com.crispy.tv.ui.resources.ic_sentiment_very_satisfied
import com.crispy.tv.ui.resources.ic_shield
import com.crispy.tv.ui.resources.ic_skull
import com.crispy.tv.ui.resources.ic_theaters
import com.crispy.tv.ui.resources.ic_videocam
import org.jetbrains.compose.resources.DrawableResource

/**
 * Single source of truth for genre glyphs, kept in sync with the web client's
 * `src/lib/genreIcons.tsx` mapping so home hero metadata and the discover genre
 * picker render the same icon for a given genre key.
 *
 * Only use this for vector glyphs. `SearchGenreSuggestion.imageResId` points at
 * raster artwork instead, which must be drawn with `Image`, never `Icon`
 * (tinting flattens the photo into a solid block).
 */
fun genreIcon(genre: String): DrawableResource {
    return when (genre.trim().lowercase()) {
        "action" -> Res.drawable.ic_bolt
        "adventure" -> Res.drawable.ic_map
        "animated", "animation" -> Res.drawable.ic_theaters
        "comedy" -> Res.drawable.ic_sentiment_very_satisfied
        "crime" -> Res.drawable.ic_gavel
        "documentary" -> Res.drawable.ic_videocam
        "drama" -> Res.drawable.ic_masks
        "family" -> Res.drawable.ic_group
        "fantasy" -> Res.drawable.ic_auto_awesome
        "horror" -> Res.drawable.ic_skull
        "history" -> Res.drawable.ic_account_balance
        "music" -> Res.drawable.ic_music_note
        "mystery" -> Res.drawable.ic_search
        "reality" -> Res.drawable.ic_live_tv
        "romance" -> Res.drawable.ic_favorite
        "scifi", "sci-fi", "science fiction", "sci-fi & fantasy", "sci fi & fantasy",
        "sci-fi and fantasy" -> Res.drawable.ic_rocket
        "sport" -> Res.drawable.ic_emoji_events
        "thriller" -> Res.drawable.ic_flash_on
        "tv movie" -> Res.drawable.ic_live_tv
        "war" -> Res.drawable.ic_shield
        "western" -> Res.drawable.ic_movie
        else -> Res.drawable.ic_movie
    }
}
