import OpenCineAppKit
import OpenCineCameraControls
import OpenCineCore
import OpenCineDJIApple
import OpenPocketViewCore
import XCTest

@testable import OpenPocketCine

#if DEBUG && targetEnvironment(simulator)
/// SIMULATED actual common reports and a contradictory legacy presentation.
@MainActor final class PocketCommonReadoutTests: XCTestCase {
    func testEmulatedAngleCannotDisplayRetainedLegacyValuesWithoutTheCommonSource() {
        let model = AppModel()
        let priorAngle = model.shutterUsesAngle
        defer { model.shutterUsesAngle = priorAngle; model.shell.close(); model.session.disconnect() }
        model.session = CameraSession(borrowing: HevcDecoder())
        var retained = CameraStatus()
        retained.shootingMode = 1
        retained.expoMode = .manual
        retained.shutterDenom = 50
        retained.fps = 25
        model.session.status = retained
        model.shutterUsesAngle = true
        model.control.unbind()
        XCTAssertNil(model.control.snapshot)
        XCTAssertEqual(model.session.status.shutterDenom, 50, "The legacy facade deliberately retains its old report")
        let input = MonitorOperatorCaptureInputs<CaptureSheet, Int, MonitorOperatorLegacyCaptureIdentity>
            .captureSheetHost(model)
        XCTAssertEqual(input.readouts.first { $0.id == .shutter }?.value, "--",
            "The emulated angle requires the current common shutter and recording cadence")
    }

    func testCaptureStripRetainsTheReportedNativeFocusStrategy() async throws {
        var reported = CameraStatus()
        reported.shootingMode = 1
        reported.expoMode = .manual
        reported.focusMode = .continuous
        reported.focusTrack = .productShowcase
        let model = AppModel()
        defer { model.shell.close(); model.session.disconnect() }
        model.session = CameraSession(borrowing: HevcDecoder())
        model.session.updateMultiview(camera: DjiAppleSighting(id: UUID(), name: "SIMULATED Pocket focus",
            model: CameraModel.resolve(modelId: 0x22, name: "SIMULATED Pocket focus"), modelId: 0x22),
            driver: nil, status: reported)
        model.control.bind(model.session.control)
        XCTAssertTrue(model.captureCapabilities.focusMode, "The native fixture actually declares focus")
        for _ in 0..<20_000 {
            if model.control.snapshot?.value(of: .focusMode).value == .focusMode(.continuous) { break }
            await Task.yield()
        }
        XCTAssertEqual(model.control.snapshot?.value(of: .focusMode).value, .focusMode(.continuous))
        let input = MonitorOperatorCaptureInputs<CaptureSheet, Int, MonitorOperatorLegacyCaptureIdentity>
            .captureSheetHost(model)
        XCTAssertEqual(input.readouts.first { $0.id == .focus }?.value, "Showcase")
        XCTAssertEqual(input.nativeQuick(.focus)?.snapshot.selection, "Showcase")
        XCTAssertEqual(input.nativeQuick(.focus)?.snapshot.options, model.focusOptions.map(\.chip))
    }

    func testCaptureStripReadsTheCommonReportInsteadOfLegacyWirePresentation() async throws {
        var reported = CameraStatus()
        reported.shootingMode = 1
        reported.expoMode = .manual
        reported.isoIndex = .iso400
        reported.iso = 400
        reported.shutterDenom = 50
        reported.availableIsoIndices = [.iso200, .iso400, .iso800]
        let model = AppModel()
        defer { model.shell.close(); model.session.disconnect() }
        model.presentSimulatedControls(status: reported,
            body: CameraModel.resolve(modelId: 0x22, name: "SIMULATED"))
        for _ in 0..<20_000 {
            if model.control.snapshot?.value(of: .iso).value == .iso(.manual(400)) { break }
            await Task.yield()
        }
        XCTAssertEqual(model.control.snapshot?.value(of: .iso).value, .iso(.manual(400)))
        var legacy = reported
        legacy.isoIndex = .iso800
        legacy.iso = 800
        model.session.status = legacy
        let input = MonitorOperatorCaptureInputs<CaptureSheet, Int, MonitorOperatorLegacyCaptureIdentity>
            .captureSheetHost(model)
        XCTAssertEqual(input.readouts.first { $0.id == .iso }?.value, "400")
        XCTAssertEqual(model.session.status.iso, 800, "The fixture really contains contradictory legacy presentation")
        XCTAssertFalse(model.captureStripShowsPendingEdits, "Pocket retains report-until-confirmation policy")
    }
}
#endif
