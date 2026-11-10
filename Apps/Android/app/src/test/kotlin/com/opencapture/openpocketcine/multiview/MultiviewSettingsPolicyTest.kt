package com.opencapture.openpocketcine.multiview

import com.opencapture.opencine.cameracontrols.LiveSheet
import com.opencapture.opencine.core.CapabilitySnapshot
import com.opencapture.opencine.core.SettingAccess
import com.opencapture.opencine.core.SupportStatus
import com.opencapture.opencine.dji.fromJson
import com.opencapture.opencine.vocabulary.CameraCommands
import com.opencapture.opencine.vocabulary.CameraModel
import com.opencapture.opencine.vocabulary.CameraStatus
import com.opencapture.opencine.vocabulary.ControlCapabilities
import com.opencapture.opencine.vocabulary.ControlCapability
import com.opencapture.opencine.vocabulary.NoOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiviewSettingsPolicyTest {
    private fun categories(camera: CameraModel, status: CameraStatus): List<LiveSheet> =
        multiviewSettingsCategories(camera, status, ControlCapabilities.derive(
            CapabilitySnapshot(audioLevels = SupportStatus.SUPPORTED), camera.captureProfile))

    @Test fun audioAdmissionUsesTypedSupport() {
        val camera = CameraModel("SIMULATED camera")
        val video = CameraStatus(shootingMode = CameraCommands.SHOOT_VIDEO)
        val declarations = listOf(ControlCapability.unknown<NoOffer>(), ControlCapability.unsupported<NoOffer>(),
            ControlCapability.supported<NoOffer>(), ControlCapability.supported<NoOffer>(access = SettingAccess.READ_ONLY))
        for (audio in declarations) {
            val controls = ControlCapabilities(audio = audio)
            assertEquals(audio.isOffered, LiveSheet.AUDIO in multiviewSettingsCategories(camera, video, controls),
                "${audio.support}/${audio.access}")
            assertFalse(LiveSheet.AUDIO in multiviewSettingsCategories(camera,
                video.copy(shootingMode = CameraCommands.SHOOT_PHOTO), controls))
        }
    }

    @Test fun nativeCategoriesFollowCameraCapabilitiesAndShootingMode() {
        val pocket = CameraModel.fromJson("""{"name":"Osmo Pocket 4","family":"pocket","supportsFocusMode":true,"supportsTapFocus":true}""")
        val video = CameraStatus(shootingMode = CameraCommands.SHOOT_VIDEO)
        assertEquals(listOf(LiveSheet.ISO, LiveSheet.SHUTTER, LiveSheet.EXPO, LiveSheet.WB,
            LiveSheet.FOCUS, LiveSheet.FORMAT, LiveSheet.COLOR, LiveSheet.MODE, LiveSheet.AUDIO),
            categories(pocket, video))
        val photo = categories(pocket, video.copy(shootingMode = CameraCommands.SHOOT_PHOTO))
        assertTrue(photo.containsAll(listOf(LiveSheet.ISO, LiveSheet.WB, LiveSheet.MODE)))
        assertFalse(photo.any { it in listOf(LiveSheet.FORMAT, LiveSheet.COLOR, LiveSheet.AUDIO) })
        val nano = CameraModel("Osmo Nano", family = "nano", supportsFocusMode = false, supportsTapFocus = false)
        assertFalse(LiveSheet.FOCUS in categories(nano, video))
        assertFalse(LiveSheet.APERTURE in categories(pocket, video))
        assertTrue(LiveSheet.APERTURE in categories(CameraModel.fromJson("""{"name":"Osmo Action 6","family":"other","supportsFocusMode":false,"supportsAperture":true}"""), video))
    }

    @Test fun recordingLocksModeFormatAndColorButKeepsExposureControls() {
        for (sheet in LiveSheet.entries) {
            assertFalse(multiviewSettingsLocked(sheet, false))
            assertEquals(sheet in listOf(LiveSheet.MODE, LiveSheet.FORMAT, LiveSheet.COLOR),
                multiviewSettingsLocked(sheet, true))
        }
    }
}
