package com.opencapture.openpocketcine.football

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencapture.openpocketcine.AppModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Snelheid van draaien en zoomen in de bedien-versie. */
private enum class PadSpeed(val label: String, val stick: Float, val zoomPerSecond: Double) {
    TRAAG("Traag", 0.30f, 0.6),
    NORMAAL("Normaal", 0.60f, 1.2),
    SNEL("Snel", 1.00f, 2.4),
}

private class PadPrefs(context: Context) {
    private val p = context.getSharedPreferences("openpocketcine.bediening", Context.MODE_PRIVATE)
    var speed: PadSpeed
        get() = PadSpeed.values().getOrNull(p.getInt("speed", 1)) ?: PadSpeed.NORMAAL
        set(v) = p.edit().putInt("speed", v.ordinal).apply()
    var onLeft: Boolean
        get() = p.getBoolean("onLeft", false)
        set(v) = p.edit().putBoolean("onLeft", v).apply()
    var flipX: Boolean
        get() = p.getBoolean("flipX", false)
        set(v) = p.edit().putBoolean("flipX", v).apply()
    var flipY: Boolean
        get() = p.getBoolean("flipY", false)
        set(v) = p.edit().putBoolean("flipY", v).apply()
}

/**
 * Bedien-versie: één paneel met grote knoppen. Ingedrukt houden = blijven draaien/zoomen, loslaten =
 * stoppen. Pijlen sturen de gimbal (zelfde weg als een gamepad), + en − zoomen traploos.
 */
@Composable
fun ManualPad(model: AppModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { PadPrefs(context) }
    var speed by remember { mutableStateOf(prefs.speed) }
    var onLeft by remember { mutableStateOf(prefs.onLeft) }
    var flipX by remember { mutableStateOf(prefs.flipX) }
    var flipY by remember { mutableStateOf(prefs.flipY) }
    var showMenu by remember { mutableStateOf(false) }
    val zoomNow by model.session.zoomReadout.collectAsState()
    val scope = rememberCoroutineScope()
    val driver = remember { PadDriver(model) }
    DisposableEffect(Unit) { onDispose { driver.stopAll() } }

    Box(
        modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
        contentAlignment = if (onLeft) Alignment.BottomStart else Alignment.BottomEnd,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0x99000000))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = if (onLeft) Alignment.Start else Alignment.End,
        ) {
            if (showMenu) {
                PadMenu(
                    speed, flipX, flipY, onLeft,
                    onSpeed = { speed = it; prefs.speed = it },
                    onFlipX = { flipX = it; prefs.flipX = it },
                    onFlipY = { flipY = it; prefs.flipY = it },
                    onSide = { onLeft = it; prefs.onLeft = it },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val pad: @Composable () -> Unit = {
                    DPad(
                        onPress = { dx, dy ->
                            val sx = (if (flipX) -dx else dx) * speed.stick
                            val sy = (if (flipY) -dy else dy) * speed.stick
                            driver.startStick(scope, sx, sy)
                        },
                        onRelease = { driver.stopStick() },
                        onCenter = { driver.stopStick(); model.session.recenterGimbal() },
                    )
                }
                val zoom: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HoldButton("+", size = 92.dp, color = Color(0xFF1565C0),
                            onPress = { driver.startZoom(scope, +speed.zoomPerSecond) }, onRelease = { driver.stopZoom() })
                        Text("%.1f×".format(zoomNow), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        HoldButton("−", size = 92.dp, color = Color(0xFF1565C0),
                            onPress = { driver.startZoom(scope, -speed.zoomPerSecond) }, onRelease = { driver.stopZoom() })
                        SmallButton("1×") { driver.stopZoom(); model.session.setZoomSlider(1.0) }
                    }
                }
                if (onLeft) { zoom(); pad() } else { pad(); zoom() }
            }
            SmallButton(if (showMenu) "Klaar" else "⚙ ${speed.label}") { showMenu = !showMenu }
        }
    }
}

/** Houdt de stick/zoom vast zolang een knop ingedrukt is; loslaten zet alles stil. */
private class PadDriver(private val model: AppModel) {
    private var stickJob: Job? = null
    private var zoomJob: Job? = null
    private var zoomAnchor = 1.0
    private var zoomCurrent = 1.0

    fun startStick(scope: kotlinx.coroutines.CoroutineScope, x: Float, y: Float) {
        stickJob?.cancel()
        stickJob = scope.launch {
            while (isActive) {
                if (model.canDriveGimbalPad()) model.updateGimbalPadStick(x, y)
                delay(80)
            }
        }
    }

    fun stopStick() {
        stickJob?.cancel()
        stickJob = null
        model.endGimbalStick()
    }

    fun startZoom(scope: kotlinx.coroutines.CoroutineScope, perSecond: Double) {
        zoomJob?.cancel()
        zoomAnchor = model.zoomReadout.coerceAtLeast(1.0)
        zoomCurrent = zoomAnchor
        zoomJob = scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            // Meteen een klein stapje, zodat een korte tik ook iets doet.
            zoomCurrent = (zoomCurrent + perSecond * 0.15).coerceIn(1.0, model.zoomMax().coerceAtLeast(1.0))
            model.updateZoomPinch(zoomCurrent / zoomAnchor)
            while (isActive) {
                delay(50)
                val now = android.os.SystemClock.elapsedRealtime()
                val dt = (now - last) / 1000.0
                last = now
                zoomCurrent = (zoomCurrent + perSecond * dt).coerceIn(1.0, model.zoomMax().coerceAtLeast(1.0))
                model.updateZoomPinch(zoomCurrent / zoomAnchor)
            }
        }
    }

    fun stopZoom() {
        if (zoomJob == null) return
        zoomJob?.cancel()
        zoomJob = null
        model.endZoomPinch()
    }

    fun stopAll() {
        stopStick()
        stopZoom()
    }
}

@Composable
private fun DPad(onPress: (Float, Float) -> Unit, onRelease: () -> Unit, onCenter: () -> Unit) {
    val b = 84.dp
    val arrow = Color(0xFF2E7D32)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HoldButton("▲", b, arrow, { onPress(0f, 1f) }, onRelease)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HoldButton("◀", b, arrow, { onPress(-1f, 0f) }, onRelease)
            Box(
                Modifier.size(b).clip(RoundedCornerShape(42.dp)).background(Color(0x55FFFFFF)).clickable { onCenter() },
                contentAlignment = Alignment.Center,
            ) { Text("Midden", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            HoldButton("▶", b, arrow, { onPress(1f, 0f) }, onRelease)
        }
        HoldButton("▼", b, arrow, { onPress(0f, -1f) }, onRelease)
    }
}

@Composable
private fun HoldButton(label: String, size: Dp, color: Color, onPress: () -> Unit, onRelease: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val press by androidx.compose.runtime.rememberUpdatedState(onPress)
    val release by androidx.compose.runtime.rememberUpdatedState(onRelease)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) Color.White else color)
            .border(2.dp, Color(0x66FFFFFF), RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    press()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) color else Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x55FFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PadMenu(
    speed: PadSpeed, flipX: Boolean, flipY: Boolean, onLeft: Boolean,
    onSpeed: (PadSpeed) -> Unit, onFlipX: (Boolean) -> Unit, onFlipY: (Boolean) -> Unit, onSide: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Snelheid", color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in PadSpeed.values()) Choice(s.label, s == speed) { onSpeed(s) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Links/rechts omdraaien", flipX) { onFlipX(!flipX) }
            Choice("Op/neer omdraaien", flipY) { onFlipY(!flipY) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Paneel links", onLeft) { onSide(true) }
            Choice("Paneel rechts", !onLeft) { onSide(false) }
        }
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF2E7D32) else Color(0x44FFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, color = Color.White, fontSize = 14.sp) }
}
