import Foundation
import Observation
import ContractRunner

/// Port of the Android HomeViewModel data assembly: fetch the profile home
/// snapshot, plan it with the contract `HomeCatalogPlanner`, and layer the
/// Continue Watching rail on top.
@MainActor
@Observable
final class HomeViewModel {
    struct RailSection: Identifiable, Equatable {
        let catalogId: String
        let title: String
        let items: [MediaCard]

        var id: String { catalogId }
    }

    struct HeroItem: Identifiable, Equatable {
        let mediaKey: String
        let title: String
        let descriptionText: String
        let ratingText: String?
        let yearText: String?
        let genres: [String]
        let backdropUrl: String?

        var id: String { mediaKey }
    }

    private(set) var heroItems: [HeroItem] = []
    private(set) var rails: [RailSection] = []
    private(set) var continueWatchingItems: [MediaCard] = []
    private(set) var isLoading = false
    private(set) var statusMessage = ""

    private var didInitialLoad = false

    func loadIfNeeded(environment: AppEnvironment) async {
        guard !didInitialLoad else { return }
        await load(environment: environment)
    }

    func load(environment: AppEnvironment) async {
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

    func dismissContinueWatching(_ item: MediaCard, environment: AppEnvironment) async {
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
