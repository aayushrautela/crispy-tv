import Foundation
import Observation
import ContractRunner

/// Port of the Android HomeViewModel data assembly: fetch the profile home
/// snapshot, plan it with the contract `HomeCatalogPlanner`, and layer the
/// Continue Watching rail on top.
@MainActor
@Observable
public final class HomeViewModel {
    struct RailSection: Identifiable, Equatable {
public         let catalogId: String
public         let title: String
public         let items: [MediaCard]

public         var id: String { catalogId }
    }

    struct PillSection: Identifiable, Equatable {
public         let catalogId: String
public         let title: String

public         var id: String { catalogId }
    }

    struct HeroItem: Identifiable, Equatable {
public         let mediaKey: String
public         let title: String
public         let descriptionText: String
public         let ratingText: String?
public         let yearText: String?
public         let genres: [String]
public         let backdropUrl: String?

public         var id: String { mediaKey }
    }

    public private(set) var pills: [PillSection] = []
    public private(set) var heroItems: [HeroItem] = []
    public private(set) var rails: [RailSection] = []
    public private(set) var continueWatchingItems: [MediaCard] = []
    public private(set) var isLoading = false
    public private(set) var statusMessage = ""

    private var didInitialLoad = false

public     func loadIfNeeded(environment: AppEnvironment) async {
        guard !didInitialLoad else { return }
        await load(environment: environment)
    }

public     func load(environment: AppEnvironment) async {
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

            let response = try await homeResponse
            apply(
                planned: planPersonalHomeFeed(snapshot: HomeSnapshotMapper.snapshot(from: response)),
                rawSections: response?.sections ?? []
            )

            let continueWatching = try await continueWatchingResult
            continueWatchingItems = continueWatching.items.map { MediaCard.from($0) }
            didInitialLoad = true
        } catch {
            statusMessage = error.localizedDescription
        }
    }

public     func dismissContinueWatching(_ item: MediaCard, environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else { return }
        continueWatchingItems.removeAll { $0.id == item.id }
        _ = try? await environment.backend.dismissContinueWatching(
            accessToken: context.accessToken,
            profileId: context.profileId,
            itemId: item.itemId
        )
    }

    // MARK: - Planning

    private func apply(planned: HomeCatalogFeedPlan, rawSections: [ProfileHomeSection]) {
        heroItems = planned.heroResult.items.map { item in
            HeroItem(
                mediaKey: item.mediaKey,
                title: item.title,
                descriptionText: item.description,
                ratingText: item.rating,
                yearText: item.year,
                genres: item.genres,
                backdropUrl: item.backdropUrl
            )
        }

public         var rawByCatalogId: [String: [ClientMediaCard]] = [:]
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
