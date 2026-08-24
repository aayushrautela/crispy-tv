import SwiftUI
import CrispyKit

/// Port of the Android `ProfileManagementRoute`: list profiles, add profile,
/// edit name/kids/avatar.
struct ProfileManagementScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var profiles: [BackendProfile] = []
    @State private var avatars: [AvatarItem] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var editingProfile: BackendProfile?
    @State private var showAddSheet = false

    var body: some View {
        Group {
            if isLoading && profiles.isEmpty {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(profiles) { profile in
                        Button {
                            editingProfile = profile
                        } label: {
                            HStack(spacing: 12) {
                                avatarThumb(profile)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(profile.name)
                                    if profile.isKids {
                                        Text("Kids")
                                            .font(.caption2.weight(.semibold))
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 2)
                                            .background(Theme.accent.opacity(0.25), in: .capsule)
                                    }
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        .foregroundStyle(.primary)
                    }

                    Button {
                        showAddSheet = true
                    } label: {
                        Label("Add profile", systemImage: "plus.circle")
                    }
                }
            }
            if let errorMessage {
                Text(errorMessage).font(.footnote).foregroundStyle(.red).padding()
            }
        }
        .navigationTitle("Manage profiles")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .sheet(item: $editingProfile) { profile in
            ProfileEditSheet(profile: profile, avatars: avatars) { await load() }
        }
        .sheet(isPresented: $showAddSheet) {
            ProfileEditSheet(profile: nil, avatars: avatars) { await load() }
        }
    }

    @ViewBuilder
    private func avatarThumb(_ profile: BackendProfile) -> some View {
        ZStack {
            if let url = profile.avatarUrl.nilIfBlank {
                RemoteImage(url: url)
                    .clipShape(.circle)
            } else {
                Circle().fill(Color(.tertiarySystemFill))
                Text(profile.initials ?? "?")
                    .font(.caption.weight(.semibold))
            }
        }
        .frame(width: 36, height: 36)
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            guard let context = await environment.backendContext() else { return }
            profiles = try await environment.backend.listProfiles(accessToken: context.accessToken)
            if avatars.isEmpty {
                avatars = (try? await environment.backend.getAvatars(accessToken: context.accessToken)) ?? []
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// Create/edit form; `profile == nil` means create.
struct ProfileEditSheet: View {
    let profile: BackendProfile?
    let avatars: [AvatarItem]
    var onSaved: () async -> Void

    @Environment(AppEnvironment.self) private var environment
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var isKids = false
    @State private var selectedAvatarId: String?
    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(profile == nil ? "New profile" : "Edit profile") {
                    TextField("Name", text: $name)
                    Toggle("Kids profile", isOn: $isKids)
                }

                Section("Avatar") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 56), spacing: 10)], spacing: 10) {
                        avatarOption("avatar_01", url: nil)
                        ForEach(avatars) { avatar in
                            avatarOption(avatar.id, url: avatar.url)
                        }
                    }
                }

                if let errorMessage {
                    Text(errorMessage).font(.footnote).foregroundStyle(.red)
                }
            }
            .navigationTitle(profile == nil ? "Add profile" : "Edit profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isSaving {
                        ProgressView().controlSize(.small)
                    } else {
                        Button("Save") { Task { await save() } }
                            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
            .onAppear {
                if let profile {
                    name = profile.name
                    isKids = profile.isKids
                    selectedAvatarId = currentAvatarId(from: profile.avatarUrl)
                } else {
                    selectedAvatarId = "avatar_01"
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder
    private func avatarOption(_ id: String, url: String?) -> some View {
        Button {
            selectedAvatarId = id
        } label: {
            ZStack {
                if let url = url.nilIfBlank ?? builtInUrl(for: id) {
                    RemoteImage(url: url)
                        .clipShape(.rect(cornerRadius: 12))
                } else {
                    RoundedRectangle(cornerRadius: 12).fill(Color(.tertiarySystemFill))
                    Image(systemName: "person.fill")
                }
                if selectedAvatarId == id {
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(Theme.accent, lineWidth: 3)
                }
            }
            .frame(height: 56)
        }
        .buttonStyle(.plain)
    }

    /// Built-in avatars are served from the backend catalog; when the avatars
    /// list hasn't loaded we still render the known ids.
    private func builtInUrl(for id: String) -> String? {
        let base = environment.config.backendURL.nilIfBlank ?? nil
        guard let base else { return nil }
        return "\(base)/v1/avatars/\(id)"
    }

    private func currentAvatarId(from urlString: String?) -> String? {
        guard let urlString = urlString.nilIfBlank, let url = URL(string: urlString) else { return nil }
        return url.lastPathComponent
    }

    private func save() async {
        isSaving = true
        defer { isSaving = false }
        do {
            let avatarUrl = builtInUrl(for: selectedAvatarId ?? "avatar_01")
            if let profile {
                guard let context = await environment.backendContext() else { return }
                _ = try await environment.backend.updateProfile(
                    accessToken: context.accessToken,
                    profileId: profile.id,
                    name: name,
                    isKids: isKids,
                    avatarUrl: avatarUrl
                )
            } else {
                guard let context = await environment.backendContext() else { return }
                _ = try await environment.backend.createProfile(
                    accessToken: context.accessToken,
                    name: name,
                    sortOrder: nil,
                    isKids: isKids,
                    avatarUrl: avatarUrl
                )
            }
            errorMessage = nil
            await onSaved()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
