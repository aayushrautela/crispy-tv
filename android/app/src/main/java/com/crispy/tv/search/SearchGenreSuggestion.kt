package com.crispy.tv.search

import com.crispy.tv.R

enum class SearchGenreSuggestion(
    val label: String,
    val imageResId: Int,
    val movieGenreId: Int,
    val tvGenreId: Int? = null,
) {
    ACTION(
        label = "Action",
        imageResId = R.drawable.genre_action,
        movieGenreId = 28,
        tvGenreId = 10759,
    ),
    ANIMATED(
        label = "Animated",
        imageResId = R.drawable.genre_animated,
        movieGenreId = 16,
        tvGenreId = 16,
    ),
    COMEDY(
        label = "Comedy",
        imageResId = R.drawable.genre_comedy,
        movieGenreId = 35,
        tvGenreId = 35,
    ),
    DOCUMENTARY(
        label = "Documentary",
        imageResId = R.drawable.genre_documentary,
        movieGenreId = 99,
        tvGenreId = 99,
    ),
    DRAMA(
        label = "Drama",
        imageResId = R.drawable.genre_drama,
        movieGenreId = 18,
        tvGenreId = 18,
    ),
    FAMILY(
        label = "Family",
        imageResId = R.drawable.genre_family,
        movieGenreId = 10751,
        tvGenreId = 10751,
    ),
    FANTASY(
        label = "Fantasy",
        imageResId = R.drawable.genre_fantasy,
        movieGenreId = 14,
        tvGenreId = 10765,
    ),
    HORROR(
        label = "Horror",
        imageResId = R.drawable.genre_horror,
        movieGenreId = 27,
    ),
    MYSTERY(
        label = "Mystery",
        imageResId = R.drawable.genre_mystery,
        movieGenreId = 9648,
        tvGenreId = 9648,
    ),
    ROMANCE(
        label = "Romance",
        imageResId = R.drawable.genre_romance,
        movieGenreId = 10749,
    ),
    SCI_FI(
        label = "Sci-Fi",
        imageResId = R.drawable.genre_scifi,
        movieGenreId = 878,
        tvGenreId = 10765,
    ),
    THRILLER(
        label = "Thriller",
        imageResId = R.drawable.genre_thriller,
        movieGenreId = 53,
    ),
}
