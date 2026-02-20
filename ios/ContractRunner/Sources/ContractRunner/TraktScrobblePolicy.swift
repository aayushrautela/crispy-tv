import Foundation

public enum TraktScrobbleStage: String {
    case start
    case stop
}

public struct TraktScrobbleDecision: Equatable {
    public let endpoint: String
    public let marksWatched: Bool
    public let updatesPlaybackProgress: Bool

    public init(endpoint: String, marksWatched: Bool, updatesPlaybackProgress: Bool) {
        self.endpoint = endpoint
        self.marksWatched = marksWatched
        self.updatesPlaybackProgress = updatesPlaybackProgress
    }
}

public func decideTraktScrobble(stage: TraktScrobbleStage, progressPercent: Double) -> TraktScrobbleDecision {
    let progress = min(max(progressPercent, 0), 100)
    if stage == .start {
        return TraktScrobbleDecision(
            endpoint: "/scrobble/start",
            marksWatched: false,
            updatesPlaybackProgress: false
        )
    }

    let marksWatched = progress >= 80
    return TraktScrobbleDecision(
        endpoint: "/scrobble/stop",
        marksWatched: marksWatched,
        updatesPlaybackProgress: !marksWatched
    )
}
