import CrispyKit
import SwiftUI

/// Port of the Android `SettingsScreen` hub: the profile menu's "Open settings"
/// lands here. Rows ship only with a real destination — Android's inert rows
/// (Subtitles, Language & Region) arrive with the landings that build them.
struct SettingsHubScreen: View {
    var body: some View {
        List {
            Section {
                NavigationLink {
                    ProfileManagementScreen()
                } label: {
                    Label("Account and subscription", systemImage: "person.crop.circle")
                }
            } header: {
                Text("Account")
            } footer: {
                Text("Customize your experience")
            }

            Section {
                NavigationLink {
                    PlaybackSettingsScreen()
                } label: {
                    Label("Playback", systemImage: "slider.horizontal.3")
                }
            } header: {
                Text("Personalization")
            }

            Section {
                NavigationLink {
                    AddonsSettingsScreen()
                } label: {
                    Label("Addons", systemImage: "puzzlepiece.extension")
                }
            } header: {
                Text("Integrations")
            }

            Section {
                NavigationLink {
                    AboutScreen()
                } label: {
                    Label("About", systemImage: "info.circle")
                }
            } header: {
                Text("System")
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}
