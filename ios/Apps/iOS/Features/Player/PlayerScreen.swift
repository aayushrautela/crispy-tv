import ContractRunner
import CrispyKit
import Foundation
import SwiftUI

/// The player chrome: the video surface, the controls over it, and the
/// curtains the contract's phase puts up.
///
/// The chrome is written against the session, not against an engine, so the
/// same controls drive every engine. The surface is a parameter because the
/// engine owns the view it draws into.
struct PlayerScreen<Surface: View>: View {
    /// The session that owns the state this screen renders.
    let session: PlayerSession
    private let surface: Surface
    private let title: String?
    private let onClose: () -> Void

    @State private var controlsVisible = true
    @State private var scrubPosition: Double?
    @State private var isTrackSheetPresented = false
    @State private var nowPlaying = NowPlayingController()
    @State private var hideTask: Task<Void, Never>?

    init(
        session: PlayerSession,
        title: String? = nil,
        onClose: @escaping () -> Void,
        @ViewBuilder surface: () -> Surface
    ) {
        self.session = session
        self.title = title
        self.onClose = onClose
        self.surface = surface()
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            surface
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { toggleControls() }

            VStack(spacing: 12) {
                topBar
                Spacer(minLength: 0)
                if controlsVisible {
                    bottomBar
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)

            curtain
        }
        .background(Color.black)
        .sheet(isPresented: $isTrackSheetPresented) {
            PlayerTrackSheet(tracks: session.tracks) { selection in
                session.selectTrack(selection)
            }
        }
        .onAppear { startNowPlaying() }
        .onDisappear { stopNowPlaying() }
        .onChange(of: session.state.phase) { _, _ in refreshNowPlaying() }
        .onChange(of: session.state.intent) { _, _ in refreshNowPlaying() }
        .onChange(of: session.positionMs) { _, _ in refreshNowPlaying() }
    }

    // MARK: - Bars

    private var topBar: some View {
        HStack(spacing: 12) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(8)
            }
            .buttonStyle(.plain)
            if let title {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(.white)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Text(timeLabel(session.positionMs))
                Slider(
                    value: Binding(
                        get: { scrubPosition ?? Double(session.positionMs) / 1000 },
                        set: { scrubPosition = $0 }
                    ),
                    in: 0...max(1, Double(session.durationMs) / 1000),
                    onEditingChanged: { editing in
                        if editing {
                            scrubPosition = Double(session.positionMs) / 1000
                        } else if let scrubPosition {
                            session.seek(to: Int64(scrubPosition * 1000))
                            self.scrubPosition = nil
                        }
                    }
                )
                Text(timeLabel(session.durationMs))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.white)

            HStack(spacing: 30) {
                Button { skip(-10_000) } label: {
                    Image(systemName: "gobackward.10")
                        .font(.title2)
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)

                Button { togglePlay() } label: {
                    Image(systemName: playIcon)
                        .font(.largeTitle)
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)

                Button { skip(10_000) } label: {
                    Image(systemName: "goforward.10")
                        .font(.title2)
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)

                Spacer(minLength: 0)

                if !session.tracks.isEmpty {
                    Button { isTrackSheetPresented = true } label: {
                        Image(systemName: "captions.bubble")
                            .font(.title2)
                            .foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Curtain

    @ViewBuilder
    private var curtain: some View {
        switch session.state.phase {
        case .error:
            messageCurtain(
                symbol: "exclamationmark.triangle",
                title: "Playback failed",
                message: session.lastErrorMessage,
                actionTitle: "Close",
                action: onClose
            )
        case .ended:
            messageCurtain(
                symbol: "checkmark.circle",
                title: "Playback finished",
                message: nil,
                actionTitle: "Play again",
                action: replay
            )
        case .buffering:
            ProgressView()
                .tint(Theme.spinner)
                .scaleEffect(1.4)
        default:
            EmptyView()
        }
    }

    private func messageCurtain(
        symbol: String,
        title: String,
        message: String?,
        actionTitle: String,
        action: @escaping () -> Void
    ) -> some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.largeTitle)
                .foregroundStyle(.white)
            Text(title)
                .font(.headline)
                .foregroundStyle(.white)
            if let message {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
            }
            Button(actionTitle, action: action)
                .buttonStyle(.borderedProminent)
                .tint(Theme.spinner)
        }
        .padding(24)
        .frame(maxWidth: 320)
        .background(.ultraThinMaterial, in: .rect(cornerRadius: 20))
        .padding()
    }

    // MARK: - Transport

    private var isPlaying: Bool {
        session.state.intent == .play
            && session.state.phase != .ended
            && session.state.phase != .error
    }

    /// The icon of the play button, which is a replay when the media ended.
    private var playIcon: String {
        if session.state.phase == .ended { return "arrow.counterclockwise" }
        return isPlaying ? "pause.fill" : "play.fill"
    }

    private func togglePlay() {
        // A finished media has to be opened again to play again, because the
        // contract's `ended` phase does not leave on a seek.
        if session.state.phase == .ended {
            replay()
            return
        }
        if isPlaying {
            session.pause()
        } else {
            session.play()
        }
        scheduleHide()
    }

    private func skip(_ deltaMs: Int64) {
        let upperBound = session.durationMs > 0 ? session.durationMs : Int64.max
        let target = max(0, min(upperBound, session.positionMs + deltaMs))
        session.seek(to: target)
        scheduleHide()
    }

    private func replay() {
        session.replay()
        scheduleHide()
    }

    private func toggleControls() {
        controlsVisible.toggle()
        scheduleHide()
    }

    /// Hides the controls a few seconds after they were used, so the video is
    /// not covered while it plays.
    private func scheduleHide() {
        hideTask?.cancel()
        guard controlsVisible, isPlaying else { return }
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            controlsVisible = false
        }
    }

    private func timeLabel(_ milliseconds: Int64) -> String {
        let totalSeconds = max(0, milliseconds / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    // MARK: - Now playing

    private func startNowPlaying() {
        nowPlaying.onPlay = { session.play() }
        nowPlaying.onPause = { session.pause() }
        nowPlaying.onSeek = { positionMs in session.seek(to: positionMs) }
        nowPlaying.onSkipForward = { skip(10_000) }
        nowPlaying.onSkipBackward = { skip(-10_000) }
        nowPlaying.start()
        refreshNowPlaying()
    }

    private func refreshNowPlaying() {
        nowPlaying.update(
            title: title,
            positionMs: session.positionMs,
            durationMs: session.durationMs,
            isPlaying: isPlaying
        )
    }

    private func stopNowPlaying() {
        nowPlaying.stop()
    }
}
