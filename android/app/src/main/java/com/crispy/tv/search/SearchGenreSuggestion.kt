package com.crispy.tv.search

import com.crispy.tv.R

enum class SearchGenreSuggestion(
    val label: String,
    val imageResId: Int,
) {
    ACTION(
        label = "Action",
        imageResId = R.drawable.genre_action,
    ),
    ANIMATED(
        label = "Animated",
        imageResId = R.drawable.genre_animated,
    ),
    COMEDY(
        label = "Comedy",
        imageResId = R.drawable.genre_comedy,
    ),
    DOCUMENTARY(
        label = "Documentary",
        imageResId = R.drawable.genre_documentary,
    ),
    DRAMA(
        label = "Drama",
        imageResId = R.drawable.genre_drama,
    ),
    FAMILY(
        label = "Family",
        imageResId = R.drawable.genre_family,
    ),
    FANTASY(
        label = "Fantasy",
        imageResId = R.drawable.genre_fantasy,
    ),
    HORROR(
        label = "Horror",
        imageResId = R.drawable.genre_horror,
    ),
    MYSTERY(
        label = "Mystery",
        imageResId = R.drawable.genre_mystery,
    ),
    ROMANCE(
        label = "Romance",
        imageResId = R.drawable.genre_romance,
    ),
    SCI_FI(
        label = "Sci-Fi",
        imageResId = R.drawable.genre_scifi,
    ),
    THRILLER(
        label = "Thriller",
        imageResId = R.drawable.genre_thriller,
    ),
}
