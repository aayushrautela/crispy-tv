import CrispyKit
import Foundation
import Libmpv
import os

/// The libmpv playback engine.
///
/// libmpv is a C library that is driven by a blocking wait for events, so the
/// engine runs two background queues: one waits for mpv events and turns them
/// into `PlaybackEvent`s, the other polls the playhead. The playhead is
/// polled rather than observed on purpose — mpv's `time-pos` changes on every
/// frame, and an observed property would flood the event loop.
///
/// The engine is `@unchecked Sendable` because the libmpv client API is
/// thread-safe and the little state the engine owns is behind a lock.
final class MPVPlaybackEngine: PlaybackEngine, @unchecked Sendable {
    let engineId = "mpv"

    /// The surface mpv renders into. The SwiftUI surface installs this layer
    /// in a view; mpv is bound to the layer itself, not to the view.
    let metalLayer: MPVMetalLayer

    private let handle: OpaquePointer?
    private let stream: AsyncStream<PlaybackEvent>
    private let continuation: AsyncStream<PlaybackEvent>.Continuation
    private let eventQueue = DispatchQueue(
        label: "com.crispy.rewrite.player.mpv.events",
        qos: .userInitiated
    )
    private let pollQueue = DispatchQueue(
        label: "com.crispy.rewrite.player.mpv.poll",
        qos: .utility
    )
    private let eventGroup = DispatchGroup()
    private let lock = NSLock()
    private let logger = Logger(subsystem: "com.crispy.rewrite.ios", category: "player")
    private var pollTimer: DispatchSourceTimer?
    private var isStopped = false
    private var hasReportedFailure = false
    private var lastPositionMs: Int64 = -1
    private var lastTracks: [PlaybackTrack] = []

    init() {
        let events = AsyncStream.makeStream(of: PlaybackEvent.self)
        stream = events.stream
        continuation = events.continuation

        let layer = MPVPlaybackEngine.makeMetalLayer()
        metalLayer = layer

        let started = MPVPlaybackEngine.startHandle(for: layer)
        handle = started.handle
        if let handle = started.handle {
            startEventLoop(handle)
            startPolling(handle)
        } else {
            continuation.yield(.failed(started.error))
        }
    }

    func makeEventStream() -> AsyncStream<PlaybackEvent> {
        stream
    }

    func open(_ source: PlaybackSource) {
        guard let handle, !stopped() else { return }
        lock.lock()
        hasReportedFailure = false
        lock.unlock()

        // mpv reads the request headers from a string list property. The
        // values are joined the way mpv expects them: `Name: value` pairs
        // separated by commas.
        let headerFields = source.headers
            .map { "\($0.key): \($0.value)" }
            .joined(separator: ",")
        mpv_set_property_string(handle, "http-header-fields", headerFields)

        var arguments = [source.url.absoluteString, "replace"]
        if source.resumePositionMs > 0 {
            let seconds = Double(source.resumePositionMs) / 1000
            arguments.append(String(format: "start=%.3f", seconds))
        }
        command(handle, "loadfile", arguments: arguments)
    }

    func play() {
        guard let handle, !stopped() else { return }
        mpv_set_property_string(handle, "pause", "no")
    }

    func pause() {
        guard let handle, !stopped() else { return }
        mpv_set_property_string(handle, "pause", "yes")
    }

    func seek(to positionMs: Int64) {
        guard let handle, !stopped() else { return }
        let seconds = Double(positionMs) / 1000
        command(handle, "seek", arguments: [String(format: "%.3f", seconds), "absolute+exact"])
    }

    func selectTrack(_ selection: PlaybackTrackSelection) {
        guard let handle, !stopped() else { return }
        switch selection {
        case .track(let track):
            mpv_set_property_string(handle, track.kind.mpvProperty, track.id)
        case .disabled(let kind):
            mpv_set_property_string(handle, kind.mpvProperty, "no")
        }
    }

    func shutdown() {
        guard let handle, !stopped() else { return }
        setStopped()
        pollTimer?.cancel()
        pollTimer = nil
        continuation.finish()
        // The event loop has to be gone before the handle is destroyed, and
        // the destruction must not happen on the caller's thread: mpv waits
        // for its own threads to join.
        eventQueue.async { [eventGroup] in
            eventGroup.wait()
            mpv_terminate_destroy(handle)
        }
    }

    // MARK: - Setup

    /// The options the engine runs with.
    ///
    /// The video output is mpv's next generation renderer over Vulkan, driven
    /// by MoltenVK — that is the only path in this build that renders into a
    /// `CAMetalLayer`, and the `wid` option below is what binds the layer.
    private static let options: [(name: String, value: String)] = [
        ("vo", "gpu-next"),
        ("gpu-api", "vulkan"),
        ("gpu-context", "moltenvk"),
        ("hwdec", "videotoolbox"),
        // AVFoundation's sample buffer renderer is the only audio output this
        // build of mpv has for iOS.
        ("ao", "avfoundation"),
        ("audio-channels", "auto"),
        ("audio-fallback-to-null", "yes"),
        // One queue, no interop, a FIFO swap chain: the MoltenVK defaults
        // flicker or deadlock on iOS.
        ("vulkan-swap-mode", "fifo"),
        ("vulkan-queue-count", "1"),
        ("vulkan-async-compute", "no"),
        ("vulkan-async-transfer", "no"),
        ("vulkan-disable-interop", "yes"),
        // Keep the file loaded when it ends, so the player can show the last
        // frame instead of a black surface.
        ("keep-open", "yes"),
        // HDR passthrough. The screen has to be told, which is what the
        // surface's extended dynamic range workaround is for.
        ("target-colorspace-hint", "yes"),
        ("video-rotate", "no"),
        ("subs-match-os-language", "yes"),
        ("subs-fallback", "yes"),
        // mpv must not take over the terminal, and it must stay alive while
        // it has nothing to play.
        ("terminal", "no"),
        ("idle", "yes")
    ]

    /// The options the engine cannot run without.
    private static let criticalOptions: Set<String> = ["vo", "gpu-api", "gpu-context"]

    private static func makeMetalLayer() -> MPVMetalLayer {
        // A layer belongs to the thread that draws it, so it is made on the
        // main thread even when the engine is built elsewhere.
        let layer = Thread.isMainThread
            ? MPVMetalLayer()
            : DispatchQueue.main.sync { MPVMetalLayer() }
        // mpv draws into the layer through MoltenVK, never through a Metal
        // render pass, so the layer does not have to keep its contents.
        layer.framebufferOnly = true
        return layer
    }

    private static func startHandle(for layer: MPVMetalLayer) -> (handle: OpaquePointer?, error: String) {
        guard let handle = mpv_create() else {
            return (nil, "libmpv could not be created")
        }

        // mpv renders into the layer, and it identifies the layer by address.
        // The reference's bits are the pointer mpv's moltenvk context wants.
        var windowId = unsafeBitCast(layer, to: Int64.self)
        if mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &windowId) < 0 {
            mpv_terminate_destroy(handle)
            return (nil, "libmpv rejected the render surface")
        }

        // mpv only reports log messages that were asked for, and the codec
        // failures the engine has to tell apart from a fetch failure arrive
        // as error-level log lines.
        mpv_request_log_messages(handle, "warn")

        for option in options {
            if mpv_set_option_string(handle, option.name, option.value) >= 0 {
                continue
            }
            guard criticalOptions.contains(option.name) else {
                // A rejected tuning option is not worth failing the player
                // over; mpv falls back to its default.
                continue
            }
            mpv_terminate_destroy(handle)
            return (nil, "libmpv rejected the option \(option.name)")
        }

        if mpv_initialize(handle) < 0 {
            mpv_terminate_destroy(handle)
            return (nil, "libmpv could not be initialized")
        }
        return (handle, "")
    }

    // MARK: - Events

    private func startEventLoop(_ handle: OpaquePointer) {
        // The cache runs dry and the player stalls. mpv reports that as a
        // property, and it is the only buffering signal there is.
        mpv_observe_property(handle, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        // The tracks the media carries, and the ones that are selected. The
        // list and the selection are observed separately: mpv reports a new
        // list when the file is opened, and a new selection when the user
        // picks a track.
        mpv_observe_property(handle, 0, "track-list", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "current-tracks", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "aid", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "sid", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "vid", MPV_FORMAT_NONE)

        let group = eventGroup
        group.enter()
        eventQueue.async { [weak self] in
            defer { group.leave() }
            while true {
                guard let self, !self.stopped() else { return }
                guard let event = mpv_wait_event(handle, 0.05) else { return }
                if event.pointee.event_id == MPV_EVENT_NONE {
                    // The wait timed out; there is nothing to report.
                    continue
                }
                self.handleEvent(event.pointee)
                if event.pointee.event_id == MPV_EVENT_SHUTDOWN {
                    return
                }
            }
        }
    }

    private func handleEvent(_ event: mpv_event) {
        switch event.event_id {
        case MPV_EVENT_FILE_LOADED:
            continuation.yield(.ready)
            // The file is open, so the media's tracks are known.
            emitTracks()
        case MPV_EVENT_VIDEO_RECONFIG:
            // The video output was set up, which is as close to "the first
            // frame is on screen" as mpv gets.
            continuation.yield(.firstFrame)
        case MPV_EVENT_END_FILE:
            handleEndFile(event)
        case MPV_EVENT_PROPERTY_CHANGE:
            handlePropertyChange(event)
        case MPV_EVENT_LOG_MESSAGE:
            handleLogMessage(event)
        default:
            break
        }
    }

    private func handleEndFile(_ event: mpv_event) {
        guard let data = event.data else { return }
        let endFile = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
        switch endFile.reason {
        case MPV_END_FILE_REASON_EOF:
            continuation.yield(.ended)
        case MPV_END_FILE_REASON_ERROR:
            reportFailure(errorMessage(for: endFile.error))
        default:
            // A stop or a quit is something the engine asked for itself.
            break
        }
    }

    private func handlePropertyChange(_ event: mpv_event) {
        guard let data = event.data else { return }
        let property = data.assumingMemoryBound(to: mpv_event_property.self).pointee
        guard let name = property.name else { return }
        switch String(cString: name) {
        case "paused-for-cache":
            guard let value = property.data else { return }
            // The flag is an int, not a bool.
            let pausedForCache = value.assumingMemoryBound(to: Int32.self).pointee != 0
            if pausedForCache {
                continuation.yield(.buffering)
            } else {
                continuation.yield(.ready)
            }
        case "track-list", "current-tracks", "aid", "sid", "vid":
            // The list of tracks, or the track that plays, has changed.
            emitTracks()
        default:
            break
        }
    }

    // MARK: - Tracks

    /// Reports the tracks of the media, and the ones that are selected.
    private func emitTracks() {
        guard let handle, !stopped() else { return }
        let tracks = readTracks(handle)
        lock.lock()
        let unchanged = tracks == lastTracks
        lastTracks = tracks
        lock.unlock()
        guard !unchanged else { return }
        continuation.yield(.tracksChanged(tracks))
    }

    /// Reads the tracks out of mpv's track list.
    ///
    /// mpv keeps the list in a property tree, and every track is a node of
    /// it, so the tracks are read one path at a time.
    private func readTracks(_ handle: OpaquePointer) -> [PlaybackTrack] {
        guard let count = intProperty(handle, "track-list/count") else { return [] }
        // The track that plays is the one mpv reports in its current tracks;
        // the aliases are the fallback for a player that has no such node.
        let selectedAudio = selectedTrackId(handle, node: "audio", alias: "aid")
        let selectedSubtitle = selectedTrackId(handle, node: "sub", alias: "sid")
        let selectedVideo = selectedTrackId(handle, node: "video", alias: "vid")

        var tracks: [PlaybackTrack] = []
        for index in 0..<Int(count) {
            guard let id = intProperty(handle, "track-list/\(index)/id") else { continue }
            guard let kind = PlaybackTrackKind(mpvType: stringProperty(handle, "track-list/\(index)/type")) else {
                continue
            }
            let selected: Bool
            switch kind {
            case .audio: selected = selectedAudio == id
            case .subtitle: selected = selectedSubtitle == id
            case .video: selected = selectedVideo == id
            }
            tracks.append(
                PlaybackTrack(
                    id: String(id),
                    kind: kind,
                    title: stringProperty(handle, "track-list/\(index)/title"),
                    language: stringProperty(handle, "track-list/\(index)/lang"),
                    isDefault: flagProperty(handle, "track-list/\(index)/default"),
                    isForced: flagProperty(handle, "track-list/\(index)/forced"),
                    isSelected: selected
                )
            )
        }
        return tracks
    }

    private func handleLogMessage(_ event: mpv_event) {
        guard let data = event.data else { return }
        let message = data.assumingMemoryBound(to: mpv_event_log_message.self).pointee
        guard let level = message.level, let rawText = message.text else { return }
        let text = String(cString: rawText)
        guard String(cString: level) == "error" else { return }
        guard isCodecFailure(text) else {
            // A decoder failure is a playback failure; anything else is a
            // complaint about a stream mpv can play without.
            logger.error("mpv: \(text, privacy: .public)")
            return
        }
        reportFailure(text)
    }

    private func reportFailure(_ message: String) {
        lock.lock()
        let alreadyReported = hasReportedFailure
        hasReportedFailure = true
        lock.unlock()
        guard !alreadyReported else { return }
        if isCodecFailure(message) {
            continuation.yield(.codecError)
        } else {
            continuation.yield(.failed(message))
        }
    }

    /// A failure the media could not be decoded with, as opposed to a failure
    /// the media could not be fetched with.
    private func isCodecFailure(_ message: String) -> Bool {
        let message = message.lowercased()
        return message.contains("decoder")
            || message.contains("codec")
            || message.contains("no suitable")
            || message.contains("unsupported")
    }

    private func errorMessage(for code: Int32) -> String {
        guard let text = mpv_error_string(code) else {
            return "libmpv failed with code \(code)"
        }
        return String(cString: text)
    }

    // MARK: - Transport state

    private func startPolling(_ handle: OpaquePointer) {
        let timer = DispatchSource.makeTimerSource(queue: pollQueue)
        timer.schedule(deadline: .now() + .milliseconds(200), repeating: .milliseconds(200))
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            self.poll(handle)
        }
        timer.resume()
        pollTimer = timer
    }

    private func poll(_ handle: OpaquePointer) {
        let positionMs = Int64((doubleProperty(handle, "time-pos") * 1000).rounded())
        let durationMs = Int64((doubleProperty(handle, "duration") * 1000).rounded())
        let bufferedMs = Int64((doubleProperty(handle, "demuxer-cache-duration") * 1000).rounded())

        lock.lock()
        let previousPositionMs = lastPositionMs
        lastPositionMs = positionMs
        lock.unlock()
        guard positionMs != previousPositionMs else { return }

        continuation.yield(
            .positionChanged(positionMs: positionMs, durationMs: durationMs, bufferedMs: bufferedMs)
        )
    }

    private func doubleProperty(_ handle: OpaquePointer, _ name: String) -> Double {
        var value = 0.0
        guard mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, &value) >= 0 else { return 0 }
        // The property is not a number while no file is playing.
        return value.isFinite ? value : 0
    }

    private func intProperty(_ handle: OpaquePointer, _ name: String) -> Int64? {
        var value: Int64 = 0
        guard mpv_get_property(handle, name, MPV_FORMAT_INT64, &value) >= 0 else { return nil }
        return value
    }

    private func stringProperty(_ handle: OpaquePointer, _ name: String) -> String? {
        guard let raw = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(raw) }
        return String(cString: raw)
    }

    private func flagProperty(_ handle: OpaquePointer, _ name: String) -> Bool {
        var value: Int32 = 0
        guard mpv_get_property(handle, name, MPV_FORMAT_FLAG, &value) >= 0 else { return false }
        return value != 0
    }

    /// The id of the track that plays, of one kind.
    private func selectedTrackId(_ handle: OpaquePointer, node: String, alias: String) -> Int64? {
        intProperty(handle, "current-tracks/\(node)/id") ?? intProperty(handle, alias)
    }

    private func command(_ handle: OpaquePointer, _ name: String, arguments: [String]) {
        var commandArguments: [UnsafePointer<CChar>?] = []
        for argument in [name] + arguments {
            guard let copy = strdup(argument) else { continue }
            commandArguments.append(UnsafePointer(copy))
        }
        commandArguments.append(nil)
        defer {
            for argument in commandArguments {
                if let argument {
                    free(UnsafeMutableRawPointer(mutating: argument))
                }
            }
        }
        mpv_command(handle, &commandArguments)
    }

    // MARK: - State

    private func stopped() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return isStopped
    }

    private func setStopped() {
        lock.lock()
        isStopped = true
        lock.unlock()
    }
}

/// mpv's own vocabulary for the tracks a media carries.
private extension PlaybackTrackKind {
    /// mpv calls a subtitle track `sub`.
    init?(mpvType: String?) {
        switch mpvType {
        case "audio": self = .audio
        case "video": self = .video
        case "sub": self = .subtitle
        default: return nil
        }
    }

    /// The mpv property that chooses the track of this kind.
    var mpvProperty: String {
        switch self {
        case .audio: return "audio-track"
        case .video: return "video-track"
        case .subtitle: return "sub-track"
        }
    }
}
