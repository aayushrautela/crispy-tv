import XCTest
@testable import ContractRunner

final class MetadataTmdbEnhancerContractTests: XCTestCase {
    func testMetadataTmdbEnhancerFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "metadata_tmdb_enhancer")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one metadata_tmdb_enhancer fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("metadata_tmdb_enhancer", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let mediaType = MetadataMediaType.fromContractValue(
                try requireString(input, "media_type", fixture: fixtureURL)
            )
            let contentId = try requireString(input, "content_id", fixture: fixtureURL)
            let season = optionalInt(input, "season")
            let episode = optionalInt(input, "episode")
            let addonMeta = try parseMetadataRecord(
                try requireObject(input, "addon_meta", fixture: fixtureURL),
                fixtureURL: fixtureURL
            )
            let tmdbMeta = try optionalObject(input, "tmdb_meta").map { value in
                try parseMetadataRecord(value, fixtureURL: fixtureURL)
            }

            let actualNeedsEnrichment = needsTmdbMetaEnrichment(addonMeta, mediaType: mediaType)
            XCTAssertEqual(
                try requireBool(expected, "needs_enrichment", fixture: fixtureURL),
                actualNeedsEnrichment,
                "\(caseId): needs_enrichment"
            )

            let actualMerged = withDerivedSeasons(
                mergeAddonAndTmdbMeta(addonMeta: addonMeta, tmdbMeta: tmdbMeta, mediaType: mediaType),
                mediaType: mediaType
            )
            let expectedMerged = try parseMetadataRecord(
                try requireObject(expected, "merged", fixture: fixtureURL),
                fixtureURL: fixtureURL
            )
            XCTAssertEqual(expectedMerged, actualMerged, "\(caseId): merged")

            let expectedSeasonNumbers = try intArray(
                try requireArray(expected, "derived_season_numbers", fixture: fixtureURL),
                fixture: fixtureURL,
                key: "derived_season_numbers"
            )
            XCTAssertEqual(
                expectedSeasonNumbers,
                actualMerged.seasons.map(\.seasonNumber),
                "\(caseId): derived_season_numbers"
            )

            let expectedEpisodeCounts = try intArray(
                try requireArray(expected, "derived_episode_counts", fixture: fixtureURL),
                fixture: fixtureURL,
                key: "derived_episode_counts"
            )
            XCTAssertEqual(
                expectedEpisodeCounts,
                actualMerged.seasons.map(\.episodeCount),
                "\(caseId): derived_episode_counts"
            )

            let expectedBridgeIds = try stringArray(
                try requireArray(expected, "bridge_candidate_ids", fixture: fixtureURL),
                fixture: fixtureURL,
                key: "bridge_candidate_ids"
            )
            XCTAssertEqual(
                expectedBridgeIds,
                bridgeCandidateIds(contentId: contentId, season: season, episode: episode, tmdbMeta: tmdbMeta),
                "\(caseId): bridge_candidate_ids"
            )
        }
    }

    private func parseMetadataRecord(_ object: [String: Any], fixtureURL: URL) throws -> MetadataRecord {
        let seasonValues = try requireArray(object, "seasons", fixture: fixtureURL)
        let seasons: [MetadataSeason] = try seasonValues.map { value in
            guard let seasonObject = value as? [String: Any] else {
                throw ContractTestError.invalidFixture("\(fixtureURL.lastPathComponent): season entry must be object")
            }
            return MetadataSeason(
                id: try requireString(seasonObject, "id", fixture: fixtureURL),
                name: try requireString(seasonObject, "name", fixture: fixtureURL),
                overview: try requireString(seasonObject, "overview", fixture: fixtureURL),
                seasonNumber: try requireInt(seasonObject, "season_number", fixture: fixtureURL),
                episodeCount: try requireInt(seasonObject, "episode_count", fixture: fixtureURL),
                airDate: optionalString(seasonObject, "air_date")
            )
        }

        let videoValues = try requireArray(object, "videos", fixture: fixtureURL)
        let videos: [MetadataVideo] = try videoValues.map { value in
            guard let videoObject = value as? [String: Any] else {
                throw ContractTestError.invalidFixture("\(fixtureURL.lastPathComponent): video entry must be object")
            }
            return MetadataVideo(
                season: optionalInt(videoObject, "season"),
                episode: optionalInt(videoObject, "episode"),
                released: optionalString(videoObject, "released")
            )
        }

        return MetadataRecord(
            id: try requireString(object, "id", fixture: fixtureURL),
            imdbId: optionalString(object, "imdb_id"),
            cast: try stringArray(try requireArray(object, "cast", fixture: fixtureURL), fixture: fixtureURL, key: "cast"),
            director: try stringArray(try requireArray(object, "director", fixture: fixtureURL), fixture: fixtureURL, key: "director"),
            castWithDetails: try stringArray(
                try requireArray(object, "cast_with_details", fixture: fixtureURL),
                fixture: fixtureURL,
                key: "cast_with_details"
            ),
            similar: try stringArray(try requireArray(object, "similar", fixture: fixtureURL), fixture: fixtureURL, key: "similar"),
            collectionItems: try stringArray(
                try requireArray(object, "collection_items", fixture: fixtureURL),
                fixture: fixtureURL,
                key: "collection_items"
            ),
            seasons: seasons,
            videos: videos
        )
    }
}
