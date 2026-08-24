import CrispyKit
import SwiftUI

/// Text wordmark standing in for the Android animated brand asset until the
/// logo files are ported to the asset catalog.
struct CrispyWordmark: View {
    var body: some View {
        HStack(spacing: 3) {
            Text("crispy")
                .font(.title2.weight(.heavy))
            Circle()
                .fill(Theme.accent)
                .frame(width: 7, height: 7)
                .padding(.top, 8)
        }
    }
}

/// Avatar button in the top bar mirroring `ProfileIconButton`: avatar image,
/// else initials, else person glyph.
struct ProfileMenuButton: View {
    let profileName: String?
    let avatarUrl: String?
    var action: () -> Void

    init(profile: BackendProfile?, action: @escaping () -> Void) {
        self.profileName = profile?.name
        self.avatarUrl = profile?.avatarUrl
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            avatar
                .frame(width: 32, height: 32)
                .clipShape(.circle)
                .glassEffect(.regular.interactive(), in: .circle)
        }
        .accessibilityLabel("Open profile menu")
    }

    @ViewBuilder
    private var avatar: some View {
        if let avatarUrl = avatarUrl?.nilIfBlank {
            RemoteImage(url: avatarUrl)
        } else if let initials = initials() {
            ZStack {
                Color(.tertiarySystemFill)
                Text(initials)
                    .font(.caption2.weight(.semibold))
            }
        } else {
            ZStack {
                Color(.tertiarySystemFill)
                Image(systemName: "person")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func initials() -> String? {
        guard let name = profileName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        let parts = name.split(separator: " ").prefix(2)
        let joined = parts.compactMap { $0.first }.map(String.init).joined()
        return joined.isEmpty ? nil : joined.uppercased()
    }
}
