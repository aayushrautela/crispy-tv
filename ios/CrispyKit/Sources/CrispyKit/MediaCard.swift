import Foundation
import ContractRunner

/// UI-facing media card shared by Home/Discover/Library/Search, mirroring the
/// fields the Android screens read off `CatalogItem`/`ClientMediaCard`.
public struct MediaCard: Identifiable, Equatable {
public let itemId: String
public let type: String
public let title: String
public let posterUrl: String?
public let backdropUrl: String?
public let logoUrl: String?
public let ratingText: String?
public let yearText: String?
public let genre: String?
public let maturityRating: String?
public let description: String?
public let progressPercent: Double?
public let parentSeriesId: String?
public let watchlisted: Bool

public var id: String { itemId }

    /// The title identity whose details page should open for this card.
    /// Episode-shaped continue-watching cards route to their parent show.
public var detailsItemId: String {
        if type == "episode", let parentSeriesId = parentSeriesId.nilIfBlank {
            return parentSeriesId
        }
        return itemId
    }

public var detailsRoute: AppRoute {
        .details(itemId: detailsItemId, itemType: type == "episode" ? "show" : type)
    }
}

public func formatRating(_ value: Double?) -> String? {
    guard let value, value.isFinite, value > 0 else { return nil }
    return String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
}

extension MediaCard {
public static func from(_ card: ClientMediaCard) -> MediaCard {
        MediaCard(
            itemId: card.itemId,
            type: normalizeCatalogType(card.mediaType),
            title: card.title,
            posterUrl: card.posterUrl,
            backdropUrl: card.backdropUrl,
            logoUrl: card.logoUrl,
            ratingText: formatRating(card.rating),
            yearText: card.year.map(String.init),
            genre: card.genres.first,
            maturityRating: card.maturityRating,
            description: card.overview,
            progressPercent: card.progress?.percent ?? resolvedProgressPercent(
                positionSeconds: card.progress?.positionSeconds,
                durationSeconds: card.progress?.durationSeconds
            ),
            parentSeriesId: card.parent?.seriesItemId,
            watchlisted: card.progress?.watchlisted ?? false
        )
    }

public static func from(_ item: HomeCatalogItem) -> MediaCard {
        MediaCard(
            itemId: item.mediaKey,
            type: item.type,
            title: item.title,
            posterUrl: item.posterUrl,
            backdropUrl: item.backdropUrl,
            logoUrl: nil,
            ratingText: item.rating,
            yearText: item.year,
            genre: nil,
            maturityRating: nil,
            description: item.description,
            progressPercent: nil,
            parentSeriesId: nil,
            watchlisted: false
        )
    }

public static func from(_ suggestion: SearchSuggestionItem) -> MediaCard {
        MediaCard(
            itemId: suggestion.itemId,
            type: suggestion.itemType,
            title: suggestion.title,
            posterUrl: suggestion.posterUrl,
            backdropUrl: nil,
            logoUrl: nil,
            ratingText: nil,
            yearText: suggestion.year.map(String.init),
            genre: nil,
            maturityRating: nil,
            description: nil,
            progressPercent: nil,
            parentSeriesId: nil,
            watchlisted: false
        )
    }

public static func from(_ item: SearchMediaItem) -> MediaCard {
        MediaCard(
            itemId: item.itemId,
            type: item.itemType,
            title: item.title,
            posterUrl: item.posterUrl,
            backdropUrl: item.backdropUrl,
            logoUrl: item.logoUrl,
            ratingText: formatRating(item.rating),
            yearText: item.year.map(String.init),
            genre: item.genres.first,
            maturityRating: item.maturityRating,
            description: item.overview,
            progressPercent: nil,
            parentSeriesId: item.seriesItemIdnil,
            watchlisted: false
        )
    }
}

/// Mirrors the Android mediaType → catalog type normalization.
public func normalizeCatalogType(_ raw: String) -> String {
    switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "anime": return "anime"
    case "episode", "show", "tv", "series": return "show"
    default: return "movie"
    }
}

private func resolvedProgressPercent(positionSeconds: Int?, durationSeconds: Int?) -> Double? {
    guard let positionSeconds, let durationSeconds, durationSeconds > 0 else { return nil }
    return min(max(Double(positionSeconds) / Double(durationSeconds) * 100.0, 0), 100)
}
