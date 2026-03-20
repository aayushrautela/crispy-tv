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
            let mediaType = MetadataMediaType.fromContractValue(try requireString(input, "media_type", fixture: fixtureURL))
            let meta = try parseMetadataRecord(try requireObject(input, "meta", fixture: fixtureURL), fixture: fixtureURL)
            let tmdbMeta = try parseMetadataRecordIfPresent(input["tmdb_meta"], fixture: fixtureURL)

            let actualDerived = withDerivedSeasons(meta, mediaType: mediaType).seasons
            let actualBridgeCandidates = bridgeCandidateIds(
                contentId: try requireString(input, "content_id", fixture: fixtureURL),
                season: optionalInt(input, "season"),
                episode: optionalInt(input, "episode"),
                tmdbMeta: tmdbMeta
            )

            let expectedDerived = try requireArray(expected, "derived_seasons", fixture: fixtureURL).map {
                try parseMetadataSeason($0, fixture: fixtureURL)
            }
            let expectedBridgeCandidates = try stringArrayValue(
                try requireArray(expected, "bridge_candidate_ids", fixture: fixtureURL),
                fixture: fixtureURL,
                field: "bridge_candidate_ids"
            )

            XCTAssertEqual(expectedDerived, actualDerived, "\(caseId): derived_seasons")
            XCTAssertEqual(expectedBridgeCandidates, actualBridgeCandidates, "\(caseId): bridge_candidate_ids")
        }
    }

    private func parseMetadataRecord(_ object: [String: Any], fixture: URL) throws -> MetadataRecord {
        MetadataRecord(
            id: try requireString(object, "id", fixture: fixture),
            imdbId: optionalString(object, "imdb_id"),
            cast: try stringArrayValue(try requireArray(object, "cast", fixture: fixture), fixture: fixture, field: "cast"),
            director: try stringArrayValue(try requireArray(object, "director", fixture: fixture), fixture: fixture, field: "director"),
            castWithDetails: try stringArrayValue(
                try requireArray(object, "cast_with_details", fixture: fixture),
                fixture: fixture,
                field: "cast_with_details"
            ),
            similar: try stringArrayValue(try requireArray(object, "similar", fixture: fixture), fixture: fixture, field: "similar"),
            collectionItems: try stringArrayValue(
                try requireArray(object, "collection_items", fixture: fixture),
                fixture: fixture,
                field: "collection_items"
            ),
            seasons: try requireArray(object, "seasons", fixture: fixture).map {
                try parseMetadataSeason($0, fixture: fixture)
            },
            videos: try requireArray(object, "videos", fixture: fixture).map {
                try parseMetadataVideo($0, fixture: fixture)
            }
        )
    }

    private func parseMetadataRecordIfPresent(_ value: Any?, fixture: URL) throws -> MetadataRecord? {
        guard let value else {
            return nil
        }
        if value is NSNull {
            return nil
        }
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): tmdb_meta must be an object or null")
        }
        return try parseMetadataRecord(object, fixture: fixture)
    }

    private func parseMetadataSeason(_ value: Any, fixture: URL) throws -> MetadataSeason {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): season must be an object")
        }

        return MetadataSeason(
            id: try requireString(object, "id", fixture: fixture),
            name: try requireString(object, "name", fixture: fixture),
            overview: try requireString(object, "overview", fixture: fixture),
            seasonNumber: try requireInt(object, "season_number", fixture: fixture),
            episodeCount: try requireInt(object, "episode_count", fixture: fixture),
            airDate: optionalString(object, "air_date")
        )
    }

    private func parseMetadataVideo(_ value: Any, fixture: URL) throws -> MetadataVideo {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): video must be an object")
        }

        return MetadataVideo(
            season: optionalInt(object, "season"),
            episode: optionalInt(object, "episode"),
            released: optionalString(object, "released")
        )
    }
}

private func stringArrayValue(_ array: [Any], fixture: URL, field: String) throws -> [String] {
    var strings: [String] = []
    for value in array {
        guard let string = value as? String else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): \(field) entries must be strings")
        }
        strings.append(string)
    }
    return strings
}
