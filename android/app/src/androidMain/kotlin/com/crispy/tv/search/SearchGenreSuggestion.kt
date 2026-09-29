package com.crispy.tv.search

import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.genre_action
import com.crispy.tv.ui.resources.genre_animated
import com.crispy.tv.ui.resources.genre_comedy
import com.crispy.tv.ui.resources.genre_documentary
import com.crispy.tv.ui.resources.genre_drama
import com.crispy.tv.ui.resources.genre_family
import com.crispy.tv.ui.resources.genre_fantasy
import com.crispy.tv.ui.resources.genre_horror
import com.crispy.tv.ui.resources.genre_mystery
import com.crispy.tv.ui.resources.genre_romance
import com.crispy.tv.ui.resources.genre_scifi
import com.crispy.tv.ui.resources.genre_thriller
import org.jetbrains.compose.resources.DrawableResource

enum class SearchGenreSuggestion(
    val key: String,
    val label: String,
    val imageResId: DrawableResource,
) {
    ACTION(
        key = "action",
        label = "Action",
        imageResId = Res.drawable.genre_action,
    ),
    ANIMATED(
        key = "animated",
        label = "Animated",
        imageResId = Res.drawable.genre_animated,
    ),
    COMEDY(
        key = "comedy",
        label = "Comedy",
        imageResId = Res.drawable.genre_comedy,
    ),
    DOCUMENTARY(
        key = "documentary",
        label = "Documentary",
        imageResId = Res.drawable.genre_documentary,
    ),
    DRAMA(
        key = "drama",
        label = "Drama",
        imageResId = Res.drawable.genre_drama,
    ),
    FAMILY(
        key = "family",
        label = "Family",
        imageResId = Res.drawable.genre_family,
    ),
    FANTASY(
        key = "fantasy",
        label = "Fantasy",
        imageResId = Res.drawable.genre_fantasy,
    ),
    HORROR(
        key = "horror",
        label = "Horror",
        imageResId = Res.drawable.genre_horror,
    ),
    MYSTERY(
        key = "mystery",
        label = "Mystery",
        imageResId = Res.drawable.genre_mystery,
    ),
    ROMANCE(
        key = "romance",
        label = "Romance",
        imageResId = Res.drawable.genre_romance,
    ),
    SCI_FI(
        key = "scifi",
        label = "Sci-Fi",
        imageResId = Res.drawable.genre_scifi,
    ),
    THRILLER(
        key = "thriller",
        label = "Thriller",
        imageResId = Res.drawable.genre_thriller,
    ),
}
