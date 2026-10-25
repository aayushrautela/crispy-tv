import XCTest
@testable import CrispyKit

/// The selection rule mirrored from Android's `ProfileMenuRoute.loadActiveProfile`:
/// the stored id wins while it still names a profile, otherwise the first profile.
@MainActor
final class ActiveProfileSnapshotTests: XCTestCase {
    func testStoredIdWinsOverTheFirstProfile() {
        let profiles = [
            makeProfile(id: "p1", name: "First"),
            makeProfile(id: "p2", name: "Second"),
        ]

        let snapshot = ActiveProfileSnapshot.resolve(profiles: profiles, activeId: "p2")

        XCTAssertEqual(snapshot?.id, "p2")
        XCTAssertEqual(snapshot?.name, "Second")
    }

    func testUnknownIdFallsBackToTheFirstProfile() {
        let profiles = [
            makeProfile(id: "p1", name: "First"),
            makeProfile(id: "p2", name: "Second"),
        ]

        let snapshot = ActiveProfileSnapshot.resolve(profiles: profiles, activeId: "gone")

        XCTAssertEqual(snapshot?.id, "p1")
    }

    func testNoStoredIdSelectsTheFirstProfile() {
        let profiles = [makeProfile(id: "p1", name: "First")]

        let snapshot = ActiveProfileSnapshot.resolve(profiles: profiles, activeId: nil)

        XCTAssertEqual(snapshot?.id, "p1")
    }

    func testNoProfilesMeansNoSnapshot() {
        XCTAssertNil(ActiveProfileSnapshot.resolve(profiles: [], activeId: "p1"))
        XCTAssertNil(ActiveProfileSnapshot.resolve(profiles: [], activeId: nil))
    }

    func testNameAndAvatarPassThroughTrimmed() {
        let profiles = [
            makeProfile(id: "p1", name: "  Ada  ", avatarUrl: "  https://example.com/a.png?x=1  "),
        ]

        let snapshot = ActiveProfileSnapshot.resolve(profiles: profiles, activeId: "p1")

        XCTAssertEqual(snapshot?.name, "Ada")
        XCTAssertEqual(snapshot?.avatarUrl, "https://example.com/a.png?x=1")
    }

    func testBlankNameAndAvatarBecomeNil() {
        let profiles = [makeProfile(id: "p1", name: "   ", avatarUrl: " ")]

        let snapshot = ActiveProfileSnapshot.resolve(profiles: profiles, activeId: "p1")

        XCTAssertEqual(snapshot?.id, "p1")
        XCTAssertNil(snapshot?.name)
        XCTAssertNil(snapshot?.avatarUrl)
    }

    private func makeProfile(id: String, name: String, avatarUrl: String? = nil) -> BackendProfile {
        BackendProfile(id: id, name: name, avatarUrl: avatarUrl, isKids: false, sortOrder: 0)
    }
}
