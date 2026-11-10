import MonitorUI
import SwiftUI

/// OpenPocketCine launch identity: DJI Sky Blue on black.
enum AppBrand {
    static var identity: MonitorBrandIdentity {
        MonitorBrandIdentity(
            name: "OpenPocketCine", monogram: "OPC", accent: { Color(red: 0, green: 163 / 255, blue: 230 / 255) },
            glow: Color(red: 45 / 255, green: 111 / 255, blue: 160 / 255))
    }
}

struct LaunchSplashOverlay: View {
    @Binding var isVisible: Bool

    var body: some View {
        MonitorLaunchSplashOverlay(isVisible: $isVisible) {
            MonitorBrandLaunchSplash(AppBrand.identity)
        }
    }
}
