#if DEBUG
import ContractRunner
import CrispyKit
import SwiftUI

/// A developer screen that plays one URL through the mpv engine.
///
/// It exists so the engine can be checked on a device before there is a
/// stream source to play: the URL is typed in by hand.
struct MPVSmokeScreen: View {
    @State private var urlText = ""
    @State private var engine: MPVPlaybackEngine?
    @State private var session: PlayerSession?

    var body: some View {
        VStack(spacing: 16) {
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

            if let engine {
                MPVPlayerSurface(engine: engine)
                    .aspectRatio(16.0 / 9.0, contentMode: .fit)
                    .background(Color.black)
            }

            if let session {
                VStack(alignment: .leading, spacing: 4) {
                    Text("phase: \(session.state.phase.rawValue)")
                    Text("position: \(session.positionMs / 1000)s of \(session.durationMs / 1000)s")
                    if let message = session.lastErrorMessage {
                        Text(message)
                            .foregroundStyle(.red)
                    }
                }
                .font(.caption.monospaced())
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal)
            }
        }
        .padding(.vertical)
        .navigationTitle("MPV smoke test")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear {
            session?.close()
            session = nil
            engine = nil
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
        session?.close()
        let engine = MPVPlaybackEngine()
        let session = PlayerSession(engine: engine)
        self.engine = engine
        self.session = session
        session.open(PlaybackSource(url: url))
    }
}
#endif
