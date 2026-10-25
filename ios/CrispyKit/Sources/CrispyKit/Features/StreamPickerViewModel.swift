import Foundation
import Observation

/// Resolves the streams for one title, and holds them for the picker.
///
/// The resolution is the account's addons being asked what they have, so it
/// needs a signed-in account: an account with no addons installed has no
/// streams, and that is a different answer from "no addon had this title".
@MainActor
@Observable
public final class StreamPickerViewModel {
    public private(set) var streams: [StreamSource] = []
    public private(set) var isLoading = false
    public private(set) var errorMessage: String?

    public init() {}

    /// Asks the account's addons for the streams of one title.
    public func load(mediaType: StreamMediaType, lookupId: String, environment: AppEnvironment) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        guard let context = await environment.backendContext() else {
            errorMessage = "Sign in to play."
            return
        }

        let addons = (try? await environment.backend.listAddons(accessToken: context.accessToken)) ?? []
        guard !addons.isEmpty else {
            errorMessage = "This account has no addons installed yet."
            return
        }

        let resolved = await environment.streamsClient.resolveStreams(
            addons: addons,
            mediaType: mediaType,
            lookupId: lookupId
        )
        streams = resolved
        if resolved.isEmpty {
            errorMessage = "No playable source was found for this title."
        }
    }

    /// The stream to play without being asked, when the viewer turned
    /// auto-select on.
    ///
    /// The best stream is the first one: the resolver sorts by quality and then
    /// by title, and the picker lists them in that order. No streams means no
    /// answer — the caller keeps the picker, which is where the empty case's
    /// message already lives.
    public func autoPlayStream(autoSelect: Bool) -> StreamSource? {
        guard autoSelect else { return nil }
        return streams.first
    }
}
