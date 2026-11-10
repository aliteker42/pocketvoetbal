package com.opencapture.openpocketcine

import com.opencapture.monitorui.ReviewLiveValues
import com.opencapture.opencine.vocabulary.CameraCommands
import kotlin.test.Test
import kotlin.test.assertEquals

/** SIMULATED: the LIVE review stages the shared review values as this camera reports them. */
class MonitorReviewLiveTest {
    @Test fun liveReviewStagesTheSharedValuesInThePocketColour() {
        val values = ReviewLiveValues.SIMULATED
        val status = MonitorReview.liveStatus(values)
        assertEquals(values.iso, status.iso)
        assertEquals(values.shutterDenominator, status.shutterDenom)
        assertEquals(values.whiteBalanceKelvin, status.wbKelvin)
        assertEquals(values.framesPerSecond, status.fps)
        assertEquals(CameraCommands.RES_4K, status.resolutionCode)
        assertEquals(values.batteryPercent, status.batteryPercent)
        assertEquals(values.storageFreeMebibytes.toInt(), status.storageFreeMb)
        assertEquals(CameraCommands.COLOR_DLOG2, status.colorMode)
        assertEquals("Osmo Pocket 4 Pro", MonitorReview.liveCamera.model.name)
    }
}
