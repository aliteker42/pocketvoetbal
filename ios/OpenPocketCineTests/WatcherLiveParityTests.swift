import MetalKit
import MonitorUI
import OpenCineAppKitApple
import OpenCineApple
import OpenCineCameraControls
import OpenCineRelay
import OpenPocketViewCore
import XCTest

@testable import OpenPocketCine

/// The watcher screen and the live assist source moved to OpenCineCameraControls. What stayed
/// app-specific is a typed brand value; these pin it and the shared source against the app's
/// original expressions, frozen here.
@MainActor
final class WatcherLiveParityTests: XCTestCase {
    func testLookProfileMatchesTheAppForEveryRelayedBody() {
        let model = AppModel()
        for nano in [nil, false, true] as [Bool?] {
            for name in [nil, "", "Osmo Pocket 3", "Osmo Nano", "Osmo Pocket 4P"] as [String?] {
                let state = WatcherRelayState(cameraModel: name, isNano: nano)
                let frozen = DJICameraLUTProfile.make(
                    family: state.isNano == true ? .nano : .pocket, cameraName: state.cameraModel)
                model.relayClient.state = state
                XCTAssertEqual(model.watcherCameraFamily, state.isNano == true ? .nano : .pocket)
                XCTAssertEqual(model.watcherLookProfile, frozen, "\(String(describing: nano)) \(String(describing: name))")
            }
        }
    }

    func testZoomChoiceTitlesMatchTheApp() {
        for choices in [[100], [100, 200, 300], [100, 150, 200, 400], [50, 100, 1_200], [100, 1_000]] {
            XCTAssertEqual(
                WatcherLiveView.zoomChoiceTitles(choices),
                choices.map { CamFov.displayLabel(factor: Double($0) / 100) }, "\(choices)")
        }
    }

    func testLiveAssistSourceReadsTheMonitoredSourceAsTheAppDid() {
        let model = AppModel()
        let source = model.liveAssistSource
        let frozenTransfer = model.monitorTransfer ?? model.monitorColorMode.map(MonitorTransfer.init) ?? .rec709
        XCTAssertEqual(source.readTransfer(), frozenTransfer)
        for transfer in [MonitorTransfer.rec709, .dlog2, .dlogm] {
            XCTAssertEqual(
                source.readClipByte(transfer),
                model.monitorSamples.exposureCeiling.clipByte(transfer: transfer))
        }
    }

    func testScopeDisplayMatchesTheAppPanel() {
        defer { LiveHDRDisplay.app.setEnabled(false) }
        for hdr in [false, true] {
            LiveHDRDisplay.app.setEnabled(hdr)
            let options = PocketLivePicture.scopeDisplay(MTKView())
            XCTAssertEqual(options.hdr, LiveHDRDisplay.app.isEnabled)
            XCTAssertEqual(options.gain, LiveHDRDisplay.app.presentGain)
        }
    }
}
