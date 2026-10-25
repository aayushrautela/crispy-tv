import Foundation

/// One subtitle file that travels with a stream.
///
/// An addon offers subtitles next to the stream it offers — a URL per
/// language — and the engine adds them to the player when it opens the media.
/// They are not the media's own embedded subtitles: those arrive with the
/// file, and the player reads them out of the engine's track list.
public struct PlaybackSubtitle: Equatable, Sendable, Identifiable {
    public let url: URL
    /// The name the subtitle provider gave it.
    public let label: String?
    /// The language the provider says it is in.
    public let language: String?

    public var id: String { url.absoluteString }

    public init(url: URL, label: String? = nil, language: String? = nil) {
        self.url = url
        self.label = label
        self.language = language
    }

    /// The same subtitle, when the provider's URL can be parsed.
    public init?(rawUrl: String, label: String? = nil, language: String? = nil) {
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme != nil else { return nil }
        self.init(url: url, label: label, language: language)
    }
}
