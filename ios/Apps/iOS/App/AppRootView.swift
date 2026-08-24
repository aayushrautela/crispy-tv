import SwiftUI

/// Bootstrap gate mirroring the Android `AppRoot`.
struct AppRootView: View {
    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        Group {
            switch environment.bootstrap.state {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .needsAuth:
                AuthScreen(onSignedIn: {
                    Task { await environment.bootstrap.refresh() }
                })
            case .needsProfileSelection:
                ProfileSelectorScreen(
                    onComplete: {
                        Task { await environment.bootstrap.refresh() }
                    },
                    onBack: {
                        Task { await signOutAndReset() }
                    }
                )
            case .ready:
                MainShellView()
            }
        }
        // Single bootstrap entry point; child screens must not re-trigger
        // refresh on appear or the gate ping-pongs through .loading.
        .task { await environment.bootstrap.refresh() }
    }

    private func signOutAndReset() async {
        await environment.signOut()
        environment.bootstrap.markSignedOut()
    }
}

/// Email sign-in / sign-up, port of the Android `AuthRoute` core flow.
struct AuthScreen: View {
    let onSignedIn: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @State private var email = ""
    @State private var password = ""
    @State private var isSignUp = false
    @State private var isBusy = false
    @State private var message: String?

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            VStack(alignment: .leading, spacing: 12) {
                CrispyWordmark()
                    .padding(.bottom, 8)
                TextField("Email", text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Password", text: $password)
                    .textContentType(isSignUp ? .newPassword : .password)

                Button {
                    submit()
                } label: {
                    Group {
                        if isBusy {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(isSignUp ? "Create account" : "Sign in")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(.vertical, 6)
                }
                .buttonStyle(.glassProminent)
                .disabled(!canSubmit || isBusy)

                if let message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Button(isSignUp ? "Have an account? Sign in" : "New here? Create an account") {
                    isSignUp.toggle()
                    message = nil
                }
                .font(.footnote)
            }
            .textFieldStyle(.roundedBorder)
            Spacer()
        }
        .padding(24)
    }

    private var canSubmit: Bool {
        !email.trimmingCharacters(in: .whitespaces).isEmpty && password.count >= 6
    }

    private func submit() {
        guard canSubmit, !isBusy else { return }
        isBusy = true
        message = nil
        Task {
            defer { isBusy = false }
            do {
                if isSignUp {
                    let result = try await environment.supabase.signUpWithEmail(email: email, password: password)
                    message = result.session == nil ? result.message : nil
                    if result.session != nil {
                        onSignedIn()
                    }
                } else {
                    _ = try await environment.supabase.signInWithEmail(email: email, password: password)
                    onSignedIn()
                }
            } catch let error as SupabaseAccountClient.AuthError {
                message = error.localizedMessage
            } catch {
                message = error.localizedDescription
            }
        }
    }
}

/// Profile picker / first-run setup, port of the Android `ProfileSelectorRoute`.
struct ProfileSelectorScreen: View {
    let onComplete: () -> Void
    let onBack: () -> Void

    @Environment(AppEnvironment.self) private var environment
    @State private var profiles: [BackendProfile] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var newProfileName = ""
    @State private var isCreating = false

    var body: some View {
        NavigationStack {
            Group {
                if isLoading && profiles.isEmpty {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if profiles.isEmpty {
                    setupForm
                } else {
                    profileGrid
                }
            }
            .navigationTitle(profiles.isEmpty ? "Finish setting up" : "Who's watching?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Sign out", role: .destructive, action: onBack)
                }
            }
            .task { await loadProfiles() }
        }
    }

    private var setupForm: some View {
        VStack(spacing: 16) {
            Text("Choose a name for your profile.")
                .foregroundStyle(.secondary)
            TextField("Profile name", text: $newProfileName)
                .textFieldStyle(.roundedBorder)
            Button {
                Task { await createPrimaryProfile() }
            } label: {
                Group {
                    if isCreating {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("Get started").frame(maxWidth: .infinity)
                    }
                }
                .padding(.vertical, 4)
            }
            .buttonStyle(.glassProminent)
            .disabled(newProfileName.trimmingCharacters(in: .whitespaces).isEmpty || isCreating)
            if let errorMessage {
                Text(errorMessage).font(.footnote).foregroundStyle(.red)
            }
            Spacer()
        }
        .padding(24)
    }

    private var profileGrid: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 16)], spacing: 16) {
                ForEach(sortedProfiles) { profile in
                    Button {
                        Task { await select(profile) }
                    } label: {
                        VStack(spacing: 8) {
                            ZStack {
                                if let url = profile.avatarUrl?.nilIfBlank {
                                    RemoteImage(url: url)
                                        .clipShape(.rect(cornerRadius: 20))
                                } else {
                                    RoundedRectangle(cornerRadius: 20)
                                        .fill(Color(.tertiarySystemFill))
                                    Text(profile.initials ?? "?")
                                        .font(.title2.weight(.semibold))
                                }
                            }
                            .aspectRatio(1, contentMode: .fit)
                            .glassEffect(in: .rect(cornerRadius: 20))

                            Text(profile.name)
                                .font(.subheadline.weight(.medium))
                                .lineLimit(1)
                        }
                    }
                    .buttonStyle(.plain)
                }

                Button {
                    Task { await addProfile() }
                } label: {
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 20)
                                .fill(Color(.tertiarySystemFill))
                            Image(systemName: "plus")
                                .font(.title2)
                        }
                        .aspectRatio(1, contentMode: .fit)
                        .glassEffect(in: .rect(cornerRadius: 20))
                        Text("Add profile")
                            .font(.subheadline.weight(.medium))
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(16)
            if let errorMessage {
                Text(errorMessage).font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private var sortedProfiles: [BackendProfile] {
        profiles.sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    private func loadProfiles() async {
        isLoading = true
        defer { isLoading = false }
        do {
            guard let context = await environment.backendContext() else {
                onBack()
                return
            }
            profiles = try await environment.backend.listProfiles(accessToken: context.accessToken)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func select(_ profile: BackendProfile) async {
        let userId = environment.supabase.currentSession()?.userId
        environment.profileStore.setActiveProfileId(profile.id, userId: userId)
        onComplete()
    }

    private func createPrimaryProfile() async {
        guard let session = await environment.supabase.ensureValidSession() else { return }
        isCreating = true
        defer { isCreating = false }
        do {
            let avatarUrl = environment.config.backendURL.isEmpty
                ? ""
                : "\(environment.config.backendURL)/v1/avatars/avatar_01"
            let profile = try await environment.backend.bootstrapAccount(
                accessToken: session.accessToken,
                name: newProfileName,
                interfaceLanguage: Locale.current.language.languageCode?.identifier ?? "en",
                avatarUrl: avatarUrl,
                region: nil
            )
            environment.profileStore.setActiveProfileId(profile.id, userId: session.userId)
            onComplete()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func addProfile() async {
        guard let context = await environment.backendContext() else { return }
        isCreating = true
        defer { isCreating = false }
        do {
            let profile = try await environment.backend.createProfile(
                accessToken: context.accessToken,
                name: "Profile \(profiles.count + 1)",
                sortOrder: sortedProfiles.count,
                isKids: false,
                avatarUrl: nil
            )
            profiles.append(profile)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
