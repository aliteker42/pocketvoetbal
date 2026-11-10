import MonitorPresentation
import OpenCineCameraControls
import OpenCineCameraVocabulary
import struct OpenCineCore.SavedCamera
import OpenPocketViewCore

/// This app's side of the shared debug feed-stress seam (`FeedStressAutomation` in OpenCine).
/// The run reconnects a single saved Pocket 4 Pro when the feed is not live yet.
extension AppModel: FeedStressHost {
    var feedStressIsLive: Bool { session.phase == .live }
    var feedStressStatus: CameraStatus { session.status }
    var feedStressBodyFamily: CameraBodyFamily { session.bodyFamily }

    func feedStressAssistIsOn(_ tool: MonitorAssistSelection) -> Bool { assist.isOn(tool) }
    func feedStressToggleAssist(_ tool: MonitorAssistSelection) { assist.toggle(tool) }

    func feedStressReconnect() -> FeedStressReconnect {
        if session.phase == .live { return .live }
        let matches = savedCameras.filter(Self.isPocket4Pro)
        guard matches.count == 1 else { return matches.isEmpty ? .none : .ambiguous }
        reconnect(matches[0])
        return .requested
    }

    func feedStressEndGimbalStick() { session.gimbal.endStick(cancelMove: true) }
    func feedStressStopRecording() { session.control.record(start: false) }
    func feedStressSetISO(_ index: IsoIndex) { session.control.setISO(index) }
    func feedStressSetIsoLimit(_ limit: IsoLimit) { session.setIsoLimit(limit) }
    func feedStressSetWhiteBalanceAuto(tint: Int) { session.control.setWhiteBalanceAuto(tint: tint) }

    func feedStressSetWhiteBalanceCustom(kelvin: Int, tint: Int) {
        session.control.setWhiteBalanceCustom(kelvin: kelvin, tint: tint)
    }

    private static func isPocket4Pro(_ camera: SavedCamera) -> Bool {
        if let id = camera.modelId, id == 0x0022 { return true }
        let name = camera.modelName.lowercased().replacingOccurrences(of: " ", with: "")
        return name.contains("pocket4p") || name.contains("4pro")
    }
}
