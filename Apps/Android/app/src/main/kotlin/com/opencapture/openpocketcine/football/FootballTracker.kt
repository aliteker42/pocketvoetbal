package com.opencapture.openpocketcine.football

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.opencapture.opencine.core.CameraCommandIntent
import com.opencapture.opencine.dji.PocketCameraSession
import com.opencapture.opencine.vocabulary.FocusOverlay
import com.opencapture.opencine.vocabulary.TrackingBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Wat het scherm laat zien terwijl de voetbal-modus loopt. */
data class FootballState(
    val active: Boolean = false,
    val mode: DirectorMode = DirectorMode.IDLE,
    val players: List<Detection> = emptyList(),
    val balls: List<Detection> = emptyList(),
    val playX: Double = 0.5,
    val playY: Double = 0.5,
    val confidence: Double = 0.0,
    val cameraTracking: Boolean = false,
    val detectorLatencyMs: Long = 0,
    val detectorLabel: String = "–",
    val detectorError: String? = null,
    val recordingStartedByUs: Boolean = false,
    val lastActionLabel: String = "",
    val framesSeen: Long = 0,
)

/**
 * De lijm: live-beelden → [PlayerDetector] → [PlayFinder] → [CameraDirector] → camera-opdrachten op de
 * [PocketCameraSession]. Draait een 10 Hz regel-tik zolang de modus aan staat.
 */
class FootballTracker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessionOwner: () -> PocketCameraSession,
) {
    private val _state = MutableStateFlow(FootballState())
    val state: StateFlow<FootballState> = _state.asStateFlow()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    var settings: FootballSettings = FootballSettingsStore.load(context)
        private set

    private var detector: PlayerDetector? = null
    private var finder: PlayFinder? = null
    private var director: CameraDirector? = null
    private var tickJob: Job? = null
    @Volatile private var latestPlay: PlayEstimate? = null
    @Volatile private var latestObservation: SceneObservation? = null
    private var lastStick: Pair<Double, Double>? = null
    private var lastStickSentAt = 0L
    private var framesSeen = 0L

    private val session get() = sessionOwner()

    fun updateSettings(next: FootballSettings) {
        settings = next
        FootballSettingsStore.save(context, next)
        if (_active.value) {
            // Opnieuw starten met het nieuwe profiel; de camera blijft gewoon aan.
            val wasRecording = _state.value.recordingStartedByUs
            stopInternal(stopRecording = false)
            startInternal(startRecording = false)
            _state.value = _state.value.copy(recordingStartedByUs = wasRecording)
        }
    }

    fun start() {
        if (_active.value) return
        startInternal(startRecording = settings.recordWithTracking)
    }

    fun stop() {
        if (!_active.value) return
        stopInternal(stopRecording = settings.recordWithTracking && _state.value.recordingStartedByUs)
    }

    fun toggle() = if (_active.value) stop() else start()

    private fun startInternal(startRecording: Boolean) {
        val det = detector ?: PlayerDetector(context).also { detector = it }
        val profile = settings.profile
        finder = PlayFinder(profile)
        val dir = CameraDirector(settings)
        director = dir
        latestPlay = null
        latestObservation = null
        framesSeen = 0
        _active.value = true
        val now = nowSeconds()
        apply(dir.start(cameraObservation(), now))
        var startedRecording = false
        if (startRecording && !session.chromeStatus.value.isRecording) {
            runCatching { session.control.request(CameraCommandIntent.StartRecording) }
            startedRecording = true
        }
        _state.value =
            FootballState(
                active = true, mode = dir.mode, detectorLabel = det.delegateLabel, recordingStartedByUs = startedRecording,
                lastActionLabel = "Gestart · ${settings.sport.label} · ${settings.position.label}",
            )
        tickJob?.cancel()
        tickJob =
            scope.launch {
                while (isActive && _active.value) {
                    delay(TICK_MS)
                    tick()
                }
            }
    }

    private fun stopInternal(stopRecording: Boolean) {
        tickJob?.cancel()
        tickJob = null
        director?.let { apply(it.stop()) }
        director = null
        lastStick = null
        if (stopRecording && session.chromeStatus.value.isRecording) {
            runCatching { session.control.request(CameraCommandIntent.StopRecording) }
        }
        _active.value = false
        _state.value = FootballState(active = false, lastActionLabel = "Gestopt")
    }

    fun release() {
        if (_active.value) stopInternal(stopRecording = false)
        detector?.close()
        detector = null
    }

    // ---- Beelden ------------------------------------------------------------------------------

    /** Of het live-pad nu een beeld moet uitlezen voor ons. */
    fun wantsFrame(): Boolean = _active.value && detector?.wantsFrame() == true

    fun considerNv21(nv21: ByteArray, width: Int, height: Int): Boolean {
        val det = detector ?: return false
        if (!_active.value) return false
        return det.detectNv21(nv21, width, height, ::onObservation)
    }

    fun considerBitmap(bitmap: Bitmap): Boolean {
        val det = detector ?: return false
        if (!_active.value) return false
        return det.detectBitmap(bitmap, ::onObservation)
    }

    private fun onObservation(obs: SceneObservation) {
        val f = finder ?: return
        framesSeen++
        latestObservation = obs
        latestPlay = f.update(obs)
    }

    // ---- Regel-tik ----------------------------------------------------------------------------

    private fun tick() {
        val dir = director ?: return
        val now = nowSeconds()
        val camera = cameraObservation()
        val play = latestPlay ?: PlayEstimate(0.5, 0.5, 0.0, null, 0.0, 0, false, 0.0)
        // Een oude schatting (detector stil) telt als "niets gezien".
        val stale = latestObservation?.let { now - it.timeSeconds > STALE_SECONDS } ?: true
        val effective = if (stale) play.copy(confidence = 0.0, anchor = null, playerCount = 0) else play
        apply(dir.tick(effective, camera, now))
        // Stick-waarde af en toe herhalen zodat de verbinding hem niet als losgelaten ziet.
        lastStick?.let { (x, y) ->
            if (SystemClock.elapsedRealtime() - lastStickSentAt > STICK_REFRESH_MS) sendStick(x, y)
        }
        val obs = latestObservation
        val det = detector
        _state.value =
            _state.value.copy(
                mode = dir.mode,
                players = if (stale) emptyList() else obs?.persons.orEmpty(),
                balls = if (stale) emptyList() else obs?.balls.orEmpty(),
                playX = effective.x, playY = effective.y, confidence = effective.confidence,
                cameraTracking = camera.isTracking,
                detectorLatencyMs = det?.lastLatencyMs ?: 0, detectorLabel = det?.delegateLabel ?: "–",
                detectorError = det?.error, framesSeen = framesSeen,
            )
    }

    private fun cameraObservation(): CameraObservation {
        val s = session
        val hud = s.trackingHud.value
        val subject =
            when (val o = hud.overlay) {
                is FocusOverlay.Subject -> o.box
                is FocusOverlay.Search -> o.box
                else -> null
            }
        val pose = s.liveGimbalWaypoint
        return CameraObservation(
            isTracking = hud.isTracking,
            subjectX = subject?.centerX, subjectY = subject?.centerY,
            yawDeg = pose?.yawDeg, pitchDeg = pose?.pitchDeg,
            zoom = s.zoomCycleFrom(),
            zoomStops = s.zoomStops().ifEmpty { listOf(1.0, 2.0, 3.0) },
        )
    }

    private fun apply(actions: List<DirectorAction>) {
        if (actions.isEmpty()) return
        val s = session
        for (a in actions) {
            when (a) {
                is DirectorAction.Track -> {
                    s.startTracking(TrackingBox.fromCenter(a.cx, a.cy, a.w, a.h))
                    note("Volgt speler op ${(a.cx * 100).toInt()}%, ${(a.cy * 100).toInt()}%")
                }
                DirectorAction.CancelTrack -> {
                    s.cancelSubjectTracking()
                }
                is DirectorAction.Stick -> {
                    if (a.x == 0.0 && a.y == 0.0) {
                        lastStick = null
                        s.endGimbalStick()
                    } else {
                        lastStick = a.x to a.y
                        sendStick(a.x, a.y)
                    }
                }
                is DirectorAction.Zoom -> {
                    s.setZoom(a.factor)
                    note("Zoom ${"%.1f".format(a.factor)}×")
                }
            }
        }
    }

    private fun sendStick(x: Double, y: Double) {
        lastStickSentAt = SystemClock.elapsedRealtime()
        session.updateGimbalStick(x.toFloat(), y.toFloat(), linear = true)
    }

    private fun note(label: String) {
        _state.value = _state.value.copy(lastActionLabel = label)
    }

    private fun nowSeconds() = SystemClock.elapsedRealtime() / 1000.0

    companion object {
        const val TICK_MS = 100L
        const val STALE_SECONDS = 0.8
        const val STICK_REFRESH_MS = 300L
    }
}
