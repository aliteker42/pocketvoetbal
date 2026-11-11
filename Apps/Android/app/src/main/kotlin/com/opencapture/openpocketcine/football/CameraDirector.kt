package com.opencapture.openpocketcine.football

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.tan

/** Wat de camera op dit moment van zichzelf weet. */
data class CameraObservation(
    /** De Pocket volgt een onderwerp (ActiveTrack actief). */
    val isTracking: Boolean,
    /** Middelpunt van het door de camera gemelde onderwerp, of null. */
    val subjectX: Double?,
    val subjectY: Double?,
    /** Gimbal-hoek (graden, rechts positief) of null als onbekend. */
    val yawDeg: Double?,
    /** Gimbal-kanteling (graden, omhoog positief) of null. */
    val pitchDeg: Double?,
    /** Huidige zoomfactor. */
    val zoom: Double,
    /** Toegestane zoomstops, oplopend. */
    val zoomStops: List<Double>,
)

sealed class DirectorAction {
    /** Geef dit kader (0..1) aan de eigen tracking van de camera. */
    data class Track(val cx: Double, val cy: Double, val w: Double, val h: Double) : DirectorAction()
    data object CancelTrack : DirectorAction()
    /** Virtuele joystick, −1..1 per as; (0,0) = loslaten. */
    data class Stick(val x: Double, val y: Double) : DirectorAction()
    data class Zoom(val factor: Double) : DirectorAction()
}

enum class DirectorMode(val label: String) {
    IDLE("Uit"),
    SEARCHING("Zoekt spelers"),
    FOLLOWING("Volgt het spel"),
    LOST("Spel kwijt — zoomt uit"),
}

/**
 * Pure regelaar (geen Android): spelschatting + camerastatus → camera-acties.
 *
 * Twee manieren van sturen:
 *  - CAMERA_TRACK: we kiezen een speler en geven hem aan ActiveTrack. De camera doet het soepele
 *    werk; wij wisselen alleen van speler als het spel te ver van hem weg is.
 *  - APP_STEER: we sturen de gimbal zelf. De regelaar rekent in graden (de beeldfout maal het
 *    gezichtsveld bij de huidige zoom), zodat 3× niet drie keer zo zenuwachtig is als 1×. Het teken
 *    en de snelheid van de joystick-assen worden al rijdend geleerd uit de gimbal-hoeken, en de
 *    relatie beeld-rechts ↔ yaw-omhoog uit hoe de spelerswolk door het beeld schuift.
 */
class CameraDirector(private val settings: FootballSettings) {
    private val profile = settings.profile

    var mode: DirectorMode = DirectorMode.IDLE
        private set

    /** Richting waarin de camera stond bij Start: het midden van het veld. */
    private var homeYaw: Double? = null

    private var lastRetargetAt = Double.NEGATIVE_INFINITY
    private var offTargetSince: Double? = null
    private var lostSince: Double? = null
    private var seenSince: Double? = null
    private var centeredSince: Double? = null
    private var lastZoomAt = Double.NEGATIVE_INFINITY
    private var zoomedOutForLost = false
    private var trackRequestedAt = Double.NEGATIVE_INFINITY

    // Leren van de assen (APP_STEER en terug-naar-thuis).
    private val panStick = AxisLearner()
    private val tiltStick = AxisLearner()
    private val imageYaw = ImageYawSignLearner()
    private var lastYaw: Double? = null
    private var lastPitch: Double? = null
    private var lastMeanPlayerX: Double? = null
    private var lastStickX = 0.0
    private var lastStickY = 0.0
    private var stickSince: Double? = null
    private var lastTickAt: Double? = null
    private var stickHeld = false

    fun start(camera: CameraObservation, now: Double): List<DirectorAction> {
        homeYaw = camera.yawDeg
        mode = DirectorMode.SEARCHING
        lostSince = now
        seenSince = null
        centeredSince = null
        lastRetargetAt = Double.NEGATIVE_INFINITY
        trackRequestedAt = Double.NEGATIVE_INFINITY
        zoomedOutForLost = false
        lastTickAt = now
        lastYaw = camera.yawDeg
        lastPitch = camera.pitchDeg
        val actions = mutableListOf<DirectorAction>()
        val startZoom = nearestStop(profile.defaultZoom, allowedStops(camera.zoomStops))
        if (abs(startZoom - camera.zoom) > 0.05) {
            actions += DirectorAction.Zoom(startZoom)
            lastZoomAt = now
        }
        return actions
    }

    fun stop(): List<DirectorAction> {
        mode = DirectorMode.IDLE
        val actions = mutableListOf<DirectorAction>()
        if (stickHeld) actions += DirectorAction.Stick(0.0, 0.0)
        stickHeld = false
        lastStickX = 0.0
        lastStickY = 0.0
        actions += DirectorAction.CancelTrack
        return actions
    }

    fun tick(play: PlayEstimate, camera: CameraObservation, now: Double): List<DirectorAction> {
        if (mode == DirectorMode.IDLE) return emptyList()
        val actions = mutableListOf<DirectorAction>()
        val dt = lastTickAt?.let { (now - it).coerceIn(0.01, 1.0) } ?: 0.1
        lastTickAt = now
        learn(play, camera, dt, now)

        val seen = play.confidence > 0.0
        if (seen) {
            if (seenSince == null) seenSince = now
            lostSince = null
        } else {
            seenSince = null
            centeredSince = null
            if (lostSince == null) lostSince = now
        }
        val lostFor = lostSince?.let { now - it } ?: 0.0
        val lost = !seen && lostFor >= profile.lostAfterSeconds

        when {
            lost -> handleLost(camera, now, actions)
            seen -> {
                mode = DirectorMode.FOLLOWING
                restoreZoomAfterLost(camera, now, actions)
                when (settings.steering) {
                    Steering.CAMERA_TRACK -> followByTracking(play, camera, now, actions)
                    Steering.APP_STEER -> followBySteering(play, camera, now, actions)
                }
                if (settings.autoZoom) autoZoom(play, camera, now, actions)
            }
            else -> {
                // Even niets gezien (een gemiste detectie, een traag beeld): niets overhaasten. Bij
                // APP_STEER de huidige beweging nog even door laten lopen; pas na een korte tijd
                // de stick loslaten. Anders wordt elke hik in de detectie een hik in de gimbal.
                if (mode != DirectorMode.LOST) mode = DirectorMode.SEARCHING
                if (settings.steering == Steering.APP_STEER && lostFor >= COAST_SECONDS) releaseStick(actions)
            }
        }
        return actions
    }

    // ---- CAMERA_TRACK -------------------------------------------------------------------------

    private fun followByTracking(play: PlayEstimate, camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>) {
        val anchor = play.anchor ?: return
        // Buiten de pan-grens: tracking loslaten en terugduwen, anders volgt ActiveTrack een speler
        // het hek in.
        val overLimit = panOverLimit(camera.yawDeg)
        if (overLimit != 0.0) {
            if (camera.isTracking) actions += DirectorAction.CancelTrack
            holdStick(-overLimit * 0.4 * panStick.sign, 0.0, now, actions)
            lastRetargetAt = now
            return
        }
        releaseStick(actions)

        val requestPending = now - trackRequestedAt < TRACK_SETTLE_SECONDS
        if (!camera.isTracking) {
            if (!requestPending && now - lastRetargetAt >= RETRY_TRACK_SECONDS) {
                actions += trackAction(anchor)
                lastRetargetAt = now
                trackRequestedAt = now
                offTargetSince = null
            }
            return
        }
        val sx = camera.subjectX
        val sy = camera.subjectY
        if (sx == null || sy == null) return
        val off = hypot(play.x - sx, play.y - sy)
        if (off > profile.retargetDistance) {
            if (offTargetSince == null) offTargetSince = now
            val heldFor = now - offTargetSince!!
            if (heldFor >= profile.retargetHoldSeconds && now - lastRetargetAt >= profile.retargetMinIntervalSeconds &&
                anchor.distanceTo(sx, sy) > profile.retargetDistance * 0.5
            ) {
                actions += trackAction(anchor)
                lastRetargetAt = now
                trackRequestedAt = now
                offTargetSince = null
            }
        } else {
            offTargetSince = null
        }
    }

    private fun trackAction(anchor: Detection): DirectorAction.Track {
        // Mimo weigert kaders kleiner dan ~9% per zijde; een verre speler krijgt een ruimer kader
        // rond zijn middelpunt.
        val w = max(anchor.w * 1.3, MIN_TRACK_SIDE)
        val h = max(anchor.h * 1.1, MIN_TRACK_SIDE)
        return DirectorAction.Track(anchor.cx.coerceIn(w / 2, 1 - w / 2), anchor.cy.coerceIn(h / 2, 1 - h / 2), w, h)
    }

    // ---- APP_STEER ----------------------------------------------------------------------------

    private fun followBySteering(play: PlayEstimate, camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>) {
        if (camera.isTracking) actions += DirectorAction.CancelTrack
        var ex = play.x - 0.5
        val ey = play.y - profile.targetY
        // Spelers tegen de rand: het spel loopt die kant uit, al vóór het zwaartepunt daar is.
        if (abs(play.edgePressure) > 0.5) ex += 0.12 * sign(play.edgePressure)

        val fov = horizontalFovDeg(camera.zoom)
        // Gewenste draaisnelheid in graden/s: fout in graden maal Kp (tijdconstante ≈ 1/Kp).
        var yawRate = if (abs(ex) > profile.steerDeadband) STEER_KP * ex * fov * imageYaw.sign else 0.0
        var pitchRate = if (abs(ey) > profile.steerDeadband * 1.5) -STEER_KP * 0.5 * ey * fov * (9.0 / 16.0) else 0.0
        val maxRate = profile.steerMax * panStick.degPerSecondAtFullStick
        yawRate = yawRate.coerceIn(-maxRate, maxRate)
        pitchRate = pitchRate.coerceIn(-maxRate * 0.5, maxRate * 0.5)

        // Pan-grens: niet verder die kant op.
        val over = panOverLimit(camera.yawDeg)
        if (over != 0.0 && sign(yawRate) == sign(over)) yawRate = 0.0

        val stickX = panStick.stickFor(yawRate)
        val stickY = tiltStick.stickFor(pitchRate)
        if (stickX == 0.0 && stickY == 0.0) releaseStick(actions) else holdStick(stickX, stickY, now, actions)
    }

    /** Leer tekens en snelheden uit wat de gimbal en de spelerswolk deden sinds de vorige tik. */
    private fun learn(play: PlayEstimate, camera: CameraObservation, dt: Double, now: Double) {
        val yaw = camera.yawDeg
        val pitch = camera.pitchDeg
        val steady = stickSince?.let { now - it >= STEADY_STICK_SECONDS } == true
        if (yaw != null && lastYaw != null) {
            val dYaw = wrapDeg(yaw - lastYaw!!)
            if (lastStickX != 0.0) panStick.observe(lastStickX, dYaw, dt, steady)
            // Camera naar rechts (yaw omhoog) ⇒ spelers schuiven naar links in beeld.
            val meanX = play.meanPlayerX
            if (meanX != null && lastMeanPlayerX != null && play.playerCount >= 3) imageYaw.observe(dYaw, meanX - lastMeanPlayerX!!)
            lastMeanPlayerX = meanX
        } else {
            lastMeanPlayerX = play.meanPlayerX
        }
        if (pitch != null && lastPitch != null && lastStickY != 0.0) tiltStick.observe(lastStickY, pitch - lastPitch!!, dt, steady)
        lastYaw = yaw
        lastPitch = pitch
    }

    // ---- Kwijt --------------------------------------------------------------------------------

    private fun handleLost(camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>) {
        mode = DirectorMode.LOST
        if (camera.isTracking) actions += DirectorAction.CancelTrack
        if (!zoomedOutForLost) {
            val target = nearestStop(profile.lostZoom, allowedStops(camera.zoomStops))
            if (target < camera.zoom - 0.05) {
                actions += DirectorAction.Zoom(target)
                lastZoomAt = now
            }
            zoomedOutForLost = true
        }
        // Rustig terug naar de startrichting (midden van het veld).
        val yaw = camera.yawDeg
        val home = homeYaw
        if (yaw != null && home != null) {
            val delta = wrapDeg(home - yaw)
            if (abs(delta) > HOME_TOLERANCE_DEG) {
                val rate = (delta * 0.8).coerceIn(-HOME_MAX_RATE, HOME_MAX_RATE)
                holdStick(panStick.stickFor(rate), 0.0, now, actions)
                return
            }
        }
        releaseStick(actions)
    }

    private fun restoreZoomAfterLost(camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>) {
        if (!zoomedOutForLost) return
        val seenFor = seenSince?.let { now - it } ?: 0.0
        if (seenFor < 1.0) return
        zoomedOutForLost = false
        if (!settings.autoZoom) {
            val target = nearestStop(profile.defaultZoom, allowedStops(camera.zoomStops))
            if (abs(target - camera.zoom) > 0.05) {
                actions += DirectorAction.Zoom(target)
                lastZoomAt = now
            }
        }
    }

    // ---- Zoom ---------------------------------------------------------------------------------

    private fun autoZoom(play: PlayEstimate, camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>) {
        val centered = abs(play.x - 0.5) < CENTERED_BAND
        if (centered) { if (centeredSince == null) centeredSince = now } else centeredSince = null
        if (now - lastZoomAt < ZOOM_MIN_INTERVAL_SECONDS) return
        val stops = allowedStops(camera.zoomStops)
        val current = nearestStop(camera.zoom, stops)
        val idx = stops.indexOf(current)
        val tall = play.anchor?.h ?: 0.0
        val target =
            when {
                // Uitzoomen: speler te groot, spelers over de hele breedte, of het spel hangt aan een rand.
                (tall > profile.zoomOutWhenTallerThan || play.extentX > WIDE_EXTENT || abs(play.edgePressure) > 0.6) && idx > 0 ->
                    stops[idx - 1]
                // Inzoomen: pas als het spel een paar seconden rustig in het midden staat en na de zoom
                // alle spelers nog ruim in beeld passen.
                idx < stops.size - 1 && tall in 0.01..profile.zoomInWhenShorterThan && play.playerCount >= 2 &&
                    (centeredSince?.let { now - it } ?: 0.0) >= ZOOM_IN_STABLE_SECONDS &&
                    play.extentX * (stops[idx + 1] / current) < ZOOM_IN_FIT -> stops[idx + 1]
                else -> return
            }
        if (abs(target - camera.zoom) < 0.05) return
        actions += DirectorAction.Zoom(target)
        lastZoomAt = now
        centeredSince = null
    }

    private fun allowedStops(stops: List<Double>): List<Double> =
        stops.filter { it <= settings.maxZoom + 0.01 }.ifEmpty { listOf(1.0) }

    // ---- Hulp ---------------------------------------------------------------------------------

    /** 0 binnen de grens; anders de overschrijding als teken (+ rechts, − links), geschaald 0.1..1. */
    private fun panOverLimit(yawDeg: Double?): Double {
        val yaw = yawDeg ?: return 0.0
        val home = homeYaw ?: return 0.0
        val rel = wrapDeg(yaw - home)
        return when {
            rel > profile.panLimitRightDeg -> ((rel - profile.panLimitRightDeg) / 20.0).coerceIn(0.1, 1.0)
            rel < -profile.panLimitLeftDeg -> -((-rel - profile.panLimitLeftDeg) / 20.0).coerceIn(0.1, 1.0)
            else -> 0.0
        }
    }

    private fun holdStick(x: Double, y: Double, now: Double, actions: MutableList<DirectorAction>) {
        val cx = x.coerceIn(-1.0, 1.0)
        val cy = y.coerceIn(-1.0, 1.0)
        if (stickHeld && abs(cx - lastStickX) < 0.02 && abs(cy - lastStickY) < 0.02) return
        if (!stickHeld || sign(cx) != sign(lastStickX)) stickSince = now
        stickHeld = true
        lastStickX = cx
        lastStickY = cy
        actions += DirectorAction.Stick(cx, cy)
    }

    private fun releaseStick(actions: MutableList<DirectorAction>) {
        if (!stickHeld) return
        stickHeld = false
        stickSince = null
        lastStickX = 0.0
        lastStickY = 0.0
        actions += DirectorAction.Stick(0.0, 0.0)
    }

    companion object {
        const val MIN_TRACK_SIDE = 0.11
        const val TRACK_SETTLE_SECONDS = 1.5
        const val RETRY_TRACK_SECONDS = 2.0
        const val ZOOM_MIN_INTERVAL_SECONDS = 4.0
        const val ZOOM_IN_STABLE_SECONDS = 3.0
        const val ZOOM_IN_FIT = 0.70
        const val WIDE_EXTENT = 0.85
        const val CENTERED_BAND = 0.15
        const val HOME_TOLERANCE_DEG = 8.0
        /** Zo lang rijdt de gimbal door op de laatste stick als de detectie even niets geeft. */
        const val COAST_SECONDS = 1.0
        const val HOME_MAX_RATE = 25.0
        /** P-versterking in 1/s: een fout van 10° wordt met 15°/s ingelopen (tijdconstante ~0,7 s). */
        const val STEER_KP = 1.5
        const val STEADY_STICK_SECONDS = 0.4
        /** Horizontaal gezichtsveld van de Pocket 4P op 1× (20 mm equivalent, 16:9). */
        const val HFOV_1X_DEG = 84.0

        fun horizontalFovDeg(zoom: Double): Double {
            val z = if (zoom >= 1.0) zoom else 1.0
            return Math.toDegrees(2 * atan(tan(Math.toRadians(HFOV_1X_DEG / 2)) / z))
        }

        fun nearestStop(value: Double, stops: List<Double>): Double =
            stops.minByOrNull { abs(it - value) } ?: value

        fun wrapDeg(d: Double): Double {
            var x = d % 360.0
            if (x > 180) x -= 360
            if (x < -180) x += 360
            return x
        }
    }
}

/**
 * Leert per joystick-as het teken (werkt + of − op de gemeten hoek) en de snelheid bij volle
 * uitslag, uit wat de gimbal deed terwijl de stick vastgehouden werd.
 */
class AxisLearner(
    private val signThresholdDeg: Double = 3.0,
    initialDegPerSecond: Double = 90.0,
) {
    private var signAccumulator = 0.0
    var sign = 1.0
        private set
    var signDecided = false
        private set
    var degPerSecondAtFullStick = initialDegPerSecond
        private set
    private var rateSamples = 0

    fun observe(commandedStick: Double, observedDeltaDeg: Double, dt: Double, steady: Boolean) {
        if (commandedStick == 0.0) return
        if (!signDecided && abs(observedDeltaDeg) >= 0.05) {
            signAccumulator += commandedStick * observedDeltaDeg
            if (abs(signAccumulator) >= signThresholdDeg) {
                sign = if (signAccumulator < 0) -1.0 else 1.0
                signDecided = true
            }
        }
        // Snelheid alleen uit een stick die al even stilstaat (de gimbal heeft dan zijn vaart) en de
        // goede kant op draait.
        if (signDecided && steady && abs(commandedStick) >= 0.2 && sign * commandedStick * observedDeltaDeg > 0) {
            val rate = abs(observedDeltaDeg / dt) / abs(commandedStick)
            if (rate in 5.0..400.0) {
                degPerSecondAtFullStick = if (rateSamples == 0) rate else degPerSecondAtFullStick * 0.8 + rate * 0.2
                rateSamples++
            }
        }
    }

    /** Stick-uitslag voor een gewenste snelheid in graden/s (0 ⇒ 0). */
    fun stickFor(degPerSecond: Double): Double {
        if (degPerSecond == 0.0) return 0.0
        val stick = sign * degPerSecond / degPerSecondAtFullStick
        return stick.coerceIn(-1.0, 1.0)
    }
}

/**
 * Leert of yaw-omhoog beeld-rechts is (standaard) of andersom: draait de camera naar rechts, dan
 * schuift de spelerswolk naar links door het beeld. Een enkel beeld bewijst niets (spelers lopen
 * zelf); pas na een paar graden consistente beweging valt de beslissing.
 */
class ImageYawSignLearner(private val thresholdDeg: Double = 6.0) {
    private var accumulator = 0.0
    var sign = 1.0
        private set
    var decided = false
        private set

    fun observe(deltaYawDeg: Double, deltaMeanImageX: Double) {
        if (decided || abs(deltaYawDeg) < 0.5 || abs(deltaMeanImageX) < 0.005) return
        // Verwacht bij teken +1: tegengesteld (product negatief).
        accumulator += deltaYawDeg * (if (deltaMeanImageX < 0) 1.0 else -1.0)
        if (abs(accumulator) >= thresholdDeg) {
            sign = if (accumulator > 0) 1.0 else -1.0
            decided = true
        }
    }
}
