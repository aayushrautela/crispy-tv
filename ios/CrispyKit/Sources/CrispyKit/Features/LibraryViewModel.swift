import ContractRunner
import Foundation
import Observation

/// Port of the Android `LibraryViewModel`: History / Watchlist / Ratings
/// sections with cursor-based backend paging.
@MainActor
@Observable
public final class LibraryViewModel {
    public init() {}
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

    /// Optimistic mark/unmark-watched toggle: the UI updates immediately and
    /// the mutation waits in the outbox, so the change lands even offline.
    public func setWatched(_ item: MediaCard, watched: Bool, environment: AppEnvironment) async {
        if selectedSection == .history && !watched {
            items.removeAll { $0.id == item.id }
        }
        await environment.userMutationOutbox.enqueue(
            TitleWatchedMutation(
                id: newPendingMutationId(),
                titleItemId: item.itemId,
                entityId: item.itemId,
                createdAtMs: clientEpochMs(),
                attempt: 0,
                status: .pending,
                nextAttemptAtMs: 0, // the outbox sets the first attempt on enqueue
                contentType: MediaContentType(rawValue: item.type) ?? .movie,
                desired: watched
            )
        )
    }

    /// Optimistic watchlist add/remove, queued through the outbox.
    public func toggleWatchlist(_ item: MediaCard, environment: AppEnvironment) async {
        let adding = !item.watchlisted
        if selectedSection == .watchlist && !adding {
            items.removeAll { $0.id == item.id }
        } else if let index = items.firstIndex(where: { $0.id == item.id }) {
            items[index] = MediaCard(
                itemId: item.itemId, type: item.type, title: item.title,
                artworkUrl: item.artworkUrl, logoUrl: item.logoUrl,
                ratingText: item.ratingText, yearText: item.yearText, genre: item.genre,
                maturityRating: item.maturityRating, description: item.description,
                progressPercent: item.progressPercent, parentSeriesId: item.parentSeriesId,
                watchlisted: adding
            )
        }
        await environment.userMutationOutbox.enqueue(
            WatchlistMutation(
                id: newPendingMutationId(),
                titleItemId: item.itemId,
                entityId: item.itemId,
                createdAtMs: clientEpochMs(),
                attempt: 0,
                status: .pending,
                nextAttemptAtMs: 0, // the outbox sets the first attempt on enqueue
                desired: adding
            )
        )
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
            items.append(contentsOf: result.items.map { MediaCard.from($0, preferSeriesArtwork: true) })
            nextCursor = result.nextCursor
            hasMore = result.hasMore
            statusMessage = ""
        } catch {
            statusMessage = error.localizedDescription
        }
    }
}
