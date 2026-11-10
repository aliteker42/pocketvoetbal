import OpenCineAppKitApple
import MonitorPresentation
import OpenCineCameraControls
import OpenCineCameraVocabulary
import OpenPocketViewCore
import SwiftUI

/// Pocket monitor tools around a remote source (OpenCineCameraControls).
typealias WatcherLiveView = MonitorOperatorWatcherLiveView<AppModel>

extension AppModel: MonitorWatcherLiveHost {
    typealias Picture = PocketLivePicture
    static var assistStores: AssistPanelStores { .app }
    static var falseColorLooks: FalseColorLooks { .app }
    static var operatorHaptics: OperatorHaptics { .app }
    static var watcherBrand: MonitorWatcherLiveBrand { MonitorWatcherLiveBrand() }
    var watcherCameraFamily: CameraBodyFamily { relayClient.state.isNano == true ? .nano : .pocket }
    var watcherLookProfile: CameraLUTProfile? {
        DJICameraLUTProfile.make(family: watcherCameraFamily, cameraName: relayClient.state.cameraModel)
    }
    func inspectorPreview(tool: MonitorAssistSelection) -> some View { AssistInspectorPreview(tool: tool) }
    func lookMenu() -> some View { LUTAssist.longPressMenu(assist: assist) }
}

extension HevcDecoder: MonitorOperatorLiveDecoder {}
