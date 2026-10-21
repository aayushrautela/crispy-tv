import Foundation
import MediaPlayer

/// Publishes what is playing to the system: the lock screen, the control
/// centre and the remote control buttons.
///
/// The controller is not actor isolated on purpose — the system's remote
/// commands arrive on its own threads. Everything it calls back into runs on
/// the main actor, because that is where the session lives.
final class NowPlayingController {
    /// Called when the system asks for playback to continue.
    var onPlay: (@MainActor () -> Void)?
    /// Called when the system asks for playback to stop.
    var onPause: (@MainActor () -> Void)?
    /// Called when the system asks for a position.
    var onSeek: (@MainActor (Int64) -> Void)?
    var onSkipForward: (@MainActor () -> Void)?
    var onSkipBackward: (@MainActor () -> Void)?

    private var targets: [(command: MPRemoteCommand, target: Any)] = []

    /// Registers the remote commands. Calling it twice does nothing.
    func start() {
        guard targets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.isEnabled = true
        targets.append(
            (center.playCommand, center.playCommand.addTarget { [weak self] _ in
                Task { @MainActor in self?.onPlay?() }
                return .success
            })
        )

        center.pauseCommand.isEnabled = true
        targets.append(
            (center.pauseCommand, center.pauseCommand.addTarget { [weak self] _ in
                Task { @MainActor in self?.onPause?() }
                return .success
            })
        )

        center.changePlaybackPositionCommand.isEnabled = true
        targets.append(
            (center.changePlaybackPositionCommand, center.changePlaybackPositionCommand.addTarget { [weak self] event in
                guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                    return .commandFailed
                }
                let positionMs = Int64(positionEvent.positionTime * 1000)
                Task { @MainActor in self?.onSeek?(positionMs) }
                return .success
            })
        )

        center.skipForwardCommand.isEnabled = true
        targets.append(
            (center.skipForwardCommand, center.skipForwardCommand.addTarget { [weak self] _ in
                Task { @MainActor in self?.onSkipForward?() }
                return .success
            })
        )

        center.skipBackwardCommand.isEnabled = true
        targets.append(
            (center.skipBackwardCommand, center.skipBackwardCommand.addTarget { [weak self] _ in
                Task { @MainActor in self?.onSkipBackward?() }
                return .success
            })
        )
    }

    /// Reports what is playing to the system.
    func update(title: String?, positionMs: Int64, durationMs: Int64, isPlaying: Bool) {
        var info: [String: Any] = [
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0
        ]
        if let title {
            info[MPMediaItemPropertyTitle] = title
        }
        if durationMs > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Unregisters the remote commands and clears the system information.
    func stop() {
        for (command, target) in targets {
            command.removeTarget(target)
        }
        targets.removeAll()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
