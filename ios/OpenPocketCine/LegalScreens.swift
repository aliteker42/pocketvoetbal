import MonitorPresentation
import MonitorUI
import OpenCineCameraControls
import SwiftUI

/// In-app legal pages. Canonical website copy lives at openpocketcine.app/privacy and /terms.
/// Privacy and terms are this app's own text; licenses and NOTICE are the repository's LICENSE,
/// THIRD-PARTY-NOTICES.md and NOTICE, copied into the bundle's Legal folder at build time (OpenCine
/// `scripts/stage-legal-resources.py`), with the platform's third-party notices.
extension MonitorLegalDocuments {
    static let app = MonitorLegalDocuments(
        wordmark: "OpenPocketCine", privacy: .text(appPrivacy), terms: .text(appTerms),
        licenses: bundledText([
            resource("LICENSE", extension: "txt", subdirectory: "Legal", in: .main),
            resource("THIRD-PARTY-NOTICES", extension: "txt", subdirectory: "Legal", in: .main),
            MonitorPlatformLegalNotices.text,
        ]),
        notice: bundledText([resource("NOTICE", extension: "txt", subdirectory: "Legal", in: .main)]))

    /// Settings shows the same set with the terms as the web page, like the other platforms.
    static let settings = MonitorLegalDocuments(
        wordmark: app.wordmark, privacy: app.privacy,
        terms: OpenPocketCineLinks.terms.map { .link($0) } ?? .unavailable, licenses: app.licenses, notice: app.notice)

    private static let appPrivacy = """
        OpenPocketCine talks to your Osmo Pocket over Bluetooth and the camera's own Wi-Fi. The app has no account. Automatic error reports are optional and off by default.

        OpenCapture is the data controller. Privacy or deletion requests: support@openpocketcine.app.

        \(MonitorSettingsCopy.reliabilityReportsPrivacy)

        Reporting a problem yourself
        Report a problem sends your description and any optional reply email to OpenCapture through Sentry when you tap Send report. Technical details are included only if you choose them and can be reviewed first. You can choose up to three photos or screenshots and review or remove them before sending. Selected images are resized and re-encoded without original filenames or location metadata. Only share images you have permission to send; no images are attached automatically. This one-off submission does not enable automatic reports. Unsent reports stay privately on this phone, expire after 7 days (removed when the app next runs), wait until you leave camera Wi-Fi, and can be removed before delivery. Do not include passwords or other sensitive information in your message. You can decline the first-launch prompt and enable automatic reports later in System.

        What stays on this phone
        • Saved camera names and last SSID. The camera Wi-Fi password is stored in the iOS Keychain on this phone only.
        • Operator preferences such as Keep Screen Awake and the last LUT look.
        • Imported .cube files you choose to open.

        What never leaves the phone
        • Live HEVC, DUML telemetry, and pairing traffic stay on the local link. We do not operate a cloud.

        Third parties
        • Apple (or Google) may process permission prompts and OS diagnostics under their own policies. TestFlight or Play betas may send crash reports to the developer under Apple or Google terms.
        • Frame.io is optional. If this build is configured and you sign in, clips you pick upload device → Adobe. The token stays in the on-device Keychain.
        • Saving a clip or using the share sheet is something you start. Apple or the app you pick then handles that file.
        • AF-C face boxes are computed on this phone from the live preview. Face geometry is not uploaded.
        • The source is at github.com/erik-sutton95/OpenPocketCine.

        The full privacy policy is https://openpocketcine.app/privacy/
        """

    private static let appTerms = """
        OpenPocketCine is free software under the Apache License 2.0. You may use, modify, and share it under that license.

        This is an unofficial field monitor. It is not affiliated with, endorsed by, or supported by DJI. "DJI", "Osmo", and "Osmo Pocket" are trademarks of SZ DJI Technology Co., Ltd., used only to identify the cameras the app can talk to.

        Reverse-engineered protocol behavior can be incomplete or wrong. Do not rely on this app as the only way to start or stop a take. Record from the camera body until those commands are proven.

        The software is provided on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See LICENSE in the repository.

        By using the app you agree to follow the Code of Conduct when participating in the project.
        """
}

/// One legal page in the shared legal screen.
struct LegalDocumentView: View {
    typealias Kind = MonitorLegalPage
    let kind: Kind
    let onClose: () -> Void

    var body: some View {
        MonitorLegalDocumentScreen(documents: .app, page: kind, onClose: onClose)
    }
}
