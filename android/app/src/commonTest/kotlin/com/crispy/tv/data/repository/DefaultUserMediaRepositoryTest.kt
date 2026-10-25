package com.crispy.tv.data.repository

import com.crispy.tv.player.CanonicalWatchStateSnapshot
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.WatchProgressSnapshot
import com.crispy.tv.testing.FakeWatchHistoryService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resume rule [DefaultUserMediaRepository.getResumePositionMs] owns.
 *
 * It used to be two identical `progressPercent in 1.0..95.0` blocks inside
 * `DetailsViewModel` and `HomeSelectorViewModel`, and neither of them ever asked
 * the server — so a second device, or a fresh install, resumed at zero while
 * the server knew where the profile got to. The rule is one function now, and
 * this is its suite: the local band, the server fallback, and the same band
 * applied to the server's answer.
 */
class DefaultUserMediaRepositoryTest {
    private val service = FakeWatchHistoryService()
    private val repository = DefaultUserMediaRepository(service)

    private val identity =
        PlaybackIdentity(
            itemId = "tt1",
            contentType = MetadataLabMediaType.MOVIE,
            title = "Title",
        )

    private fun progress(currentSeconds: Double, durationSeconds: Double = 6_000.0) =
        WatchProgressSnapshot(
            currentTimeSeconds = currentSeconds,
            durationSeconds = durationSeconds,
            lastUpdatedEpochMs = 0L,
        )

    private fun serverState(
        resumePositionSeconds: Double?,
        progressPercent: Double?,
    ) = CanonicalWatchStateSnapshot(
        isWatched = false,
        watchedAtEpochMs = null,
        isInWatchlist = false,
        liked = null,
        resumePositionSeconds = resumePositionSeconds,
        durationSeconds = 6_000.0,
        progressPercent = progressPercent,
    )

    @Test
    fun `local progress in the resumable band answers without asking the server`() = runTest {
        service.localProgressAnswer = progress(currentSeconds = 2_400.0)

        assertEquals(2_400_000L, repository.getResumePositionMs(identity))
        assertEquals(
            "the local store answered, so the server is never asked",
            emptyList<String>(),
            service.titleWatchStateCalls,
        )
    }

    @Test
    fun `an empty local store resumes from the server`() = runTest {
        service.titleWatchStateAnswer =
            serverState(resumePositionSeconds = 900.0, progressPercent = 15.0)

        assertEquals(900_000L, repository.getResumePositionMs(identity))
    }

    @Test
    fun `local progress outside the band still asks the server`() = runTest {
        // 96% is "finished" to the local gate, and a gate failure is not an
        // answer: a stale duration can put a genuine mid-title position over
        // the band while the server holds the correct one.
        service.localProgressAnswer = progress(currentSeconds = 5_760.0)
        service.titleWatchStateAnswer =
            serverState(resumePositionSeconds = 900.0, progressPercent = 15.0)

        assertEquals(900_000L, repository.getResumePositionMs(identity))
    }

    @Test
    fun `a server answer outside the band does not resume`() = runTest {
        service.titleWatchStateAnswer =
            serverState(resumePositionSeconds = 5_760.0, progressPercent = 96.0)

        assertEquals(0L, repository.getResumePositionMs(identity))
    }

    @Test
    fun `a server without a position answers zero`() = runTest {
        service.titleWatchStateAnswer =
            serverState(resumePositionSeconds = null, progressPercent = 40.0)

        assertEquals(0L, repository.getResumePositionMs(identity))
    }

    @Test
    fun `no local and no server answer answers zero`() = runTest {
        assertEquals(0L, repository.getResumePositionMs(identity))
    }
}
