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
    /** De zoom die wíj het laatst gevraagd hebben; de camera meldt soms iets anders en daar reageren we niet op. */
    private var commandedZoom: Double? = null
    // Zoomregeling: hoe lang een in/uit-wens al aanhoudt.
    private var wantInSince: Double? = null
    private var wantOutSince: Double? = null
    // Hand gaat voor: tot wanneer de app stilhoudt omdat de gebruiker zelf stuurde.
    private var handUntil = Double.NEGATIVE_INFINITY
    private var stickReleasedAt = Double.NEGATIVE_INFINITY
    private var handPanned = false
    private var handRefYaw: Double? = null
    /** Gebeurtenissen voor het logboek (bijv. handbediening); de tracker haalt ze op. */
    val events = ArrayList<String>()
    private var trackRequestedAt = Double.NEGATIVE_INFINITY

    // Eén speler alleen (de keeper) is geen spel: na een paar seconden gaan we zoeken.
    private var aloneSince: Double? = null
    // Kwijt-modus: wanneer begonnen, hoe ver al uitgezoomd, en de zoekbeweging.
    private var lostModeSince: Double? = null
    private var lostZoomNow: Double? = null
    /** De gimbalhoek is pas te vertrouwen als we hem hebben zien veranderen. */
    private var yawTrusted = false

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
        aloneSince = null
        lostModeSince = null
        lostZoomNow = null
        yawTrusted = false
        lastTickAt = now
        lastYaw = camera.yawDeg
        lastPitch = camera.pitchDeg
        val actions = mutableListOf<DirectorAction>()
        val startZoom = nearestStop(profile.defaultZoom, zoomLadder())
        wantInSince = null
        wantOutSince = null
        handUntil = Double.NEGATIVE_INFINITY
        handPanned = false
        commandedZoom = startZoom
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

        // ---- Hand gaat voor ----
        // Draaien terwijl wij geen stick vasthouden (en de camera zelf niemand volgt) is de gebruiker.
        // We vergelijken met de hoek van het moment dat wij stopten met sturen, zodat ook een
        // rustige handbeweging (een paar graden per seconde) opvalt.
        val yaw = camera.yawDeg
        if (yaw != null && !stickHeld && !camera.isTracking && now - stickReleasedAt > HAND_STICK_GUARD_SECONDS) {
            val ref = handRefYaw
            if (ref == null) {
                handRefYaw = yaw
            } else if (abs(wrapDeg(yaw - ref)) >= HAND_YAW_DEG) {
                if (now >= handUntil) events += "HAND draai (%.0f° → %.0f°)".format(ref, yaw)
                handUntil = now + HAND_PAUSE_SECONDS
                handPanned = true
                handRefYaw = yaw
            }
        } else {
            handRefYaw = null
        }
        // Zoom die afwijkt van wat wij vroegen (en niet net door ons veranderd) is ook de gebruiker.
        val ours = commandedZoom
        if (ours != null && now - lastZoomAt > HAND_ZOOM_GUARD_SECONDS && abs(camera.zoom - ours) > HAND_ZOOM_DELTA) {
            events += "HAND zoom (%.1f× → %.1f×)".format(ours, camera.zoom)
            commandedZoom = camera.zoom
            lastZoomAt = now
            handUntil = now + HAND_PAUSE_SECONDS
        }
        if (now < handUntil) {
            releaseStick(actions)
            wantInSince = null
            wantOutSince = null
            return actions
        }
        if (handPanned) {
            // Waar de gebruiker hem neerzette is het nieuwe midden.
            handPanned = false
            if (yaw != null) homeYaw = yaw
            events += "HAND klaar, verder vanaf %.0f°".format(yaw ?: 0.0)
        }

        // Eén speler zonder bal (meestal de keeper) volgen we maar kort. Daarna telt hij een tijd
        // niet mee en gaan we het spel zoeken; vinden we niets beters, dan krijgt hij weer een kans.
        var loneIgnored = false
        if (settings.ignoreLonePlayer) {
            val alone = play.confidence > 0.0 && play.playerCount <= 1 && !play.ballSeen
            if (alone) { if (aloneSince == null) aloneSince = now } else aloneSince = null
            aloneSince?.let { since ->
                val t = now - since
                if (t >= LONE_SECONDS + LONE_SEARCH_SECONDS) aloneSince = now else if (t >= LONE_SECONDS) loneIgnored = true
            }
        }
        val seen = play.confidence > 0.0 && !loneIgnored
        if (seen) {
            if (seenSince == null) seenSince = now
            lostSince = null
        } else {
            seenSince = null
            centeredSince = null
            if (lostSince == null) lostSince = now
        }
        val lostFor = lostSince?.let { now - it } ?: 0.0
        val lost = !seen && (loneIgnored || lostFor >= profile.lostAfterSeconds)

        when {
            lost -> handleLost(camera, now, actions)
            seen -> {
                mode = DirectorMode.FOLLOWING
                lostModeSince = null
                restoreZoomAfterLost(camera, now, actions)
                if (profile.behindGoal) {
                    followBehindGoal(play, camera, now, actions)
                } else {
                    when (settings.steering) {
                        Steering.CAMERA_TRACK -> followByTracking(play, camera, now, actions)
                        Steering.APP_STEER -> followBySteering(play, camera, now, actions)
                    }
                    if (settings.autoZoom) autoZoom(play, camera, now, actions)
                }
            }
            else -> {
                // Even niets gezien (een gemiste detectie, een traag beeld): niets overhaasten. Bij
                // APP_STEER de huidige beweging nog even door laten lopen; pas na een korte tijd
                // de stick loslaten. Anders wordt elke hik in de detectie een hik in de gimbal.
                if (mode != DirectorMode.LOST) mode = DirectorMode.SEARCHING
                if ((settings.steering == Steering.APP_STEER || profile.behindGoal) && lostFor >= COAST_SECONDS) releaseStick(actions)
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

        // De app meldt "tracking uit" al na 0,35 s zonder bericht van de camera, terwijl de Pocket
        // vaak gewoon doorvolgt. Daarom na een opdracht een respijt waarin we de camera vertrouwen
        // en níet opnieuw een kader sturen (elke herstart = een schok in het beeld).
        val requestPending = now - trackRequestedAt < TRACK_GRACE_SECONDS
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

    // ---- Achter de goal -----------------------------------------------------------------------

    /**
     * Het spel loopt van de camera af. Zoom volgt de afstand (spelers klein ⇒ inzoomen, groot ⇒
     * uitzoomen); draaien alleen als het spel duidelijk uit het midden staat, en dan traag.
     */
    private fun followBehindGoal(play: PlayEstimate, camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>) {
        if (camera.isTracking) actions += DirectorAction.CancelTrack

        zoomControl(play, camera, now, actions, outHeight = BG_ZOOM_OUT_HEIGHT)

        // Draaien: ruime dode zone, lage snelheid, binnen de pan-grens.
        val ex = play.x - 0.5
        var yawRate = if (abs(ex) > BG_PAN_DEADBAND) BG_PAN_KP * (ex - sign(ex) * BG_PAN_DEADBAND) * horizontalFovDeg(camera.zoom) * imageYaw.sign else 0.0
        val maxRate = BG_PAN_MAX_STICK * panStick.degPerSecondAtFullStick
        yawRate = yawRate.coerceIn(-maxRate, maxRate)
        val over = panOverLimit(camera.yawDeg)
        if (over != 0.0 && sign(yawRate) == sign(over)) yawRate = 0.0
        val stickX = panStick.stickFor(yawRate)
        if (stickX == 0.0) releaseStick(actions) else holdStick(stickX, 0.0, now, actions)
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
        homeYaw?.let { h -> if (yaw != null && abs(wrapDeg(yaw - h)) > 2.0) yawTrusted = true }
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
        if (mode != DirectorMode.LOST || lostModeSince == null) lostModeSince = now
        mode = DirectorMode.LOST
        val lostFor = now - lostModeSince!!
        if (camera.isTracking) actions += DirectorAction.CancelTrack

        // Uitzoomen in twee stappen: eerst naar de kwijt-zoom van het profiel, en als dat na een
        // paar seconden niets oplevert helemaal naar de wijdste stand.
        val stops = zoomLadder()
        val ours = lostZoomNow ?: commandedZoom ?: camera.zoom
        val widest = stops.minOrNull() ?: 1.0
        val target = if (lostFor >= WIDEST_AFTER_SECONDS) widest else nearestStop(profile.lostZoom, stops)
        if (target < ours - 0.05) {
            actions += DirectorAction.Zoom(target)
            lastZoomAt = now
            lostZoomNow = target
            // Na het terugvinden niet in één klap terug inzoomen: de zoomregeling bouwt weer op.
            commandedZoom = target
            zoomedOutForLost = true
        }

        val yaw = camera.yawDeg
        val home = homeYaw
        val sweepT = lostFor - SWEEP_AFTER_SECONDS
        if (settings.sweepSearch && !profile.behindGoal && sweepT >= 0 && sweepT < SWEEP_MAX_SECONDS) {
            // Zoeken: rustig heen en weer draaien rond de startrichting (driehoeksgolf).
            val phase = ((sweepT + SWEEP_HALF_SECONDS) / (2 * SWEEP_HALF_SECONDS)).toInt()
            val inPhase = (sweepT + SWEEP_HALF_SECONDS) - phase * 2 * SWEEP_HALF_SECONDS // 0..2T
            val tri = (inPhase / SWEEP_HALF_SECONDS - 1.0) * (if (phase % 2 == 0) 1.0 else -1.0) // -1..1
            if (yawTrusted && yaw != null && home != null) {
                val amplitude = minOf(profile.panLimitLeftDeg, profile.panLimitRightDeg, 75.0) * 0.6
                val rel = wrapDeg(yaw - home)
                val rate = ((tri * amplitude - rel) * 1.0).coerceIn(-SWEEP_RATE_DEG, SWEEP_RATE_DEG)
                holdStick(panStick.stickFor(rate), 0.0, now, actions)
            } else {
                // Hoek onbekend: op tijd sturen, zacht, even lang heen als terug.
                val dir = if (phase % 2 == 0) 1.0 else -1.0
                holdStick(dir * SWEEP_STICK, 0.0, now, actions)
            }
            return
        }
        // Niet (meer) zoeken: rustig terug naar de startrichting (midden van het veld).
        if (yawTrusted && yaw != null && home != null) {
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
        // Bewust geen sprong terug: na "kwijt" zoomt de gewone zoomregeling stap voor stap weer in.
        if (!zoomedOutForLost) return
        zoomedOutForLost = false
        lostZoomNow = null
    }

    // ---- Zoom ---------------------------------------------------------------------------------

    private fun autoZoom(play: PlayEstimate, camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>) {
        zoomControl(play, camera, now, actions, outHeight = profile.zoomOutWhenTallerThan)
    }

    /**
     * Zoom zonder pingpong. Kleine stappen (0,5×), en een stap inzoomen mag alleen als de spelers
     * ná die stap nog ruim onder de uitzoomgrens blijven. Zo kan een inzoomstap nooit meteen de
     * uitzoomregel raken. Elke wens moet een tijd aanhouden, en na een zoom is er rust.
     */
    private fun zoomControl(play: PlayEstimate, camera: CameraObservation, now: Double, actions: MutableList<DirectorAction>, outHeight: Double) {
        if (now - lastZoomAt < ZOOM_SETTLE_SECONDS) { wantInSince = null; wantOutSince = null; return }
        val ladder = zoomLadder()
        val current = nearestStop(commandedZoom ?: camera.zoom, ladder)
        val idx = ladder.indexOf(current)
        val h = if (profile.behindGoal) play.medianHeight else (play.anchor?.h ?: play.medianHeight)

        val wantOut = idx > 0 && (h > outHeight || play.extentX > ZOOM_OUT_EXTENT || abs(play.edgePressure) > 0.6)
        val r = if (idx < ladder.size - 1) ladder[idx + 1] / current else 1.0
        val wantIn = !wantOut && idx < ladder.size - 1 && play.playerCount >= 2 &&
            h in 0.01..(outHeight * ZOOM_IN_MARGIN / r) && play.extentX * r < ZOOM_IN_FIT && abs(play.x - 0.5) < 0.25

        if (wantOut) { if (wantOutSince == null) wantOutSince = now } else wantOutSince = null
        if (wantIn) { if (wantInSince == null) wantInSince = now } else wantInSince = null

        val target =
            when {
                wantOutSince?.let { now - it >= ZOOM_OUT_HOLD_SECONDS } == true -> ladder[idx - 1]
                wantInSince?.let { now - it >= ZOOM_IN_HOLD_SECONDS } == true && now - lastZoomAt >= ZOOM_IN_DWELL_SECONDS -> ladder[idx + 1]
                else -> return
            }
        commandedZoom = target
        actions += DirectorAction.Zoom(target)
        lastZoomAt = now
        wantInSince = null
        wantOutSince = null
        centeredSince = null
    }

    /** 1×, 1,5×, 2×, ... tot de ingestelde maximale zoom. */
    private fun zoomLadder(): List<Double> {
        val out = ArrayList<Double>()
        var z = 1.0
        while (z <= settings.maxZoom + 0.01) { out += z; z += ZOOM_STEP }
        return out
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
        stickReleasedAt = lastTickAt ?: 0.0
        stickSince = null
        lastStickX = 0.0
        lastStickY = 0.0
        actions += DirectorAction.Stick(0.0, 0.0)
    }

    companion object {
        const val MIN_TRACK_SIDE = 0.11
        const val TRACK_GRACE_SECONDS = 6.0
        const val RETRY_TRACK_SECONDS = 3.0
        const val ZOOM_MIN_INTERVAL_SECONDS = 4.0
        const val ZOOM_IN_STABLE_SECONDS = 3.0
        const val ZOOM_IN_FIT = 0.70
        const val WIDE_EXTENT = 0.85
        const val CENTERED_BAND = 0.15
        const val BG_ZOOM_IN_HEIGHT = 0.22
        const val BG_ZOOM_OUT_HEIGHT = 0.48
        const val BG_PAN_DEADBAND = 0.15
        const val BG_PAN_KP = 0.6
        const val BG_PAN_MAX_STICK = 0.25
        const val ZOOM_STEP = 0.5
        const val ZOOM_SETTLE_SECONDS = 2.0
        const val ZOOM_OUT_HOLD_SECONDS = 1.5
        const val ZOOM_IN_HOLD_SECONDS = 4.0
        const val ZOOM_IN_DWELL_SECONDS = 8.0
        /** Na een inzoomstap moeten de spelers onder deze fractie van de uitzoomgrens blijven. */
        const val ZOOM_IN_MARGIN = 0.7
        const val ZOOM_OUT_EXTENT = 0.85
        const val HAND_YAW_DEG = 4.0
        const val HAND_STICK_GUARD_SECONDS = 1.0
        const val HAND_PAUSE_SECONDS = 5.0
        const val HAND_ZOOM_GUARD_SECONDS = 3.0
        const val HAND_ZOOM_DELTA = 0.35
        const val LONE_SECONDS = 4.0
        const val LONE_SEARCH_SECONDS = 12.0
        const val WIDEST_AFTER_SECONDS = 3.0
        const val SWEEP_AFTER_SECONDS = 2.5
        const val SWEEP_HALF_SECONDS = 2.5
        /** Vier volle slagen; eindigt weer in het midden. */
        const val SWEEP_MAX_SECONDS = 20.0
        const val SWEEP_RATE_DEG = 18.0
        const val SWEEP_STICK = 0.2
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
