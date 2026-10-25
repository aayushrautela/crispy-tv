import Foundation

#if DEBUG
/// The launch arguments the developer smoke harness reads.
///
/// The harness exists so that "does this actually decode" is one command:
///
/// ```sh
/// xcrun simctl launch booted com.crispy.rewrite.ios -mpvSmokeURL https://example.com/movie.mkv
/// ```
///
/// The argument is read once, when the app starts, and the player opens over
/// whatever the app was showing. A URL that is not there is not an error: the
/// app simply starts normally.
enum SmokeLaunchArguments {
    /// The argument that carries the URL to play.
    static let urlKey = "-mpvSmokeURL"

    /// The URL to play, when the launch asked for one.
    static var url: URL? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: urlKey) else { return nil }
        let valueIndex = arguments.index(after: index)
        guard valueIndex < arguments.endIndex else { return nil }
        let value = arguments[valueIndex]
        guard let url = URL(string: value), url.scheme != nil else { return nil }
        return url
    }
}
#endif
