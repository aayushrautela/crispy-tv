import SwiftUI

/// Android's SYSTEM/About row: version, build, and where the pieces come from.
struct AboutScreen: View {
    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    private var build: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
    }

    var body: some View {
        List {
            Section("App") {
                infoRow("Version", value: version)
                infoRow("Build", value: build)
            }

            Section {
                Text("Crispy plays video with mpv, bundled through MPVKit. Movie and show metadata is served by the Crispy backend.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Credits")
            }
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func infoRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
        }
    }
}
