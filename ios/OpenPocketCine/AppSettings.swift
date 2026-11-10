import MonitorPresentation
import MonitorUI
import OpenCineAppKit
import OpenCineCameraControls
import OpenPocketViewCore
import SwiftUI
import UIKit


enum OpenPocketCineLinks {
    static let source = URL(string: "https://github.com/erik-sutton95/OpenPocketCine")
    static let support = URL(
        string: "https://github.com/erik-sutton95/OpenPocketCine/discussions/categories/q-a")
    static let reportProblem = URL(
        string: "https://github.com/erik-sutton95/OpenPocketCine/issues/new?template=bug_report.yml"
    )
    static let featureRequest = URL(
        string: "https://github.com/erik-sutton95/OpenPocketCine/discussions/new?category=ideas")
    static let privacy = URL(string: "https://openpocketcine.app/privacy/")
    static let terms = URL(string: "https://openpocketcine.app/terms/")
}

extension OperatorHaptics {
    /// The operator's haptics switch, read at each tap.
    static let app = OperatorHaptics { OperatorPrefs.hapticsEnabled }
}
