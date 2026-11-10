import OpenCineApple
import OpenCineMedia
import XCTest

@testable import OpenPocketCine

/// SIMULATED host callbacks exercise the actual app decoder and its publication lease.
@MainActor
final class HevcDecoderParityTests: XCTestCase {
    func testKnownCalibrationSeedsTheAttachedLiveSource() {
        let decoder = HevcDecoder()
        defer { decoder.close() }
        XCTAssertTrue(decoder.demand.assists.allowsTransferInference)
        let samples = LiveFrameSampleBus()
        decoder.bindSourcePublication(samples)
        decoder.sourceCalibrationProvider = { 800 }

        XCTAssertTrue(decoder.refreshSourceCalibration())
        XCTAssertEqual(samples.exposureCeiling.resolvedISO(), 800)
    }

    func testCalibrationProviderResetCannotReassignItsReportToTheSuccessor() {
        let decoder = HevcDecoder()
        defer { decoder.close() }
        let samples = LiveFrameSampleBus()
        decoder.bindSourcePublication(samples)
        let original = decoder.sourceToken
        decoder.sourceCalibrationProvider = { [weak decoder] in
            decoder?.sourceCalibrationProvider = nil
            decoder?.reset()
            return 800
        }

        XCTAssertFalse(decoder.refreshSourceCalibration())
        XCTAssertNotEqual(decoder.sourceToken, original)
        XCTAssertEqual(samples.exposureCeiling.resolvedISO(), 1600)
    }

    func testEffectsAndProviderPreserveIndependentPreviewDemand() {
        let decoder = HevcDecoder()
        defer { decoder.close() }
        decoder.needsWatchPreview = true
        var effects = OpenCineMedia.MonitorFrameEffects()
        effects.histogram = true
        effects.faceAF = true
        decoder.effects = effects
        XCTAssertTrue(decoder.demand.assists.histogram)
        XCTAssertTrue(decoder.demand.decodedSource)
        XCTAssertTrue(decoder.demand.externalPreview)

        effects.histogram = false
        effects.faceAF = false
        effects.inspectorSample = true
        decoder.effectsProvider = { effects }
        let supplied = decoder.demandProvider?()
        XCTAssertEqual(decoder.effects, effects)
        XCTAssertEqual(supplied?.assists.histogram, false)
        XCTAssertEqual(supplied?.decodedSource, true)
        XCTAssertEqual(supplied?.externalPreview, true)
        decoder.needsWatchPreview = false
        XCTAssertTrue(decoder.demand.decodedSource)
        XCTAssertFalse(decoder.demand.externalPreview)
    }

    func testMissingColorReportPreservesTheAcceptedTransfer() {
        let decoder = HevcDecoder()
        defer { decoder.close() }
        decoder.incomingColorMode = .normal
        XCTAssertEqual(decoder.incomingTransfer, .rec709)
        decoder.incomingColorMode = nil
        XCTAssertEqual(decoder.incomingTransfer, .rec709)
        decoder.incomingColorMode = .hdr
        XCTAssertEqual(decoder.incomingTransfer, .hdr)
    }

    func testTerminalCloseClearsHostProvidersAndCannotReopen() {
        let decoder = HevcDecoder()
        decoder.effectsProvider = { .init() }
        decoder.sourceCalibrationProvider = { 800 }
        decoder.close()
        XCTAssertNil(decoder.effectsProvider)
        XCTAssertNil(decoder.sourceCalibrationProvider)
        XCTAssertNil(decoder.sourceToken)
        decoder.reset()
        decoder.startSimulatorSampleIfNeeded()
        XCTAssertNil(decoder.sourceToken)
    }
}
