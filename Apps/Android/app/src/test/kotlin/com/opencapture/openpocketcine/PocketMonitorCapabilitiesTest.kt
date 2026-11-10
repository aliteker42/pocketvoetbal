package com.opencapture.openpocketcine

import com.opencapture.opencine.core.CapabilitySnapshot
import com.opencapture.opencine.core.MonitorLayoutCapabilities
import com.opencapture.opencine.dji.DjiCameraSessionAdapter
import com.opencapture.opencine.vocabulary.CameraModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pocket lays out timecode and audio from the body's declaration; nothing camera-dependent before the body is known. */
class PocketMonitorCapabilitiesTest {
    private fun declared(model: CameraModel?) = MonitorLayoutCapabilities(
        CapabilitySnapshot(
            timecodeCapability = DjiCameraSessionAdapter.timecodeCapability(model),
            audioLevels = DjiCameraSessionAdapter.audioLevelsSupport(model),
        ),
        appLayoutBase,
    )

    @Test fun theLayoutIsTheDeclarationWithNoAppTimecodeOverride() {
        val nano = declared(CameraModel(name = "Osmo Nano", family = "nano", verified = true))
        // Multi View reads the declaration and hides the Nano clock, as main did. The single-view
        // clock slot is the shared DISP rule (MonitorOperatorVocabularySurface), not an app flag.
        assertFalse(nano.timecode)
        assertTrue(declared(CameraModel(name = "Osmo Pocket 3", verified = true)).timecode)
    }

    /** The shared pre-connection rule (deliberate alignment with iOS): no body, no AUDIO. */
    @Test fun noAudioPinOrTileBeforeTheBodyIsKnown() {
        assertFalse(declared(null).audio)
        assertFalse(com.opencapture.opencine.vocabulary.MonitorLayoutCapabilities(
            com.opencapture.opencine.vocabulary.ControlCapabilities.beforeConnection(null), appLayoutBase).audio)
    }
}
