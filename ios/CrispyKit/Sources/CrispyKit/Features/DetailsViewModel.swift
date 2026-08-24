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
    public private(set) var seasons: [MetadataSeason] = []
    public private(set) var similar: [MediaCard] = []
    public private(set) var episodes: [MetadataEpisode] = []
    public private(set) var selectedSeasonNumber: Int?
    public private(set) var isLoadingDetail = true
    public private(set) var isLoadingEpisodes = false
    public private(set) var errorMessage = ""

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
            similar = (extras?.similar ?? []).map { card in
                MediaCard(
                    itemId: card.itemId,
                    type: card.itemType,
                    title: card.title,
                    posterUrl: card.images.posterUrl,
                    backdropUrl: card.images.backdropUrl,
                    logoUrl: card.images.logoUrl,
                    ratingText: formatRating(card.rating),
                    yearText: card.releaseYear.map(String.init),
                    genre: nil,
                    maturityRating: nil,
                    description: nil,
                    progressPercent: nil,
                    parentSeriesId: nil
                )
            }
            extrasLoaded = true

            if isShow {
                let targetSeason = selectedSeasonNumber ?? seasons.first?.seasonNumber ?? 1
                await selectSeason(targetSeason, environment: environment)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

public var isShow: Bool {
        itemType == "show" || !seasons.isEmpty || (detail?.item.itemType == "show")
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
