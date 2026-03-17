package com.crispy.tv.search

enum class SearchGenreSuggestion(
    val label: String,
    val movieGenreId: Int,
    val tvGenreId: Int? = null,
) {
    ACTION(
        label = "Action",
        movieGenreId = 28,
        tvGenreId = 10759,
    ),
    ANIMATED(
        label = "Animated",
        movieGenreId = 16,
        tvGenreId = 16,
    ),
    COMEDY(
        label = "Comedy",
        movieGenreId = 35,
        tvGenreId = 35,
    ),
    DOCUMENTARY(
        label = "Documentary",
        movieGenreId = 99,
        tvGenreId = 99,
    ),
    DRAMA(
        label = "Drama",
        movieGenreId = 18,
        tvGenreId = 18,
    ),
    FAMILY(
        label = "Family",
        movieGenreId = 10751,
        tvGenreId = 10751,
    ),
    FANTASY(
        label = "Fantasy",
        movieGenreId = 14,
        tvGenreId = 10765,
    ),
    HORROR(
        label = "Horror",
        movieGenreId = 27,
    ),
    MYSTERY(
        label = "Mystery",
        movieGenreId = 9648,
        tvGenreId = 9648,
    ),
    ROMANCE(
        label = "Romance",
        movieGenreId = 10749,
    ),
    SCI_FI(
        label = "Sci-Fi",
        movieGenreId = 878,
        tvGenreId = 10765,
    ),
    THRILLER(
        label = "Thriller",
        movieGenreId = 53,
    ),
}
