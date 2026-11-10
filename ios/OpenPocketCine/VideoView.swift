import OpenCineAppKitApple
import OpenCineApple
import OpenCineCameraControls

/// This app's typed values for the shared live picture (OpenCineAppKitApple).
enum PocketLivePicture: MonitorLivePictureApp {
    typealias Decoder = HevcDecoder
    static var hdrDisplay: LiveHDRDisplay { .app }
    static var feedUpscaler: MonitorUpscalerPreference { FeedUpscaleSwitch.shared }
    static var logSubsystem: String { "com.opencapture.openpocketcine" }
    static var presentObserver: (() -> Void)? {
        #if DEBUG
            { FeedStressAutomation.noteMetalPresent() }
        #else
            nil
        #endif
    }
}

extension HevcDecoder: MonitorLiveVideoSource {}

typealias VideoView = MonitorLiveVideoView<PocketLivePicture>
typealias VideoDisplayRepresentable = MonitorLiveVideoRepresentable<PocketLivePicture>
typealias DisplayLayerView = MonitorLiveDisplayLayerView<PocketLivePicture>
typealias CIFeedView = MonitorLiveFeedView<PocketLivePicture>
