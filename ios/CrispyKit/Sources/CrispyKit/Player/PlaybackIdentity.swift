import Foundation

/// What is playing, in the shape the backend's watch events carry.
///
/// The Android `PlaybackIdentity` carries more — the content type, the external
/// ids, the year — but only these fields reach a watch event, so only these
/// fields are here.
public struct PlaybackIdentity: Equatable, Sendable {
    /// The item the backend knows the item by.
    public let itemId: String
    /// The title of what is playing.
    public let title: String
    /// The show's title, when what is playing is an episode of one.
    public let showTitle: String?
    public let season: Int?
    public let episode: Int?

    public init(
        itemId: String,
        title: String,
        showTitle: String? = nil,
        season: Int? = nil,
        episode: Int? = nil
    ) {
        self.itemId = itemId
        self.title = title
        self.showTitle = showTitle
        self.season = season
        self.episode = episode
    }
}
