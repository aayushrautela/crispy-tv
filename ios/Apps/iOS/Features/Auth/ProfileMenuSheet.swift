import CrispyKit
import SwiftUI

/// The Android `ProfileMenuRoute` entry point, shown as a sheet: switch active
/// profile, open the settings hub, manage profiles, sign out.
struct ProfileMenuSheet: View {
    var onDismissed: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss

    @State private var profiles: [BackendProfile] = []
    @State private var activeProfileId: String?
    @State private var isLoading = false

    var body: some View {
        NavigationStack {
            List {
                Section("Profiles") {
                    ForEach(profiles) { profile in
                        Button {
                            select(profile)
                        } label: {
                            HStack(spacing: 12) {
                                ZStack {
                                    if let url = profile.avatarUrl?.nilIfBlank {
                                        RemoteImage(url: url)
                                            .clipShape(.circle)
                                    } else {
                                        Circle()
                                            .fill(Color(.tertiarySystemFill))
                                        Text(profile.initials ?? "?")
                                            .font(.caption.weight(.semibold))
                                    }
                                }
                                .frame(width: 32, height: 32)

                                Text(profile.name)
                                Spacer()
                                if profile.id == activeProfileId {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.primary)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                }

                Section {
                    NavigationLink(value: MenuRoute.settings) {
                        Label("Open settings", systemImage: "gearshape")
                    }
                    NavigationLink(value: MenuRoute.management) {
                        Label("Manage profiles", systemImage: "person.2")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        Task { await signOut() }
                    } label: {
                        Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                }
            }
            .navigationDestination(for: MenuRoute.self) { route in
                switch route {
                case .settings: SettingsHubScreen()
                case .management: ProfileManagementScreen()
                }
            }
            .navigationTitle("Account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task { await load() }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(.thinMaterial)
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        let session = await environment.supabase.ensureValidSession()
        activeProfileId = environment.profileStore.activeProfileId(userId: session?.userId)
        guard let session else { return }
        let fetched = (try? await environment.backend.listProfiles(accessToken: session.accessToken)) ?? []
        profiles = fetched
        environment.adoptActiveProfile(profiles: fetched)
    }

    private func select(_ profile: BackendProfile) {
        let userId = environment.supabase.currentSession()?.userId
        environment.profileStore.setActiveProfileId(profile.id, userId: userId)
        activeProfileId = profile.id
        environment.adoptActiveProfile(profiles: profiles)
        onDismissed()
        dismiss()
    }

    private func signOut() async {
        await environment.signOut()
        environment.bootstrap.markSignedOut()
        onDismissed()
        dismiss()
    }
}

enum MenuRoute: Hashable {
    case settings
    case management
}
