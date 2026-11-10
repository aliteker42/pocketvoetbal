import Foundation
import OpenCineAppKit
import OpenCineDJIApple
import OpenCineMedia
import OpenPocketViewCore
import XCTest

@testable import OpenPocketCine

private final class NotesFileManager: FileManager, @unchecked Sendable {
    let support = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    override func urls(for directory: FileManager.SearchPathDirectory, in domainMask: FileManager.SearchPathDomainMask) -> [URL] {
        directory == .applicationSupportDirectory ? [support] : super.urls(for: directory, in: domainMask)
    }
}

/// Behavior-audit regressions: the shared catalog shows only the library's note line, so the
/// app's media notes and copy have to reach it (PIB-082, PIB-083).
@MainActor final class PocketMediaNotesRegressionTests: XCTestCase {
    func testHostNoteReachesTheSharedGalleryNoteLine() {
        let manager = NotesFileManager(), media = CameraMedia(fileManager: manager)
        let session = CameraSession(borrowing: HevcDecoder(), cameraMedia: media)
        defer { session.disconnect(); session.decoder.close(); try? FileManager.default.removeItem(at: manager.support) }
        let library = session.mediaLibrary
        session.mediaNote = MediaOperatorCopy.deleteFailed
        XCTAssertEqual(library.note, MediaOperatorCopy.deleteFailed)
    }

    func testGalleryCopyNamesPlaybackAndTheCameraGallery() {
        XCTAssertEqual(CameraSession.mediaLibraryCopy.openFailed, MediaOperatorCopy.playbackFailed)
        XCTAssertEqual(CameraSession.mediaLibraryCopy.sourceHeld, MediaOperatorCopy.browsingOnCamera)
        XCTAssertEqual(CameraSession.mediaLibraryCopy.empty, MediaOperatorCopy.noClips)
    }

    /// SIMULATED connection and clock drive the real camera-gallery note producer.
    func testCameraGalleryProducerPublishesToTheSharedGalleryNote() async throws {
        let manager = NotesFileManager()
        let media = CameraMedia(fileManager: manager)
        let peer = ConnectionDonorPeer(cached: true)
        let session = CameraSession(cameraMedia: media, heartbeatTiming: peer.heartbeatTiming)
        defer {
            session.disconnect()
            session.decoder.close()
            try? FileManager.default.removeItem(at: manager.support)
            withExtendedLifetime(peer) {}
        }
        guard await peer.admitExistingConnection(session) else {
            XCTFail("SIMULATED media producer requires a real admitted connection run")
            return
        }
        session.decoder.effects.histogram = true
        session.decoder.unlockHardwareDecoder()
        let presented = expectation(description: "Native picture precedes the camera gallery")
        let originalPresented = session.decoder.onPresentedFrame
        session.decoder.onPresentedFrame = {
            originalPresented?()
            presented.fulfill()
        }
        session.decoder.handleDecodedFrame(ScopeTestBuffers.makeEdgeBuffer())
        await fulfillment(of: [presented], timeout: 30)
        session.decoder.onPresentedFrame = originalPresented
        XCTAssertNotNil(session.decoder.lastPresentedAt)
        XCTAssertFalse(session.cameraGalleryOpen)

        session.status.inPlayback = true
        session.startKeepalive(ssid: "SIMULATED")
        peer.advance(to: peer.time)
        XCTAssertTrue(session.cameraGalleryOpen, "The real heartbeat follows camera playback")
        let camera = try XCTUnwrap(session.connectedCamera)
        let current = await session.control.currentSnapshot()
        let snapshot = try XCTUnwrap(current)
        let port = PocketMediaPort(
            session: session, snapshot: snapshot,
            target: session.control.target, cameraID: camera.id.uuidString)
        let source = try XCTUnwrap(port.mediaFacts).identity
        let library = session.mediaLibrary
        port.mediaBrowseChanged(
            true, binding: .init(session: snapshot.session, epoch: snapshot.epoch), source: source)

        XCTAssertTrue(session.isBrowsingMedia)
        XCTAssertEqual(library.note, MediaOperatorCopy.browsingOnCamera)
    }
}
