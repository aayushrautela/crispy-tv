import Foundation
import Observation

/// Port of the Android `LibraryViewModel`: History / Watchlist / Ratings
/// sections with cursor-based backend paging.
@MainActor
@Observable
public final class LibraryViewModel {
    public enum Section: String, CaseIterable, Identifiable {
        case history = "History"
        case watchlist = "Watchlist"
        case ratings = "Ratings"

public var id: String { rawValue }
    }

    public private(set) var sections: [Section] = Section.allCases
    public private(set) var selectedSection: Section = .history
    public private(set) var items: [MediaCard] = []
    public private(set) var isLoadingFirstPage = false
    public private(set) var statusMessage = ""

    private var nextCursor: String?
    private var hasMore = false
    private var isFetchingNextPage = false

public func loadIfNeeded(environment: AppEnvironment) async {
        guard items.isEmpty else { return }
        await reload(environment: environment)
    }

public func reload(environment: AppEnvironment) async {
        items = []
        nextCursor = nil
        hasMore = true
        isLoadingFirstPage = true
        defer { isLoadingFirstPage = false }
        await fetchNextPage(environment: environment)
    }

public func select(_ section: Section, environment: AppEnvironment) async {
        guard section != selectedSection else { return }
        selectedSection = section
        await reload(environment: environment)
    }

public func loadNextPageIfNeeded(current itemId: String, environment: AppEnvironment) async {
        guard hasMore, !isFetchingNextPage, !isLoadingFirstPage else { return }
        guard itemId == items.last?.id else { return }
        await fetchNextPage(environment: environment)
    }

    /// Optimistic mark/unmark-watched toggle (server mutation without the
    /// outbox, which arrives in a later milestone).
public func setWatched(_ item: MediaCard, watched: Bool, environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else { return }
        if selectedSection == .history && !watched {
            items.removeAll { $0.id == item.id }
        }
        let result = try? await (watched
            ? environment.backend.markWatched(accessToken: context.accessToken, profileId: context.profileId, itemId: item.itemId)
            : environment.backend.unmarkWatched(accessToken: context.accessToken, profileId: context.profileId, itemId: item.itemId))
        _ = result
    }

    private func fetchNextPage(environment: AppEnvironment) async {
        guard hasMore, !isFetchingNextPage else { return }
        guard let context = await environment.backendContext() else {
            statusMessage = "Sign in to load your library."
            return
        }
        isFetchingNextPage = true
        defer { isFetchingNextPage = false }
        do {
            let result: ClientMediaCardQueryResult
            switch selectedSection {
            case .history:
                result = try await environment.backend.listWatchHistory(accessToken: context.accessToken, profileId: context.profileId, limit: 60, cursor: nextCursor)
            case .watchlist:
                result = try await environment.backend.listWatchlist(accessToken: context.accessToken, profileId: context.profileId, limit: 60, cursor: nextCursor)
            case .ratings:
                result = try await environment.backend.listRatings(accessToken: context.accessToken, profileId: context.profileId, limit: 60, cursor: nextCursor)
            }
            items.append(contentsOf: result.items.map { MediaCard.from($0) })
            nextCursor = result.nextCursor
            hasMore = result.hasMore
            statusMessage = ""
        } catch {
            statusMessage = error.localizedDescription
        }
    }
}
