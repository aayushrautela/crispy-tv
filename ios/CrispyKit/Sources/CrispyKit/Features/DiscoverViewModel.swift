import Foundation
import Observation
import ContractRunner

/// Port of the Android `DiscoverViewModel`: catalog picker sourced from the
/// home snapshot via the contract planner, pages built with `buildCatalogPage`.
@MainActor
@Observable
public final class DiscoverViewModel {
    public struct CatalogOption: Identifiable, Equatable {
        public let catalogId: String
        public let title: String

        public var id: String { catalogId }
    }

    public enum TypeFilter: String, CaseIterable, Identifiable {
        case all = "All"
        case movies = "Movies"
        case series = "Series"

public var id: String { rawValue }

public var mediaType: String? {
            switch self {
            case .all: return nil
            case .movies: return "movie"
            case .series: return "show"
            }
        }
    }

    public private(set) var catalogs: [CatalogOption] = []
    public private(set) var selectedCatalog: CatalogOption?
    public private(set) var typeFilter: TypeFilter = .movies
    public private(set) var items: [MediaCard] = []
    public private(set) var isLoadingFirstPage = false
    public private(set) var statusMessage = ""

    private var snapshot: HomeCatalogSnapshot?
    private var loadedPage = 0

public func loadIfNeeded(environment: AppEnvironment) async {
        guard snapshot == nil else { return }
        await reload(environment: environment)
    }

public func reload(environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else {
            statusMessage = "Sign in and select a profile to load catalogs."
            return
        }
        isLoadingFirstPage = items.isEmpty
        defer { isLoadingFirstPage = false }
        do {
            let response = try await environment.backend.getHome(accessToken: context.accessToken, profileId: context.profileId)
            snapshot = HomeSnapshotMapper.snapshot(from: response)
            refreshCatalogOptions()
            if selectedCatalog == nil {
                selectedCatalog = catalogs.first
            }
            loadedPage = 0
            items = []
            await loadNextPage(environment: environment)
        } catch {
            statusMessage = error.localizedDescription
        }
    }

public func select(_ option: CatalogOption?, environment: AppEnvironment) async {
        selectedCatalog = option
        items = []
        loadedPage = 0
        isLoadingFirstPage = true
        defer { isLoadingFirstPage = false }
        await loadNextPage(environment: environment)
    }

public func setTypeFilter(_ filter: TypeFilter, environment: AppEnvironment) async {
        typeFilter = filter
        refreshCatalogOptions()
        if let selected = selectedCatalog, !catalogs.contains(selected) {
            selectedCatalog = catalogs.first
        }
        items = []
        loadedPage = 0
        isLoadingFirstPage = true
        defer { isLoadingFirstPage = false }
        await loadNextPage(environment: environment)
    }

    /// Loads the next page when the grid approaches the end.
public func loadNextPage(environment: AppEnvironment) async {
        guard let snapshot, let selectedCatalog else { return }
        let nextPage = loadedPage + 1
        let result = buildCatalogPage(
            snapshot: snapshot,
            sectionCatalogId: selectedCatalog.catalogId,
            page: nextPage,
            pageSize: 24
        )
        guard !result.items.isEmpty else { return }
        loadedPage = nextPage
        items.append(contentsOf: result.items.map { MediaCard.from($0) })
        statusMessage = result.statusMessage
    }

    private func refreshCatalogOptions() {
        guard let snapshot else {
            catalogs = []
            return
        }
        let (refs, message) = listDiscoverCatalogs(snapshot: snapshot, mediaType: typeFilter.mediaType)
        catalogs = refs.map { ref in
            CatalogOption(catalogId: ref.section.catalogId, title: ref.section.displayTitle)
        }
        statusMessage = message
    }
}
