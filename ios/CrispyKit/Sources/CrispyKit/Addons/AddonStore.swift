import Foundation

/// The backend operations the addon screens need.
///
/// The seam exists so the screen can be tested without a backend: the app hands
/// it the backend client, and a test hands it a store that answers from a table.
public protocol AddonStore {
    /// The addons the account has installed, as the backend has them.
    func listAddons(accessToken: String) async throws -> [AddonDescriptor]

    /// Installs an addon from its manifest URL, and returns the row the backend
    /// stored. The account's profile is the owner of the installation.
    func installAddon(
        accessToken: String,
        profileId: String,
        manifestUrl: String,
        type: String,
        payload: [String: String]
    ) async throws -> AddonDescriptor

    /// Removes one installed addon, by the id the backend knows it by.
    func uninstallAddon(accessToken: String, profileId: String, addonId: String) async throws
}

extension CrispyBackendClient: AddonStore {}
