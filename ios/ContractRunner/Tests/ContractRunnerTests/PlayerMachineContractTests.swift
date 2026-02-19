import XCTest
@testable import ContractRunner

final class PlayerMachineContractTests: XCTestCase {
    func testPlayerMachineFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "player_machine")
        XCTAssertFalse(fixtures.isEmpty, "No player_machine fixtures found")

        for url in fixtures {
            let root = try FixtureLoader.readJSONObject(from: url)
            let nowMs = (root["now_ms"] as? Int64) ?? Int64((root["now_ms"] as? Int) ?? 0)
            let caseId = (root["case_id"] as? String) ?? "unknown"
            guard let steps = root["steps"] as? [[String: Any]] else {
                XCTFail("Missing steps in \(url.path)")
                continue
            }

            var state = initialPlayerState(sessionId: caseId, nowMs: nowMs)

            for (index, step) in steps.enumerated() {
                guard
                    let event = step["event"] as? [String: Any],
                    let eventType = event["type"] as? String,
                    let expect = step["expect"] as? [String: Any],
                    let expectedPhase = expect["phase"] as? String,
                    let expectedIntent = expect["intent"] as? String
                else {
                    XCTFail("Malformed step[\(index)] in \(url.path)")
                    continue
                }

                let tMs = (step["t_ms"] as? Int64) ?? Int64((step["t_ms"] as? Int) ?? 0)
                let action = actionFor(type: eventType, engine: event["engine"] as? String)
                state = reducePlayerState(state, action: action, nowMs: nowMs + tMs)

                XCTAssertEqual(state.phase.rawValue, expectedPhase, "Phase mismatch in \(url.path) step \(index)")
                XCTAssertEqual(state.intent.rawValue, expectedIntent, "Intent mismatch in \(url.path) step \(index)")

                if let expectedEngine = expect["engine"] as? String {
                    XCTAssertEqual(state.engine, expectedEngine, "Engine mismatch in \(url.path) step \(index)")
                }
            }
        }
    }

    private func actionFor(type: String, engine: String?) -> PlayerAction {
        switch type {
        case "OPEN_HTTP":
            return .openHttp(engine: engine)
        case "OPEN_TORRENT":
            return .openTorrent(engine: engine)
        case "TORRENT_STREAM_RESOLVED":
            return .torrentStreamResolved
        case "NATIVE_FIRST_FRAME":
            return .nativeFirstFrame
        case "NATIVE_READY":
            return .nativeReady
        case "NATIVE_BUFFERING":
            return .nativeBuffering
        case "NATIVE_END", "NATIVE_ENDED":
            return .nativeEnded
        case "NATIVE_CODEC_ERROR":
            return .nativeCodecError
        case "USER_INTENT_PLAY", "PLAY":
            return .userIntentPlay
        case "USER_INTENT_PAUSE", "PAUSE":
            return .userIntentPause
        default:
            XCTFail("Unsupported event type: \(type)")
            return .nativeEnded
        }
    }
}
