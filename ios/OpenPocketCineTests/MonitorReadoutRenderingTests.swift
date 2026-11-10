import OpenCineHostedTestSupport
import SwiftUI
import XCTest

@testable import MonitorUI
@testable import OpenPocketCine

/// The status chrome on this app's model. The drum and shadow cases are in MonitorUITests.
@MainActor
final class MonitorReadoutRenderingTests: XCTestCase {
    func testPortraitStorageRendersOutsideTheRasterizedStatusBand() async throws {
        let session = OpenPocketCine.CameraSession(borrowing: HevcDecoder())
        let model = AppModel(session: session)
        session.status.storageFreeMb = 102_400
        session.status.storageTotalMb = 131_072
        model.control.bind(session.control)
        defer { model.control.unbind() }
        _ = await HostedCondition.wait { model.control.snapshot?.storage.value != nil }
        let snapshot = try XCTUnwrap(model.control.snapshot)
        XCTAssertEqual(
            snapshot.storage.value?.first?.freeBytes.value, 107_374_182_400,
            "The native fixture publishes its real MiB readback through the common storage observation"
        )
        let layout = LiveMonitorLayout.fieldMonitor(
            size: CGSize(width: 440, height: 956),
            safeArea: EdgeInsets(top: 62, leading: 0, bottom: 34, trailing: 0),
            sourceAspect: 16 / 9, fill: false, showsValues: true, showsBottomBars: true)
        let renderer = ImageRenderer(
            content: ZStack(alignment: .topLeading) {
                Color.black
                FieldMonitorStatusChrome(menu: .constant(nil), layout: layout)
                    .frame(width: layout.topDeck.width)
                    .position(x: layout.topDeck.midX, y: layout.topDeck.midY)
            }
            .frame(width: 440, height: 956)
            .environment(model))
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.cgImage)
        let attachment = XCTAttachment(image: UIImage(cgImage: image))
        attachment.name = "SIMULATED portrait storage above the status band"
        attachment.lifetime = .keepAlways
        add(attachment)
        let bytes = try rgba(image)
        // The compact header returns to the corner position requested by the operator.
        // Derive the crop from the painted telemetry bounds, retaining the visibility check.
        // The painted band rides the non-tablet portrait headerLift (6 points,
        // FieldMonitorLayout) above the retained gauge corner, so the anchor follows the
        // shared suite's relative bounds (MonitorPortraitHeaderClearanceTests) instead of
        // the pre-lift absolute corner constant.
        let headerTop = Int(layout.battery.minY - 13)
        let headerBottom = headerTop + 48
        XCTAssertLessThanOrEqual(
            headerTop, Int(layout.presentation!.gauges.y),
            "Storage stays inside the restored corner band, lifted above the gauge corner"
        )
        // White glyph pixels per row of the storage crop. The crop is 48 points tall, so its
        // lower rows reach into the status band, where the shared standby/recording chip
        // ("Standby / recording chip on the status bar", MonitorDisplayChrome) paints white
        // once a camera is connected. Only the first contiguous run of painted rows is the
        // storage readout; the band's own glyphs below the gap belong to the band.
        let paintedPerRow = (headerTop..<headerBottom).map { y in
            (10..<180).filter { x in
                let index = (y * image.width + x) * 4
                return bytes[index] > 220 && bytes[index + 1] > 220 && bytes[index + 2] > 220
            }.count
        }
        let storageFirst = try XCTUnwrap(
            paintedPerRow.firstIndex { $0 > 0 }, "Storage must paint in the safe header")
        let storageRows = paintedPerRow[storageFirst...].prefix { $0 > 0 }
        let lowestStorageRow = headerTop + storageFirst + storageRows.count - 1
        // The 48-point touch target may extend below the band; painted glyphs must clear it.
        XCTAssertLessThan(lowestStorageRow, Int(layout.topDeck.minY))
        XCTAssertGreaterThan(
            storageRows.reduce(0, +), 100,
            "Storage must remain visible in the safe header, above the lower status band's raster bounds"
        )
    }

    private func rgba(_ image: CGImage) throws -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let context = try XCTUnwrap(
            CGContext(
                data: &bytes, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: image.width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return bytes
    }
}
