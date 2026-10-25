import Foundation
import XCTest
@testable import CrispyKit

/// The addon manifest's pure rules: the URL a user typed, the manifest that URL
/// answers with, and the preview those two make.
final class AddonManifestTests: XCTestCase {
    // MARK: - What the user typed

    func testABareHostBecomesAnHttpsManifestUrl() {
        XCTAssertEqual(
            AddonManifestURL.normalize("addon.example"),
            "https://addon.example/manifest.json"
        )
        XCTAssertEqual(
            AddonManifestURL.normalize("  addon.example/  "),
            "https://addon.example/manifest.json"
        )
        XCTAssertEqual(
            AddonManifestURL.normalize("addon.example:9999"),
            "https://addon.example:9999/manifest.json"
        )
    }

    func testAStremioShareLinkBecomesHttps() {
        XCTAssertEqual(
            AddonManifestURL.normalize("stremio://addon.example/manifest.json"),
            "https://addon.example/manifest.json"
        )
        XCTAssertEqual(
            AddonManifestURL.normalize("stremio://addon.example/config"),
            "https://addon.example/config/manifest.json"
        )
    }

    func testTheManifestFileNameIsAppendedOnlyOnce() {
        XCTAssertEqual(
            AddonManifestURL.normalize("https://addon.example/configure"),
            "https://addon.example/configure/manifest.json"
        )
        XCTAssertEqual(
            AddonManifestURL.normalize("https://addon.example/MANIFEST.JSON"),
            "https://addon.example/MANIFEST.JSON"
        )
    }

    func testTheQueryTravelsWithTheManifestUrl() {
        XCTAssertEqual(
            AddonManifestURL.normalize("https://addon.example/config?token=abc"),
            "https://addon.example/config/manifest.json?token=abc"
        )
        XCTAssertEqual(
            AddonManifestURL.normalize("https://addon.example/config?"),
            "https://addon.example/config/manifest.json"
        )
    }

    func testWhatCannotBeAManifestUrlIsRejected() {
        XCTAssertNil(AddonManifestURL.normalize(""))
        XCTAssertNil(AddonManifestURL.normalize("   "))
        XCTAssertNil(AddonManifestURL.normalize("https://"))
    }

    func testTheAddonIdFallsBackToTheHost() {
        XCTAssertEqual(AddonManifestURL.addonId(of: "https://addon.example/manifest.json"), "addon.example")
        XCTAssertEqual(AddonManifestURL.addonId(of: "addon.example/config"), "addon.example")
        XCTAssertNil(AddonManifestURL.addonId(of: ""))
    }

    func testManifestUrlsMatchIgnoringCase() {
        XCTAssertTrue(
            AddonManifestURL.matches(
                "https://Addon.example/manifest.json",
                " https://addon.example/manifest.json "
            )
        )
        XCTAssertFalse(
            AddonManifestURL.matches(
                "https://a.example/manifest.json",
                "https://b.example/manifest.json"
            )
        )
    }

    func testTheBaseIsTheManifestUrlWithoutItsFileName() {
        XCTAssertEqual(
            AddonManifestURL.base(of: "https://addon.example/sub/manifest.json?x=1"),
            "https://addon.example/sub"
        )
        XCTAssertEqual(
            AddonManifestURL.base(of: "https://addon.example/manifest.json"),
            "https://addon.example/"
        )
    }

    // MARK: - The assets a manifest names

    func testAnAssetIsResolvedAgainstTheAddonsDirectory() {
        XCTAssertEqual(
            AddonManifestURL.resolveAsset(base: "https://addon.example/sub", raw: "logo.png"),
            "https://addon.example/sub/logo.png"
        )
        XCTAssertEqual(
            AddonManifestURL.resolveAsset(base: "https://addon.example/sub/", raw: "logo.png"),
            "https://addon.example/sub/logo.png"
        )
        XCTAssertEqual(
            AddonManifestURL.resolveAsset(base: "https://addon.example/sub", raw: "/logo.png"),
            "https://addon.example/logo.png"
        )
        XCTAssertEqual(
            AddonManifestURL.resolveAsset(base: "https://addon.example/sub", raw: "//cdn.example/logo.png"),
            "https://cdn.example/logo.png"
        )
        XCTAssertEqual(
            AddonManifestURL.resolveAsset(base: "https://addon.example/sub", raw: "https://cdn.example/logo.png"),
            "https://cdn.example/logo.png"
        )
        XCTAssertEqual(
            AddonManifestURL.resolveAsset(base: "https://addon.example/sub", raw: "data:image/png;base64,AAAA"),
            "data:image/png;base64,AAAA"
        )
        XCTAssertNil(AddonManifestURL.resolveAsset(base: "https://addon.example/sub", raw: "   "))
    }

    // MARK: - The manifest

    func testTheManifestReadsBothResourceShapes() {
        let manifest = AddonManifest.parse([
            "id": "org.example.addon",
            "name": "Example",
            "types": ["movie", "series"],
            "resources": [
                "catalog",
                ["name": "stream", "types": ["movie"], "idPrefixes": ["tt"]] as [String: Any],
                7,
                "  ",
            ] as [Any],
        ])

        XCTAssertEqual(manifest.id, "org.example.addon")
        XCTAssertEqual(manifest.name, "Example")
        XCTAssertEqual(manifest.types, ["movie", "series"])
        XCTAssertEqual(manifest.resourceNames, ["catalog", "stream"])
        XCTAssertEqual(manifest.resources.count, 2, "a number and a blank string are not resources")
        XCTAssertEqual(manifest.resources.last?.types, ["movie"])
        XCTAssertEqual(manifest.resources.last?.idPrefixes, ["tt"])
    }

    func testAResourceIsShownOnceWhileEveryDeclarationStays() {
        let manifest = AddonManifest.parse([
            "resources": [
                "stream",
                ["name": "stream", "types": ["movie"]] as [String: Any],
            ] as [Any],
        ])

        XCTAssertEqual(manifest.resourceNames, ["stream"])
        XCTAssertEqual(manifest.resources.count, 2)
        XCTAssertEqual(manifest.resources.first?.types, [])
        XCTAssertEqual(manifest.resources.last?.types, ["movie"])
    }

    func testAManifestWithoutResourcesDeclaresNone() {
        let manifest = AddonManifest.parse(["id": "org.example.addon"])

        XCTAssertTrue(manifest.resources.isEmpty)
        XCTAssertTrue(manifest.resourceNames.isEmpty)
        XCTAssertNil(manifest.name)
    }

    // MARK: - The preview

    func testThePreviewReadsAManifestIntoTheInstallCard() {
        let preview = AddonManifestPreview.make(
            manifestUrl: "https://addon.example/manifest.json",
            manifest: AddonManifest.parse([
                "id": "org.example.addon",
                "name": "Example",
                "description": "Example streams",
                "version": "1.2.3",
                "logo": "logo.png",
                "types": ["movie"],
                "resources": ["stream"],
            ])
        )

        XCTAssertEqual(preview.name, "Example")
        XCTAssertEqual(preview.description, "Example streams")
        XCTAssertEqual(preview.version, "1.2.3")
        XCTAssertEqual(preview.addonId, "org.example.addon")
        XCTAssertEqual(preview.logoUrl, "https://addon.example/logo.png")
        XCTAssertEqual(preview.resources, ["stream"])
        XCTAssertEqual(preview.types, ["movie"])
        XCTAssertTrue(preview.warnings.isEmpty)
    }

    func testAManifestWithoutANameIsShownByItsId() {
        let preview = AddonManifestPreview.make(
            manifestUrl: "https://addon.example/manifest.json",
            manifest: AddonManifest.parse([
                "id": "org.example.addon",
                "types": ["movie"],
                "resources": ["stream"],
            ])
        )

        XCTAssertEqual(preview.name, "org.example.addon")
        XCTAssertEqual(preview.description, "https://addon.example/manifest.json")
        XCTAssertNil(preview.logoUrl)
        XCTAssertTrue(preview.warnings.isEmpty)
    }

    func testThePreviewWarnsAboutEverythingTheManifestIsMissing() {
        let preview = AddonManifestPreview.make(
            manifestUrl: "http://addon.example/manifest.json",
            manifest: AddonManifest.parse([:])
        )

        XCTAssertEqual(preview.name, "Unknown addon")
        XCTAssertEqual(preview.description, "http://addon.example/manifest.json")
        XCTAssertNil(preview.addonId)
        XCTAssertNil(preview.version)
        XCTAssertEqual(
            preview.warnings,
            [
                "This addon uses an insecure HTTP URL.",
                "Manifest is missing a stable addon id.",
                "Manifest does not list addon resources.",
                "Manifest does not declare supported media types.",
            ]
        )
    }
}
