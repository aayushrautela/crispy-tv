import Foundation
import ContractRunner

/// Maps a backend `ProfileHomeResponse` into the contract-domain snapshot the
/// planners consume. Mirrors `HomeCatalogService.toSnapshot()` on Android;
/// shared by Home and Discover so both plan from identical input.
public enum HomeSnapshotMapper {
public static func snapshot(from response: ProfileHomeResponse?) -> HomeCatalogSnapshot {
        guard let response else {
            return empty("No recommendations available right now.")
        }
        let lists = response.sections.compactMap { section -> HomeCatalogList? in
            let items: [HomeCatalogItem] = section.items.map { card in
                HomeCatalogItem(
                    mediaKey: card.itemId.trimmingCharacters(in: .whitespacesAndNewlines),
                    title: card.title.trimmingCharacters(in: .whitespacesAndNewlines),
                    artworkUrl: card.images.artwork.medium,
                    addonId: "backend",
                    type: normalizeCatalogType(card.mediaType),
                    rating: formatRating(card.rating),
                    year: card.year.map(String.init),
                    description: card.overview
                )
            }
            if items.isEmpty { return nil }
            let listKey = section.listKey.trimmingCharacters(in: .whitespaces)
            let kind = listKey.isEmpty ? "home" : listKey
            return HomeCatalogList(
                kind: kind,
                variantKey: listKey.isEmpty ? "default" : listKey,
                source: .personal,
                presentation: presentation(for: section.layout),
                name: section.title,
                heading: section.title,
                title: section.title,
                subtitle: section.subtitle ?? "",
                items: items
            )
        }
        return snapshot(profileId: response.profileId, lists: lists)
    }

public static func empty(_ statusMessage: String) -> HomeCatalogSnapshot {
        HomeCatalogSnapshot(profileId: nil, lists: [], statusMessage: statusMessage)
    }

    private static func snapshot(profileId: String, lists: [HomeCatalogList]) -> HomeCatalogSnapshot {
        let message = lists.isEmpty ? "No recommendations available right now." : ""
        return HomeCatalogSnapshot(
            profileId: profileId.nilIfBlank,
            lists: lists,
            statusMessage: message
        )
    }

    /// Backend layout strings → contract presentations. Collection shelves map
    /// to rails for now (dedicated shelf UI arrives with M2).
public static func presentation(for layout: String) -> HomeCatalogPresentation {
        switch layout.trimmingCharacters(in: .whitespaces).lowercased() {
        case "herocarousel", "hero", "landscape": return .hero
        case "categorytabs": return .pill
        default: return .rail
        }
    }
}
