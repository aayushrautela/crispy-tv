import Foundation
import Observation
import ContractRunner

/// Port of the Android `CatalogRoute`: full-page grid fed by contract
/// `buildCatalogPage` over the loaded home snapshot.
@MainActor
@Observable
final class CatalogListViewModel {
    private(set) var items: [MediaCard] = []
    private(set) var isLoading = false
    private(set) var statusMessage = ""

    private var snapshot: HomeCatalogSnapshot?
    private let catalogId: String
    private var loadedPage = 0

    init(catalogId: String) {
        self.catalogId = catalogId
    }

    func loadIfNeeded(environment: AppEnvironment) async {
        guard snapshot == nil else { return }
        await reload(environment: environment)
    }

    func reload(environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else {
            statusMessage = "Sign in to load this collection."
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            let response = try await environment.backend.getHome(accessToken: context.accessToken, profileId: context.profileId)
            snapshot = HomeSnapshotMapper.snapshot(from: response)
            loadedPage = 0
            items = []
            await loadNextPage()
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    func loadNextPage() async {
        guard let snapshot else { return }
        let nextPage = loadedPage + 1
        let result = buildCatalogPage(
            snapshot: snapshot,
            sectionCatalogId: catalogId,
            page: nextPage,
            pageSize: 30
        )
        guard !result.items.isEmpty else { return }
        loadedPage = nextPage
        items.append(contentsOf: result.items.map { MediaCard.from($0) })
        statusMessage = result.statusMessage
    }
}
