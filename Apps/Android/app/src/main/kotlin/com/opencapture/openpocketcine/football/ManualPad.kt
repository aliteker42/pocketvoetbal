package com.opencapture.openpocketcine.football

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencapture.openpocketcine.AppModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.opencapture.opencine.vocabulary.CamFov
import kotlin.math.max

/**
 * Bedien-versie: links twee grote zoomknoppen. Draaien gaat met de gewone joystick van de app (in deze
 * versie extra groot), dus precies dezelfde, stabiele besturing als in de gewone app. Zoomen gebruikt
 * dezelfde zoomweg als een gamepad in de gewone app.
 */
@Composable
fun ManualPad(model: AppModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val driver = remember { PadDriver(model) }
    DisposableEffect(Unit) { onDispose { driver.stopZoom() } }

    Box(modifier.fillMaxSize().navigationBarsPadding().padding(20.dp)) {
        Column(
            Modifier.align(Alignment.CenterStart).panel(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HoldButton("+", 110.dp, ZoomColor, { driver.startZoom(scope, +ZOOM_THROW) }, { driver.stopZoom() })
            HoldButton("−", 110.dp, ZoomColor, { driver.startZoom(scope, -ZOOM_THROW) }, { driver.stopZoom() })
        }
    }
}

private val ZoomColor = Color(0xFF1565C0)

/** Hoe ver de "zoom-stick" ingedrukt wordt (0..1), zoals een half ingedrukte gamepad-zoom. */
private const val ZOOM_THROW = 0.5

private fun Modifier.panel() =
    clip(RoundedCornerShape(28.dp)).background(Color(0x88000000)).padding(12.dp)

/** Zoom zolang een knop ingedrukt is: dezelfde pomp als de gamepad-zoom van de gewone app. */
private class PadDriver(private val model: AppModel) {
    private var zoomJob: Job? = null
    private var zoomAnchor = 1.0
    private var zoomCurrent = 1.0

    fun startZoom(scope: CoroutineScope, throwY: Double) {
        zoomJob?.cancel()
        zoomAnchor = model.zoomReadout
        zoomCurrent = zoomAnchor
        zoomJob = scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            apply(throwY, CamFov.ZOOM_STEP_INTERVAL_MS / 1000.0)
            while (isActive) {
                delay(CamFov.ZOOM_STEP_INTERVAL_MS)
                if (!model.canDriveGimbalPad()) break
                val now = android.os.SystemClock.elapsedRealtime()
                apply(throwY, (now - last) / 1000.0)
                last = now
            }
        }
    }

    private fun apply(throwY: Double, dt: Double) {
        if (!model.zoomColorHopPending) zoomCurrent = CamFov.zoomStep(zoomCurrent, throwY, dt, model.zoomMax())
        model.updateZoomPinch(zoomCurrent / max(zoomAnchor, CamFov.MIN_FACTOR))
    }

    fun stopZoom() {
        if (zoomJob == null) return
        zoomJob?.cancel()
        zoomJob = null
        model.endZoomPinch()
    }
}

@Composable
private fun HoldButton(label: String, size: Dp, color: Color, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(22.dp))
            .background(if (pressed) Color.White else color)
            .border(2.dp, Color(0x66FFFFFF), RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    press()
                    try {
                        // Blijf bewegen tot de vinger los is, ook als hij een beetje van de knop schuift.
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        pressed = false
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) color else Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}

