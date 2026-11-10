import UIKit
import XCTest

@testable import OpenPocketCine

final class SettingsSharedOwnerTests: XCTestCase {
    @MainActor func testClosingMultiviewDoesNotReleaseTheRootWakePreference() {
        let previous = UIApplication.shared.isIdleTimerDisabled
        defer { UIApplication.shared.isIdleTimerDisabled = previous }
        // The root already applied the operator's true preference. No camera or scan is started.
        UIApplication.shared.isIdleTimerDisabled = true
        let session = MultiviewSession(saveStage: { _ in true }, loadNetwork: { _, _ in nil })
        session.stop()
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled,
            "Closing a child surface must not release the root's active wake preference")
    }
}
