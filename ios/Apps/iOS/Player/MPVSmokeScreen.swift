#if DEBUG
import CrispyKit
import SwiftUI

/// A developer screen that plays one URL through the mpv engine.
///
/// It exists so the engine can be checked on a device before there is a
/// stream source to play: the URL is typed in by hand.
struct MPVSmokeScreen: View {
    @State private var urlText = ""
    @State private var source: PlaybackSource?
    @State private var isPlayerPresented = false
    @State private var status = ""

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                TextField("https://…", text: $urlText)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Button("Play") { play() }
                    .buttonStyle(.borderedProminent)
                    .disabled(playableURL == nil)
            }
            .padding(.horizontal)

            if !status.isEmpty {
                Text(status)
                    .font(.caption.monospaced())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical)
        .navigationTitle("MPV smoke test")
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $isPlayerPresented) {
            if let source {
                MPVPlayerScreen(source: source, onStatus: { status in
                    self.status = status
                })
            }
        }
    }

    /// The URL to play, when it is one mpv can fetch.
    private var playableURL: URL? {
        let trimmed = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme else { return nil }
        guard scheme == "http" || scheme == "https" else { return nil }
        return url
    }

    private func play() {
        guard let url = playableURL else { return }
        source = PlaybackSource(url: url)
        isPlayerPresented = true
    }
}
#endif
