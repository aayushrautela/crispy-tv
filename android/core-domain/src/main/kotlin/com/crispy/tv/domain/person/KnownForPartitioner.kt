package com.crispy.tv.domain.person

enum class KnownForRail(val title: String) {
    Movies("Movies"),
    Shows("Shows"),
    Interviews("Interviews"),
}

object KnownForPartitioner {

    private val INTERVIEW_GENRES = setOf("documentary", "talk")

    fun <T> partition(
        items: List<T>,
        typeOf: (T) -> String,
        genresOf: (T) -> List<String>,
    ): Map<KnownForRail, List<T>> {
        val buckets = mutableMapOf<KnownForRail, MutableList<T>>()
        for (item in items) {
            val rail = classify(genresOf(item), typeOf(item))
            buckets.getOrPut(rail) { mutableListOf() }.add(item)
        }
        return buildMap {
            for (rail in KnownForRail.entries) {
                buckets[rail]?.takeIf { it.isNotEmpty() }?.let { put(rail, it) }
            }
        }
    }

    private fun classify(genres: List<String>, type: String): KnownForRail {
        val isInterview = genres.any { it.trim().lowercase() in INTERVIEW_GENRES }
        if (isInterview) return KnownForRail.Interviews
        return when (type.trim().lowercase()) {
            "show", "anime", "episode" -> KnownForRail.Shows
            else -> KnownForRail.Movies
        }
    }
}
