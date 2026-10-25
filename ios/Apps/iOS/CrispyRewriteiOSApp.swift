import CrispyKit
import SwiftUI

@main
@MainActor
struct CrispyRewriteiOSApp: App {
    @State private var environment = AppEnvironment()
    /// The URL the smoke harness asked for, when it asked for one.
    @State private var smokePlayback: SmokePlayback?

    var body: some Scene {
        WindowGroup {
            rootView
        }
    }

    @ViewBuilder
    private var rootView: some View {
        #if DEBUG
        AppRootView()
            .environment(environment)
            .preferredColorScheme(.dark)
            .fullScreenCover(item: $smokePlayback) { playback in
                MPVPlayerScreen(
                    source: PlaybackSource(url: playback.url),
                    title: playback.url.host
                )
            }
            .task { smokePlayback = SmokeLaunchArguments.url.map { SmokePlayback(url: $0) } }
        #else
        AppRootView()
            .environment(environment)
            .preferredColorScheme(.dark)
        #endif
    }
}

/// The smoke harness's playback, so the player can be presented over it.
struct SmokePlayback: Identifiable {
    let id = UUID()
    let url: URL
}
