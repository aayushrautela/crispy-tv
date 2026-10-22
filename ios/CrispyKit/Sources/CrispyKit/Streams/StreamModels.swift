import Foundation

/// The kinds of media an addon can serve streams for.
///
/// These are the addon's own vocabulary, which is not the backend's: the
/// backend calls a show a `series` and a movie a `movie`, and the addon's
/// stream resource path uses the same words.
public enum StreamMediaType: String, Equatable, Sendable, CaseIterable {
    case movie
    case series
    case anime

    /// The path segment the addon's stream resource uses.
    public var apiPath: String { rawValue }

    /// The media type the backend's metadata names.
    ///
    /// The backend spells a show three ways in different places, so all three
    /// land on the same media type.
    public init?(metadataMediaType: String) {
        switch metadataMediaType.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "movie": self = .movie
        case "series", "show", "tv": self = .series
        case "anime": self = .anime
        default: return nil
        }
    }
}

/// An addon the account has installed.
///
/// The account's addon list is the backend's, so this is where the iOS app
/// learns which addons to ask for streams — the Android app keeps the same
/// list in a local registry, synced with the same backend rows.
public struct AddonDescriptor: Equatable, Sendable, Identifiable {
    public let id: String
    public let manifestUrl: String
    /// The addon's display name, when the backend knows it.
    public let name: String
    /// The addon's kind. Only the `stremio` kind speaks the protocol below.
    public let type: String

    public init(id: String, manifestUrl: String, name: String = "", type: String = "stremio") {
        self.id = id
        self.manifestUrl = manifestUrl
        self.name = name
        self.type = type
    }
}

/// One subtitle an addon offers for a stream.
public struct StreamSubtitleOption: Equatable, Sendable, Identifiable {
    public let url: String
    public let language: String?
    public let name: String?

    public var id: String { "\(language ?? "")|\(name ?? "")|\(url)" }

    public init(url: String, language: String? = nil, name: String? = nil) {
        self.url = url
        self.language = language
        self.name = name
    }
}

/// One stream an addon offers for an item.
///
/// A stream is either a **direct** stream — a URL mpv can fetch — or a
/// **torrent**, which is an info hash and a file index. iOS has no torrent
/// engine, so a torrent stream is carried but never offered as playable.
public struct StreamSource: Equatable, Sendable, Identifiable {
    /// The stream's identity within the list it was resolved from.
    public let id: String
    /// The addon that offered the stream.
    public let addonId: String
    public let addonName: String
    /// The addon's own name for the stream.
    public let name: String
    /// The stream's display title, which is where the quality is written.
    public let title: String
    /// The direct media URL, when the stream is one.
    public let url: String?
    /// The torrent's info hash, when the stream is a torrent.
    public let infoHash: String?
    /// The torrent's file index, when the stream is a torrent.
    public let fileIdx: Int?
    /// The request headers the stream needs, from the addon's behavior hints.
    public let headers: [String: String]
    /// The subtitles the addon offers for this stream.
    public let subtitles: [StreamSubtitleOption]

    public init(
        id: String,
        addonId: String,
        addonName: String,
        name: String,
        title: String,
        url: String?,
        infoHash: String?,
        fileIdx: Int?,
        headers: [String: String] = [:],
        subtitles: [StreamSubtitleOption] = []
    ) {
        self.id = id
        self.addonId = addonId
        self.addonName = addonName
        self.name = name
        self.title = title
        self.url = url
        self.infoHash = infoHash
        self.fileIdx = fileIdx
        self.headers = headers
        self.subtitles = subtitles
    }

    /// Whether the stream is a URL the player can open.
    public var isDirect: Bool { url != nil }

    /// The source the player opens, when the stream is a direct URL.
    ///
    /// The headers travel with it: an addon that needs a referer or a
    /// user-agent to serve the file says so in the stream's behavior hints, and
    /// the player has to send them.
    public var playbackSource: PlaybackSource? {
        guard let url, let parsed = URL(string: url), parsed.scheme != nil else { return nil }
        return PlaybackSource(url: parsed, headers: headers)
    }

    /// The height the title claims, when it claims one.
    ///
    /// The quality is written into the title as `1080p` and friends; there is
    /// no field for it, so it is read out of the text.
    public var qualityHeight: Int? {
        let text = title.lowercased()
        var index = text.startIndex
        while index < text.endIndex {
            if text[index].isNumber {
                let start = index
                while index < text.endIndex, text[index].isNumber { index = text.index(after: index) }
                let digits = text[start..<index]
                if index < text.endIndex, text[index] == "p", let height = Int(digits) {
                    return height
                }
            } else {
                index = text.index(after: index)
            }
        }
        return nil
    }
}
