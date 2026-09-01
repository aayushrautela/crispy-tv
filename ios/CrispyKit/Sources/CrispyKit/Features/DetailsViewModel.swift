import Foundation
import Observation
import ContractRunner

/// Port of the Android `DetailsViewModel` data assembly (backend slice):
/// title detail + extras (seasons/similar) + season episodes.
@MainActor
@Observable
public final class DetailsViewModel {
public let itemId: String
public let itemType: String

    public private(set) var detail: MetadataTitleDetail?
    public private(set) var seasons: [ClientMediaCard] = []
    public private(set) var similar: [MediaCard] = []
    public private(set) var episodes: [ClientMediaCard] = []
    public private(set) var reviews: [MetadataReview] = []
    public private(set) var selectedSeasonNumber: Int?
    public private(set) var isLoadingDetail = true
    public private(set) var isLoadingEpisodes = false
    public private(set) var errorMessage = ""
    public private(set) var watchState: WatchState?
    public private(set) var ctaLabel = "Play"
    public private(set) var ctaIconSystemName = "play.fill"

    private var extrasLoaded = false

public init(itemId: String, itemType: String) {
        self.itemId = itemId
        self.itemType = itemType
    }



    public func loadIfNeeded(environment: AppEnvironment) async {
        guard detail == nil else { return }
        await load(environment: environment)
    }

public func load(environment: AppEnvironment) async {
        guard let context = await environment.backendContext() else {
            errorMessage = "Sign in to view details."
            return
        }
        isLoadingDetail = true
        defer { isLoadingDetail = false }
        do {
            async let detailResult = environment.backend.getMetadataItemDetail(accessToken: context.accessToken, itemId: itemId)
            async let extrasResult = environment.backend.getMetadataItemExtras(accessToken: context.accessToken, itemId: itemId)

            let loadedDetail = try await detailResult
            detail = loadedDetail
            errorMessage = ""

            let extras = try? await extrasResult
            seasons = extras?.seasons ?? []
            similar = (extras?.similar ?? []).map { MediaCard.from($0) }
            reviews = extras?.reviews ?? []
            extrasLoaded = true

            if let context2 = await environment.backendContext() {
                watchState = try? await environment.backend.getWatchState(
                    accessToken: context2.accessToken,
                    profileId: context2.profileId,
                    itemId: itemId
                )
                resolveCta()
            }

            if isShow {
                let targetSeason = selectedSeasonNumber ?? seasons.first?.parent?.seasonNumber ?? 1
                await selectSeason(targetSeason, environment: environment)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Mirrors WatchCtaResolver: continue (2–85%) → Rewatch (played) → Watch now.
    private func resolveCta() {
        guard let state = watchState else { return }
        let percent = state.progressPercent
        let hasResume = (state.resumePositionSeconds ?? 0) > 0
        let usableContinue = (percent.map { $0 >= 2 && $0 < 85 } ?? false) || (percent == nil && hasResume)
        if usableContinue {
            ctaLabel = "Resume"
            ctaIconSystemName = "play.fill"
        } else if state.played {
            ctaLabel = "Rewatch"
            ctaIconSystemName = "arrow.clockwise"
        } else {
            ctaLabel = "Watch now"
            ctaIconSystemName = "play.fill"
        }
    }

public var isShow: Bool {
        itemType == "show" || itemType == "tv" || !seasons.isEmpty || (detail?.item.mediaType == "tv")
    }

public func selectSeason(_ seasonNumber: Int, environment: AppEnvironment) async {
        guard selectedSeasonNumber != seasonNumber || episodes.isEmpty else { return }
        selectedSeasonNumber = seasonNumber
        guard let context = await environment.backendContext() else { return }
        isLoadingEpisodes = true
        defer { isLoadingEpisodes = false }
        episodes = (try? await environment.backend.getSeriesEpisodes(
            accessToken: context.accessToken,
            seriesItemId: itemId,
            season: seasonNumber
        )) ?? []
    }
}
