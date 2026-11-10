import Foundation
import Observation
import OpenCineAppKit
import XCTest

@testable import OpenPocketCine

private final class SettingsCacheFileManager: FileManager, @unchecked Sendable {
    let support = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    override func urls(for directory: SearchPathDirectory, in domainMask: SearchPathDomainMask) -> [URL] {
        directory == .applicationSupportDirectory ? [support] : super.urls(for: directory, in: domainMask)
    }
    private let condition = NSCondition()
    private var held = true
    private var fail = false
    private var blocked: XCTestExpectation?

    func arm(_ expectation: XCTestExpectation) {
        condition.lock(); blocked = expectation; condition.unlock()
    }
    func release(failing: Bool = false) {
        condition.lock(); fail = failing; held = false; condition.broadcast(); condition.unlock()
    }
    override func removeItem(at url: URL) throws {
        if url.lastPathComponent.hasPrefix(".clearing-zz-secondary-") {
            condition.lock()
            let notification = blocked
            blocked = nil
            notification?.fulfill()
            while held { condition.wait() }
            let shouldFail = fail
            condition.unlock()
            if shouldFail {
                throw NSError(domain: "SIMULATED Settings cache", code: 7,
                    userInfo: [NSLocalizedDescriptionKey: "SIMULATED secondary deletion failed"])
            }
        }
        try super.removeItem(at: url)
    }
}

@MainActor private final class CacheReadoutWatcher {
    let predicate: () -> Bool
    let fulfill: () -> Void
    private var finished = false
    init(predicate: @escaping () -> Bool, fulfill: @escaping () -> Void) {
        self.predicate = predicate; self.fulfill = fulfill
    }
    func start() {
        guard !finished else { return }
        let ready = withObservationTracking { predicate() } onChange: {
            Task { @MainActor [weak self] in self?.start() }
        }
        if ready { finished = true; fulfill() }
    }
}

/// SIMULATED held real filesystem deletion through the actual shipping Settings source factory.
@MainActor final class SettingsCacheAdapterTests: XCTestCase {
    private func waitFor(_ description: String, _ predicate: @escaping () -> Bool) async {
        let done = expectation(description: description)
        let watcher = CacheReadoutWatcher(predicate: predicate, fulfill: { done.fulfill() })
        watcher.start()
        await fulfillment(of: [done], timeout: 5)
        withExtendedLifetime(watcher) {}
        XCTAssertTrue(predicate(), description)
    }

    func testRetainedSettingsSourceCannotClearAReplacementSession() async throws {
        let firstManager = SettingsCacheFileManager(), nextManager = SettingsCacheFileManager()
        let firstMedia = CameraMedia(fileManager: firstManager)
        let nextMedia = CameraMedia(fileManager: nextManager)
        let first = CameraSession(borrowing: HevcDecoder(), cameraMedia: firstMedia)
        let next = CameraSession(borrowing: HevcDecoder(), cameraMedia: nextMedia)
        let model = AppModel(session: first)
        let cache = MonitorSettingsCacheModel()
        defer {
            cache.retire()
            model.delivery.close()
            first.disconnect(); next.disconnect()
            first.decoder.close(); next.decoder.close()
            try? FileManager.default.removeItem(at: firstManager.support)
            try? FileManager.default.removeItem(at: nextManager.support)
        }
        try firstMedia.cacheDirectory(cameraID: first.mediaCameraID).write(Data(repeating: 1, count: 4096), at: "fixture.bin")
        try nextMedia.cacheDirectory(cameraID: next.mediaCameraID).write(Data(repeating: 2, count: 8192), at: "fixture.bin")
        let retained = model.settingsCacheSource
        model.session = next
        XCTAssertEqual(retained.revision, next.mediaCacheRevision,
            "Coincident content revisions must not admit a replaced session")
        XCTAssertNil(model.shell.monitor)
        cache.refresh(retained)
        cache.requestClear()
        XCTAssertFalse(cache.confirmationPresented)
        cache.clear()
        for _ in 0..<1000 where cache.clearing { await Task.yield() }
        XCTAssertFalse(cache.clearing)
        let firstBytes = await firstMedia.cacheByteCountAllCameras()
        let nextBytes = await nextMedia.cacheByteCountAllCameras()
        XCTAssertEqual(firstBytes, 4096)
        XCTAssertEqual(nextBytes, 8192, "The stale parent must send no Clear to its replacement")
    }

    func testDelayedOtherBucketClearRecountsAfterCurrentLibraryRevision() async throws {
        try await clearScenario(failing: false)
    }
    func testDelayedOtherBucketFailureRemainsVisibleAndRetryRecounts() async throws {
        try await clearScenario(failing: true)
    }
    private func clearScenario(failing: Bool) async throws {
        let manager = SettingsCacheFileManager()
        let media = CameraMedia(fileManager: manager)
        let session = CameraSession(borrowing: HevcDecoder(), cameraMedia: media)
        let model = AppModel(session: session)
        let cache = MonitorSettingsCacheModel()
        defer {
            manager.release()
            cache.retire()
            model.delivery.close()
            session.disconnect()
            session.decoder.close()
            try? FileManager.default.removeItem(at: manager.support)
        }
        let current = media.cacheDirectory(cameraID: session.mediaCameraID)
        let other = media.cacheDirectory(cameraID: "zz-secondary")
        try current.write(Data(repeating: 1, count: 4096), at: "fixture.bin")
        try other.write(Data(repeating: 2, count: 8192), at: "fixture.bin")
        cache.refresh(model.settingsCacheSource)
        await waitFor("Initial actual all-camera bytes") { cache.bytes == 12288 }
        let revision = session.mediaCacheRevision
        let blocked = expectation(description: "Second actual bucket deletion is held")
        manager.arm(blocked)
        cache.requestClear(); cache.clear()
        await fulfillment(of: [blocked], timeout: 5)
        XCTAssertEqual(session.mediaCacheRevision, revision,
            "Pocket publishes content revision after all bucket deletions, unlike the early-library path")
        let partial = await media.cacheByteCountAllCameras()
        XCTAssertEqual(partial, 8192)
        // Renew the same operator scope while its actual second bucket is still pending.
        cache.refresh(model.settingsCacheSource)
        await waitFor("Renewed source counts remaining actual bytes") { cache.bytes == 8192 }
        manager.release(failing: failing)
        await waitFor("Captured Clear completes") { !cache.clearing }
        if failing {
            let remaining = await media.cacheByteCountAllCameras()
            XCTAssertEqual(remaining, 8192)
            await waitFor("Failed Clear recounts remaining actual bytes") { cache.bytes == 8192 }
            XCTAssertEqual(cache.bytes, 8192)
            XCTAssertTrue(cache.clearFailed)
            XCTAssertEqual(cache.failure, "SIMULATED secondary deletion failed")
            manager.release()
            cache.refresh(model.settingsCacheSource)
            cache.requestClear(); cache.clear()
            await waitFor("Retry Clear completes") { !cache.clearing }
        }
        let remaining = await media.cacheByteCountAllCameras()
        XCTAssertEqual(remaining, 0)
        await waitFor("Final readout matches actual bytes") { cache.bytes == 0 }
        XCTAssertFalse(cache.clearFailed)
        XCTAssertNil(cache.failure)
    }
}
