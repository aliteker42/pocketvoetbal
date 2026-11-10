package com.opencapture.openpocketcine.conformance

import com.opencapture.monitorui.MonitorCapabilities
import com.opencapture.opencine.appuitesting.ConformanceApp
import com.opencapture.opencine.appuitesting.ConformanceProfile
import com.opencapture.openpocketcine.MainActivity

/**
 * The shared conformance scenarios (docs/conformance/SCENARIOS.md) against the real launcher, staged
 * by the Debug review host with SIMULATED sources only. ConformanceCoverageTest fails when one is missing.
 */
class GalleryRoundTrip : com.opencapture.opencine.appuitesting.GalleryRoundTrip(PocketReview)

class ShareRoundTrip : com.opencapture.opencine.appuitesting.ShareRoundTrip(PocketReview)

class ConfirmationCancel : com.opencapture.opencine.appuitesting.ConfirmationCancel(PocketReview)

class ChromeEditReturn : com.opencapture.opencine.appuitesting.ChromeEditReturn(PocketReview)

class AccessibilityTraversal : com.opencapture.opencine.appuitesting.AccessibilityTraversal(PocketReview)

class ScreenMatrix : com.opencapture.opencine.appuitesting.ScreenMatrix(PocketReview)

class ConformanceCoverageTest : com.opencapture.opencine.appuitesting.ConformanceCoverageTest(PocketReview)

private object PocketReview : ConformanceApp {
    override val activity = MainActivity::class.java
    // A local review has no connected camera or optional camera controls.
    override val profile = ConformanceProfile("PocketCine", MonitorCapabilities())
}
