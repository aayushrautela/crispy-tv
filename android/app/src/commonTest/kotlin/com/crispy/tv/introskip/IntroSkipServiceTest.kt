package com.crispy.tv.introskip

import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import com.crispy.tv.network.HttpRequest
import com.crispy.tv.platform.AppLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * Coverage for [RemoteIntroSkipService], which reached `:app`'s `commonMain` in
 * the landing that gave it its three slots (`logger`, `nowMs`, `ioDispatcher`)
 * and its [CrispyHttpClient] dependency declaration.
 *
 * ## Why this suite could be written now and not before
 *
 * The file's only pin was `org.json`. With the node type swapped to
 * `kotlinx.serialization.json` the remaining collaborators are all ports, so a
 * `commonTest` can construct the class outright -- no Robolectric, no
 * `androidHostTest`. **Moving a file into `commonMain` does not make it
 * testable; a constructor of interfaces does.** This is the second instance of
 * that rule here, and the difference from `AiInsightsRepository` is the whole
 * point: the collaborator was never a `class` to begin with.
 *
 * ## One case here read like a defect and was not
 *
 * [anAniSkipIntervalInSecondsBecomesMilliseconds] was originally named
 * `anAniSkipIntervalIsMultipliedByAThousand` and asserted that a 10.5-second
 * opening became 10,500,000ms -- presented as a pre-existing unit bug. It is not
 * a bug. AniSkip v2 reports `startTime`/`endTime` in **seconds**, `secondsToMillis`
 * is the correct conversion, and the same multiplication appears in AniSkip's own
 * reference client. The "bug" was an artifact of a fixture whose values could not
 * be right under either reading; it is fixed at the fixture, and the case now
 * pins the real contract with a payload that carries the field that disambiguates
 * it. **An asserted defect is the most expensive kind of wrong test, because it
 * reads as evidence and invites a "fix" that would break working code.**
 *
 * [aNotFoundFromIntroDbStillFallsThroughToMalResolution] is the other case worth
 * reading before changing it. A 404 from IntroDB costs two extra requests, and
 * that is the multi-provider fallback working as designed rather than a waste:
 * AniSkip is keyed by MAL id, so an IMDb-only request cannot reach it at all
 * without the ARM/Kitsu resolution step this path performs.
 */
private const val INTRO_DB_ROOT = "https://api.introdb.app"
private const val INTRO_DB_SEGMENTS = "$INTRO_DB_ROOT/segments"
private const val ANI_SKIP = "https://api.aniskip.com/v2/skip-times"
private const val KITSU = "https://kitsu.io/api/edge/anime"
private const val ARM = "https://arm.haglund.dev/api/v2/imdb"

/**
 * Three segments, all longer than the 500ms minimum.
 *
 * **Deliberately written out of order** -- `outro` first. The service sorts by
 * `startTimeMs`, so a fixture that already happened to be sorted would pass on an
 * implementation that never sorted, which is the "a comment claiming a choice
 * between two orderings that coincide is unobservable" defect.
 */
private val INTRO_DB_BODY = """
{
  "outro": { "start_sec": 1200.0, "end_sec": 1230.5 },
  "recap": { "start_sec": 60.0, "end_sec": 70.0 },
  "intro": { "start_sec": 0.0, "end_sec": 30.0 }
}
""".trimIndent()

/** One AniSkip result. */
/**
 * A real AniSkip v2 payload, including the `episodeLength` this suite does not
 * read.
 *
 * **`episodeLength` is here to be the disambiguator, not decoration.** AniSkip
 * v2 documents `interval.startTime`/`endTime` and `episodeLength` in the *same*
 * unit, and it is the only field in the payload that can settle which unit the
 * interval fields are in -- a bare pair of numbers cannot. As seconds this
 * payload is coherent: a 1417.16s (~23.6 min) episode whose opening ends at
 * 90.5s. As milliseconds the same numbers describe a 1.4-second episode whose
 * opening ends at 0.09s, which is not a thing.
 *
 * This fixture previously carried `10500.0`/`40250.0`, which read as
 * milliseconds and as seconds describe an eleven-hour episode. Those values
 * are what made [anAniSkipIntervalInSecondsBecomesMilliseconds] look like a
 * unit bug for a while -- see the note on
 * [anAniSkipIntervalInSecondsBecomesMilliseconds].
 */
private val ANI_SKIP_BODY = """
{
  "found": true,
  "results": [
    {
      "interval": { "startTime": 0.0, "endTime": 90.5 },
      "skipType": "OP",
      "skipId": "opening-1",
      "episodeLength": 1417.16
    }
  ]
}
""".trimIndent()

/** An AniSkip result carrying everything the reader needs to assert on. */
private class FakeHttpClient(
    private val respond: (url: String) -> CrispyHttpResponse,
) : CrispyHttpClient {

    val requestedUrls = mutableListOf<String>()
    val timeouts = mutableListOf<Long?>()

    /** Fail the next single GET, to separate one failure from a total outage. */
    var failNextGet: Throwable? = null

    /** Fail every GET, so a throw is exercised through the whole provider chain. */
    var failEveryGet: Boolean = false

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
        query: List<Pair<String, String>>,
    ): CrispyHttpResponse {
        requestedUrls += url
        timeouts += callTimeoutMs
        if (failEveryGet) throw IllegalStateException("transport down")
        failNextGet?.let { failNextGet = null; throw it }
        return respond(url)
    }

    override suspend fun getOrNull(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
        query: List<Pair<String, String>>,
    ): CrispyHttpResponse? = get(url, headers, callTimeoutMs, query)

    override suspend fun execute(
        request: HttpRequest,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = error("IntroSkipService never calls execute.")

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = error("IntroSkipService never calls postJson.")

    override suspend fun delete(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = error("IntroSkipService never calls delete.")

    val calls: Int get() = requestedUrls.size
    fun callsMatching(fragment: String): Int = requestedUrls.count { it.contains(fragment) }
    fun singleUrlMatching(fragment: String): String =
        requestedUrls.single { it.contains(fragment) }
}

private class RecordingLogger : AppLogger {
    val warnings = mutableListOf<String>()
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, throwable: Throwable?) {
        warnings += message
    }

    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}

/**
 * A clock the test advances by hand.
 *
 * **`nowMs` is read exactly once per `getSkipIntervals` call** -- the cache check
 * and the cache stamp share the single reading -- so a fixed clock cannot make
 * those two disagree, and no assertion about the gap between them is available.
 * What the counter *can* establish is the reading count itself, which is what
 * the TTL arithmetic rests on. See [nowIsReadExactlyOncePerCall].
 */
private class ManualClock(var now: Long = 0L) {
    var reads: Int = 0
        private set

    fun read(): Long {
        reads++
        return now
    }
}

/**
 * The subject under test, over a clock and a double the test can both reach.
 *
 * **[respond] is the *last* parameter on purpose.** Kotlin binds a trailing
 * lambda to the last parameter, so with it first every `Harness { url -> ... }` in
 * this file silently passed the lambda as `cacheTtlMs: Long` -- which compiles as
 * a type error at best and as a *different* test at worst. Every call site here
 * that cannot use trailing-lambda syntax names `respond =` explicitly.
 */
private class Harness(
    cacheTtlMs: Long = 600_000L,
    respond: (url: String) -> CrispyHttpResponse,
) {
    val clock = ManualClock()
    val http = FakeHttpClient(respond)
    val logger = RecordingLogger()

    val service = RemoteIntroSkipService(
        httpClient = http,
        logger = logger,
        nowMs = clock::read,
        ioDispatcher = Dispatchers.Unconfined,
        introDbBaseUrl = INTRO_DB_ROOT,
        cacheTtlMs = cacheTtlMs,
    )
}

/** IntroDB answers with [introDbBody]; every other URL is a test error. */
private fun introDbOnly(introDbBody: String = INTRO_DB_BODY): (String) -> CrispyHttpResponse = { url ->
    when {
        url.startsWith(INTRO_DB_SEGMENTS) -> CrispyHttpResponse(200, introDbBody)
        else -> error("Unexpected request: $url")
    }
}

private fun armResolves(malId: Int): (String) -> CrispyHttpResponse = { url ->
    when {
        url.startsWith(INTRO_DB_SEGMENTS) -> CrispyHttpResponse(200, "{}")
        url.startsWith(ARM) -> CrispyHttpResponse(200, """{"results":[{"myanimelist":$malId}]}""")
        url.startsWith(ANI_SKIP) -> CrispyHttpResponse(200, ANI_SKIP_BODY)
        else -> error("Unexpected request: $url")
    }
}

// ---------------------------------------------------------------------------
// normalize() -- the request-side rules, observed as "was anything fetched?"
// ---------------------------------------------------------------------------

class IntroSkipRequestNormalizationTest {

    @Test
    fun aNonPositiveSeasonIsRefusedBeforeAnyRequest() = runTest {
        val h = Harness(respond = introDbOnly())

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 0, episode = 1)
        )

        assertEquals(emptyList(), result)
        assertEquals(0, h.http.calls, "normalize() refuses the request, so nothing may be fetched.")
    }

    @Test
    fun aNonPositiveEpisodeIsRefusedBeforeAnyRequest() = runTest {
        val h = Harness(respond = introDbOnly())

        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 0))

        assertEquals(0, h.http.calls, "episode is refused the same way season is.")
    }

    @Test
    fun aMalIdIsRefusedWhenItIsNotPositive() = runTest {
        val h = Harness(respond = introDbOnly())

        val result = h.service.getSkipIntervals(IntroSkipRequest(season = 1, episode = 1, malId = 0))

        assertEquals(emptyList(), result)
        assertEquals(0, h.http.calls)
    }

    @Test
    fun anImdbIdThatIsNotTtPrefixedIsDroppedAndTheRestOfTheRequestStillWorks() = runTest {
        val h = Harness { url ->
            if (url.startsWith(ANI_SKIP)) CrispyHttpResponse(200, ANI_SKIP_BODY)
            else error("Unexpected request: $url")
        }

        h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "1234567", season = 1, episode = 1, malId = 1535)
        )

        assertEquals(0, h.http.callsMatching(INTRO_DB_ROOT), "A malformed imdb id must not reach IntroDB.")
        assertEquals(0, h.http.callsMatching("arm.haglund"), "Nor the ARM lookup that an imdb id would drive.")
        assertEquals(1, h.http.callsMatching(ANI_SKIP), "malId still carries the request to AniSkip.")
    }

    @Test
    fun aRequestWithNoUsableIdentifierIsRefusedBeforeAnyRequest() = runTest {
        val h = Harness(respond = introDbOnly())

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "not-an-id", season = 1, episode = 1)
        )

        assertEquals(emptyList(), result)
        assertEquals(0, h.http.calls)
    }

    @Test
    fun anImdbIdIsTrimmedBeforeItBecomesACacheKey() = runTest {
        val h = Harness(respond = introDbOnly())

        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "  tt1234567  ", season = 1, episode = 1))
        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1))

        assertEquals(
            1,
            h.http.calls,
            "A padded and an unpadded id are one request, so they share a cache entry.",
        )
    }
}

// ---------------------------------------------------------------------------
// fetchIntervals() -- the provider order
// ---------------------------------------------------------------------------

class IntroSkipProviderOrderTest {

    @Test
    fun aNonEmptyIntroDbAnswerStopsTheLookupBeforeAnyMalResolution() = runTest {
        val h = Harness(respond = introDbOnly())

        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1))

        assertEquals(1, h.http.callsMatching(INTRO_DB_SEGMENTS))
        assertEquals(0, h.http.callsMatching("arm.haglund"), "IntroDB answered, so MAL resolution must not run.")
        assertEquals(0, h.http.callsMatching(ANI_SKIP))
    }

    @Test
    fun anEmptyIntroDbAnswerFallsThroughToArmThenAniSkip() = runTest {
        val h = Harness(respond = armResolves(1535))

        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt1234567", season = 2, episode = 5))

        assertEquals(1, h.http.callsMatching("arm.haglund"), "An imdb id resolves a MAL id through ARM.")
        assertTrue(
            h.http.singleUrlMatching(ANI_SKIP).contains("/skip-times/1535/5"),
            "ARM's myanimelist value is the MAL id in the AniSkip path. Saw ${h.http.requestedUrls}",
        )
    }

    @Test
    fun aKitsuIdIsResolvedThroughKitsuAndNotThroughArm() = runTest {
        val h = Harness { url ->
            when {
                url.startsWith(KITSU) -> CrispyHttpResponse(
                    200,
                    """
                    {"data":[
                      {"attributes":{"externalSite":"anilist/anime","externalId":"9999"}},
                      {"attributes":{"externalSite":"myanimelist/anime","externalId":"5114"}}
                    ]}
                    """.trimIndent(),
                )
                url.startsWith(ANI_SKIP) -> CrispyHttpResponse(200, ANI_SKIP_BODY)
                else -> error("Unexpected request: $url")
            }
        }

        h.service.getSkipIntervals(IntroSkipRequest(season = 1, episode = 3, kitsuId = 42))

        assertEquals(1, h.http.callsMatching("kitsu.io"))
        assertEquals(0, h.http.callsMatching("arm.haglund"), "A kitsuId resolves through Kitsu, so ARM is not asked.")
        assertTrue(
            h.http.singleUrlMatching(ANI_SKIP).contains("/skip-times/5114/3"),
            "Kitsu's myanimelist externalId is the MAL id and the anilist entry is ignored. Saw ${h.http.requestedUrls}",
        )
    }

    @Test
    fun aKitsuExternalIdGivenAsANumericStringIsStillReadAsAnId() = runTest {
        val h = Harness { url ->
            when {
                url.startsWith(KITSU) -> CrispyHttpResponse(
                    200,
                    """{"data":[{"attributes":{"externalSite":"myanimelist/anime","externalId":"5114"}}]}""",
                )
                url.startsWith(ANI_SKIP) -> CrispyHttpResponse(200, ANI_SKIP_BODY)
                else -> error("Unexpected request: $url")
            }
        }

        h.service.getSkipIntervals(IntroSkipRequest(season = 1, episode = 3, kitsuId = 42))

        assertTrue(
            h.http.singleUrlMatching(ANI_SKIP).contains("/skip-times/5114/3"),
            """A quoted "5114" and a bare 5114 are the same id; a fixture with only the
            quoted form is what proves the string path still parses. Saw ${h.http.requestedUrls}""",
        )
    }

    @Test
    fun aMalIdIsUsedDirectlyWithoutAnyLookup() = runTest {
        val h = Harness { url ->
            if (url.startsWith(ANI_SKIP)) CrispyHttpResponse(200, ANI_SKIP_BODY)
            else error("Unexpected request: $url")
        }

        h.service.getSkipIntervals(IntroSkipRequest(season = 4, episode = 1, malId = 777))

        assertEquals(1, h.http.calls, "Only the AniSkip request may be made.")
        assertTrue(h.http.requestedUrls.single().contains("/skip-times/777/1"))
    }

    @Test
    fun anUnresolvableMalLookupStopsBeforeAniSkip() = runTest {
        val h = Harness { url ->
            when {
                url.startsWith(INTRO_DB_SEGMENTS) -> CrispyHttpResponse(200, "{}")
                url.startsWith(ARM) -> CrispyHttpResponse(200, """{"results":[]}""")
                else -> error("Unexpected request: $url")
            }
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(emptyList(), result)
        assertEquals(0, h.http.callsMatching(ANI_SKIP), "No MAL id means no AniSkip request.")
    }

    @Test
    fun aKitsuEntryWithAnUnreadableExternalIdResolvesToNoMalId() = runTest {
        val h = Harness { url ->
            when {
                url.startsWith(KITSU) -> CrispyHttpResponse(
                    200,
                    """{"data":[{"attributes":{"externalSite":"myanimelist/anime","externalId":"not-a-number"}}]}""",
                )
                else -> error("Unexpected request: $url")
            }
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(season = 1, episode = 3, kitsuId = 42)
        )

        assertEquals(emptyList(), result)
        assertEquals(1, h.http.calls, "An unreadable externalId resolves to nothing, so AniSkip is never asked.")
    }
}

// ---------------------------------------------------------------------------
// The response filters
// ---------------------------------------------------------------------------

class IntroSkipResponseTest {

    @Test
    fun segmentsAreSortedByStartTimeAndNotByJsonOrder() = runTest {
        val h = Harness(respond = introDbOnly())

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(listOf(0L, 60_000L, 1_200_000L), result.map { it.startTimeMs })
        assertEquals(
            listOf(
                IntroSkipSegmentType.INTRO,
                IntroSkipSegmentType.RECAP,
                IntroSkipSegmentType.OUTRO,
            ),
            result.map { it.segmentType },
            "The fixture lists outro first, so an unsorted implementation fails here.",
        )
    }

    @Test
    fun introDbSecondsBecomeMillisecondsAndAFractionalSecondIsKept() = runTest {
        val h = Harness(respond = introDbOnly())

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(1_230_500L, result.last().endTimeMs, "1230.5s is 1230500ms; the fraction is not truncated away.")
    }

    @Test
    fun anIntroDbSegmentCarriesNoSkipId() = runTest {
        val h = Harness(respond = introDbOnly())

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertTrue(result.all { it.skipId == null }, "IntroDB has no per-segment id to report.")
        assertTrue(result.all { it.provider == IntroSkipProvider.INTRO_DB })
    }

    @Test
    fun anAniSkipSegmentCarriesItsSkipIdAndSegmentType() = runTest {
        val h = Harness { url ->
            if (url.startsWith(ANI_SKIP)) CrispyHttpResponse(200, ANI_SKIP_BODY)
            else error("Unexpected request: $url")
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(season = 1, episode = 3, malId = 777)
        )

        assertEquals(1, result.size)
        assertEquals("opening-1", result.single().skipId)
        assertEquals(IntroSkipSegmentType.OP, result.single().segmentType)
        assertEquals(IntroSkipProvider.ANI_SKIP, result.single().provider)
    }

    @Test
    fun anAniSkipIntervalInSecondsBecomesMilliseconds() = runTest {
        val h = Harness { url ->
            if (url.startsWith(ANI_SKIP)) CrispyHttpResponse(200, ANI_SKIP_BODY)
            else error("Unexpected request: $url")
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(season = 1, episode = 3, malId = 777)
        )

        // AniSkip v2 documents startTime/endTime in SECONDS, and this code feeds
        // them to secondsToMillis, so 0.0 and 90.5 become 0ms and 90500ms.
        //
        // **This case asserted the opposite once.** An earlier fixture carried
        // 10500.0/40250.0, which read as milliseconds and as seconds describe an
        // eleven-hour episode; from that fixture it looked like a unit bug that
        // turned a 10.5s opening into 10_500_000ms. AniSkip's own reference
        // implementation multiplies by 1000 the same way this code does, and its
        // documented example pairs `endTime: 1420` with `episodeLength: 1420`.
        // The code was always right; the fixture was not. See ANI_SKIP_BODY for
        // why `episodeLength` is the field that settles it.
        assertEquals(0L, result.single().startTimeMs)
        assertEquals(90_500L, result.single().endTimeMs)
    }

    @Test
    fun aSegmentAtOrUnderTheMinimumLengthIsDroppedAndTheOthersSurvive() = runTest {
        val h = Harness(
            respond = introDbOnly(
                """
                {
                  "intro": { "start_sec": 0.0, "end_sec": 0.4 },
                  "recap": { "start_sec": 10.0, "end_sec": 10.5 },
                  "outro": { "start_sec": 100.0, "end_sec": 130.0 }
                }
                """.trimIndent(),
            )
        )

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(
            listOf(10_000L, 100_000L),
            result.map { it.startTimeMs },
            """
            The filter is `end - start < 500`, so 400ms is dropped and 500ms is
            exactly on the bound and survives. A fixture asserting both were dropped
            would be describing a different comparison than the one in the code.
            """.trimIndent(),
        )
    }

    @Test
    fun aNegativeOrUnreadableStartDropsOnlyThatSegment() = runTest {
        val h = Harness(
            respond = introDbOnly(
                """
                {
                  "intro": { "start_sec": -5.0, "end_sec": 30.0 },
                  "recap": { "end_sec": 70.0 },
                  "outro": { "start_sec": 100.0, "end_sec": 130.0 }
                }
                """.trimIndent(),
            )
        )

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(
            listOf(100_000L),
            result.map { it.startTimeMs },
            "A negative start and an absent start_sec both read as NaN, which becomes -1ms.",
        )
    }

    @Test
    fun anIntroDbAnswerThatIsEntirelyFilteredOutStillFallsThroughToAniSkip() = runTest {
        val h = Harness { url ->
            when {
                url.startsWith(INTRO_DB_SEGMENTS) ->
                    CrispyHttpResponse(200, """{"intro":{"start_sec":0.0,"end_sec":0.1}}""")
                url.startsWith(ARM) -> CrispyHttpResponse(200, """{"results":[{"myanimelist":1535}]}""")
                url.startsWith(ANI_SKIP) -> CrispyHttpResponse(200, ANI_SKIP_BODY)
                else -> error("Unexpected request: $url")
            }
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(
            1,
            h.http.callsMatching(ANI_SKIP),
            "The fallback runs on the *filtered* result, so a wholly filtered IntroDB answer is an empty one.",
        )
        assertEquals(1, result.size)
    }

    @Test
    fun aNotFoundFromIntroDbStillFallsThroughToMalResolution() = runTest {
        val h = Harness { CrispyHttpResponse(404, "") }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(emptyList(), result)
        assertEquals(
            2,
            h.http.calls,
            """
            404 returns an empty list, and an empty list is what triggers the MAL
            fallback -- so a definitive "no segments for this episode" from IntroDB
            costs two more requests. Pre-existing at HEAD; pinned, not changed.
            """.trimIndent(),
        )
        assertEquals(emptyList(), h.logger.warnings, "404 returns before the status warning.")
    }

    @Test
    fun aServerErrorIsWarnedAboutOnceAndIsNotRetried() = runTest {
        val h = Harness { CrispyHttpResponse(503, "") }

        h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(2, h.http.calls, "IntroDB then ARM; neither is retried.")
        assertEquals(1, h.logger.warnings.size, "IntroDB warns; the MAL lookups answer null silently.")
        assertTrue(
            h.logger.warnings.single().contains("503"),
            "The warning names the status. Saw ${h.logger.warnings}",
        )
    }

    @Test
    fun aBodyThatIsNotAJsonObjectIsAnEmptyResultWithoutThrowing() = runTest {
        val h = Harness { CrispyHttpResponse(200, "[1,2,3]") }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(emptyList(), result, "jsonObject throws on an array; the caller's runCatching absorbs it.")
        assertEquals(emptyList(), h.logger.warnings, "A parse failure is not a transport failure to warn about.")
    }

    @Test
    fun aBlankBodyIsAnEmptyResult() = runTest {
        val h = Harness { CrispyHttpResponse(200, "   ") }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(emptyList(), result)
    }

    @Test
    fun aThrownRequestBecomesAnEmptyResultAndIsWarnedAbout() = runTest {
        val h = Harness(respond = introDbOnly())
        h.http.failEveryGet = true

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(emptyList(), result)
        assertEquals(2, h.http.calls, "IntroDB throws, then ARM throws; the chain still ends empty rather than throwing.")
        assertEquals(
            2,
            h.logger.warnings.size,
            "Each failed request warns once with its own URL.",
        )
    }

    @Test
    fun onlyTheFailingRequestIsAffectedWhenOneCallThrows() = runTest {
        val h = Harness(respond = armResolves(1535))
        h.http.failNextGet = IllegalStateException("one bad response")

        // IntroDB throws; the MAL fallback then runs and succeeds.
        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 5)
        )

        assertEquals(3, h.http.calls, "IntroDB, ARM and AniSkip are all attempted.")
        assertEquals(1, result.size, "A single failed request does not abandon the whole lookup.")
    }

    @Test
    fun anAniSkipAnswerThatDoesNotSayItFoundAnythingIsEmpty() = runTest {
        val h = Harness { url ->
            when {
                url.startsWith(INTRO_DB_SEGMENTS) -> CrispyHttpResponse(200, "{}")
                url.startsWith(ARM) -> CrispyHttpResponse(200, """{"results":[{"myanimelist":1535}]}""")
                url.startsWith(ANI_SKIP) -> CrispyHttpResponse(
                    200,
                    """{"found":false,"results":[{"interval":{"startTime":0,"endTime":30000}}]}""",
                )
                else -> error("Unexpected request: $url")
            }
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(
            emptyList(),
            result,
            "found must be exactly true; results are not read when it is false.",
        )
    }

    @Test
    fun everyLookupRequestCarriesAPositiveTimeout() = runTest {
        val h = Harness(respond = armResolves(1535))

        h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 5)
        )

        assertTrue(h.http.timeouts.isNotEmpty())
        assertTrue(
            h.http.timeouts.all { it != null && it > 0L },
            "Every lookup is bounded; an unbounded one would hang a player session. Saw ${h.http.timeouts}",
        )
    }

    @Test
    fun theIntroDbQueryIsBuiltInOrderAndPercentEncoded() = runTest {
        val h = Harness(respond = introDbOnly())

        h.service.getSkipIntervals(
            IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)
        )

        assertEquals(
            "$INTRO_DB_SEGMENTS?imdb_id=tt1234567&season=1&episode=1",
            h.http.requestedUrls.single(),
        )
    }

    @Test
    fun aTrailingSlashOnTheConfiguredBaseUrlIsNotDoubled() = runTest {
        val h = Harness(respond = introDbOnly())

        RemoteIntroSkipService(
            httpClient = h.http,
            logger = h.logger,
            nowMs = h.clock::read,
            ioDispatcher = Dispatchers.Unconfined,
            introDbBaseUrl = "$INTRO_DB_ROOT/",
        ).getSkipIntervals(IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1))

        assertEquals(1, h.http.callsMatching("/segments?"), "Saw ${h.http.requestedUrls}")
    }
}

// ---------------------------------------------------------------------------
// The cache
// ---------------------------------------------------------------------------

class IntroSkipCacheTest {

    private val request = IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1)

    @Test
    fun aSecondCallInsideTheTtlIsServedFromTheCache() = runTest {
        val h = Harness(cacheTtlMs = 1_000L, respond = introDbOnly())

        h.service.getSkipIntervals(request)
        h.clock.now = 999L
        val second = h.service.getSkipIntervals(request)

        assertEquals(1, h.http.calls, "999ms is inside a 1000ms TTL.")
        assertEquals(3, second.size, "The cached value is the fetched one, not a re-derivation.")
    }

    @Test
    fun aCallPastTheTtlRefetches() = runTest {
        val h = Harness(cacheTtlMs = 1_000L, respond = introDbOnly())

        h.service.getSkipIntervals(request)
        h.clock.now = 1_001L
        h.service.getSkipIntervals(request)

        assertEquals(2, h.http.calls, "1001ms is past a 1000ms TTL.")
    }

    @Test
    fun theTtlBoundaryIsInclusive() = runTest {
        val h = Harness(cacheTtlMs = 1_000L, respond = introDbOnly())

        h.service.getSkipIntervals(request)
        h.clock.now = 1_000L
        h.service.getSkipIntervals(request)

        assertEquals(1, h.http.calls, "The check is `<=`, so exactly the TTL is still a hit.")
    }

    @Test
    fun anEmptyResultIsCachedAndNotRetriedInsideTheTtl() = runTest {
        val h = Harness(cacheTtlMs = 1_000L) { CrispyHttpResponse(404, "") }

        assertEquals(emptyList(), h.service.getSkipIntervals(request))
        h.clock.now = 500L
        assertEquals(emptyList(), h.service.getSkipIntervals(request))

        assertEquals(
            2,
            h.http.calls,
            """
            Two, not one: the first call asks IntroDB and then ARM, and the second
            call asks neither. A cached empty list is an empty list rather than a
            null, so `cached != null` short-circuits it -- "answers empty" and "was
            never stored" are two different facts and only this case separates them.
            """.trimIndent(),
        )
    }

    @Test
    fun aFailedFetchIsCachedLikeAnyOtherEmptyResult() = runTest {
        val h = Harness(cacheTtlMs = 1_000L, respond = introDbOnly())
        h.http.failEveryGet = true

        h.service.getSkipIntervals(request)
        val afterFirst = h.http.calls
        h.clock.now = 500L
        h.service.getSkipIntervals(request)

        assertEquals(afterFirst, h.http.calls, "A transport failure is cached as an empty result too.")
    }

    @Test
    fun differentEpisodesDoNotShareACacheEntry() = runTest {
        val h = Harness(respond = introDbOnly())

        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 1))
        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt1234567", season = 1, episode = 2))

        assertEquals(2, h.http.calls, "The episode is part of the key, so a different episode must refetch.")
    }

    @Test
    fun everyNormalizedIdentifierIsPartOfTheCacheKey() = runTest {
        val h = Harness(respond = introDbOnly())

        // Same imdb id, season and episode, but a kitsu id added: a different key.
        h.service.getSkipIntervals(request)
        h.service.getSkipIntervals(request.copy(kitsuId = 9))

        assertEquals(2, h.http.calls)
    }

    @Test
    fun nowIsReadExactlyOncePerCall() = runTest {
        val h = Harness(respond = introDbOnly())

        h.service.getSkipIntervals(request)

        assertEquals(
            1,
            h.clock.reads,
            """
            The cache check and the cache stamp share one reading, so an entry is
            stamped with the time the request *started* and not the time it
            finished. A fixed clock cannot show this; only the read count can.
            """.trimIndent(),
        )
    }

    @Test
    fun aRefetchedEntryStillAnswersWithTheFullSet() = runTest {
        val h = Harness(cacheTtlMs = 100L, respond = introDbOnly())

        var now = 0L
        repeat(5) {
            h.clock.now = now
            assertEquals(3, h.service.getSkipIntervals(request).size, "refetch $it")
            now += 101L
        }
        assertEquals(5, h.http.calls, "Every step is past the TTL, so every step refetches.")

        // Step inside the TTL of the final stamp. If trimming or replacement had
        // dropped the entry this would be a sixth fetch.
        h.clock.now = now - 101L + 50L
        assertEquals(3, h.service.getSkipIntervals(request).size)
        assertEquals(5, h.http.calls, "The surviving entry answered without a fetch.")
    }

    @Test
    fun expiredEntriesAreDroppedRatherThanRefetchedOnEveryCall() = runTest {
        val h = Harness(cacheTtlMs = 100L, respond = introDbOnly())

        // Five distinct keys, all stamped at the same instant.
        repeat(5) { index ->
            h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt000000$index", season = 1, episode = 1))
        }
        assertEquals(5, h.http.calls)

        // A new key at a much later instant trims the five older ones.
        h.clock.now = 10_000L
        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt9999999", season = 1, episode = 1))
        assertEquals(6, h.http.calls)

        h.clock.now = 10_010L
        h.service.getSkipIntervals(IntroSkipRequest(imdbId = "tt9999999", season = 1, episode = 1))
        assertEquals(
            6,
            h.http.calls,
            "The recent entry survives trimming; the expired ones would have refetched had they not been dropped.",
        )
    }
}

// ---------------------------------------------------------------------------
// The interval's own arithmetic
// ---------------------------------------------------------------------------

class IntroSkipIntervalTest {

    private val interval = IntroSkipInterval(
        startTimeMs = 0L,
        endTimeMs = 30_000L,
        segmentType = IntroSkipSegmentType.INTRO,
        provider = IntroSkipProvider.INTRO_DB,
    )

    @Test
    fun theIntervalIsActiveFromItsStart() = assertTrue(interval.isActiveAt(0L))

    @Test
    fun theIntervalIsActiveUpToHalfASecondBeforeItsEnd() = assertTrue(interval.isActiveAt(29_499L))

    @Test
    fun theIntervalGoesInactiveHalfASecondBeforeItsEnd() = assertFalse(
        interval.isActiveAt(29_500L),
        "The window is `position < end - 500`, so the last 500ms are already inactive.",
    )

    @Test
    fun theIntervalIsNotActiveAtOrAfterItsEnd() {
        assertFalse(interval.isActiveAt(30_000L))
        assertFalse(interval.isActiveAt(60_000L))
    }

    @Test
    fun theIntervalIsNotActiveBeforeItsStart() = assertFalse(interval.isActiveAt(-1L))

    @Test
    fun theStableKeyNamesProviderTypeAndBothBounds() {
        assertEquals("INTRO_DB:INTRO:0:30000", interval.stableKey)
    }

    @Test
    fun theStableKeyChangesWithEveryFieldItNames() {
        val key = interval.stableKey

        assertTrue(interval.copy(startTimeMs = 1L).stableKey != key, "the start")
        assertTrue(interval.copy(endTimeMs = 30_001L).stableKey != key, "the end")
        assertTrue(interval.copy(segmentType = IntroSkipSegmentType.RECAP).stableKey != key, "the type")
        assertTrue(interval.copy(provider = IntroSkipProvider.ANI_SKIP).stableKey != key, "the provider")
        assertEquals(key, interval.copy(skipId = "ignored").stableKey, "skipId is deliberately not in the key.")
    }
}

class IntroSkipSegmentTypeTest {

    @Test
    fun everyWireValueMapsToItsOwnSegmentType() {
        val expected = mapOf(
            "intro" to IntroSkipSegmentType.INTRO,
            "outro" to IntroSkipSegmentType.OUTRO,
            "recap" to IntroSkipSegmentType.RECAP,
            "op" to IntroSkipSegmentType.OP,
            "ed" to IntroSkipSegmentType.ED,
            "mixed-op" to IntroSkipSegmentType.MIXED_OP,
            "mixed-ed" to IntroSkipSegmentType.MIXED_ED,
        )

        for ((wire, segmentType) in expected) {
            assertEquals(segmentType, IntroSkipSegmentType.fromWire(wire), "wire value \"$wire\"")
            assertEquals(
                segmentType,
                IntroSkipSegmentType.fromWire(wire.uppercase()),
                "wire value \"$wire\" uppercased",
            )
        }
    }

    @Test
    fun anUnrecognisedWireValueIsUnknown() = assertEquals(
        IntroSkipSegmentType.UNKNOWN,
        IntroSkipSegmentType.fromWire("opening"),
    )

    @Test
    fun anEmptyOrAbsentWireValueIsUnknown() {
        assertEquals(IntroSkipSegmentType.UNKNOWN, IntroSkipSegmentType.fromWire(""))
        assertEquals(IntroSkipSegmentType.UNKNOWN, IntroSkipSegmentType.fromWire(null))
    }

    @Test
    fun anUnknownSkipTypeLeavesTheIntervalIntactRatherThanDroppingIt() = runTest {
        val h = Harness { url ->
            if (url.startsWith(ANI_SKIP)) {
                CrispyHttpResponse(
                    200,
                    """
                    {"found":true,"results":[
                      {"interval":{"startTime":0,"endTime":30000},"skipType":"opening","skipId":"x"}
                    ]}
                    """.trimIndent(),
                )
            } else {
                error("Unexpected request: $url")
            }
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(season = 1, episode = 3, malId = 777)
        )

        assertEquals(1, result.size, "An unrecognised type maps to UNKNOWN, it does not discard the interval.")
        assertEquals(IntroSkipSegmentType.UNKNOWN, result.single().segmentType)
    }

    @Test
    fun anAbsentSkipIdBecomesNullRatherThanTheTextNull() = runTest {
        val h = Harness { url ->
            if (url.startsWith(ANI_SKIP)) {
                CrispyHttpResponse(
                    200,
                    """
                    {"found":true,"results":[
                      {"interval":{"startTime":0,"endTime":30000},"skipType":"OP","skipId":null}
                    ]}
                    """.trimIndent(),
                )
            } else {
                error("Unexpected request: $url")
            }
        }

        val result = h.service.getSkipIntervals(
            IntroSkipRequest(season = 1, episode = 3, malId = 777)
        )

        assertEquals(
            null,
            result.single().skipId,
            "A JSON null is not the four characters \"null\"; this is the node type earning its keep.",
        )
    }
}

/**
 * [skipLabelFor] was `private` in `androidMain`, so the label a user sees had no test
 * at all until the overlay moved to `commonMain` and the function was widened to
 * `internal` -- the file and the decision had to move together, because `private` is a
 * property of the file rather than of the package.
 */
class IntroSkipSkipLabelTest {

    /**
     * The whole table, as a set comparison. A hand-picked list of cases is a sample: a
     * new `IntroSkipSegmentType` would quietly ship with no label case beside it and the
     * suite would pass. Iterating `entries` makes the addition itself the failure.
     */
    @Test
    fun everySegmentTypeHasTheLabelItIsNamedFor() {
        val expected = mapOf(
            IntroSkipSegmentType.INTRO to "Skip Intro",
            IntroSkipSegmentType.OUTRO to "Skip Ending",
            IntroSkipSegmentType.RECAP to "Skip Recap",
            IntroSkipSegmentType.OP to "Skip Intro",
            IntroSkipSegmentType.ED to "Skip Ending",
            IntroSkipSegmentType.MIXED_OP to "Skip Intro",
            IntroSkipSegmentType.MIXED_ED to "Skip Ending",
            IntroSkipSegmentType.UNKNOWN to "Skip",
        )

        assertEquals(
            expected.keys,
            IntroSkipSegmentType.entries.toSet(),
            "The enum gained a value, so this table is missing a row rather than the code being wrong.",
        )

        for ((segmentType, label) in expected) {
            assertEquals(label, skipLabelFor(segmentType), "Wrong label for $segmentType")
        }
    }

    /**
     * The grouping, stated as a rule rather than as eight rows. The names imply it --
     * anything opening shares INTRO's label, anything closing shares OUTRO's -- and the
     * eight rows above cannot tell "the rule holds" from "the rule holds by coincidence
     * for these eight". This one fails if a closing type is ever routed to INTRO's arm.
     */
    @Test
    fun openingsShareOneLabelAndClosingsShareAnotherAndTheRestStandAlone() {
        val openings = setOf(
            IntroSkipSegmentType.INTRO,
            IntroSkipSegmentType.OP,
            IntroSkipSegmentType.MIXED_OP,
        )
        val closings = setOf(
            IntroSkipSegmentType.OUTRO,
            IntroSkipSegmentType.ED,
            IntroSkipSegmentType.MIXED_ED,
        )
        val standalone = setOf(IntroSkipSegmentType.RECAP, IntroSkipSegmentType.UNKNOWN)

        val openingLabel = skipLabelFor(IntroSkipSegmentType.INTRO)
        for (segmentType in openings) {
            assertEquals(openingLabel, skipLabelFor(segmentType), "$segmentType is an opening")
        }

        val closingLabel = skipLabelFor(IntroSkipSegmentType.OUTRO)
        for (segmentType in closings) {
            assertEquals(closingLabel, skipLabelFor(segmentType), "$segmentType is a closing")
        }

        // The two groups are distinguished by a word, not by punctuation or case, so an
        // assertion of "they differ" is the one that carries meaning.
        assertNotEquals(
            openingLabel,
            closingLabel,
            "If openings and closings read the same, the split above proves nothing.",
        )

        for (segmentType in standalone) {
            assertNotEquals(
                openingLabel,
                skipLabelFor(segmentType),
                "$segmentType is neither an opening nor a closing",
            )
            assertNotEquals(
                closingLabel,
                skipLabelFor(segmentType),
                "$segmentType is neither an opening nor a closing",
            )
        }
    }
}
