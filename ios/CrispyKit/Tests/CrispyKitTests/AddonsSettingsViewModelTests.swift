import Foundation
import XCTest
@testable import CrispyKit

/// The addon settings screen's sequence: show what the account has, check a
/// typed URL, read its manifest, install it, remove it.
@MainActor
final class AddonsSettingsViewModelTests: XCTestCase {
    // MARK: - Loading

    func testLoadShowsTheAccountsAddons() async {
        let store = FakeAddonStore(addons: [addon(id: "org.example.addon", name: "Example")])
        let viewModel = makeViewModel(store: store)

        await viewModel.load()

        XCTAssertEqual(viewModel.installed.map(\.id), ["org.example.addon"])
        XCTAssertEqual(store.listRequestCount, 1)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testLoadWithoutASignInAsksForOne() async {
        let store = FakeAddonStore()
        let viewModel = makeViewModel(store: store, context: { nil })

        await viewModel.load()

        XCTAssertEqual(viewModel.errorMessage, "Sign in to manage addons.")
        XCTAssertTrue(viewModel.installed.isEmpty)
        XCTAssertEqual(store.listRequestCount, 0)
    }

    func testLoadHappensOnceUnlessAForceAsksAgain() async {
        let store = FakeAddonStore()
        let viewModel = makeViewModel(store: store)

        await viewModel.load()
        await viewModel.load()
        XCTAssertEqual(store.listRequestCount, 1)

        await viewModel.load(force: true)
        XCTAssertEqual(store.listRequestCount, 2)
    }

    func testAFailedLoadIsReported() async {
        let store = FakeAddonStore()
        store.listError = TestFailure.refused
        let viewModel = makeViewModel(store: store)

        await viewModel.load()

        XCTAssertEqual(viewModel.errorMessage, "Unable to load your addons.")
    }

    // MARK: - Checking a draft

    func testAnEmptyDraftAsksForAUrl() async {
        let viewModel = makeViewModel()

        await viewModel.checkDraft()

        XCTAssertEqual(viewModel.errorMessage, "Enter an addon manifest URL first.")
        XCTAssertNil(viewModel.preview)
    }

    func testADraftThatCannotBeAManifestUrlIsRejected() async {
        let manifests = FakeManifestFetching()
        let viewModel = makeViewModel(manifests: manifests)

        viewModel.updateDraftUrl("https://")
        await viewModel.checkDraft()

        XCTAssertEqual(viewModel.errorMessage, "The addon URL is invalid. Try a full manifest URL.")
        XCTAssertTrue(manifests.requestedURLs.isEmpty)
    }

    func testAnAddonAlreadyInstalledIsNotCheckedAgain() async {
        let store = FakeAddonStore(addons: [addon(manifestUrl: "https://addon.example/manifest.json")])
        let manifests = FakeManifestFetching()
        let viewModel = makeViewModel(store: store, manifests: manifests)
        await viewModel.load()

        viewModel.updateDraftUrl("https://ADDON.example/manifest.json")
        await viewModel.checkDraft()

        XCTAssertEqual(viewModel.errorMessage, "That addon is already installed.")
        XCTAssertTrue(manifests.requestedURLs.isEmpty)
    }

    func testAManifestThatCannotBeReadIsReported() async {
        let manifests = FakeManifestFetching()
        let viewModel = makeViewModel(manifests: manifests)

        viewModel.updateDraftUrl("addon.example")
        await viewModel.checkDraft()

        XCTAssertEqual(viewModel.errorMessage, "Unable to load addon manifest from that URL.")
        XCTAssertEqual(manifests.requestedURLs, ["https://addon.example/manifest.json"])
        XCTAssertNil(viewModel.preview)
    }

    func testCheckingADraftReadsTheNormalizedUrlAndPreviewsTheManifest() async {
        let manifests = FakeManifestFetching(manifests: [
            "https://addon.example/manifest.json": exampleManifest,
        ])
        let viewModel = makeViewModel(manifests: manifests)

        viewModel.updateDraftUrl("addon.example")
        await viewModel.checkDraft()

        XCTAssertEqual(manifests.requestedURLs, ["https://addon.example/manifest.json"])
        XCTAssertEqual(viewModel.preview?.name, "Example")
        XCTAssertEqual(viewModel.preview?.manifestUrl, "https://addon.example/manifest.json")
        XCTAssertNil(viewModel.errorMessage)
    }

    func testEditingTheDraftDropsThePreviewAndTheMessages() async {
        let manifests = FakeManifestFetching(manifests: [
            "https://addon.example/manifest.json": exampleManifest,
        ])
        let viewModel = makeViewModel(manifests: manifests)
        viewModel.updateDraftUrl("addon.example")
        await viewModel.checkDraft()
        XCTAssertNotNil(viewModel.preview)

        viewModel.updateDraftUrl("addon.example/other")

        XCTAssertNil(viewModel.preview)
        XCTAssertNil(viewModel.statusMessage)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testCancellingAnInstallDropsThePreview() async {
        let manifests = FakeManifestFetching(manifests: [
            "https://addon.example/manifest.json": exampleManifest,
        ])
        let viewModel = makeViewModel(manifests: manifests)
        viewModel.updateDraftUrl("addon.example")
        await viewModel.checkDraft()

        viewModel.cancelInstall()

        XCTAssertNil(viewModel.preview)
        XCTAssertEqual(viewModel.draftUrl, "addon.example")
    }

    // MARK: - Installing

    func testInstallingUsesThePreviewedUrlAndTheProfile() async {
        let store = FakeAddonStore()
        let manifests = FakeManifestFetching(manifests: [
            "https://addon.example/manifest.json": exampleManifest,
        ])
        let viewModel = makeViewModel(store: store, manifests: manifests)
        viewModel.updateDraftUrl("addon.example")
        await viewModel.checkDraft()

        await viewModel.confirmInstall()

        XCTAssertEqual(
            store.installRequests,
            [
                FakeAddonStore.InstallRequest(
                    accessToken: "token",
                    profileId: "profile-1",
                    manifestUrl: "https://addon.example/manifest.json",
                    type: "stremio"
                )
            ]
        )
        XCTAssertEqual(viewModel.installed.map(\.id), ["org.example.addon"])
        XCTAssertEqual(viewModel.statusMessage, "Installed Example.")
        XCTAssertEqual(viewModel.draftUrl, "")
        XCTAssertNil(viewModel.preview)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testInstallingWithoutASignInAsksForOne() async {
        let store = FakeAddonStore()
        let manifests = FakeManifestFetching(manifests: [
            "https://addon.example/manifest.json": exampleManifest,
        ])
        let viewModel = makeViewModel(store: store, manifests: manifests)
        viewModel.updateDraftUrl("addon.example")
        await viewModel.checkDraft()

        await viewModel.confirmInstall()

        XCTAssertEqual(viewModel.errorMessage, "Sign in to manage addons.")
        XCTAssertTrue(store.installRequests.isEmpty)
        XCTAssertNotNil(viewModel.preview, "the preview stays for the next attempt")
    }

    func testAFailedInstallKeepsThePreview() async {
        let store = FakeAddonStore()
        store.installError = TestFailure.refused
        let manifests = FakeManifestFetching(manifests: [
            "https://addon.example/manifest.json": exampleManifest,
        ])
        let viewModel = makeViewModel(store: store, manifests: manifests)
        viewModel.updateDraftUrl("addon.example")
        await viewModel.checkDraft()

        await viewModel.confirmInstall()

        XCTAssertEqual(viewModel.errorMessage, "Unable to install that addon.")
        XCTAssertNotNil(viewModel.preview)
        XCTAssertTrue(viewModel.installed.isEmpty)
        XCTAssertNil(viewModel.statusMessage)
    }

    func testInstallingReplacesTheRowTheBackendAlreadyHas() async {
        let store = FakeAddonStore(addons: [addon(id: "org.example.addon", name: "Example")])
        let manifests = FakeManifestFetching(manifests: [
            "https://other.example/manifest.json": AddonManifest.parse([
                "name": "Other",
                "types": ["movie"],
                "resources": ["stream"],
            ]),
        ])
        let viewModel = makeViewModel(store: store, manifests: manifests)
        await viewModel.load()
        XCTAssertEqual(viewModel.installed.count, 1)

        viewModel.updateDraftUrl("https://other.example/manifest.json")
        await viewModel.checkDraft()
        await viewModel.confirmInstall()

        XCTAssertEqual(store.installRequests.map(\.manifestUrl), ["https://other.example/manifest.json"])
        XCTAssertEqual(viewModel.installed.count, 1, "the row the backend re-stored replaces the one it had")
    }

    // MARK: - Removing

    func testRemovingAnAddonUsesItsIdAndDropsTheRow() async {
        let store = FakeAddonStore(addons: [addon(id: "org.example.addon", name: "Example")])
        let viewModel = makeViewModel(store: store)
        await viewModel.load()

        await viewModel.remove(addon(id: "org.example.addon", name: "Example"))

        XCTAssertEqual(
            store.uninstallRequests,
            [
                FakeAddonStore.UninstallRequest(
                    accessToken: "token",
                    profileId: "profile-1",
                    addonId: "org.example.addon"
                )
            ]
        )
        XCTAssertTrue(viewModel.installed.isEmpty)
        XCTAssertEqual(viewModel.statusMessage, "Removed Example.")
        XCTAssertNil(viewModel.removingAddonId)
    }

    func testRemovingAnAddonWithoutAnIdUsesItsHost() async {
        let store = FakeAddonStore(addons: [addon(id: "", name: "Example")])
        let viewModel = makeViewModel(store: store)
        await viewModel.load()

        await viewModel.remove(addon(id: "", name: "Example"))

        XCTAssertEqual(store.uninstallRequests.map(\.addonId), ["addon.example"])
        XCTAssertTrue(viewModel.installed.isEmpty)
        XCTAssertEqual(viewModel.statusMessage, "Removed Example.")
    }

    func testAFailedRemovalKeepsTheRow() async {
        let store = FakeAddonStore(addons: [addon(id: "org.example.addon", name: "Example")])
        store.uninstallError = TestFailure.refused
        let viewModel = makeViewModel(store: store)
        await viewModel.load()

        await viewModel.remove(addon(id: "org.example.addon", name: "Example"))

        XCTAssertEqual(viewModel.errorMessage, "Unable to remove that addon.")
        XCTAssertEqual(viewModel.installed.count, 1)
        XCTAssertNil(viewModel.removingAddonId)
    }

    func testTheNameShownForAnAddonFallsBackToItsId() {
        let viewModel = makeViewModel()

        XCTAssertEqual(viewModel.displayName(of: addon(id: "org.example.addon", name: "Example")), "Example")
        XCTAssertEqual(viewModel.displayName(of: addon(id: "org.example.addon", name: "   ")), "org.example.addon")
    }

    // MARK: - Fixtures

    private func makeViewModel(
        store: FakeAddonStore = FakeAddonStore(),
        manifests: FakeManifestFetching = FakeManifestFetching(),
        context: @escaping AddonsSettingsViewModel.ContextProvider = {
            BackendContext(accessToken: "token", profileId: "profile-1")
        }
    ) -> AddonsSettingsViewModel {
        AddonsSettingsViewModel(store: store, manifests: manifests, context: context)
    }

    private func addon(
        id: String = "org.example.addon",
        name: String = "Example",
        manifestUrl: String = "https://addon.example/manifest.json"
    ) -> AddonDescriptor {
        AddonDescriptor(id: id, manifestUrl: manifestUrl, name: name, type: "stremio")
    }

    private var exampleManifest: AddonManifest {
        AddonManifest.parse([
            "id": "org.example.addon",
            "name": "Example",
            "types": ["movie"],
            "resources": ["stream"],
        ])
    }
}

private enum TestFailure: Error {
    case refused
}

/// The backend's addon store, answered from a table.
final class FakeAddonStore: AddonStore {
    struct InstallRequest: Equatable {
        let accessToken: String
        let profileId: String
        let manifestUrl: String
        let type: String
    }

    struct UninstallRequest: Equatable {
        let accessToken: String
        let profileId: String
        let addonId: String
    }

    private let table: [AddonDescriptor]
    private(set) var listRequestCount = 0
    private(set) var installRequests: [InstallRequest] = []
    private(set) var uninstallRequests: [UninstallRequest] = []
    var listError: Error?
    var installError: Error?
    var uninstallError: Error?

    init(addons: [AddonDescriptor] = []) {
        self.table = addons
    }

    func listAddons(accessToken: String) async throws -> [AddonDescriptor] {
        listRequestCount += 1
        if let listError { throw listError }
        return table
    }

    func installAddon(
        accessToken: String,
        profileId: String,
        manifestUrl: String,
        type: String,
        payload: [String: String]
    ) async throws -> AddonDescriptor {
        installRequests.append(
            InstallRequest(
                accessToken: accessToken,
                profileId: profileId,
                manifestUrl: manifestUrl,
                type: type
            )
        )
        if let installError { throw installError }
        return AddonDescriptor(
            id: "org.example.addon",
            manifestUrl: manifestUrl,
            name: "Example",
            type: type
        )
    }

    func uninstallAddon(accessToken: String, profileId: String, addonId: String) async throws {
        uninstallRequests.append(
            UninstallRequest(accessToken: accessToken, profileId: profileId, addonId: addonId)
        )
        if let uninstallError { throw uninstallError }
    }
}

/// The addon world's manifests, answered from a table.
final class FakeManifestFetching: AddonManifestFetching {
    private let table: [String: AddonManifest]
    private(set) var requestedURLs: [String] = []

    init(manifests: [String: AddonManifest] = [:]) {
        self.table = manifests
    }

    func fetchManifest(url: URL) async -> AddonManifest? {
        requestedURLs.append(url.absoluteString)
        return table[url.absoluteString]
    }
}
