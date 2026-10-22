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
                .fill(.white)
                .frame(width: 7, height: 7)
                .padding(.top, 8)
        }
    }
}

/// Avatar button in the top bar mirroring `ProfileIconButton`: avatar image,
/// else initials, else person glyph.
struct ProfileMenuButton: View {
    let name: String?
    let avatarUrl: String?
    var action: () -> Void

    init(name: String? = nil, avatarUrl: String? = nil, action: @escaping () -> Void) {
        self.name = name
        self.avatarUrl = avatarUrl
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
        guard let name = name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
        let parts = name.split(separator: " ").prefix(2)
        let joined = parts.compactMap { $0.first }.map(String.init).joined()
        return joined.isEmpty ? nil : joined.uppercased()
    }
}

/// Owns the profile-menu sheet and the avatar toolbar button for a tab root, so
/// every tab gets the Android `ProfileMenuRoute` entry point without repeating
/// the presentation state. Reads the active profile off the environment.
struct ProfileMenuPresentation: ViewModifier {
    var onDismissed: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @State private var showProfileMenu = false

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    ProfileMenuButton(
                        name: environment.activeProfile?.name,
                        avatarUrl: environment.activeProfile?.avatarUrl
                    ) {
                        showProfileMenu = true
                    }
                }
            }
            .sheet(isPresented: $showProfileMenu) {
                ProfileMenuSheet(onDismissed: onDismissed)
            }
    }
}

extension View {
    /// Adds the avatar button and the sheet it opens; `onDismissed` refreshes
    /// the screen behind the menu, mirroring Android's load key on re-entry.
    func profileMenu(onDismissed: @escaping () -> Void) -> some View {
        modifier(ProfileMenuPresentation(onDismissed: onDismissed))
    }
}
