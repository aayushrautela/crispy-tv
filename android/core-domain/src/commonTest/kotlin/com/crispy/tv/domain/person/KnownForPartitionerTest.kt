package com.crispy.tv.domain.person

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class FakeCard(
    val type: String,
    val genres: List<String>,
    val id: String,
)

class KnownForPartitionerTest {

    private fun partition(cards: List<FakeCard>): Map<KnownForRail, List<FakeCard>> =
        KnownForPartitioner.partition(
            items = cards,
            typeOf = { it.type },
            genresOf = { it.genres },
        )

    @Test
    fun splitsByMediaType() {
        val cards = listOf(
            FakeCard("movie", listOf("Action"), "m1"),
            FakeCard("show", listOf("Drama"), "s1"),
            FakeCard("movie", listOf("Comedy"), "m2"),
        )
        val result = partition(cards)
        assertEquals(listOf("m1", "m2"), result[KnownForRail.Movies]?.map { it.id })
        assertEquals(listOf("s1"), result[KnownForRail.Shows]?.map { it.id })
        assertTrue(result[KnownForRail.Interviews].isNullOrEmpty())
    }

    @Test
    fun documentaryAndTalkGoToInterviews() {
        val cards = listOf(
            FakeCard("movie", listOf("Documentary"), "doc1"),
            FakeCard("show", listOf("Talk"), "talk1"),
            FakeCard("show", listOf("Drama", "Documentary"), "doc2"),
        )
        val result = partition(cards)
        assertEquals(listOf("doc1", "talk1", "doc2"), result[KnownForRail.Interviews]?.map { it.id })
        assertTrue(result[KnownForRail.Movies].isNullOrEmpty())
        assertTrue(result[KnownForRail.Shows].isNullOrEmpty())
    }

    @Test
    fun interviewGenreWinsOverMediaType() {
        val cards = listOf(
            FakeCard("movie", listOf("Documentary"), "docMovie"),
            FakeCard("show", listOf("Talk"), "talkShow"),
        )
        val result = partition(cards)
        assertEquals(listOf("docMovie", "talkShow"), result[KnownForRail.Interviews]?.map { it.id })
    }

    @Test
    fun animeAndEpisodeAreShows() {
        val cards = listOf(
            FakeCard("anime", listOf("Animation"), "a1"),
            FakeCard("episode", listOf("Drama"), "e1"),
        )
        val result = partition(cards)
        assertEquals(listOf("a1", "e1"), result[KnownForRail.Shows]?.map { it.id })
    }

    @Test
    fun dropsEmptyRails() {
        val cards = listOf(FakeCard("movie", listOf("Action"), "m1"))
        val result = partition(cards)
        assertEquals(setOf(KnownForRail.Movies), result.keys)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertTrue(partition(emptyList()).isEmpty())
    }

    @Test
    fun genreMatchingIsCaseInsensitive() {
        val cards = listOf(
            FakeCard("movie", listOf("DOCUMENTARY"), "d1"),
            FakeCard("show", listOf("Talk"), "t1"),
        )
        val result = partition(cards)
        assertEquals(listOf("d1", "t1"), result[KnownForRail.Interviews]?.map { it.id })
    }
}
