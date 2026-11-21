package com.opencapture.openpocketcine.football

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
 * Bedien-versie: links onder een zoom-joystick (alleen op/neer), op dezelfde hoogte als de gewone
 * joystick rechts onder. Omhoog schuiven = inzoomen, omlaag = uitzoomen; hoe verder, hoe sneller.
 * Loslaten = terugveren en stoppen. Zoomen gaat via dezelfde zoomweg als een gamepad in de gewone app.
 */
@Composable
fun ManualPad(model: AppModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val driver = remember { PadDriver(model) }
    // Met één vinger over het beeld slepen = draaien, precies zoals de joystick (zelfde omrekening en
    // dezelfde weg naar de camera). Vol uitslag bij ongeveer 40% van de beeldbreedte slepen.
    DisposableEffect(Unit) {
        com.opencapture.opencine.cameracontrols.LiveFeedDrag.onDrag = { dx, dy, w, _ ->
            val stick = 0.6f * w
            val m = com.opencapture.opencine.vocabulary.CameraCommands.mapGimbalStickTouch(dx, dy, stick, stick * 41f / 101f, true)
            model.updateGimbalStick(m.commandX, m.commandY)
        }
        com.opencapture.opencine.cameracontrols.LiveFeedDrag.onDragEnd = { model.endGimbalStick() }
        onDispose {
            com.opencapture.opencine.cameracontrols.LiveFeedDrag.onDrag = null
            com.opencapture.opencine.cameracontrols.LiveFeedDrag.onDragEnd = null
            model.endGimbalStick()
            driver.stopZoom()
        }
    }

    Box(modifier.fillMaxSize().navigationBarsPadding().padding(start = 24.dp, bottom = 150.dp)) {
        ZoomStick(
            Modifier.align(Alignment.BottomStart),
            onMove = { t -> driver.zoom(scope, t) },
            onRelease = { driver.stopZoom() },
        )
    }
}

/** Grootste zoomsnelheid (0..1 van de gamepad-zoom) als de knop helemaal boven/onder staat. */
private const val ZOOM_MAX_THROW = 0.8

/** Zoom zolang de zoom-joystick uit het midden staat: dezelfde pomp als de gamepad-zoom. */
private class PadDriver(private val model: AppModel) {
    private var zoomJob: Job? = null
    private var zoomAnchor = 1.0
    private var zoomCurrent = 1.0
    @Volatile private var throwY = 0.0

    fun zoom(scope: CoroutineScope, t: Double) {
        throwY = t
        if (zoomJob?.isActive == true) return
        zoomAnchor = model.zoomReadout
        zoomCurrent = zoomAnchor
        zoomJob = scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            apply(CamFov.ZOOM_STEP_INTERVAL_MS / 1000.0)
            while (isActive) {
                delay(CamFov.ZOOM_STEP_INTERVAL_MS)
                if (!model.canDriveGimbalPad()) break
                val now = android.os.SystemClock.elapsedRealtime()
                apply((now - last) / 1000.0)
                last = now
            }
        }
    }

    private fun apply(dt: Double) {
        if (!model.zoomColorHopPending) zoomCurrent = CamFov.zoomStep(zoomCurrent, throwY, dt, model.zoomMax())
        model.updateZoomPinch(zoomCurrent / max(zoomAnchor, CamFov.MIN_FACTOR))
    }

    fun stopZoom() {
        throwY = 0.0
        if (zoomJob == null) return
        zoomJob?.cancel()
        zoomJob = null
        model.endZoomPinch()
    }
}

/** Verticale joystick: knop omhoog/omlaag slepen, links/rechts doet niets. */
@Composable
private fun ZoomStick(modifier: Modifier, onMove: (Double) -> Unit, onRelease: () -> Unit) {
    val height = 230.dp
    val width = 110.dp
    val knob = 92.dp
    var dy by remember { mutableStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(55.dp))
            .background(Color(0x55000000))
            .border(2.dp, Color(0xE6FFFFFF), RoundedCornerShape(55.dp))
            .pointerInput(Unit) {
                val travel = (size.height - knob.toPx()) / 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    // Waar je de vinger neerzet telt als midden: daarna schuiven = zoomen.
                    val startY = down.position.y
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            c.consume()
                            if (!c.pressed) break
                            dy = (c.position.y - startY).coerceIn(-travel, travel)
                            val n = -dy / travel // omhoog = positief = inzoomen
                            move(if (kotlin.math.abs(n) < 0.12f) 0.0 else n * ZOOM_MAX_THROW)
                        }
                    } finally {
                        pressed = false
                        dy = 0f
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", Modifier.align(Alignment.TopCenter).padding(top = 6.dp), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("−", Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(0, dy.toInt()) }
                .size(knob)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (pressed) Color(0xFF1565C0) else Color(0xCCFFFFFF)),
        )
    }
}
