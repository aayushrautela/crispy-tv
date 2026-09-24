package com.crispy.tv.search

import com.crispy.tv.R

enum class SearchGenreSuggestion(
    val key: String,
    val label: String,
    val imageResId: Int,
) {
    ACTION(
        key = "action",
        label = "Action",
        imageResId = R.drawable.genre_action,
    ),
    ANIMATED(
        key = "animated",
        label = "Animated",
        imageResId = R.drawable.genre_animated,
    ),
    COMEDY(
        key = "comedy",
        label = "Comedy",
        imageResId = R.drawable.genre_comedy,
    ),
    DOCUMENTARY(
        key = "documentary",
        label = "Documentary",
        imageResId = R.drawable.genre_documentary,
    ),
    DRAMA(
        key = "drama",
        label = "Drama",
        imageResId = R.drawable.genre_drama,
    ),
    FAMILY(
        key = "family",
        label = "Family",
        imageResId = R.drawable.genre_family,
    ),
    FANTASY(
        key = "fantasy",
        label = "Fantasy",
        imageResId = R.drawable.genre_fantasy,
    ),
    HORROR(
        key = "horror",
        label = "Horror",
        imageResId = R.drawable.genre_horror,
    ),
    MYSTERY(
        key = "mystery",
        label = "Mystery",
        imageResId = R.drawable.genre_mystery,
    ),
    ROMANCE(
        key = "romance",
        label = "Romance",
        imageResId = R.drawable.genre_romance,
    ),
    SCI_FI(
        key = "scifi",
        label = "Sci-Fi",
        imageResId = R.drawable.genre_scifi,
    ),
    THRILLER(
        key = "thriller",
        label = "Thriller",
        imageResId = R.drawable.genre_thriller,
    ),
}
