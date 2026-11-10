package com.opencapture.openpocketcine

import com.opencapture.opencine.appkit.MonitorCaptureSelection
import com.opencapture.opencine.appkit.MonitorControlModel
import com.opencapture.opencine.appkit.captureField
import com.opencapture.opencine.appkit.captureValue
import com.opencapture.opencine.appkit.commit
import com.opencapture.opencine.cameracontrols.LiveSheet
import com.opencapture.opencine.core.ISOValue
import com.opencapture.opencine.core.SettingValue
import com.opencapture.opencine.core.testing.FakeCameraSession
import com.opencapture.opencine.core.testing.lunaLike
import kotlinx.coroutines.test.runTest
import kotlin.test.assertNotNull
import com.opencapture.opencine.core.SettingKey
import com.opencapture.opencine.core.CapabilitySnapshot
import com.opencapture.opencine.core.SettingAccess
import com.opencapture.opencine.core.SupportStatus
import com.opencapture.opencine.vocabulary.CameraCommands
import com.opencapture.opencine.vocabulary.CameraCaptureProfile
import com.opencapture.opencine.vocabulary.CaptureNativeISOCapabilities
import com.opencapture.opencine.vocabulary.ControlCapabilities
import com.opencapture.opencine.vocabulary.ControlFamily
import com.opencapture.opencine.vocabulary.ControlStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pocket's capture sheet host values, as on main. */
class CaptureSheetHostTest {
    /** Behavior audit PAA-204: the ISO popup drum starred the curve's native ISO in the standalone app. */
    @Test
    fun isoPopupDrumStarsTheNativeIso() {
        val dLogM = com.opencapture.opencine.vocabulary.CameraStatus(colorMode = CameraCommands.COLOR_DLOG_M,
            shootingMode = CameraCommands.SHOOT_VIDEO)
        assertEquals(setOf("400"), pocketCommonDrumMarks(LiveSheet.ISO, dLogM))
        assertEquals(setOf("1600"), pocketCommonDrumMarks(LiveSheet.ISO,
            dLogM.copy(colorMode = CameraCommands.COLOR_DLOG2)))
        assertEquals(emptySet(), pocketCommonDrumMarks(LiveSheet.SHUTTER, dLogM))
    }

    @Test fun typedModelControlsEveryHostProjectionAndKeepsUnknown() {
        var model = ControlCapabilities()
        val state = pocketCaptureSessionState({ null }, { null }, { false }, { false },
            controls = { model })
        assertFalse(state.focusModeSupported)
        assertFalse(state.irisSupported)
        assertFalse(state.nativeIsoHopSupported)
        assertNull(state.nativeISO.hop)
        assertNull(state.nativeISO.ceiling)
        model = ControlCapabilities.beforeConnection(CameraCaptureProfile(supportsFocusMode = true,
            supportsAperture = true, nativeISO = CaptureNativeISOCapabilities(hop = true, ceiling = false)))
        assertTrue(state.focusModeSupported && state.irisSupported && state.nativeIsoHopSupported)
        assertEquals(false, state.nativeISO.ceiling)
        model = model.restricting(mapOf(ControlFamily.FOCUS to ControlStatus(SupportStatus.SUPPORTED, SettingAccess.READ_ONLY),
            ControlFamily.IRIS to ControlStatus(SupportStatus.UNSUPPORTED)))
        assertFalse(state.focusModeSupported || state.irisSupported)
    }

    /** SIMULATED common report and a contradictory pending edit (iOS `PocketCommonReadoutTests`). */
    @Test fun theCaptureStripReadsTheReportUntilTheCameraConfirms() = runTest {
        assertEquals(MonitorCaptureSelection.REPORTED, pocketCaptureReadoutSelection,
            "Pocket retains report-until-confirmation policy")
        val camera = FakeCameraSession.lunaLike()
        camera.cameraReportsStatus()
        val control = MonitorControlModel(backgroundScope)
        control.bind(camera)
        val field = assertNotNull(control.captureField(SettingKey.ISO))
        val reported = control.captureValue(SettingKey.ISO, pocketCaptureReadoutSelection)
        val pending = SettingValue.Iso(ISOValue.Manual(800))
        assertTrue(reported != pending, "The fixture really reports a different value")
        assertTrue(control.commit(field.choices.first { it.value == pending }, field))
        assertEquals(pending, control.captureValue(SettingKey.ISO, MonitorCaptureSelection.PENDING))
        assertEquals(reported, control.captureValue(SettingKey.ISO, pocketCaptureReadoutSelection))
    }

    @Test fun anUnqualifiedNativeIsoPairDoesNotOfferAnAutomaticHop() {
        val unknown = pocketCaptureSessionState({ null }, { null }, { false }, { false }, { ControlCapabilities() })
        assertFalse(unknown.nativeIsoHopSupported, "No native ISO pair was declared by this source")
        val knownFocus = pocketCaptureSessionState({ null }, { null }, { false }, { false },
            { ControlCapabilities.beforeConnection(CameraCaptureProfile(supportsFocusMode = true, supportsAperture = true)) })
        assertFalse(knownFocus.nativeIsoHopSupported, "Focus and iris support do not declare a native ISO pair")
    }

    @Test fun isoShutterEvWhiteBalanceAndFocusOpenThroughCore() {
        val manual = CameraCommands.EXPO_MANUAL
        assertEquals(SettingKey.ISO, capturePanelSettingKey(LiveSheet.ISO, manual))
        assertEquals(SettingKey.SHUTTER, capturePanelSettingKey(LiveSheet.SHUTTER, manual))
        assertEquals(SettingKey.EXPOSURE_COMPENSATION, capturePanelSettingKey(LiveSheet.SHUTTER, CameraCommands.EXPO_AUTO))
        assertEquals(SettingKey.WHITE_BALANCE, capturePanelSettingKey(LiveSheet.WB, manual))
        assertEquals(SettingKey.FOCUS_MODE, capturePanelSettingKey(LiveSheet.FOCUS, manual))
        for (sheet in LiveSheet.entries - setOf(LiveSheet.ISO, LiveSheet.SHUTTER, LiveSheet.WB, LiveSheet.FOCUS)) {
            assertNull(capturePanelSettingKey(sheet, manual), "$sheet stays on the typed path")
        }
    }

    @Test fun locksFollowTheSessionAndTheFormatPinIsReadLive() {
        var aeLock: Any? = null
        var awbKelvin: Int? = null
        var pinned = true
        val state = pocketCaptureSessionState({ aeLock }, { awbKelvin }, { true }, { pinned }, { ControlCapabilities() })
        assertFalse(state.aeLocked)
        assertNull(state.awbLockKelvin)
        assertTrue(state.awbLockOffered)
        assertTrue(state.formatPinned)
        aeLock = Any()
        awbKelvin = 5600
        pinned = false // the pin expired: the FORMAT sheet reseats again without a new state
        assertTrue(state.aeLocked)
        assertEquals(5600, state.awbLockKelvin)
        assertFalse(state.formatPinned)
    }

    @Test fun focusAndIrisFollowTheModelWithoutInventingNativeIsoSupport() {
        val none = pocketCaptureSessionState({ null }, { null }, { false }, { false }, { ControlCapabilities() })
        assertFalse(none.focusModeSupported)
        assertFalse(none.irisSupported)
        assertFalse(none.nativeIsoHopSupported)
        assertNull(none.nativeISO.hop)
        assertNull(none.nativeISO.ceiling)
        val both = pocketCaptureSessionState({ null }, { null }, { false }, { false },
            { ControlCapabilities.beforeConnection(CameraCaptureProfile(supportsFocusMode = true, supportsAperture = true)) })
        assertTrue(both.focusModeSupported)
        assertTrue(both.irisSupported)
        assertFalse(both.nativeIsoHopSupported)
        assertNull(both.nativeISO.hop)
        assertNull(both.nativeISO.ceiling)
    }

    /** No legacy layout may override the required model, even before a body is known. */
    @Test fun theBodysControlModelDecidesOnceKnown() {
        var known = ControlCapabilities.beforeConnection(null)
        val state = pocketCaptureSessionState({ null }, { null }, { false }, { false },
            controls = { known })
        assertFalse(state.controlCapabilities.audio.isOffered, "no body: nothing camera-dependent")
        val pocket = com.opencapture.opencine.vocabulary.CameraCaptureProfile(
            supportsFocusMode = true, supportsTapFocus = true, supportsAperture = false, hasGimbal = true, supportsZoom = true)
        known = com.opencapture.opencine.vocabulary.ControlCapabilities.derive(
            CapabilitySnapshot(audioLevels = SupportStatus.SUPPORTED), pocket)
        val controls = state.controlCapabilities
        assertTrue(controls.focus.isOffered)
        assertFalse(controls.iris.isOffered)
        assertTrue(controls.audio.isOffered)
        assertNull(controls.nativeISO.hop)
        known = ControlCapabilities.beforeConnection(null)
        assertFalse(state.controlCapabilities.audio.isOffered)
    }
}
