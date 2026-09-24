import ContractRunner
import Foundation
import Observation

/// Port of the Android discover layer: browses `/v1/browse/titles` with
/// type/genre/sort filters, fanning out `all` into movie+series requests and
/// paging through the contract planner (`planBrowseRequests`/`mergeBrowsePage`).
@MainActor
@Observable
public final class DiscoverViewModel {
    public init() {}

    public enum TypeFilter: String, CaseIterable, Identifiable {
        case all = "all"
        case movies = "movie"
        case series = "series"

        public var id: String { rawValue }

        public var label: String {
            switch self {
            case .all: return "All"
            case .movies: return "Movies"
            case .series: return "Shows"
            }
        }
    }

    public enum SortFilter: String, CaseIterable, Identifiable {
        case popularity = "popularity"
        case rating = "rating"
        case release = "release"

        public var id: String { rawValue }

        public var label: String {
            switch self {
            case .popularity: return "Trending"
            case .rating: return "Rating"
            case .release: return "Release date"
            }
        }
    }

    public struct Genre: Identifiable, Equatable {
        public let key: String
        public let label: String

        public var id: String { key }
    }

    /// Row order and keys mirror the web SEARCH_GENRES list.
    public static let genres: [Genre] = [
        Genre(key: "action", label: "Action"),
        Genre(key: "animated", label: "Animated"),
        Genre(key: "comedy", label: "Comedy"),
        Genre(key: "documentary", label: "Documentary"),
        Genre(key: "drama", label: "Drama"),
        Genre(key: "family", label: "Family"),
        Genre(key: "fantasy", label: "Fantasy"),
        Genre(key: "horror", label: "Horror"),
        Genre(key: "mystery", label: "Mystery"),
        Genre(key: "romance", label: "Romance"),
        Genre(key: "scifi", label: "Sci-Fi"),
        Genre(key: "thriller", label: "Thriller"),
    ]

    public private(set) var typeFilter: TypeFilter = .all
    public private(set) var genre: Genre?
    public private(set) var sortFilter: SortFilter = .popularity
    public private(set) var items: [MediaCard] = []
    public private(set) var isLoadingFirstPage = false
    public private(set) var statusMessage = ""

    private var loadedPage = 0
    private var hasMore = false
    private var hasLoaded = false

    public func loadIfNeeded(environment: AppEnvironment) async {
        guard !hasLoaded else { return }
        await reload(environment: environment)
    }

    public func reload(environment: AppEnvironment) async {
        await loadPage(0, environment: environment)
    }

    public func setTypeFilter(_ filter: TypeFilter, environment: AppEnvironment) async {
        guard typeFilter != filter else { return }
        typeFilter = filter
        await loadPage(0, environment: environment)
    }

    public func setGenre(_ genre: Genre?, environment: AppEnvironment) async {
        guard self.genre != genre else { return }
        self.genre = genre
        await loadPage(0, environment: environment)
    }

    public func setSortFilter(_ filter: SortFilter, environment: AppEnvironment) async {
        guard sortFilter != filter else { return }
        sortFilter = filter
        await loadPage(0, environment: environment)
    }

    public func loadNextPage(environment: AppEnvironment) async {
        guard hasMore else { return }
        await loadPage(loadedPage + 1, environment: environment)
    }

    private func loadPage(_ targetPage: Int, environment: AppEnvironment) async {
        let combo = BrowseCombo(type: typeFilter.rawValue, genre: genre?.key, sort: sortFilter.rawValue)
        let requests = planBrowseRequests(combo: combo, page: targetPage)
        guard !requests.isEmpty else {
            loadedPage = targetPage
            hasMore = false
            return
        }

        guard let context = await environment.backendContext() else {
            statusMessage = "Sign in to browse titles."
            return
        }

        isLoadingFirstPage = targetPage == 0
        defer { isLoadingFirstPage = false }

        do {
            var responses: [String: BrowseTypeResponse] = [:]
            var cardsByType: [String: [ClientMediaCard]] = [:]
            for request in requests {
                let result = try await environment.backend.browseTitles(
                    accessToken: context.accessToken,
                    type: request.type,
                    genre: request.genre,
                    sort: request.sort,
                    page: request.page,
                    limit: pageSize
                )
                responses[request.type] = BrowseTypeResponse(
                    items: result.items.map { BrowseTypeItem(itemId: $0.itemId, type: $0.mediaType) },
                    hasMore: result.hasMore
                )
                cardsByType[request.type] = result.items
            }

            let merged = mergeBrowsePage(combo: combo, page: targetPage, responses: responses)
            let cards = requests.flatMap { request in
                (cardsByType[request.type] ?? []).map { MediaCard.from($0) }
            }
            if targetPage == 0 {
                items = cards
            } else {
                items.append(contentsOf: cards)
            }
            loadedPage = targetPage
            hasMore = merged.hasMore
            statusMessage = ""
        } catch {
            statusMessage = error.localizedDescription
        }
    }
}

private let pageSize = 60