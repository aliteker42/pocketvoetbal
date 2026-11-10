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
)

/**
 * Pure logica (geen Android): spelers + bal + beweging → "waar is het spel".
 *
 * Een voetbalwedstrijd heeft geen enkel doelwit. Het spel zit waar de bal is, en anders waar de
 * spelers samenklonteren. Spelers die ver van iedereen af staan (keeper, verdediger achter) tellen
 * weinig mee. Het resultaat wordt in de tijd gedempt zodat één gemiste detectie het beeld niet
 * laat schokken.
 */
class PlayFinder(private val profile: FootballProfile) {
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
    }

    fun update(obs: SceneObservation): PlayEstimate {
        val now = obs.timeSeconds
        val dt = lastTime?.let { (now - it).coerceIn(0.01, 1.0) } ?: 0.1
        lastTime = now

        val persons = obs.persons.filter { it.h in MIN_PLAYER_HEIGHT..MAX_PLAYER_HEIGHT && it.score >= MIN_PERSON_SCORE }

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
                spreadX = spread, playerCount = 0, ballSeen = false, edgePressure = edge,
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
        )
    }

    companion object {
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
