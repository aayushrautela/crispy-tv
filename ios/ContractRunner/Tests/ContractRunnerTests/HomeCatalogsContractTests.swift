import XCTest
@testable import ContractRunner

final class HomeCatalogsContractTests: XCTestCase {
    func testHomeCatalogFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "home_catalogs")
        XCTAssertFalse(fixtures.isEmpty, "Expected at least one home_catalogs fixture")

        for fixtureURL in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixtureURL)
            let caseId = try requireString(root, "case_id", fixture: fixtureURL)
            let suite = try requireString(root, "suite", fixture: fixtureURL)
            XCTAssertEqual("home_catalogs", suite, "\(caseId): wrong suite")

            let input = try requireObject(root, "input", fixture: fixtureURL)
            let expected = try requireObject(root, "expected", fixture: fixtureURL)
            let snapshot = try parseSnapshot(try requireObject(input, "snapshot", fixture: fixtureURL), fixture: fixtureURL)
            let discoverInput = try requireObject(input, "discover", fixture: fixtureURL)
            let pageInput = try requireObject(input, "page", fixture: fixtureURL)

            let actualPersonalFeed = planPersonalHomeFeed(
                snapshot: snapshot,
                heroLimit: try requireInt(input, "hero_limit", fixture: fixtureURL),
                sectionLimit: try requireInt(input, "section_limit", fixture: fixtureURL)
            )
            let actualDiscover = listDiscoverCatalogs(
                snapshot: snapshot,
                mediaType: optionalString(discoverInput, "media_type"),
                limit: try requireInt(discoverInput, "limit", fixture: fixtureURL)
            )
            let actualPage = buildCatalogPage(
                snapshot: snapshot,
                sectionCatalogId: try requireString(pageInput, "catalog_id", fixture: fixtureURL),
                page: try requireInt(pageInput, "page", fixture: fixtureURL),
                pageSize: try requireInt(pageInput, "page_size", fixture: fixtureURL)
            )

            XCTAssertEqual(try parsePersonalFeed(try requireObject(expected, "personal_feed", fixture: fixtureURL), fixture: fixtureURL), actualPersonalFeed, "\(caseId): personal_feed")
            let expectedDiscover = try parseDiscover(try requireObject(expected, "discover", fixture: fixtureURL), fixture: fixtureURL)
            XCTAssertEqual(expectedDiscover.catalogs, actualDiscover.0, "\(caseId): discover catalogs")
            XCTAssertEqual(expectedDiscover.statusMessage, actualDiscover.1, "\(caseId): discover status")
            XCTAssertEqual(try parsePage(try requireObject(expected, "page", fixture: fixtureURL), fixture: fixtureURL), actualPage, "\(caseId): page")
        }
    }

    private func parseSnapshot(_ object: [String: Any], fixture: URL) throws -> HomeCatalogSnapshot {
        return HomeCatalogSnapshot(
            profileId: optionalString(object, "profile_id"),
            lists: try requireArray(object, "lists", fixture: fixture).map { try parseList($0, fixture: fixture) },
            statusMessage: try requireString(object, "status_message", fixture: fixture)
        )
    }

    private func parseList(_ value: Any, fixture: URL) throws -> HomeCatalogList {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): list must be an object")
        }

        guard let source = HomeCatalogSource.fromRaw(try requireString(object, "source", fixture: fixture)) else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): invalid source")
        }

        return HomeCatalogList(
            kind: try requireString(object, "kind", fixture: fixture),
            variantKey: optionalString(object, "variant_key") ?? "default",
            source: source,
            presentation: HomeCatalogPresentation.fromRaw(optionalString(object, "presentation")),
            name: optionalString(object, "name") ?? "",
            heading: optionalString(object, "heading") ?? "",
            title: optionalString(object, "title") ?? "",
            subtitle: optionalString(object, "subtitle") ?? "",
            items: try requireArray(object, "items", fixture: fixture).map { try parseItem($0, fixture: fixture) },
            mediaTypes: Set(try stringArrayValue(try requireArray(object, "media_types", fixture: fixture), fixture: fixture, field: "media_types"))
        )
    }

    private func parseItem(_ value: Any, fixture: URL) throws -> HomeCatalogItem {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): item must be an object")
        }

        return HomeCatalogItem(
            id: try requireString(object, "id", fixture: fixture),
            title: try requireString(object, "title", fixture: fixture),
            posterUrl: optionalString(object, "poster_url"),
            backdropUrl: optionalString(object, "backdrop_url"),
            addonId: try requireString(object, "addon_id", fixture: fixture),
            type: try requireString(object, "type", fixture: fixture),
            rating: optionalString(object, "rating"),
            year: optionalString(object, "year"),
            description: optionalString(object, "description"),
            provider: try requireString(object, "provider", fixture: fixture),
            providerId: try requireString(object, "provider_id", fixture: fixture)
        )
    }

    private func parsePersonalFeed(_ object: [String: Any], fixture: URL) throws -> HomeCatalogFeedPlan {
        return HomeCatalogFeedPlan(
            heroResult: try parseHeroResult(try requireObject(object, "hero_result", fixture: fixture), fixture: fixture),
            sections: try requireArray(object, "sections", fixture: fixture).map { try parseSection($0, fixture: fixture) },
            sectionsStatusMessage: try requireString(object, "sections_status_message", fixture: fixture)
        )
    }

    private func parseHeroResult(_ object: [String: Any], fixture: URL) throws -> HomeCatalogHeroResult {
        return HomeCatalogHeroResult(
            items: try requireArray(object, "items", fixture: fixture).map { try parseHeroItem($0, fixture: fixture) },
            statusMessage: try requireString(object, "status_message", fixture: fixture)
        )
    }

    private func parseHeroItem(_ value: Any, fixture: URL) throws -> HomeCatalogHeroItem {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): hero item must be an object")
        }

        return HomeCatalogHeroItem(
            id: try requireString(object, "id", fixture: fixture),
            title: try requireString(object, "title", fixture: fixture),
            description: try requireString(object, "description", fixture: fixture),
            rating: optionalString(object, "rating"),
            year: optionalString(object, "year"),
            genres: try stringArrayValue(try requireArray(object, "genres", fixture: fixture), fixture: fixture, field: "genres"),
            backdropUrl: try requireString(object, "backdrop_url", fixture: fixture),
            addonId: try requireString(object, "addon_id", fixture: fixture),
            type: try requireString(object, "type", fixture: fixture),
            provider: try requireString(object, "provider", fixture: fixture),
            providerId: try requireString(object, "provider_id", fixture: fixture)
        )
    }

    private func parseSection(_ value: Any, fixture: URL) throws -> HomeCatalogSection {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): section must be an object")
        }

        guard let source = HomeCatalogSource.fromRaw(try requireString(object, "source", fixture: fixture)) else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): invalid section source")
        }

        return HomeCatalogSection(
            catalogId: try requireString(object, "catalog_id", fixture: fixture),
            source: source,
            presentation: HomeCatalogPresentation.fromRaw(optionalString(object, "presentation")),
            variantKey: optionalString(object, "variant_key") ?? "default",
            name: optionalString(object, "name") ?? "",
            heading: optionalString(object, "heading") ?? "",
            title: optionalString(object, "title") ?? "",
            subtitle: optionalString(object, "subtitle") ?? ""
        )
    }

    private func parseDiscover(_ object: [String: Any], fixture: URL) throws -> (catalogs: [HomeCatalogDiscoverRef], statusMessage: String) {
        return (
            catalogs: try requireArray(object, "catalogs", fixture: fixture).map { try parseDiscoverRef($0, fixture: fixture) },
            statusMessage: try requireString(object, "status_message", fixture: fixture)
        )
    }

    private func parseDiscoverRef(_ value: Any, fixture: URL) throws -> HomeCatalogDiscoverRef {
        guard let object = value as? [String: Any] else {
            throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): discover ref must be an object")
        }

        return HomeCatalogDiscoverRef(
            section: try parseSection(try requireObject(object, "section", fixture: fixture), fixture: fixture),
            addonName: try requireString(object, "addon_name", fixture: fixture),
            genres: try stringArrayValue(try requireArray(object, "genres", fixture: fixture), fixture: fixture, field: "genres")
        )
    }

    private func parsePage(_ object: [String: Any], fixture: URL) throws -> HomeCatalogPageResult {
        return HomeCatalogPageResult(
            items: try requireArray(object, "items", fixture: fixture).map { try parseItem($0, fixture: fixture) },
            statusMessage: try requireString(object, "status_message", fixture: fixture),
            attemptedUrls: try stringArrayValue(try requireArray(object, "attempted_urls", fixture: fixture), fixture: fixture, field: "attempted_urls")
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
