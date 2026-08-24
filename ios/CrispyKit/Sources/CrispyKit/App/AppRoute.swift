import Foundation

/// Value-based destinations pushed onto a tab's NavigationStack. Replaces the
/// Android string routes from `AppRoutes`.
enum AppRoute: Hashable {
    case details(itemId: String, itemType: String)
    case person(personId: String, profileUrl: String?)
    case catalogList(catalogId: String, title: String)
}
