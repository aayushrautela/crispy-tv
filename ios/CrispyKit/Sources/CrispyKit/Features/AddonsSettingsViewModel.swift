import Foundation
import Observation

/// The addon settings screen's state: which addons the account has, and the
/// install in progress.
///
/// Installing is two steps, because a manifest URL alone does not say what the
/// addon is: the URL is checked, the manifest is read and shown, and only then
/// is anything installed. The rules that read a URL and a manifest are the pure
/// `AddonManifestURL` and `AddonManifestPreview`, so this class is only the
/// sequence of the two calls and the state between them.
@MainActor
@Observable
public final class AddonsSettingsViewModel {
    /// How the screen reaches the backend context, resolved when it is needed.
    public typealias ContextProvider = @MainActor () async -> BackendContext?

    public private(set) var installed: [AddonDescriptor] = []
    public private(set) var draftUrl = ""
    /// The manifest the user is one tap away from installing.
    public private(set) var preview: AddonManifestPreview?
    public private(set) var isLoading = false
    public private(set) var isCheckingAddon = false
    public private(set) var isInstallingAddon = false
    /// The addon being removed, so its row can show that it is going.
    public private(set) var removingAddonId: String?
    public private(set) var statusMessage: String?
    public private(set) var errorMessage: String?

    private let store: AddonStore
    private let manifests: AddonManifestFetching
    private let context: ContextProvider
    private var hasLoaded = false

    public init(store: AddonStore, manifests: AddonManifestFetching, context: @escaping ContextProvider) {
        self.store = store
        self.manifests = manifests
        self.context = context
    }

    public convenience init(environment: AppEnvironment) {
        self.init(
            store: environment.backend,
            manifests: AddonManifestClient(http: environment.httpClient),
            context: { await environment.backendContext() }
        )
    }

    /// The URL the user is typing. Editing it invalidates the preview and the
    /// messages, because they were about the URL that is no longer there.
    public func updateDraftUrl(_ value: String) {
        guard draftUrl != value else { return }
        draftUrl = value
        preview = nil
        statusMessage = nil
        errorMessage = nil
    }

    /// Loads the installed addons, once unless a refresh asks again.
    public func load(force: Bool = false) async {
        guard !isLoading else { return }
        if hasLoaded && !force { return }
        isLoading = true
        defer { isLoading = false }
        guard let context = await context() else {
            errorMessage = "Sign in to manage addons."
            return
        }
        do {
            installed = try await store.listAddons(accessToken: context.accessToken)
            hasLoaded = true
            errorMessage = nil
        } catch {
            errorMessage = "Unable to load your addons."
        }
    }

    /// Reads the manifest the draft URL points at, so the user can see what
    /// they are about to install.
    public func checkDraft() async {
        guard !isCheckingAddon else { return }
        statusMessage = nil
        errorMessage = nil
        guard let manifestUrl = AddonManifestURL.normalize(draftUrl) else {
            errorMessage = draftUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "Enter an addon manifest URL first."
                : "The addon URL is invalid. Try a full manifest URL."
            return
        }
        if installed.contains(where: { AddonManifestURL.matches($0.manifestUrl, manifestUrl) }) {
            errorMessage = "That addon is already installed."
            return
        }
        guard let url = URL(string: manifestUrl) else {
            errorMessage = "The addon URL is invalid. Try a full manifest URL."
            return
        }
        isCheckingAddon = true
        defer { isCheckingAddon = false }
        guard let manifest = await manifests.fetchManifest(url: url) else {
            errorMessage = "Unable to load addon manifest from that URL."
            return
        }
        preview = AddonManifestPreview.make(manifestUrl: manifestUrl, manifest: manifest)
    }

    /// Drops the preview without installing anything.
    public func cancelInstall() {
        preview = nil
    }

    /// Installs the previewed addon.
    public func confirmInstall() async {
        guard let preview, !isInstallingAddon else { return }
        guard let context = await context() else {
            errorMessage = "Sign in to manage addons."
            return
        }
        isInstallingAddon = true
        defer { isInstallingAddon = false }
        do {
            let addon = try await store.installAddon(
                accessToken: context.accessToken,
                profileId: context.profileId,
                manifestUrl: preview.manifestUrl,
                type: "stremio",
                payload: [:]
            )
            merge(addon)
            statusMessage = "Installed \(preview.name)."
            errorMessage = nil
            self.preview = nil
            draftUrl = ""
        } catch {
            errorMessage = "Unable to install that addon."
        }
    }

    /// Removes an installed addon.
    public func remove(_ addon: AddonDescriptor) async {
        guard removingAddonId == nil else { return }
        guard let addonId = addon.id.nilIfBlank ?? AddonManifestURL.addonId(of: addon.manifestUrl) else {
            errorMessage = "That addon has no id to remove."
            return
        }
        guard let context = await context() else {
            errorMessage = "Sign in to manage addons."
            return
        }
        removingAddonId = addon.id
        defer { removingAddonId = nil }
        do {
            try await store.uninstallAddon(
                accessToken: context.accessToken,
                profileId: context.profileId,
                addonId: addonId
            )
            installed.removeAll { $0.id == addon.id }
            statusMessage = "Removed \(displayName(of: addon))."
            errorMessage = nil
        } catch {
            errorMessage = "Unable to remove that addon."
        }
    }

    /// The name to show for an addon the backend has, which may not have one.
    public func displayName(of addon: AddonDescriptor) -> String {
        addon.name.nilIfBlank ?? addon.id
    }

    /// Takes the addon the backend stored, in place of the row it replaces.
    ///
    /// The backend's id for an installation is the manifest URL when the
    /// manifest has no id of its own, so both are compared.
    private func merge(_ addon: AddonDescriptor) {
        installed.removeAll { existing in
            existing.id == addon.id || AddonManifestURL.matches(existing.manifestUrl, addon.manifestUrl)
        }
        installed.append(addon)
    }
}
