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
    // Alleen handbediening: de camera mag nooit zelf iemand gaan volgen of een programma draaien.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        model.session.cancelProgrammedMove()
        // Alleen als de camera echt iemand volgt, en dan één keer: geen stroom van stop-opdrachten
        // die de draaibeweging onderbreken.
        var wasTracking = false
        while (isActive) {
            val tracking = model.session.trackingHud.value.isTracking
            if (tracking && !wasTracking) model.session.cancelSubjectTracking()
            wasTracking = tracking
            delay(500)
        }
    }

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
        // Rechts onder: grote joystick, zoals de duimstick maar groter
        BigJoystick(
            Modifier.align(Alignment.CenterEnd),
            onMove = { x, y -> driver.setStick(scope, x, y) },
            onRelease = { driver.stopStick() },
        )
    }
}

private val ZoomColor = Color(0xFF1565C0)

/** Maximale stick-uitslag bij joystick helemaal tegen de rand (rustig, voor stabiel beeld). */
private const val MAX_PAN = 0.6f
private const val MAX_TILT = 0.4f
private const val MIN_STICK = 0.12f
/** Zoomsnelheid in "×" per seconde. */
private const val ZOOM_PER_SECOND = 0.8

private fun Modifier.panel() =
    clip(RoundedCornerShape(28.dp)).background(Color(0x88000000)).padding(12.dp)

/** Houdt stick/zoom vast zolang een knop ingedrukt is; loslaten zet alles meteen stil. */
private class PadDriver(private val model: AppModel) {
    private var stickJob: Job? = null
    private var zoomJob: Job? = null
    private var zoomCurrent = 1.0

    @Volatile private var stickX = 0f
    @Volatile private var stickY = 0f

    /** Joystick-stand doorgeven; zolang hij niet in het midden staat blijft de camera draaien. */
    fun setStick(scope: CoroutineScope, x: Float, y: Float) {
        stickX = x
        stickY = y
        if (stickJob?.isActive == true) return
        stickJob = scope.launch {
            // Zelfde manier als de voetbal-app: alleen versturen bij een echte verandering, en verder
            // rustig elke 300 ms herhalen zodat de camera weet dat de stick nog vastgehouden wordt.
            var sentX = Float.NaN
            var sentY = Float.NaN
            var sentAt = 0L
            while (isActive) {
                val x0 = stickX
                val y0 = stickY
                val now = android.os.SystemClock.elapsedRealtime()
                val changed = sentX.isNaN() || kotlin.math.abs(x0 - sentX) >= 0.03f || kotlin.math.abs(y0 - sentY) >= 0.03f
                if ((changed || now - sentAt >= 300) && model.canDriveGimbalPad()) {
                    model.session.updateGimbalStick(x0, y0, linear = true)
                    sentX = x0
                    sentY = y0
                    sentAt = now
                }
                delay(50)
            }
        }
    }

    fun stopStick() {
        stickX = 0f
        stickY = 0f
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
                delay(250)
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = ((now - last) / 1000.0).coerceAtMost(0.5)
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

/**
 * Grote joystick: knop in een cirkel slepen. Kleine uitslag = heel langzaam (fijn bijsturen), tegen de
 * rand = maximaal. Loslaten = knop terug naar het midden en de camera staat meteen stil.
 */
@Composable
private fun BigJoystick(modifier: Modifier, onMove: (Float, Float) -> Unit, onRelease: () -> Unit) {
    val size = 260.dp
    val knob = 100.dp
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(0x88000000))
            .border(3.dp, Color(0x88FFFFFF), androidx.compose.foundation.shape.CircleShape)
            .pointerInput(Unit) {
                val radius = (this.size.width - knob.toPx()) / 2f
                val center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
                fun update(pos: androidx.compose.ui.geometry.Offset) {
                    var d = pos - center
                    val len = d.getDistance()
                    if (len > radius) d = d * (radius / len)
                    offset = d
                    val nx = d.x / radius
                    val ny = -d.y / radius
                    // Dode zone in het midden en een kromme: rustig bij kleine uitslag.
                    // Buiten de dode zone meteen een vaste minimale snelheid (anders schakelt de camera
                    // rond zijn eigen dode zone steeds aan/uit = schokken), daarna een rustige kromme.
                    fun curve(v: Float, max: Float): Float {
                        val a = kotlin.math.abs(v)
                        if (a < 0.15f) return 0f
                        val t = (a - 0.15f) / 0.85f
                        return kotlin.math.sign(v) * (MIN_STICK + (max - MIN_STICK) * t * t)
                    }
                    // Vooral horizontaal duwen = alleen draaien, niet ook kantelen (en andersom).
                    val x = if (kotlin.math.abs(nx) >= kotlin.math.abs(ny) * 0.5f) curve(nx, MAX_PAN) else 0f
                    val y = if (kotlin.math.abs(ny) >= kotlin.math.abs(nx) * 0.5f) curve(ny, MAX_TILT) else 0f
                    if (x == 0f && y == 0f) release() else move(x, y)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    update(down.position)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            c.consume()
                            if (!c.pressed) break
                            update(c.position)
                        }
                    } finally {
                        offset = androidx.compose.ui.geometry.Offset.Zero
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offset.x.toInt(), offset.y.toInt()) }
                .size(knob)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0xFF2E7D32))
                .border(3.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
        )
    }
}
