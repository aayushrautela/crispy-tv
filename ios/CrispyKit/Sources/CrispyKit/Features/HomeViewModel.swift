import Foundation
import Observation
import ContractRunner

/// Port of the Android HomeViewModel data assembly: fetch the profile home
/// snapshot, plan it with the contract `HomeCatalogPlanner`, and layer the
/// Continue Watching rail on top.
@MainActor
@Observable
public final class HomeViewModel {
    public struct RailSection: Identifiable, Equatable {
        public let catalogId: String
        public let title: String
        public let items: [MediaCard]

        public var id: String { catalogId }
    }

    public struct PillSection: Identifiable, Equatable {
        public let catalogId: String
        public let title: String

        public var id: String { catalogId }
    }

    public struct ThisWeekItem: Identifiable, Equatable {
        public let card: MediaCard
        public let badge: String?

        public var id: String { card.id }
    }

    public struct HeroItem: Identifiable, Equatable {
        public let mediaKey: String
        public let type: String
        public let title: String
        public let descriptionText: String
        public let ratingText: String?
        public let yearText: String?
        public let genres: [String]
        public let artworkUrl: String?

        public var id: String { mediaKey }
    }

    public private(set) var pills: [PillSection] = []
    public private(set) var heroItems: [HeroItem] = []
    public private(set) var rails: [RailSection] = []
    public private(set) var continueWatchingItems: [MediaCard] = []
    public private(set) var thisWeekItems: [ThisWeekItem] = []
    public private(set) var upNextItems: [UpNextEntry] = []
    public private(set) var isLoading = false
    public private(set) var statusMessage = ""

    private var didInitialLoad = false

public func loadIfNeeded(environment: AppEnvironment) async {
        guard !didInitialLoad else { return }
        await load(environment: environment)
    }

public func load(environment: AppEnvironment) async {
        isLoading = true
        defer { isLoading = false }

        guard let context = await environment.backendContext() else {
            statusMessage = "Sign in and select a profile to load recommendations."
            return
        }

        do {
            async let homeResponse = environment.backend.getHome(accessToken: context.accessToken, profileId: context.profileId)
            async let continueWatchingResult = environment.backend.listContinueWatching(
                accessToken: context.accessToken,
                profileId: context.profileId,
                limit: 20
            )
            async let thisWeekResult = try? environment.backend.getCalendarThisWeek(
                accessToken: context.accessToken,
                profileId: context.profileId
            )
            async let upNextResult = try? environment.backend.getUpNext(
                accessToken: context.accessToken,
                profileId: context.profileId
            )

            let response = try await homeResponse
            apply(
                planned: planPersonalHomeFeed(snapshot: HomeSnapshotMapper.snapshot(from: response)),
                rawSections: response?.sections ?? []
            )

            let continueWatching = try await continueWatchingResult
            continueWatchingItems = continueWatching.items.map { MediaCard.from($0) }
            if let thisWeek = await thisWeekResult {
                thisWeekItems = thisWeek.map(Self.makeThisWeekItem)
            }
            if let upNext = await upNextResult {
                upNextItems = upNext
            }
            didInitialLoad = true
        } catch {
            statusMessage = error.localizedDescription
        }
    }

public func dismissContinueWatching(_ item: MediaCard, environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else { return }
        continueWatchingItems.removeAll { $0.id == item.id }
        _ = try? await environment.backend.dismissContinueWatching(
            accessToken: context.accessToken,
            profileId: context.profileId,
            itemId: item.itemId
        )
    }

    private static func makeThisWeekItem(_ item: SearchMediaItem) -> ThisWeekItem {
        return ThisWeekItem(card: MediaCard.from(item), badge: nil)
    }

    /// "Mon"-style label for an ISO date string.
    private static func dayLabel(from isoDate: String) -> String? {
        guard isoDate.count >= 10 else { return nil }
        let parts = isoDate.prefix(10).split(separator: "-")
        guard parts.count == 3,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]) else { return nil }
        let components = DateComponents(year: year, month: month, day: day)
        guard let date = Calendar(identifier: .gregorian).date(from: components) else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "EEE"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: date)
    }

    // MARK: - Planning

    private func apply(planned: HomeCatalogFeedPlan, rawSections: [ProfileHomeSection]) {
        heroItems = planned.heroResult.items.map { item in
            HeroItem(
                mediaKey: item.mediaKey,
                type: item.type,
                title: item.title,
                descriptionText: item.description,
                ratingText: item.rating,
                yearText: item.year,
                genres: item.genres,
                artworkUrl: item.artworkUrl
            )
        }

        var rawByCatalogId: [String: [ClientMediaCard]] = [:]
        for section in rawSections {
            let key = section.listKey.trimmingCharacters(in: .whitespaces)
            let id = buildHomeCatalogId(
                source: .personal,
                kind: key.isEmpty ? "home" : key,
                variantKey: key.isEmpty ? "default" : key
            )
            rawByCatalogId[id] = section.items
        }

        pills = planned.sections
            .filter { $0.presentation == .pill }
            .map { PillSection(catalogId: $0.catalogId, title: $0.displayTitle) }

        rails = []
        for section in planned.sections where section.presentation == .rail {
            let cards = rawByCatalogId[section.catalogId] ?? []
            let items = cards.map { MediaCard.from($0) }
            if !items.isEmpty {
                rails.append(RailSection(catalogId: section.catalogId, title: section.displayTitle, items: items))
            }
        }
        statusMessage = planned.sectionsStatusMessage
    }
}
