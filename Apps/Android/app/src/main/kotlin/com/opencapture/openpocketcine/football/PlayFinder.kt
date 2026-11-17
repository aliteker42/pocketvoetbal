package com.opencapture.openpocketcine.football

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/** Eén herkend object in beeldcoördinaten 0..1 (x naar rechts, y naar beneden), middelpunt + afmeting. */
data class Detection(val cx: Double, val cy: Double, val w: Double, val h: Double, val score: Double) {
    fun distanceTo(x: Double, y: Double) = hypot(cx - x, cy - y)
}

/** Wat de detector in één beeld zag. */
data class SceneObservation(
    val persons: List<Detection>,
    val balls: List<Detection>,
    /** Zwaartepunt van beweging in beeld (uit frame-verschil), of null. */
    val motion: Detection? = null,
    val timeSeconds: Double,
    /** De camera draait of zoomt net: stilstaan is nu niet te beoordelen. */
    val cameraMoving: Boolean = false,
    /** De camera volgt zelf een speler (ActiveTrack): gezamenlijke verschuiving wegstrepen. */
    val cameraTracking: Boolean = false,
)

/** Waar het spel is en welke speler het best te volgen is. */
data class PlayEstimate(
    val x: Double,
    val y: Double,
    /** 0..1: hoe zeker we zijn. 0 = niets gezien. */
    val confidence: Double,
    /** Speler om aan de camera-tracking te geven (dichtst bij het spel), of null. */
    val anchor: Detection?,
    /** Spreiding van de spelers in x (0 = allemaal op één plek). */
    val spreadX: Double,
    val playerCount: Int,
    val ballSeen: Boolean,
    /** -1..1: spelers hangen tegen de linker (−) of rechter (+) beeldrand — het spel loopt uit beeld. */
    val edgePressure: Double,
    /** Ongedempt gemiddelde x van de spelers in dit beeld (voor het leren van de draairichting), of null. */
    val meanPlayerX: Double? = null,
    /** Breedte die alle spelers samen innemen (max x − min x), 0..1. */
    val extentX: Double = 0.0,
    /** Mediaan van de spelershoogte in beeld (0..1): maat voor hoe ver weg het spel is. */
    val medianHeight: Double = 0.0,
    /** Personen die stilstaan (publiek, bank) en daarom niet meetellen. */
    val ignored: List<Detection> = emptyList(),
)

/**
 * Pure logica (geen Android): spelers + bal + beweging → "waar is het spel".
 *
 * Een voetbalwedstrijd heeft geen enkel doelwit. Het spel zit waar de bal is, en anders waar de
 * spelers samenklonteren. Spelers die ver van iedereen af staan (keeper, verdediger achter) tellen
 * weinig mee. Het resultaat wordt in de tijd gedempt zodat één gemiste detectie het beeld niet
 * laat schokken.
 */
class PlayFinder(private val profile: FootballProfile, private val ignoreStanding: Boolean = true) {
    /** Een gevolgde persoon: waar hij was, en of hij de laatste seconden van zijn plek kwam. */
    private class Trk(var cx: Double, var cy: Double, var h: Double, var seenAt: Double) {
        val history = ArrayDeque<DoubleArray>() // [t, cx, cy]
        var standing = false
        var judged = false
    }
    private val tracks = ArrayList<Trk>()

    /**
     * Wie staat stil? Publiek en bankzitters blijven seconden op dezelfde plek in beeld; spelers
     * niet. We koppelen detecties aan de vorige (dichtstbijzijnde, ongeveer even groot) en meten de
     * verplaatsing over ~2 s in lichaamslengtes per seconde. Detectieruis telt zo nauwelijks mee.
     */
    private fun standingPersons(persons: List<Detection>, obs: SceneObservation): Set<Detection> {
        val now = obs.timeSeconds
        tracks.removeAll { now - it.seenAt > TRACK_FORGET_SECONDS }
        if (obs.cameraMoving) tracks.forEach { it.history.clear() }
        val matched = HashMap<Detection, Trk>()
        val used = HashSet<Trk>()
        for (p in persons.sortedByDescending { it.score }) {
            val best =
                tracks.filter { it !in used && p.h / it.h in 0.6..1.7 }
                    .minByOrNull { hypot(it.cx - p.cx, it.cy - p.cy) }
                    ?.takeIf { hypot(it.cx - p.cx, it.cy - p.cy) < maxOf(0.6 * p.h, 0.04) }
            val trk = best ?: Trk(p.cx, p.cy, p.h, now).also { tracks += it }
            used += trk
            trk.cx = p.cx; trk.cy = p.cy; trk.h = p.h; trk.seenAt = now
            trk.history.addLast(doubleArrayOf(now, p.cx, p.cy))
            while (trk.history.size > 1 && now - trk.history.first()[0] > STILL_BASELINE_SECONDS) trk.history.removeFirst()
            matched[p] = trk
        }
        // Per persoon: hoe ver is hij in de afgelopen seconden van zijn plek gekomen? We nemen de
        // grootste afstand tot het oudste punt (niet alleen begin→eind: wie heen en weer loopt
        // beweegt ook), in lichaamslengtes.
        val judged = matched.filter { now - it.value.history.first()[0] >= STILL_MIN_AGE_SECONDS }
        var mx = 0.0
        var my = 0.0
        if (obs.cameraTracking && judged.size >= 3) {
            // De camera draait mee met een speler: de gezamenlijke verschuiving (mediaan) wegstrepen.
            val vx = judged.map { (p, trk) -> val f = trk.history.first(); (p.cx - f[1]) / (now - f[0]) }.sorted()
            val vy = judged.map { (p, trk) -> val f = trk.history.first(); (p.cy - f[2]) / (now - f[0]) }.sorted()
            mx = vx[vx.size / 2]
            my = vy[vy.size / 2]
        }
        for ((p, trk) in judged) {
            val f = trk.history.first()
            var reach = 0.0
            for (h in trk.history) {
                val age = h[0] - f[0]
                reach = maxOf(reach, hypot(h[1] - f[1] - mx * age, h[2] - f[2] - my * age))
            }
            val lengths = reach / p.h
            // Hysterese: stilstaand worden vraagt echt stilstaan, er weer uit komen een duidelijke beweging.
            trk.standing = if (trk.standing) lengths < STILL_REACH * 1.3 else lengths < STILL_REACH
            trk.judged = true
        }
        return matched.filter { it.value.judged && it.value.standing }.keys
    }

    private var smoothedX: Double? = null
    private var smoothedY: Double? = null
    private var lastTime: Double? = null

    private var ballX = 0.0
    private var ballY = 0.0
    private var ballSeenAt: Double? = null

    fun reset() {
        smoothedX = null
        smoothedY = null
        lastTime = null
        ballSeenAt = null
        tracks.clear()
    }

    fun update(obs: SceneObservation): PlayEstimate {
        val now = obs.timeSeconds
        val dt = lastTime?.let { (now - it).coerceIn(0.01, 1.0) } ?: 0.1
        lastTime = now

        val allPersons = obs.persons.filter { it.h in MIN_PLAYER_HEIGHT..MAX_PLAYER_HEIGHT && it.score >= MIN_PERSON_SCORE }
        val standing = if (ignoreStanding) standingPersons(allPersons, obs) else emptySet()
        val persons = allPersons.filter { it !in standing }
        val ignoredList = allPersons.filter { it in standing }

        // Bal: onthoud de laatste goede waarneming kort; een bal flikkert in detecties.
        obs.balls.filter { it.score >= MIN_BALL_SCORE }.maxByOrNull { it.score }?.let { ball ->
            if (ballSeenAt == null || now - ballSeenAt!! > BALL_MEMORY_SECONDS) {
                ballX = ball.cx
                ballY = ball.cy
            } else {
                ballX += (ball.cx - ballX) * BALL_ALPHA
                ballY += (ball.cy - ballY) * BALL_ALPHA
            }
            ballSeenAt = now
        }
        val ballValid = ballSeenAt?.let { now - it <= BALL_MEMORY_SECONDS } == true

        // Spelers: dichtheidsgewogen zwaartepunt. Gewicht = 1 + aantal buren binnen de clusterstraal,
        // zacht afgerold zodat de rand van de straal geen harde grens is.
        var cx = 0.0
        var cy = 0.0
        var wsum = 0.0
        val weights = DoubleArray(persons.size)
        for (i in persons.indices) {
            val p = persons[i]
            var w = 1.0
            for (j in persons.indices) {
                if (i == j) continue
                val d = p.distanceTo(persons[j].cx, persons[j].cy) / profile.clusterRadius
                w += exp(-d * d)
            }
            // Dichter bij de bal = belangrijker.
            // Achter de goal: een speler vlak voor de camera (groot in beeld) mag de richting niet bepalen.
            if (profile.behindGoal) w /= 1.0 + (p.h / 0.35) * (p.h / 0.35) * 3.0
            if (ballValid) w *= 1.0 + 2.0 * exp(-(p.distanceTo(ballX, ballY) / profile.clusterRadius).let { it * it })
            weights[i] = w
            cx += p.cx * w
            cy += p.cy * w
            wsum += w
        }
        val clusterValid = wsum > 0
        if (clusterValid) {
            cx /= wsum
            cy /= wsum
        }

        var targetX: Double? = null
        var targetY: Double? = null
        when {
            ballValid && clusterValid -> {
                targetX = BALL_WEIGHT * ballX + (1 - BALL_WEIGHT) * cx
                targetY = BALL_WEIGHT * ballY + (1 - BALL_WEIGHT) * cy
            }
            ballValid -> {
                targetX = ballX
                targetY = ballY
            }
            clusterValid -> {
                targetX = cx
                targetY = cy
            }
            obs.motion != null && obs.motion.score >= MIN_MOTION_SCORE -> {
                targetX = obs.motion.cx
                targetY = obs.motion.cy
            }
        }

        // Spelers tegen de rand: het spel trekt uit beeld. Negatief = links, positief = rechts.
        var edge = 0.0
        if (persons.isNotEmpty()) {
            val left = persons.count { it.cx < EDGE_BAND }
            val right = persons.count { it.cx > 1 - EDGE_BAND }
            edge = (right - left).toDouble() / persons.size
        }

        val spread =
            if (persons.size >= 2) {
                val mean = persons.sumOf { it.cx } / persons.size
                sqrt(persons.sumOf { (it.cx - mean) * (it.cx - mean) } / persons.size)
            } else 0.0

        val meanX = if (persons.isNotEmpty()) persons.sumOf { it.cx } / persons.size else null
        val extent = if (persons.size >= 2) persons.maxOf { it.cx } - persons.minOf { it.cx } else 0.0

        if (targetX == null || targetY == null) {
            // Niets gezien: oude schatting langzaam loslaten.
            return PlayEstimate(
                x = smoothedX ?: 0.5, y = smoothedY ?: 0.5, confidence = 0.0, anchor = null,
                spreadX = spread, playerCount = 0, ballSeen = false, edgePressure = edge, ignored = ignoredList,
            )
        }

        // Tijdconstante-demping: grote sprongen (bal weggeschoten) volgen we sneller dan kleine ruis.
        val jump = smoothedX?.let { hypot(targetX - it, targetY - (smoothedY ?: targetY)) } ?: 1.0
        val tau = if (jump > 0.25) FAST_TAU else SLOW_TAU
        val alpha = 1 - exp(-dt / tau)
        smoothedX = smoothedX?.let { it + (targetX - it) * alpha } ?: targetX
        smoothedY = smoothedY?.let { it + (targetY - it) * alpha } ?: targetY
        val px = smoothedX!!
        val py = smoothedY!!

        // Anker: de speler het dichtst bij het spelpunt, met voorkeur voor wie bij de bal staat.
        val anchor =
            persons.minByOrNull { p ->
                val toPlay = p.distanceTo(px, py)
                if (ballValid) 0.5 * toPlay + 0.5 * p.distanceTo(ballX, ballY) else toPlay
            }

        val confidence =
            (0.25 + 0.12 * persons.size + (if (ballValid) 0.3 else 0.0)).coerceAtMost(1.0)

        return PlayEstimate(
            x = px.coerceIn(0.0, 1.0), y = py.coerceIn(0.0, 1.0), confidence = confidence, anchor = anchor,
            spreadX = spread, playerCount = persons.size, ballSeen = ballValid, edgePressure = edge,
            meanPlayerX = meanX, extentX = extent,
            medianHeight = if (persons.isEmpty()) 0.0 else persons.map { it.h }.sorted()[persons.size / 2],
            ignored = ignoredList,
        )
    }

    companion object {
        const val TRACK_FORGET_SECONDS = 1.2
        const val STILL_BASELINE_SECONDS = 2.5
        const val STILL_MIN_AGE_SECONDS = 1.5
        /** Wie in 2,5 s minder dan dit aantal lichaamslengtes van zijn plek komt, staat stil. */
        const val STILL_REACH = 0.45
        const val MIN_PLAYER_HEIGHT = 0.04
        const val MAX_PLAYER_HEIGHT = 0.95
        const val MIN_PERSON_SCORE = 0.35
        const val MIN_BALL_SCORE = 0.30
        const val MIN_MOTION_SCORE = 0.15
        const val BALL_MEMORY_SECONDS = 1.5
        const val BALL_ALPHA = 0.5
        const val BALL_WEIGHT = 0.65
        const val EDGE_BAND = 0.12
        const val SLOW_TAU = 0.45
        const val FAST_TAU = 0.15

        fun absEdge(e: Double) = abs(e)
    }
}
