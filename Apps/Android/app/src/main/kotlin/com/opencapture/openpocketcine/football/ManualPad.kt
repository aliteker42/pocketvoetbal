package com.opencapture.openpocketcine.football

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

/**
 * Bedien-versie: links zoom (+/−), rechts pijlen. Ingedrukt houden = rustig bewegen met vaste
 * snelheid, loslaten = stoppen. Geen versnelling of naijlen, zodat het beeld stabiel blijft.
 */
@Composable
fun ManualPad(model: AppModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val driver = remember { PadDriver(model) }
    DisposableEffect(Unit) { onDispose { driver.stopAll() } }

    Box(modifier.fillMaxSize().navigationBarsPadding().padding(20.dp)) {
        // Links: zoom
        Column(
            Modifier.align(Alignment.CenterStart).panel(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HoldButton("+", 110.dp, ZoomColor, { driver.startZoom(scope, +ZOOM_PER_SECOND) }, { driver.stopZoom() })
            HoldButton("−", 110.dp, ZoomColor, { driver.startZoom(scope, -ZOOM_PER_SECOND) }, { driver.stopZoom() })
        }
        // Rechts: draaien
        Column(
            Modifier.align(Alignment.CenterEnd).panel(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val b = 96.dp
            HoldButton("▲", b, ArrowColor, { driver.startStick(scope, 0f, TILT_STICK) }, { driver.stopStick() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton("◀", b, ArrowColor, { driver.startStick(scope, -PAN_STICK, 0f) }, { driver.stopStick() })
                Spacer(Modifier.size(b))
                HoldButton("▶", b, ArrowColor, { driver.startStick(scope, PAN_STICK, 0f) }, { driver.stopStick() })
            }
            HoldButton("▼", b, ArrowColor, { driver.startStick(scope, 0f, -TILT_STICK) }, { driver.stopStick() })
        }
    }
}

private val ArrowColor = Color(0xFF2E7D32)
private val ZoomColor = Color(0xFF1565C0)

/** Vaste, rustige stick-uitslag (0..1). */
private const val PAN_STICK = 0.35f
private const val TILT_STICK = 0.25f
/** Zoomsnelheid in "×" per seconde. */
private const val ZOOM_PER_SECOND = 0.8

private fun Modifier.panel() =
    clip(RoundedCornerShape(28.dp)).background(Color(0x88000000)).padding(12.dp)

/** Houdt stick/zoom vast zolang een knop ingedrukt is; loslaten zet alles meteen stil. */
private class PadDriver(private val model: AppModel) {
    private var stickJob: Job? = null
    private var zoomJob: Job? = null
    private var zoomCurrent = 1.0

    fun startStick(scope: CoroutineScope, x: Float, y: Float) {
        stickJob?.cancel()
        stickJob = scope.launch {
            while (isActive) {
                // Lineair: precies deze uitslag, zonder het versnellingsfilter van de duimstick.
                if (model.canDriveGimbalPad()) {
                    model.session.updateGimbalStick(x, y, assistMirror = model.mirrorsHorizontally, linear = true)
                }
                delay(100)
            }
        }
    }

    fun stopStick() {
        stickJob?.cancel()
        stickJob = null
        model.endGimbalStick()
    }

    fun startZoom(scope: CoroutineScope, perSecond: Double) {
        zoomJob?.cancel()
        zoomCurrent = model.zoomReadout.coerceAtLeast(1.0)
        zoomJob = scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            var sent = zoomCurrent
            while (isActive) {
                delay(100)
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = ((now - last) / 1000.0).coerceAtMost(0.3)
                last = now
                val max = model.zoomMax().coerceAtLeast(1.0)
                zoomCurrent = (zoomCurrent + perSecond * dt).coerceIn(1.0, max)
                // Alleen versturen als het echt verandert: geen stroom identieke opdrachten.
                if (kotlin.math.abs(zoomCurrent - sent) >= 0.05) {
                    model.session.setZoomSlider(zoomCurrent)
                    sent = zoomCurrent
                }
            }
        }
    }

    fun stopZoom() {
        zoomJob?.cancel()
        zoomJob = null
    }

    fun stopAll() {
        stopStick()
        stopZoom()
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
