import AVFoundation
import CrispyKit
import SwiftUI

/// The mpv player: it owns the engine and the session, and hosts the chrome.
///
/// The engine lives in the app target, not in `CrispyKit`, so the libmpv
/// binary stays out of the package graph and out of the tvOS app.
struct MPVPlayerScreen: View {
    let source: PlaybackSource
    var title: String?
    /// Reports what the player is doing, for the developer screen.
    var onStatus: ((String) -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var engine: MPVPlaybackEngine?
    @State private var session: PlayerSession?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let engine, let session {
                PlayerScreen(session: session, title: title, onClose: { dismiss() }) {
                    MPVPlayerSurface(engine: engine)
                }
            } else {
                ProgressView()
                    .tint(Theme.spinner)
            }
        }
        .task { start() }
        .onChange(of: session?.state.phase) { _, phase in
            guard let phase else { return }
            onStatus?("phase: \(phase.rawValue)")
        }
        .onDisappear {
            session?.close()
            session = nil
            engine = nil
        }
    }

    private func start() {
        guard session == nil else { return }
        // The app owns the audio session while the player runs, so the system
        // hands the remote controls to it.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)

        let engine = MPVPlaybackEngine()
        let session = PlayerSession(engine: engine)
        self.engine = engine
        self.session = session
        session.open(source)
    }
}
