import OpenCineAppKit
import OpenCineCore
import OpenCineHostedTestSupport
import OpenPocketViewCore
import XCTest
import OpenCineDJIApple

@testable import OpenPocketCine

@MainActor
final class DJIControlLifetimeTests: XCTestCase {
    /// Every camera access point join keeps its iOS configuration (#590), so forgetting the
    /// camera removes that configuration with the saved password.
    func testForgetRemovesTheCameraNetworkConfiguration() {
        let peer = ConnectionDonorPeer(cached: true)
        let session = peer.session()
        defer { session.savedCameras = { [] }; session.decoder.close() }
        let id = UUID().uuidString
        session.savedCameras = { [SavedCamera(
            id: id, integration: DJICameraSessionAdapter.integration, advertisedName: "OsmoPocket4P-EA4A",
            model: "Osmo Pocket 4 Pro",
            routes: [.init(key: "ap", kind: .cameraAccessPoint, network: "OsmoPocket4P-EA4A")])] }
        session.forget(cameraID: id)
        XCTAssertEqual(peer.trace, ["forget", "remove network OsmoPocket4P-EA4A"])
    }

    func testOwnedShellCloseTearsDownNativeLinkAndCoverDoesNot() async {
        let session = OpenPocketCine.CameraSession()
        defer { session.disconnect() }
        let driver = DjiAppleDatalink(port: 9011, tcpPoke: false, pairingToken: "")
        session.datalink = driver
        session.phase = .live
        session.status.shootingMode = 1
        session.status.recordingReported = true
        session.status.isRecording = true
        let common = session.control
        XCTAssertEqual(common.current.recording.value, .recording)
        let model = AppModel(session: session)
        let shell = model.shell
        shell.present(camera(common))
        let monitor = shell.monitor?.id
        shell.open(.settings)
        shell.open(.media)
        shell.dismissCover()
        XCTAssertEqual(shell.monitor?.id, monitor)
        XCTAssertTrue(session.datalink === driver)
        XCTAssertFalse(driver.isClosed)
        XCTAssertEqual(session.control.id, common.id)

        shell.close()
        shell.close()
        _ = await HostedCondition.wait { driver.isClosed }
        XCTAssertTrue(driver.isClosed)
        XCTAssertNil(session.datalink)
        XCTAssertEqual(session.phase, .idle)
        XCTAssertEqual(common.current.connection, .disconnected(.requested))
        XCTAssertEqual(
            common.current.recording.value, .recording, "Close cannot claim a stopped recording")
        XCTAssertNotEqual(
            session.control.id, common.id, "The next connection cannot reuse a closed seam")
    }

    func testLateCloseCannotCancelSuccessorWhileSavedCameraScanning() {
        let session = OpenPocketCine.CameraSession()
        defer { session.disconnect() }
        let driver = DjiAppleDatalink(port: 9012, tcpPoke: false, pairingToken: "")
        session.datalink = driver
        session.phase = .live
        let first = session.control
        let next = UUID()
        session.startScan(reconnect: next)
        let successor = session.control
        first.close(.requested)
        XCTAssertNotEqual(first.id, successor.id)
        XCTAssertEqual(first.current.connection, .disconnected(.replaced))
        XCTAssertEqual(session.connectionTargetID, next)
        XCTAssertEqual(session.phase, .scanning)
        XCTAssertEqual(session.control.id, successor.id)
    }

    func testBorrowedShellReturnReleasesViewWithoutClosingTileTransport() {
        let decoder = HevcDecoder()
        let view = OpenPocketCine.CameraSession(borrowing: decoder)
        let tileDriver = DjiAppleDatalink(port: 9013, tcpPoke: false, pairingToken: "")
        defer { tileDriver.close() }
        view.datalink = tileDriver
        let common = view.control
        let model = AppModel(session: view)
        let shell = model.shell
        var releases = 0
        shell.borrow(
            camera(common),
            release: {
                releases += 1
                view.releaseMultiview()
            })
        shell.open(.settings)
        shell.dismissCover()
        XCTAssertEqual(releases, 0)
        XCTAssertFalse(tileDriver.isClosed)
        shell.close()
        shell.close()
        XCTAssertEqual(releases, 1)
        XCTAssertFalse(tileDriver.isClosed)
        XCTAssertNil(view.datalink)
        XCTAssertTrue(view.decoder === decoder)
        XCTAssertEqual(common.current.connection, .disconnected(.requested))
    }

    func testNativePanelCompatibilityUsesOneCoverAndKeepsHostIdentity() {
        let model = AppModel()
        defer { model.session.disconnect() }
        model.shell.present(camera(model.session.control))
        let monitor = model.shell.monitor?.id
        model.shell.open(.settings)
        XCTAssertEqual(model.shell.cover, .settings)
        model.shell.open(.media)
        XCTAssertEqual(model.shell.cover, .media)
        XCTAssertEqual(model.shell.monitor?.id, monitor)
        model.shell.dismissCover()
        XCTAssertNil(model.shell.cover)
        XCTAssertEqual(model.shell.monitor?.id, monitor)
    }

    func testSceneSignalsAreDeduplicatedThroughActualAppShell() {
        let model = AppModel()
        defer { model.session.disconnect() }
        var signals: [Bool] = []
        model.shell.onSceneActivity = { signals.append($0) }
        model.shell.setSceneActive(false)
        model.shell.setSceneActive(false)
        model.shell.setSceneActive(true)
        model.shell.setSceneActive(true)
        XCTAssertEqual(signals, [false, true])
    }

    func testUnknownCameraCannotApproveZoomCycleOrCapture() {
        let model = AppModel()
        defer { model.session.disconnect() }
        XCTAssertFalse(model.commitZoomCycle(2))
        XCTAssertFalse(model.commitZoomCycle(.nan))
        XCTAssertNil(model.control.recordEditor())
        XCTAssertTrue(model.control.pending.isEmpty)
    }

    func testCommonFocusKeepsUnknownAndTrackedAfCOnTheNativeStrategyPath() {
        XCTAssertEqual(CaptureFocusAdmission.commonValue("AF-S", track: nil), .focusMode(.single))
        XCTAssertEqual(CaptureFocusAdmission.commonValue("AF-C", track: .default), .focusMode(.continuous))
        XCTAssertNil(CaptureFocusAdmission.commonValue("AF-C", track: nil))
        XCTAssertNil(CaptureFocusAdmission.commonValue("AF-C", track: .productShowcase))
        XCTAssertNil(CaptureFocusAdmission.commonValue("Showcase", track: .default))
    }

    #if DEBUG && targetEnvironment(simulator)
        func testExplicitSimulatedReviewWritesNeverReachNativeSession() async throws {
            let model = AppModel()
            defer { model.session.disconnect() }
            var declared = CameraStatus()
            declared.shootingMode = 1
            declared.isoIndex = .iso100
            declared.iso = 100
            declared.whiteBalance = .custom(kelvin: 5600, tint: 0)
            let body = CameraModel.resolve(modelId: 0x22, name: "SIMULATED Pocket")
            model.presentSimulatedControls(status: declared, body: body)
            let fixture = try XCTUnwrap(model.simulatedControlReview)
            let common = fixture.control
            XCTAssertEqual(common.current.connection, .ready)
            XCTAssertEqual(common.current.recording.value, .idle)
            let receipt = common.request(.set(.iso(.manual(800))))
            XCTAssertEqual(receipt.stage, .confirmed)
            XCTAssertEqual(fixture.writes, ["SIMULATED ISO"])
            XCTAssertEqual(common.current.value(of: .iso).value, .iso(.manual(800)))
            XCTAssertNil(model.session.datalink)
            XCTAssertNil(model.session.connectedCamera)
            XCTAssertNotEqual(model.session.control.current.connection, .ready)
            XCTAssertNil(model.session.control.current.recording.value)
            XCTAssertNotEqual(model.session.captureControlState?.reported.iso, 800,
                "Rendering a simulated report cannot approve or report native camera state")
            model.shell.close()
            _ = await HostedCondition.wait { common.current.connection != .ready }
            XCTAssertEqual(common.current.connection, .disconnected(.requested))
            XCTAssertNil(model.session.datalink)
        }
    #endif

    private func camera(_ session: DJICameraSessionAdapter) -> ConnectedCamera {
        ConnectedCamera(
            saved: OpenCineCore.SavedCamera(
                id: "simulated-dji",
                integration: DJICameraSessionAdapter.integration, advertisedName: "SIMULATED DJI",
                model: "SIMULATED"), session: session)
    }
}
