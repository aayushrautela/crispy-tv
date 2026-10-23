import ContractRunner
import Foundation

/// The "type" segment inside the watch-progress keys — Android maps its
/// content types the same way, and a show is a `show` in the key even though
/// the backend calls it a `series`.
public func watchProgressKeyTypeName(_ contentType: MediaContentType) -> String {
    switch contentType {
    case .movie: return "movie"
    case .series: return "show"
    case .anime: return "anime"
    }
}

/// The key parts the local progress store and the resume lookup share: a
/// writer and a reader must agree on them, and the agreement lives here.
///
/// Android builds the episode suffix from the item id, the season and the
/// episode; movies carry no episode suffix at all.
public func watchProgressKeyParts(identity: PlaybackIdentity?) -> (id: String, type: String, episodeId: String?)? {
    guard let identity, let id = identity.itemId.nilIfBlank else { return nil }
    let type = watchProgressKeyTypeName(identity.contentType)
    var episodeId: String?
    if identity.contentType != .movie, let season = identity.season, let episode = identity.episode {
        episodeId = "\(id):\(season):\(episode)"
    }
    return (id, type, episodeId)
}

/// The record half of record-then-send: one progress event, written to the
/// local store BEFORE any network send, so a dropped send is a later replay,
/// not a lost one — the same shape Android's `BackendWatchHistoryService`
/// gives every report.
///
/// The event carries the position as the player reported it (the start's
/// minimum-position gate and the stop's completion pinning happened upstream),
/// so the store takes what the event says: `currentTime` from the position,
/// `duration` from the event, and the content duration recorded beside it.
/// Whatever a previous entry knew about the metadata (`remoteImdbId`,
/// `addonId`) survives the merge.
public func recordWatchProgress(
    store: WatchProgressStore,
    identity: PlaybackIdentity?,
    event: PlaybackEventInput
) {
    guard let parts = watchProgressKeyParts(identity: identity) else { return }
    let durationSeconds = event.durationSeconds ?? 0
    if durationSeconds > 0 {
        store.setContentDuration(
            id: parts.id,
            type: parts.type,
            durationSeconds: durationSeconds,
            episodeId: parts.episodeId
        )
    }
    let existing = store.getWatchProgress(id: parts.id, type: parts.type, episodeId: parts.episodeId)
    store.setWatchProgress(
        id: parts.id,
        type: parts.type,
        progress: WatchProgress(
            currentTimeSeconds: event.positionSeconds,
            durationSeconds: durationSeconds,
            lastUpdatedEpochMs: 0, // the store stamps its own now
            remoteImdbId: existing?.remoteImdbId,
            addonId: existing?.addonId
        ),
        episodeId: parts.episodeId
    )
}
