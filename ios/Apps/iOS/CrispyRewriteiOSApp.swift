import CrispyKit
import SwiftUI

@main
@MainActor
struct CrispyRewriteiOSApp: App {
    @State private var environment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .environment(environment)
                .preferredColorScheme(.dark)
        }
    }
}
