package com.opencapture.openpocketcine.football

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PlayerColor = Color(0xFF4CD964)
private val BallColor = Color(0xFFFFD60A)
private val PlayColor = Color(0xFFFF453A)

/**
 * Herkende spelers (groen), bal (geel) en het spelpunt (rood kruis) over het live-beeld. Hoort in de
 * picture-slot van de monitor zodat 0..1 precies op het beeld valt.
 */
@Composable
fun FootballOverlay(tracker: FootballTracker, mirrored: Boolean, modifier: Modifier = Modifier) {
    val state by tracker.state.collectAsState()
    if (!state.active || !tracker.settings.showOverlay) return
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun fx(x: Double) = (if (mirrored) 1 - x else x).toFloat() * w
        for (p in state.players) {
            val left = fx(p.cx - p.w / 2)
            val right = fx(p.cx + p.w / 2)
            drawRect(
                PlayerColor,
                topLeft = Offset(minOf(left, right), ((p.cy - p.h / 2) * h).toFloat()),
                size = Size(kotlin.math.abs(right - left), (p.h * h).toFloat()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        for (b in state.balls) {
            drawCircle(BallColor, radius = maxOf(6.dp.toPx(), (b.w * w / 2).toFloat()),
                center = Offset(fx(b.cx), (b.cy * h).toFloat()), style = Stroke(width = 3.dp.toPx()))
        }
        if (state.confidence > 0) {
            val cx = fx(state.playX)
            val cy = (state.playY * h).toFloat()
            val r = 14.dp.toPx()
            drawLine(PlayColor, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 3.dp.toPx())
            drawLine(PlayColor, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 3.dp.toPx())
        }
    }
}

/**
 * Banner na een crash: de app is de vorige keer onverwacht gestopt; deel het logboek of sluit.
 * Staat boven alles in de activity zodat hij ook verschijnt als de app vóór het live-beeld crasht.
 */
@Composable
fun CrashBanner(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var last by remember { mutableStateOf(CrashLog.lastCrashDescription(context)) }
    val when_ = last ?: return
    Column(
        modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6B71C1C))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("De app is gecrasht ($when_)", color = Color.White, fontWeight = FontWeight.SemiBold)
        Text("Deel het logboek zodat de oorzaak gevonden kan worden.", color = Color.White, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { CrashLog.share(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C))) {
                Text("Logboek delen")
            }
            Button(onClick = { CrashLog.clear(context); last = null }, colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFFFFF))) {
                Text("Sluiten")
            }
        }
    }
}

/** De ⚽-knop boven in beeld met status; tikken opent de instellingen en Start/Stop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootballControls(tracker: FootballTracker, modifier: Modifier = Modifier) {
    val state by tracker.state.collectAsState()
    var sheetOpen by remember { mutableStateOf(false) }
    val label =
        if (state.active) {
            val extra = if (state.players.isNotEmpty()) " · ${state.players.size} spelers" else ""
            "⚽ ${state.mode.label}$extra" + if (state.cameraTracking) " · volgt" else ""
        } else "⚽ Voetbal"
    Row(
        modifier
            .statusBarsPadding()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (state.active) Color(0xCC1B5E20) else Color(0x99000000))
            .clickable { sheetOpen = true }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            FootballSheet(tracker, onClose = { sheetOpen = false })
        }
    }
}

@Composable
private fun FootballSheet(tracker: FootballTracker, onClose: () -> Unit) {
    val state by tracker.state.collectAsState()
    var s by remember { mutableStateOf(tracker.settings) }
    fun change(next: FootballSettings) {
        s = next
        tracker.updateSettings(next)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Voetbal volgen", style = MaterialTheme.typography.titleLarge)
        Text(
            "Richt de camera op het midden van het veld en druk op Start. De app herkent spelers en bal in het " +
                "live-beeld en laat de Pocket het spel volgen.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { tracker.toggle(); if (!state.active) onClose() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (state.active) Color(0xFFB71C1C) else Color(0xFF1B5E20)),
        ) {
            Text(if (state.active) "STOP" else "START", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        if (state.active) {
            StatusBlock(state)
            HorizontalDivider()
        }

        Section("Sport") {
            ChipRow(Sport.values().toList(), s.sport, { it.label }) { change(s.copy(sport = it)) }
        }
        Section("Positie camera") {
            ChipRow(CameraPosition.values().toList(), s.position, { it.label }) { change(s.copy(position = it)) }
            if (s.position == CameraPosition.ZIJLIJN) {
                Spacer(Modifier.height(6.dp))
                ChipRow(PitchSide.values().toList(), s.pitchSide, { it.label }) { change(s.copy(pitchSide = it)) }
            }
        }
        Section("Sturing") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (st in Steering.values()) {
                    FilterChip(selected = s.steering == st, onClick = { change(s.copy(steering = st)) }, label = { Text(st.label) })
                }
                Text(s.steering.description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("Zoom") {
            ToggleRow("Automatisch zoomen", s.autoZoom) { change(s.copy(autoZoom = it)) }
            Text("Maximale zoom", style = MaterialTheme.typography.bodyMedium)
            ChipRow(listOf(2.0, 3.0, 4.0, 6.0), s.maxZoom, { "${it.toInt()}×" }) { change(s.copy(maxZoom = it)) }
        }
        Section("Overig") {
            ToggleRow("Opname starten/stoppen met Start/Stop", s.recordWithTracking) { change(s.copy(recordWithTracking = it)) }
            ToggleRow("Herkenning over het beeld tekenen", s.showOverlay) { change(s.copy(showOverlay = it)) }
            ToggleRow("GPU-versnelling detector (experimenteel)", s.gpuDetector) { change(s.copy(gpuDetector = it)) }
            val context = androidx.compose.ui.platform.LocalContext.current
            Button(onClick = { CrashLog.share(context) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) {
                Text("Logboek / crashlog delen")
            }
        }
    }
}

@Composable
private fun StatusBlock(state: FootballState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(state.mode.label, fontWeight = FontWeight.SemiBold)
        Text("Spelers: ${state.players.size} · Bal: ${if (state.balls.isNotEmpty()) "ja" else "nee"} · Zekerheid ${(state.confidence * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall)
        Text("Camera volgt: ${if (state.cameraTracking) "ja" else "nee"} · ${state.lastActionLabel}", style = MaterialTheme.typography.bodySmall)
        Text("Detector: ${state.detectorLabel} · ${state.detectorLatencyMs} ms · ${state.framesSeen} beelden",
            style = MaterialTheme.typography.bodySmall)
        state.detectorError?.let { Text("Fout: $it", color = Color(0xFFB71C1C), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
