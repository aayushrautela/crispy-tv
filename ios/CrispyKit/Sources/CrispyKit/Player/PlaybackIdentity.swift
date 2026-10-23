import ContractRunner
import Foundation

/// What is playing, in the shape the backend's watch events carry.
///
/// The Android `PlaybackIdentity` carries more — the external ids, the year —
/// but only these fields reach a watch event or a progress-store key, so only
/// these fields are here.
public struct PlaybackIdentity: Equatable, Sendable {
    /// The item the backend knows the item by.
    public let itemId: String
    /// The title of what is playing.
    public let title: String
    /// The show's title, when what is playing is an episode of one.
    public let showTitle: String?
    public let season: Int?
    public let episode: Int?
    /// The content type the local progress store keys the item under.
    public let contentType: MediaContentType

    public init(
        itemId: String,
        title: String,
        showTitle: String? = nil,
        season: Int? = nil,
        episode: Int? = nil,
        contentType: MediaContentType = .movie
    ) {
        self.itemId = itemId
        self.title = title
        self.showTitle = showTitle
        self.season = season
        self.episode = episode
        self.contentType = contentType
    }
}
