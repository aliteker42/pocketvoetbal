import OpenCineApple
import OpenCineCore
import OpenCineDJIApple
import OpenPocketViewCore

/// iOS Local Network privacy (TN3179) through the shared observer. Denied, the camera datalink
/// never opens (TCP 7001 and UDP sit in `.waiting` until they time out), so the datalink reports
/// its explicit policy denial and a completed handshake here.
@MainActor
enum LocalNetworkAccess {
    /// The app's one observer: the permission belongs to the app, not to a camera session.
    static let privacy = LocalNetworkPrivacy { state, source in
        ControlLiveLog.line("localNetwork: \(state.rawValue) (\(source))")
    }

    static var status: CameraNetworkPermissionState { privacy.state }

    /// Browse our own Bonjour type (listed in `NSBonjourServices`) while pairing, so the
    /// system prompt appears while the operator approves on the camera instead of after the
    /// camera Wi-Fi join (hardware pass 5.1). The shared request uses the legacy browse that
    /// raises the prompt on device; the prompt outlives its 30 s probe and the datalink
    /// re-detects.
    static func requestEarly() {
        privacy.requestBeforeCameraJoin(serviceType: WatcherRelayProtocol.serviceType)
    }

    static func note(_ evidence: DjiAppleLocalNetworkEvidence, source: String) {
        privacy.note(evidence == .allowed ? .operationSucceeded : .policyDenied, source: source)
    }
}
