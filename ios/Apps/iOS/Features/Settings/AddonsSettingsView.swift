import CrispyKit
import SwiftUI

/// The addon settings screen: what the account has installed, and the two-step
/// install of one more.
///
/// The Android app keeps an addon registry on the device and syncs it with the
/// backend. iOS has no registry, so this screen is the backend's own list plus
/// the two calls that change it.
struct AddonsSettingsScreen: View {
    @Environment(AppEnvironment.self) private var environment
    @State private var viewModel: AddonsSettingsViewModel?
    @State private var pendingRemoval: AddonDescriptor?

    var body: some View {
        Group {
            if let viewModel {
                content(viewModel)
            } else {
                ProgressView()
                    .tint(Theme.spinner)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle("Addons")
        .navigationBarTitleDisplayMode(.inline)
        .task { await ensureViewModel().load() }
        .alert("Remove addon?", isPresented: removalBinding, presenting: pendingRemoval) { addon in
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) {
                Task { await ensureViewModel().remove(addon) }
            }
        } message: { addon in
            Text("\(addon.name.nilIfBlank ?? addon.id) will no longer provide streams or catalogs.")
        }
    }

    private var removalBinding: Binding<Bool> {
        Binding(
            get: { pendingRemoval != nil },
            set: { presented in
                if !presented { pendingRemoval = nil }
            }
        )
    }

    private func content(_ viewModel: AddonsSettingsViewModel) -> some View {
        List {
            addSection(viewModel)
            if let preview = viewModel.preview {
                previewSection(preview, viewModel)
            }
            installedSection(viewModel)
            messageSection(viewModel)
        }
        .refreshable { await viewModel.load(force: true) }
    }

    private func addSection(_ viewModel: AddonsSettingsViewModel) -> some View {
        Section {
            TextField("https://example.com/manifest.json", text: Binding(
                get: { viewModel.draftUrl },
                set: { viewModel.updateDraftUrl($0) }
            ))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.URL)
            .submitLabel(.go)
            .onSubmit { Task { await viewModel.checkDraft() } }

            if viewModel.isCheckingAddon {
                HStack(spacing: 8) {
                    ProgressView()
                        .tint(Theme.spinner)
                    Text("Reading the addon manifest…")
                        .foregroundStyle(.secondary)
                }
            } else {
                Button("Check addon") {
                    Task { await viewModel.checkDraft() }
                }
                .disabled(viewModel.draftUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } header: {
            Text("Add addon")
        } footer: {
            Text("Install addon manifests to add new streams and catalogs.")
        }
    }

    @ViewBuilder
    private func previewSection(
        _ preview: AddonManifestPreview,
        _ viewModel: AddonsSettingsViewModel
    ) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(preview.name)
                    .font(.headline)
                Text(preview.description)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let version = preview.version.nilIfBlank {
                    Text("Version \(version)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Text(preview.manifestUrl)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 2)

            if !preview.resources.isEmpty {
                labelled("Resources", preview.resources.joined(separator: ", "))
            }
            if !preview.types.isEmpty {
                labelled("Media types", preview.types.joined(separator: ", "))
            }
            ForEach(preview.warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            if viewModel.isInstallingAddon {
                ProgressView()
                    .tint(Theme.spinner)
            } else {
                Button("Install") {
                    Task { await viewModel.confirmInstall() }
                }
                Button("Cancel", role: .cancel) {
                    viewModel.cancelInstall()
                }
            }
        } header: {
            Text("Install this addon?")
        }
    }

    private func installedSection(_ viewModel: AddonsSettingsViewModel) -> some View {
        Section("Installed") {
            if viewModel.isLoading && viewModel.installed.isEmpty {
                ProgressView()
                    .tint(Theme.spinner)
            } else if viewModel.installed.isEmpty {
                Text("No addons installed yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(viewModel.installed) { addon in
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(viewModel.displayName(of: addon))
                                .font(.subheadline.weight(.medium))
                            Text(addon.manifestUrl)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        if viewModel.removingAddonId == addon.id {
                            ProgressView()
                                .tint(Theme.spinner)
                        } else {
                            Button(role: .destructive) {
                                pendingRemoval = addon
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Remove \(viewModel.displayName(of: addon))")
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func messageSection(_ viewModel: AddonsSettingsViewModel) -> some View {
        if viewModel.statusMessage.nilIfBlank != nil || viewModel.errorMessage.nilIfBlank != nil {
            Section {
                if let status = viewModel.statusMessage.nilIfBlank {
                    Text(status)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if let error = viewModel.errorMessage.nilIfBlank {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
    }

    private func labelled(_ title: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(title)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .multilineTextAlignment(.trailing)
        }
        .font(.caption)
    }

    private func ensureViewModel() -> AddonsSettingsViewModel {
        if let viewModel {
            return viewModel
        }
        let created = AddonsSettingsViewModel(environment: environment)
        viewModel = created
        return created
    }
}
