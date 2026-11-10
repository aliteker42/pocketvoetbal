import AVFoundation
import CoreVideo
import Foundation
import OpenCineApple
import XCTest

@testable import OpenPocketCine

/// SIMULATED generated clip exercises the actual shared AVAssetReader and native source owner.
@MainActor
final class SimulatorVideoFeedParityTests: XCTestCase {
    func testRealReaderLoopsAndTerminalCloseRetiresItsCapturedSource() async throws {
        let fixture = try await PlaybackOutputFixture.make(in: self)
        defer { fixture.close() }
        let url = try XCTUnwrap(fixture.item.asset as? AVURLAsset).url
        let input = try Data(contentsOf: url)
        let attachment = XCTAttachment(data: input, uniformTypeIdentifier: "com.apple.quicktime-movie")
        attachment.name = "SIMULATED-reader-input.mov"
        attachment.lifetime = .keepAlways
        add(attachment)
        let decoder = MonitorLiveVideoDecoder(configuration: .init(assists: LiveAssistEngine.configuration),
            simulator: .init(clipURL: { url }, transfer: .rec709))
        defer { decoder.close() }
        let source = try XCTUnwrap(decoder.sourceToken)
        let delivered = expectation(description: "63 frames cross the 60-frame clip boundary")
        delivered.expectedFulfillmentCount = 63
        var received = 0
        decoder.onSourceFrame = { lease in
            XCTAssertEqual(decoder.sourceToken, source)
            guard let buffer = lease.pixelBuffer else {
                XCTFail("The native callback must lend a live frame")
                return
            }
            XCTAssertEqual(CVPixelBufferGetPixelFormatType(buffer),
                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
            received += 1
            if received <= 63 { delivered.fulfill() }
            if received == 63 { decoder.close() }
        }
        decoder.startSimulatorSampleIfNeeded()
        await fulfillment(of: [delivered], timeout: 30)
        XCTAssertEqual(received, 63)
        XCTAssertNil(decoder.sourceToken)
        decoder.reset()
        XCTAssertNil(decoder.sourceToken)
    }
}
