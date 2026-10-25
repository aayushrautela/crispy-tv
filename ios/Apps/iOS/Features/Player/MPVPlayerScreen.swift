import AVFoundation
import ContractRunner
import CrispyKit
import SwiftUI

/// The mpv player: it owns the engine and the session, and hosts the chrome.
///
/// The engine lives in the app target, not in `CrispyKit`, so the libmpv
/// binary stays out of the package graph and out of the tvOS app.
struct MPVPlayerScreen: View {
    let source: PlaybackSource
    var title: String?
    /// What is playing, when the player knows it.
    ///
    /// The progress reports need it, and it is what the resume position is
    /// fetched for. Without it the player still plays; it just does not report.
    var identity: PlaybackIdentity?
    /// Reports what the player is doing, for the developer screen.
    var onStatus: ((String) -> Void)?

    @Environment(\.dismiss) private var dismiss
    @Environment(AppEnvironment.self) private var environment
    @State private var engine: MPVPlaybackEngine?
    @State private var session: PlayerSession?
    @State private var reporter: PlaybackProgressReporter?

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
        .task { await run() }
        .onChange(of: session?.state.phase) { _, phase in
            guard let phase else { return }
            onStatus?("phase: \(phase.rawValue)")
        }
        .onDisappear { close() }
    }

    private func run() async {
        await start()
        await pollProgress()
    }

    private func start() async {
        guard session == nil else { return }
        // The app owns the audio session while the player runs, so the system
        // hands the remote controls to it.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)

        let resumePositionMs = await resumePositionMs()
        let engine = MPVPlaybackEngine(settings: environment.playbackSettings.settings)
        let session = PlayerSession(engine: engine)
        let reporter = makeReporter()
        // A seek moves the playhead on purpose, so the engine's transient
        // position of 0 must not be persisted as progress.
        session.onSeek = { _ in reporter.scheduleProgressSyncAfterSeek() }
        // A track the viewer picks is the preference: the player is the only
        // place a language is chosen, so it is the only writer.
        session.onTrackSelected = { selection in rememberSelection(selection) }
        self.engine = engine
        self.session = session
        self.reporter = reporter
        session.open(source.resuming(at: resumePositionMs))
    }

    /// Store the language of a picked track, the way the Android player does.
    ///
    /// Only a real track with a language is remembered. An "off" pick and a
    /// track mpv reports no language for both leave the preference alone — a
    /// pick of "no subtitles right now" is not a statement about subtitles in
    /// general, and a track with no language tag has nothing to store.
    private func rememberSelection(_ selection: PlaybackTrackSelection) {
        guard case .track(let track) = selection else { return }
        guard let language = track.language?.nilIfBlank else { return }
        let code = LanguageTags.normalize(language)
        switch track.kind {
        case .audio:
            environment.playbackSettings.setDefaultAudioLanguage(code)
        case .subtitle:
            environment.playbackSettings.setDefaultSubtitleLanguage(code)
        case .video:
            break
        }
    }

    /// Where playback should resume: the local progress store first, the
    /// backend's watch state second.
    ///
    /// A position the caller already put on the source wins: the caller knows
    /// something about the item that neither the store nor the watch state
    /// does.
    ///
    /// The local read is what makes an offline resume work — the event the
    /// player reported is on disk before any send — and the backend read
    /// covers progress this device never saw.
    private func resumePositionMs() async -> Int64 {
        if source.resumePositionMs > 0 { return source.resumePositionMs }
        guard let parts = watchProgressKeyParts(identity: identity) else { return 0 }
        if let local = environment.watchProgressStore.getWatchProgress(
            id: parts.id,
            type: parts.type,
            episodeId: parts.episodeId
        ), local.currentTimeSeconds > 0 {
            return Int64(local.currentTimeSeconds * 1000)
        }
        guard let context = await environment.backendContext() else { return 0 }
        guard let state = try? await environment.backend.getWatchState(
            accessToken: context.accessToken,
            profileId: context.profileId,
            itemId: parts.id
        ) else { return 0 }
        return state.resumePositionMs ?? 0
    }

    /// The reporter that owns every "the player moved" report.
    ///
    /// The send closure records the event into the local progress store first
    /// — the durability write, so a dropped send is a replay rather than a
    /// loss — and then sends best-effort. The send resolves the backend
    /// context when it sends, because the token may have been refreshed since
    /// the player opened.
    private func makeReporter() -> PlaybackProgressReporter {
        let environment = environment
        let identity = identity
        let store = environment.watchProgressStore
        return PlaybackProgressReporter(
            sendEvent: { input in
                recordWatchProgress(store: store, identity: identity, event: input)
                guard let context = await environment.backendContext() else { return }
                _ = try? await environment.backend.sendWatchEvent(
                    accessToken: context.accessToken,
                    profileId: context.profileId,
                    input: input
                )
            },
            appVersion: Self.appVersion
        )
    }

    /// The poll that feeds the reporter, on the same cadence the Android
    /// player polls on.
    private func pollProgress() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled else { return }
            guard let reporter, let session else { return }
            reporter.syncWatchHistory(
                identity: identity,
                positionMs: session.positionMs,
                durationMs: session.durationMs,
                isPlaying: isPlaying(session)
            )
        }
    }

    private func close() {
        if let reporter, let session {
            reporter.reportPlaybackStopped(
                identity: identity,
                positionMs: session.positionMs,
                durationMs: session.durationMs
            )
        }
        session?.close()
        session = nil
        engine = nil
        reporter = nil
    }

    private func isPlaying(_ session: PlayerSession) -> Bool {
        session.state.intent == .play
            && session.state.phase != .ended
            && session.state.phase != .error
    }

    private static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }
}
