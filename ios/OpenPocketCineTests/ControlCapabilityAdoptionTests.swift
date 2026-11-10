import MonitorPresentation
import OpenCineAppKit
import OpenCineCameraVocabulary
import OpenCineCameraControls
import OpenCineCore
import OpenCineDJIApple
import OpenPocketViewCore
import XCTest

@testable import OpenPocketCine

/// What Pocket offers per body, read from the one control capability model the DJI integration
/// provides (the app sets no capability literal). Pins the capture strip each body shows today.
@MainActor
final class ControlCapabilityAdoptionTests: XCTestCase {
    func testAudioAdmissionUsesTypedSupport() {
        let declarations: [ControlCapability<NoOffer>] = [.unknown, .unsupported, .supported(),
            .supported(access: .readOnly)]
        for audio in declarations {
            var controls = ControlCapabilities()
            controls.audio = audio
            XCTAssertEqual(CaptureSheetCapabilities(controls: controls).audio, audio.isOffered)
        }
    }

    private func model(_ id: Int?, _ name: String) -> AppModel {
        let model = AppModel()
        model.session = CameraSession(borrowing: HevcDecoder())
        if let id {
            var status = CameraStatus()
            status.shootingMode = 1
            status.expoMode = .manual
            model.session.updateMultiview(
                camera: DjiAppleSighting(id: UUID(), name: name,
                    model: CameraModel.resolve(modelId: id, name: name), modelId: id),
                driver: nil, status: status)
        }
        model.control.bind(model.session.control)
        return model
    }

    func testTheCaptureSheetGatesAreAViewOfTheModel() {
        for (id, name) in [(0x22, "OsmoPocket4P-Test"), (0x20, "OsmoPocket3-Test"), (0x19, "OsmoNano-Test"),
            (0x18, "OsmoAction6-Test")] {
            let model = model(id, name)
            defer { model.shell.close(); model.session.disconnect() }
            XCTAssertEqual(model.captureCapabilities, CaptureSheetCapabilities(controls: model.controlCapabilities), name)
        }
    }

    /// The tiles each body shows today: focus on the Pocket family, the iris strategy on Action 6,
    /// audio on every known body, never face metering. Behavior audit PIA-202/203/240: every
    /// identified body offers the Auto ISO ceiling and Auto Native ISO, as the standalone app did;
    /// with no body both stay unknown, so not offered.
    func testEachBodyShowsTodaysCaptureStrip() {
        let common: [CaptureSheet] = [.iso, .shutter, .exposure, .wb]
        let expected: [(Int?, String, [CaptureSheet])] = [
            (0x22, "OsmoPocket4P-Test", common + [.focus, .audio]),
            (0x20, "OsmoPocket3-Test", common + [.focus, .audio]),
            (0x19, "OsmoNano-Test", common + [.audio]),
            (0x18, "OsmoAction6-Test", common + [.aperture, .audio]),
            (nil, "no body", common),
        ]
        for (id, name, tiles) in expected {
            let model = model(id, name)
            defer { model.shell.close(); model.session.disconnect() }
            let input = MonitorOperatorCaptureInputs<CaptureSheet, Int, MonitorOperatorLegacyCaptureIdentity>
                .captureSheetHost(model)
            XCTAssertEqual(input.readouts.map(\.id), tiles, name)
            XCTAssertFalse(model.captureCapabilities.faceMetering, name)
            XCTAssertEqual(model.captureCapabilities.nativeIsoHop, id != nil, name)
            XCTAssertEqual(model.captureCapabilities.isoLimit, id != nil, name)
            // The shared pre-connection rule: no identified body, no AUDIO pin or tile.
            if id == nil { XCTAssertFalse(OsmoMonitorPresentation.capabilities(model.session).audio) }
        }
    }
}
