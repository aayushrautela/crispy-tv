import SwiftUI
import CrispyKit

/// Port of the Android `AccountSettingsRoute`: account identity, sync
/// provider connections, sign out, delete account.
struct AccountSettingsScreen: View {
    @Environment(AppEnvironment.self) private var environment

    @State private var email: String?
    @State private var providers: [ProviderState] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var confirmDelete = false
    @State private var isDeleting = false

    var body: some View {
        List {
            Section("Account") {
                HStack {
                    Text("Email")
                    Spacer()
                    Text(email ?? "…")
                        .foregroundStyle(.secondary)
                }
            }

            Section("Connected services") {
                if isLoading && providers.isEmpty {
                    ProgressView()
                } else if providers.isEmpty {
                    Text("No services connected.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(providers) { provider in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(provider.statusLabel.isEmpty
                                     ? provider.provider.capitalized
                                     : provider.statusLabel)
                                    .font(.subheadline.weight(.medium))
                                Spacer()
                                if provider.canDisconnect {
                                    Button("Disconnect", role: .destructive) {
                                        Task { await disconnect(provider) }
                                    }
                                    .font(.caption)
                                    .buttonStyle(.borderless)
                                }
                            }
                            if let username = provider.externalUsername.nilIfBlank {
                                Text(username)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            if let message = provider.statusMessage.nilIfBlank {
                                Text(message)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            Section {
                Button(role: .destructive) {
                    Task { await signOut() }
                } label: {
                    Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
                }
            }

            Section {
                Button(role: .destructive) {
                    confirmDelete = true
                } label: {
                    Label(isDeleting ? "Deleting…" : "Delete account", systemImage: "trash")
                }
                .disabled(isDeleting)
            } footer: {
                Text("Deletes your account, profiles and watch data on the server. This cannot be undone.")
            }
        }
        .navigationTitle("Account settings")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .alert("Delete account?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await deleteAccount() } }
        } message: {
            Text("This permanently removes your Crispy account and all of its data.")
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            guard let context = await environment.backendContext() else { return }
            email = environment.supabase.currentSession()?.email
            providers = (try? await environment.backend.listImportConnections(
                accessToken: context.accessToken,
                profileId: context.profileId
            )) ?? []
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func disconnect(_ provider: ProviderState) async {
        guard let context = await environment.backendContext() else { return }
        _ = try? await environment.backend.disconnectImportConnection(
            accessToken: context.accessToken,
            profileId: context.profileId,
            provider: provider.provider
        )
        await load()
    }

    private func signOut() async {
        await environment.signOut()
        environment.bootstrap.markSignedOut()
    }

    private func deleteAccount() async {
        guard let session = await environment.supabase.ensureValidSession() else { return }
        isDeleting = true
        defer { isDeleting = false }
        do {
            try await environment.backend.deleteAccount(accessToken: session.accessToken)
            await environment.signOut()
            environment.bootstrap.markSignedOut()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
