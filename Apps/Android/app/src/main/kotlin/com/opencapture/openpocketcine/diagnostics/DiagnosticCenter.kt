package com.opencapture.openpocketcine.diagnostics

import android.content.Context
import com.opencapture.opencine.appkit.diagnostics.DiagnosticCamera
import com.opencapture.opencine.appkit.diagnostics.DiagnosticCenterRuntime
import com.opencapture.opencine.dji.DjiCameraNetwork
import com.opencapture.opencine.dji.DjiCameraSession
import com.opencapture.opencine.dji.DjiLog
import com.opencapture.opencine.platform.LocalVPNProbe
import com.opencapture.openpocketcine.OperatorPrefs

/**
 * On-device journal, exceptions, and a shareable report: OpenCine's [DiagnosticCenterRuntime]
 * with this app's host, its DJI access points kept readable and the DJI log routed here.
 */
object DiagnosticCenter :
    DiagnosticCenterRuntime<DjiCameraSession>(
        host = AppDiagnostics.host,
        incidents = AppDiagnostics.incidents,
        isSafeNetwork = DjiCameraNetwork::isAccessPoint,
        vpnActive = LocalVPNProbe::isActive,
        camera = { session ->
            DiagnosticCamera(
                family = session.connectedCamera?.model?.family ?: "none",
                model = session.connectedCamera?.model?.name ?: "none",
                phase = session.phase.name.lowercase(),
            )
        },
    ) {
    override fun installHost(context: Context) {
        DjiLog.sink = ::log
        AppDiagnostics.reporting.noteFeedUpscaler(OperatorPrefs.feedUpscaler(context).label)
        AppDiagnostics.reporting.install(context.applicationContext, AppDiagnostics.incidents)
        AppDiagnostics.manualReport.install(context.applicationContext)
    }
}
