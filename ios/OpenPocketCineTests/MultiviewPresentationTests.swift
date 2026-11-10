import MonitorPresentation
import MonitorUI
import OpenCineAppKit
import Observation
import OpenCineCore
import OpenCineHostedTestSupport
import OpenPocketViewCore
import SwiftUI
import XCTest
import OpenCineDJIApple

@testable import OpenPocketCine

@MainActor final class MultiviewPresentationTests: XCTestCase {
    func testUnknownTelemetryDoesNotInventReadings() {
        let values = MultiviewTelemetryPresentation(settings: CameraStatus(), power: .unknown)
        XCTAssertEqual(values.iso, "—")
        XCTAssertEqual(values.shutter, "—")
        XCTAssertEqual(values.whiteBalance, "—")
        XCTAssertEqual(values.focus, "—")
        XCTAssertEqual(values.battery, "—")
        XCTAssertEqual(values.storage, "—")
        XCTAssertEqual(MultiviewTelemetryPresentation.recordingStatus(.init()), "—")
    }

    func testFullStorageAndZeroBatteryRemainRealReadings() {
        var status = CameraStatus()
        status.storageTotalMb = 128 * 1024
        status.storageFreeMb = 0
        let values = MultiviewTelemetryPresentation(
            settings: status, power: PowerStatus(battery: .init(0, origin: .cameraReported, at: nil)))
        XCTAssertEqual(values.battery, "0%")
        XCTAssertEqual(values.storage, "0 B")
    }

    /// A5-11: 60e9 free of 128e9 bytes, as the DJI wire carries them (MiB), reads as every surface.
    func testSharedStorageCaseReadsSixtyGigabytes() {
        var status = CameraStatus()
        status.storageFreeMb = 57_220
        status.storageTotalMb = 122_070
        XCTAssertEqual(MultiviewTelemetryPresentation(settings: status, power: .unknown).storage, "60 GB")
    }

    func testCommonPowerOverridesRetainedLegacyBatteryWithoutInventingUnknown() {
        var status = CameraStatus()
        status.batteryPercent = 99
        let gauge = PowerStatus(battery: .init(0.25, origin: .cameraReported, at: nil, precision: .quantized(step: 0.25)))
        XCTAssertEqual(MultiviewTelemetryPresentation(settings: status, power: gauge).battery, "1/4")
        XCTAssertEqual(MultiviewTelemetryPresentation(settings: status, power: .unknown).battery, "\u{2014}")
        let zero = PowerStatus(battery: .init(0, origin: .cameraReported, at: nil))
        XCTAssertEqual(MultiviewTelemetryPresentation(settings: status, power: zero).battery, "0%")
    }

    func testGroupResultAndRecoveryAreVisibleWithoutInventingConnectedCameras() {
        let session = MultiviewUIReview.makeSession(count: 3, recovering: true)
        XCTAssertEqual(
            MultiviewTelemetryPresentation.sessionSummary(session), "2 live · 1 reconnecting")
        XCTAssertEqual(MultiviewTelemetryPresentation.recordingStatus(session.tiles[1]), "HOLD")
        session.tiles[1].recovering = false
        session.tiles[1].hasPicture = false
        XCTAssertEqual(MultiviewTelemetryPresentation.sessionSummary(session), "2 live · 3 cameras")
        session.groupRecordingNote = "1 of 2 confirmed · check camera tiles"
        XCTAssertEqual(
            MultiviewTelemetryPresentation.sessionSummary(session), session.groupRecordingNote)
        XCTAssertEqual(
            MultiviewTelemetryPresentation.recordingStatus(session.tiles[0]), "REC 00:24")
        XCTAssertNil(session.tiles[2].timecodeReadout)
    }

    func testLayoutNamePreservesStoredGridPreference() {
        XCTAssertEqual(MultiviewLayout(rawValue: "2 × 2 grid"), .grid)
        XCTAssertEqual(MultiviewLayout.grid.displayName, "Grid")
    }

    func testSuccessfulGroupNoteDoesNotHideLaterCameraRecordingChanges() {
        let session = MultiviewUIReview.makeSession(count: 2)
        session.groupRecordingNote = "Recording on 2 cameras"
        XCTAssertEqual(MultiviewTelemetryPresentation.sessionSummary(session), "1 of 2 recording")
        session.tiles[0].recordingObservation = (false, Date())
        XCTAssertEqual(
            MultiviewTelemetryPresentation.sessionSummary(session), "2 cameras connected")
        session.groupRecordingNote = "Recording stopped"
        session.tiles[1].recordingObservation = (true, Date())
        XCTAssertEqual(MultiviewTelemetryPresentation.sessionSummary(session), "1 of 2 recording")
    }

    func testRecordingConfirmationRejectsChangedCameraStateAndMembership() {
        let session = MultiviewUIReview.makeSession(count: 2)
        for tile in session.recordingTiles { link(tile) }
        let original = MultiviewRecordContext(session: session)
        XCTAssertTrue(original.canConfirm)
        XCTAssertTrue(original.stopping)
        session.tiles[1].recordingObservation = (true, Date())
        XCTAssertNotEqual(
            original, MultiviewRecordContext(session: session),
            "A partial group change invalidates confirmation even when Stop remains the group action"
        )
        session.tiles[1].recordingObservation = (false, Date())
        XCTAssertEqual(
            original, MultiviewRecordContext(session: session),
            "A newer telemetry timestamp alone does not change the authorized state")
        session.tiles[1].settings.shootingMode = Int(ShootingMode.photo.rawValue)
        XCTAssertNotEqual(original, MultiviewRecordContext(session: session))
        session.tiles[1].camera = nil
        XCTAssertNotEqual(original, MultiviewRecordContext(session: session))
        session.closing = true
        XCTAssertFalse(MultiviewRecordContext(session: session).canConfirm)
    }

    func testIndividualRecordingConfirmationRejectsRecoveryAndUnavailableStatus() {
        let session = MultiviewUIReview.makeSession(count: 1)
        let tile = session.tiles[0]
        XCTAssertFalse(MultiviewRecordContext(session: session, tile: tile).canConfirm)
        link(tile)
        XCTAssertTrue(MultiviewRecordContext(session: session, tile: tile).canConfirm)
        tile.recovering = true
        XCTAssertFalse(MultiviewRecordContext(session: session, tile: tile).canConfirm)
    }

    /// Reordering a SwiftUI tile must preserve the actual UIKit video host as well
    /// as the decoder. This catches remounts that pure rectangle tests cannot.
    func testLayoutAndSelectionKeepAllFourNativeVideoHosts() async throws {
        let session = MultiviewUIReview.makeSession(count: 4)
        let model = AppModel()
        let view = MultiviewView(session: session, startsSession: false, reviewPictures: true)
            .environment(model)
        let controller = UIHostingController(rootView: view)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        _ = await HostedCondition.wait {
            controller.view.layoutIfNeeded()
            return displayHosts(in: controller.view).count == 4
        }
        let originalHosts = displayHosts(in: controller.view)
        XCTAssertEqual(originalHosts.count, 4)
        let identities = Set(originalHosts.map(ObjectIdentifier.init))
        for (layout, selected) in [(MultiviewLayout.grid, 2), (.centerStage, 3), (.grid, 0)] {
            session.layout = layout
            session.focusedIndex = selected
            controller.view.setNeedsLayout()
            controller.view.layoutIfNeeded()
            // Negative window: no display host may be replaced within it.
            try await Task.sleep(for: .milliseconds(150))
            XCTAssertEqual(
                Set(displayHosts(in: controller.view).map(ObjectIdentifier.init)), identities)
            XCTAssertTrue(session.tiles.allSatisfy { $0.driver == nil && $0.liveModel == nil })
        }
    }

    func testNativeStripKeepsEachCameraHostThroughScrollPromotionAndRotation() async throws {
        let session = MultiviewUIReview.makeSession(count: 4)
        let controller = MultiviewStageController()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 956, height: 440))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        let roots = session.tiles.map { tile in
            AnyView(MultiviewVideoLayer(tile: tile, hdrDisplay: false))
        }
        func owners() throws -> [UUID: ObjectIdentifier] {
            try Dictionary(
                uniqueKeysWithValues: session.tiles.map { tile in
                    let picture = try XCTUnwrap(
                        tile.decoder.displayLayer.superlayer?.delegate as? UIView)
                    let host = try XCTUnwrap(picture.superview as? DisplayLayerView)
                    XCTAssertTrue(host.ownsDisplayLayer)
                    return (tile.id, ObjectIdentifier(host))
                })
        }
        let first = MultiviewPresentationLayout(
            width: 956, height: 440,
            safeArea: .init(leading: 59, bottom: 21),
            arrangement: .centerStage, selected: 0)
        controller.update(layout: first, roots: roots)
        _ = await HostedCondition.wait {
            controller.view.layoutIfNeeded()
            return controller.view.subviews.compactMap { $0 as? UIScrollView }.first.map {
                $0.contentSize.height > $0.bounds.height
            } == true
        }
        let original = try owners()
        let strip = try XCTUnwrap(controller.view.subviews.compactMap { $0 as? UIScrollView }.first)
        XCTAssertGreaterThan(strip.contentSize.height, strip.bounds.height)
        strip.contentOffset.y = strip.contentSize.height - strip.bounds.height
        let shifted = try XCTUnwrap(controller.placement(1))
        XCTAssertLessThan(shifted.frame.minY, strip.frame.minY)
        XCTAssertEqual(shifted.clip.minY, strip.frame.minY, accuracy: 0.01)
        XCTAssertEqual(try owners(), original)
        for (width, height, selected, arrangement) in [
            (956.0, 440.0, 3, MultiviewPresentationLayout.Arrangement.centerStage),
            (956, 440, 1, .grid), (440, 956, 1, .centerStage),
            (956, 440, 2, .centerStage),
        ] {
            window.frame.size = CGSize(width: width, height: height)
            controller.view.frame = window.bounds
            let layout = MultiviewPresentationLayout(
                width: width, height: height,
                safeArea: .init(bottom: 21, trailing: 59),
                arrangement: arrangement, selected: selected)
            controller.update(layout: layout, roots: roots)
            controller.view.layoutIfNeeded()
            // Negative window: no camera host may change owner within it.
            try await Task.sleep(for: .milliseconds(100))
            XCTAssertEqual(try owners(), original)
            XCTAssertEqual(displayHosts(in: controller.view).count, 4)
            XCTAssertEqual(strip.contentOffset.y, 0, accuracy: 0.01)
        }
    }

    /// SIMULATED cameras, actual session constructors and retained UIKit video owners.
    func testRepeatedViewConstructionCreatesOneActualSessionAndKeepsNativeHosts() async throws {
        let revision = ConstructorRevision()
        let recorder = ConstructorRecorder()
        let model = AppModel()
        let controller = UIHostingController(rootView:
            ConstructorHost(revision: revision, recorder: recorder).environment(model))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        func settle(_ predicate: @escaping @MainActor () -> Bool) async {
            let expected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                MainActor.assumeIsolated {
                    controller.view.layoutIfNeeded()
                    return predicate()
                }
            }, object: nil)
            let result = await XCTWaiter.fulfillment(of: [expected], timeout: 5)
            XCTAssertEqual(result, .completed)
        }
        await settle { recorder.rendered == 0 && displayHosts(in: controller.view).count == 4 }
        let hosts = Set(displayHosts(in: controller.view).map(ObjectIdentifier.init))
        XCTAssertEqual(recorder.constructions, 1)
        for next in 1...3 {
            revision.value = next
            await settle { recorder.rendered == next }
            XCTAssertEqual(recorder.constructions, 1, "Transient View initializers cannot allocate discarded camera owners")
            XCTAssertEqual(Set(displayHosts(in: controller.view).map(ObjectIdentifier.init)), hosts)
        }
    }

    @Observable final class ConstructorRevision { var value = 0 }
    private final class ConstructorRecorder {
        var constructions = 0
        var rendered = -1
    }
    private struct ConstructorHost: View {
        let revision: ConstructorRevision
        let recorder: ConstructorRecorder
        var body: some View {
            MultiviewView(startsSession: false, reviewPictures: true, makeSession: {
                recorder.constructions += 1
                return MultiviewUIReview.makeSession(count: 4)
            })
            .onAppear { recorder.rendered = revision.value }
            .onChange(of: revision.value) { _, value in recorder.rendered = value }
        }
    }

    /// A live control link with a fresh record report, as `openPreview` and the 1 s monitor leave it.
    private func link(_ tile: MultiviewSession.Tile) {
        tile.driver = DjiAppleDatalink(port: 9020, tcpPoke: false, pairingToken: "")
        tile.controlHost = "192.0.2.20"
        tile.recordingAvailable = true
    }
}
