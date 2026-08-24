import CrispyKit
import SwiftUI

enum AppTab: Hashable {
    case home
    case discover
    case library
    case search
}

/// Main app shell mirroring the Android `MainAppShell`: three top-level tabs
/// plus a search destination. On iOS 26 the system tab bar renders as a
/// floating Liquid Glass capsule, replacing the custom Android bottom bar.
struct MainShellView: View {
    @Environment(AppEnvironment.self) private var environment

    @State private var selection: AppTab = .home
    @State private var homePath: [AppRoute] = []
    @State private var discoverPath: [AppRoute] = []
    @State private var libraryPath: [AppRoute] = []
    @State private var searchPath: [AppRoute] = []

    var body: some View {
        TabView(selection: $selection) {
            Tab("Home", systemImage: "house.fill", value: .home) {
                NavigationStack(path: $homePath) {
                    HomeScreen()
                        .homeDestinations()
                }
            }
            Tab("Discover", systemImage: "safari", value: .discover) {
                NavigationStack(path: $discoverPath) {
                    DiscoverScreen()
                        .commonDestinations()
                }
            }
            Tab("Library", systemImage: "tv.fill", value: .library) {
                NavigationStack(path: $libraryPath) {
                    LibraryScreen()
                        .commonDestinations()
                }
            }
            Tab("Search", systemImage: "magnifyingglass", value: .search, role: .search) {
                NavigationStack(path: $searchPath) {
                    SearchScreen()
                        .commonDestinations()
                }
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .onChange(of: selection) { oldValue, newValue in
            if oldValue == newValue {
                popToRoot(oldValue)
            }
        }
    }

    /// Re-tapping the current tab scrolls that stack back to its root, matching
    /// the Android scroll-to-top request counter.
    private func popToRoot(_ tab: AppTab) {
        switch tab {
        case .home: homePath = []
        case .discover: discoverPath = []
        case .library: libraryPath = []
        case .search: searchPath = []
        }
    }
}

/// Destinations shared by every tab: details, person, catalog list.
struct CommonDestinations: ViewModifier {
    func body(content: Content) -> some View {
        content.navigationDestination(for: AppRoute.self) { route in
            switch route {
            case .details(let itemId, let itemType):
                DetailsScreen(itemId: itemId, itemType: itemType)
            case .person(let personId, let profileUrl):
                PersonScreen(personId: personId, initialProfileUrl: profileUrl)
            case .catalogList(let catalogId, let title):
                CatalogListScreen(catalogId: catalogId, title: title)
            }
        }
    }
}

extension View {
    func commonDestinations() -> some View {
        modifier(CommonDestinations())
    }

    /// Home adds nothing extra today but keeps parity naming with nav graphs.
    func homeDestinations() -> some View {
        commonDestinations()
    }
}
