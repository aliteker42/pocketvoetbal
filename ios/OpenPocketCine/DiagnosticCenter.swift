import Foundation
import class OpenCineCore.LocalVPNNotice
import OpenCineAppKit
import OpenCineAppKitApple
import OpenCineCameraControls
import OpenPocketViewCore

/// This app's on-device diagnostics: OpenCine's `DiagnosticCenterRuntime` with this app's
/// journal, redaction, incidents and Local Network status, read through its session.
final class DiagnosticCenter: DiagnosticCenterRuntime {
    static let shared = DiagnosticCenter()

    private init() {
        super.init(
            host: .init(
                product: "OpenPocketCine", subsystem: "com.opencapture.openpocketcine",
                journal: ControlLiveLog, redact: { DJICameraNetwork.redact($0) },
                isSafeNetwork: { DJICameraNetwork.isAccessPoint($0) }, incidents: { AppDiagnostics.incidents },
                localNetwork: { LocalNetworkAccess.status.rawValue },
                currentCamera: { Self.camera(AppModelDiagnosticsAnchor.session) }))
    }

    @MainActor static func camera(_ session: CameraSession) -> DiagnosticCamera {
        DiagnosticCamera(
            family: session.connectedCamera?.model.family, model: session.connectedCamera?.model.name,
            phase: session.phase)
    }

    @MainActor
    func manualReport(session: CameraSession) -> String {
        manualReport(camera: Self.camera(session))
    }

    @MainActor
    func writeReport(session: CameraSession) -> URL? {
        writeReport(camera: Self.camera(session))
    }

    /// Compact paste plus `report.txt` for the system share sheet.
    @MainActor
    func beginShare(session: CameraSession) -> ShareOutcome {
        beginShare(camera: Self.camera(session))
    }
}

/// Weak back-reference so screenshot handling can read the live session
/// without a DiagnosticCenter ↔ AppModel import cycle at init.
enum AppModelDiagnosticsAnchor {
    @MainActor static weak var model: AppModel?
    @MainActor static var session: CameraSession {
        model?.session ?? CameraSession()
    }
}

extension LocalVPNNotice {
    /// This app's once-per-launch local VPN line in the diagnostics journal.
    static let app = LocalVPNNotice { message in
        DiagnosticCenter.shared.event(
            level: .notice, category: .session, code: "vpn", message: message)
    }
}
