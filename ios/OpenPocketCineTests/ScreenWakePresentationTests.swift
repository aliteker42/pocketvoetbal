import Observation
import OpenCineAppKitApple
import SwiftUI
import UIKit
import XCTest

@testable import OpenPocketCine

/// SIMULATED foreground presentation using the actual shared root modifier.
@MainActor final class ScreenWakePresentationTests: XCTestCase {
    func testForegroundFullScreenCoverRetainsTheRootWakePreference() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previous = UIApplication.shared.isIdleTimerDisabled
        let priorKeyWindow = scene.keyWindow
        UIApplication.shared.isIdleTimerDisabled = false
        let rootReady = expectation(description: "Root modifier appears")
        let appeared = expectation(description: "Foreground full-screen cover appears")
        let dismissed = expectation(description: "Foreground full-screen cover closes")
        let state = PresentationState()
        let host = UIHostingController(rootView: Color.black
            .monitorScreenWake(enabled: true)
            .environment(\.scenePhase, .active)
            .onAppear { Task { @MainActor in await Task.yield(); rootReady.fulfill() } }
            .fullScreenCover(isPresented: Binding(get: { state.presented }, set: { state.presented = $0 }),
                onDismiss: { dismissed.fulfill() }) {
                    Color.white.onAppear { appeared.fulfill() }
                })
        let window = UIWindow(windowScene: scene)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            priorKeyWindow?.makeKey()
            UIApplication.shared.isIdleTimerDisabled = previous
        }
        await fulfillment(of: [rootReady], timeout: 5)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled, "Establish the awake root before presenting")
        state.presented = true
        await fulfillment(of: [appeared], timeout: 5)
        XCTAssertNotNil(host.presentedViewController)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled,
            "A foreground cover is still the same active operator scope")
        state.presented = false
        await fulfillment(of: [dismissed], timeout: 5)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
    }
}

@MainActor @Observable private final class PresentationState {
    var presented = false
}
