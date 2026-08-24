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
    @State private var homePath = NavigationPath()
    @State private var discoverPath = NavigationPath()
    @State private var libraryPath = NavigationPath()
    @State private var searchPath = NavigationPath()

    var body: some View {
        TabView(selection: $selection) {
            Tab("Home", systemImage: "house.fill", value: .home) {
                NavigationStack(path: $homePath) {
                    HomeScreen()
                }
            }
            Tab("Discover", systemImage: "safari", value: .discover) {
                NavigationStack(path: $discoverPath) {
                    DiscoverScreen()
                }
            }
            Tab("Library", systemImage: "tv.fill", value: .library) {
                NavigationStack(path: $libraryPath) {
                    LibraryScreen()
                }
            }
            Tab("Search", systemImage: "magnifyingglass", value: .search, role: .search) {
                NavigationStack(path: $searchPath) {
                    SearchScreen()
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
        case .home: homePath = NavigationPath()
        case .discover: discoverPath = NavigationPath()
        case .library: libraryPath = NavigationPath()
        case .search: searchPath = NavigationPath()
        }
    }
}
