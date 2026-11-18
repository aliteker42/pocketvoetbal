package com.opencapture.openpocketcine.football

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FootballLogicTest {
    private val veld = FootballSettings(sport = Sport.VELD)
    private fun camera(zoom: Double = 1.0, tracking: Boolean = false, subjectX: Double? = null, yaw: Double? = 0.0) =
        CameraObservation(tracking, subjectX, if (subjectX != null) 0.5 else null, yaw, 0.0, zoom, listOf(1.0, 2.0, 3.0, 4.0, 6.0))

    @Test fun playPointSitsBetweenBallAndCluster() {
        val finder = PlayFinder(veld.profile, ignoreStanding = false)
        val players = (0 until 6).map { Detection(0.30 + it * 0.02, 0.5, 0.03, 0.12, 0.8) }
        val ball = Detection(0.60, 0.5, 0.01, 0.015, 0.5)
        var play = finder.update(SceneObservation(players, listOf(ball), null, 0.0))
        repeat(20) { play = finder.update(SceneObservation(players, listOf(ball), null, 0.1 * (it + 1))) }
        assertTrue(play.ballSeen)
        assertTrue(play.x in 0.40..0.58, "spelpunt ${play.x}")
        assertEquals(6, play.playerCount)
        assertNotNull(play.anchor)
        assertEquals(0.40, play.anchor!!.cx, 1e-9) // dichtst bij de bal
    }

    @Test fun standingSpectatorsAreIgnored() {
        val finder = PlayFinder(veld.profile)
        var play = finder.update(SceneObservation(emptyList(), emptyList(), null, 0.0))
        for (i in 0..40) {
            val t = i * 0.16
            // Acht toeschouwers rechts, stil (met wat detectieruis); drie spelers links die bewegen.
            val crowd = (0 until 8).map { Detection(0.75 + it * 0.02 + (if (i % 2 == 0) 0.003 else -0.003), 0.45, 0.03, 0.2, 0.8) }
            val players = (0 until 3).map { Detection(0.15 + it * 0.08 + 0.06 * kotlin.math.sin(t * 2 + it), 0.5 + 0.08 * kotlin.math.cos(t * 3 + it), 0.03, 0.15, 0.8) }
            play = finder.update(SceneObservation(crowd + players, emptyList(), null, t))
        }
        assertEquals(8, play.ignored.size)
        assertEquals(3, play.playerCount)
        assertTrue(play.x < 0.4, "spelpunt ${play.x} hoort bij de spelers, niet bij het publiek")
        assertTrue(play.anchor!!.cx < 0.4)
    }

    @Test fun nothingSeenMeansNoConfidence() {
        val finder = PlayFinder(veld.profile)
        val play = finder.update(SceneObservation(emptyList(), emptyList(), null, 0.0))
        assertEquals(0.0, play.confidence)
        assertNull(play.anchor)
    }

    @Test fun lonelyKeeperDoesNotPullThePlay() {
        val finder = PlayFinder(veld.profile, ignoreStanding = false)
        val cluster = (0 until 8).map { Detection(0.70 + (it % 4) * 0.02, 0.5 + (it / 4) * 0.05, 0.03, 0.12, 0.8) }
        val keeper = Detection(0.05, 0.5, 0.03, 0.12, 0.9)
        var play = finder.update(SceneObservation(cluster + keeper, emptyList(), null, 0.0))
        repeat(20) { play = finder.update(SceneObservation(cluster + keeper, emptyList(), null, 0.1 * (it + 1))) }
        assertTrue(play.x > 0.62, "spelpunt ${play.x}")
    }

    @Test fun directorStartsAtDefaultZoomAndHandsAnchorToTracking() {
        val director = CameraDirector(veld)
        val start = director.start(camera(zoom = 1.0), 0.0)
        assertEquals(listOf<DirectorAction>(DirectorAction.Zoom(3.0)), start)
        val anchor = Detection(0.62, 0.5, 0.03, 0.12, 0.8)
        val play = PlayEstimate(0.6, 0.5, 0.8, anchor, 0.05, 6, true, 0.0, 0.6, 0.1)
        val actions = director.tick(play, camera(zoom = 3.0), 0.1)
        val track = actions.filterIsInstance<DirectorAction.Track>().single()
        assertEquals(0.62, track.cx, 1e-9)
        assertTrue(track.w >= CameraDirector.MIN_TRACK_SIDE && track.h >= CameraDirector.MIN_TRACK_SIDE)
        assertEquals(DirectorMode.FOLLOWING, director.mode)
        // Direct daarna niet nog eens.
        assertTrue(director.tick(play, camera(zoom = 3.0), 0.2).none { it is DirectorAction.Track })
    }

    @Test fun directorRetargetsWhenPlayLeavesTheSubject() {
        val director = CameraDirector(veld)
        director.start(camera(zoom = 3.0), 0.0)
        val first = Detection(0.5, 0.5, 0.03, 0.12, 0.8)
        var t = 0.1
        director.tick(PlayEstimate(0.5, 0.5, 0.8, first, 0.05, 6, true, 0.0, 0.5, 0.1), camera(zoom = 3.0), t)
        // De camera volgt, het spel schuift ver naar rechts.
        val far = Detection(0.85, 0.5, 0.03, 0.12, 0.8)
        var retargeted = false
        while (t < 6.0) {
            t += 0.1
            val actions = director.tick(PlayEstimate(0.85, 0.5, 0.8, far, 0.05, 6, true, 0.0, 0.85, 0.1), camera(zoom = 3.0, tracking = true, subjectX = 0.5), t)
            if (actions.any { it is DirectorAction.Track }) { retargeted = true; break }
        }
        assertTrue(retargeted)
        assertTrue(t >= 0.1 + veld.profile.retargetHoldSeconds)
    }

    @Test fun lostZoomsOutCancelsTrackingAndTurnsHome() {
        val director = CameraDirector(veld)
        director.start(camera(zoom = 3.0, yaw = 10.0), 0.0)
        val none = PlayEstimate(0.5, 0.5, 0.0, null, 0.0, 0, false, 0.0)
        var t = 0.0
        var all = emptyList<DirectorAction>()
        while (t < veld.profile.lostAfterSeconds + 0.3) {
            t += 0.1
            all = all + director.tick(none, camera(zoom = 3.0, tracking = true, subjectX = 0.5, yaw = 40.0), t)
        }
        assertEquals(DirectorMode.LOST, director.mode)
        assertTrue(all.any { it == DirectorAction.CancelTrack })
        assertTrue(all.any { it is DirectorAction.Zoom && it.factor < 3.0 })
        val stick = all.filterIsInstance<DirectorAction.Stick>().last { it.x != 0.0 }
        assertTrue(stick.x < 0, "terug naar links richting thuis: ${stick.x}")
    }

    @Test fun lonePlayerIsDroppedAndSearchSweeps() {
        val director = CameraDirector(veld.copy(sweepSearch = true))
        val cam = FakeCam(zoom = 3.0)
        director.start(cam.obs(), 0.0)
        val keeper = Detection(0.5, 0.5, 0.05, 0.2, 0.9)
        val alone = PlayEstimate(0.5, 0.5, 0.37, keeper, 0.0, 1, false, 0.0, 0.5, 0.0)
        var t = 0.0
        val all = mutableListOf<DirectorAction>()
        while (t < CameraDirector.LONE_SECONDS + 4.0) {
            t += 0.1
            val tracking = t > 0.3 && all.none { it == DirectorAction.CancelTrack }
            val acts = director.tick(alone, cam.obs().copy(isTracking = tracking, subjectX = if (tracking) 0.5 else null, subjectY = if (tracking) 0.5 else null), t)
            all += acts
            cam.apply(acts, 0.1)
        }
        assertEquals(DirectorMode.LOST, director.mode)
        assertTrue(all.any { it == DirectorAction.CancelTrack })
        assertTrue(all.any { it is DirectorAction.Zoom && it.factor == 1.0 }, "zoomt helemaal uit")
        assertTrue(all.any { it is DirectorAction.Stick && it.x != 0.0 }, "zoekt door te draaien")
        // Een groep in beeld: weer volgen en terug naar de eigen zoom.
        val group = PlayEstimate(0.4, 0.5, 0.8, Detection(0.4, 0.5, 0.03, 0.12, 0.8), 0.05, 5, false, 0.0, 0.4, 0.2)
        val back = mutableListOf<DirectorAction>()
        repeat(15) { t += 0.1; director.tick(group, cam.obs(), t).also { back += it; cam.apply(it, 0.1) } }
        assertEquals(DirectorMode.FOLLOWING, director.mode)
        assertTrue(back.none { it is DirectorAction.Zoom && it.factor == 3.0 }, "niet in één klap terug naar 3×")
        assertTrue(back.any { it is DirectorAction.Track })
    }

    @Test fun lonePlayerWithBallIsFollowed() {
        val director = CameraDirector(veld)
        director.start(camera(zoom = 3.0), 0.0)
        val p = Detection(0.5, 0.5, 0.05, 0.2, 0.9)
        val withBall = PlayEstimate(0.5, 0.5, 0.67, p, 0.0, 1, true, 0.0, 0.5, 0.0)
        var t = 0.0
        repeat(100) { t += 0.1; director.tick(withBall, camera(zoom = 3.0, tracking = true, subjectX = 0.5), t) }
        assertEquals(DirectorMode.FOLLOWING, director.mode)
    }

    /** Nep-camera die de opdrachten van de regelaar uitvoert, zodat zoom en hoek kloppen. */
    private class FakeCam(var zoom: Double = 1.0, var yaw: Double = 0.0) {
        var stick = 0.0
        fun obs() = CameraObservation(false, null, null, yaw, 0.0, zoom, listOf(1.0, 3.0, 6.0))
        fun apply(actions: List<DirectorAction>, dt: Double) {
            for (a in actions) when (a) {
                is DirectorAction.Zoom -> zoom = a.factor
                is DirectorAction.Stick -> stick = a.x
                else -> {}
            }
            yaw += stick * 90.0 * dt
        }
    }

    @Test fun behindGoalZoomsOnDistanceAndPansLittle() {
        val s = FootballSettings(sport = Sport.ZAAL, position = CameraPosition.ACHTER_GOAL, maxZoom = 3.0)
        val director = CameraDirector(s)
        val cam = FakeCam()
        assertTrue(director.start(cam.obs(), 0.0).isEmpty())
        // Spelers "zien" groter worden naarmate de camera inzoomt.
        fun play(x: Double, baseH: Double) =
            (baseH * cam.zoom).let { h -> PlayEstimate(x, 0.5, 0.8, Detection(x, 0.5, h / 3, h, 0.8), 0.05, 4, false, 0.0, x, 0.2 * cam.zoom, h) }
        var t = 0.0
        val far = mutableListOf<DirectorAction>()
        repeat(600) { t += 0.1; director.tick(play(0.55, 0.10), cam.obs(), t).also { far += it; cam.apply(it, 0.1) } }
        assertEquals(listOf(1.5, 2.0, 2.5, 3.0), far.filterIsInstance<DirectorAction.Zoom>().map { it.factor })
        assertTrue(far.none { it is DirectorAction.Stick && it.x != 0.0 }, "kleine afwijking: niet draaien")
        assertTrue(far.none { it is DirectorAction.Track })
        // Spel komt dichtbij: stap voor stap uit tot de spelers weer passen.
        val near = mutableListOf<DirectorAction>()
        repeat(200) { t += 0.1; director.tick(play(0.5, 0.25), cam.obs(), t).also { near += it; cam.apply(it, 0.1) } }
        assertEquals(listOf(2.5, 2.0, 1.5), near.filterIsInstance<DirectorAction.Zoom>().map { it.factor })
        val side = director.tick(play(0.9, 0.3), cam.obs(), t + 0.1).filterIsInstance<DirectorAction.Stick>().single()
        assertTrue(side.x > 0 && side.x <= CameraDirector.BG_PAN_MAX_STICK + 1e-9, "stick ${side.x}")
    }

    @Test fun zoomDoesNotPingPong() {
        // Nagebootst uit de zaal (18 nov): spelers rond 0,15 hoog op 1×; vroeger 60 zooms in 9 minuten.
        for (pos in listOf(CameraPosition.ACHTER_GOAL, CameraPosition.MIDDENLIJN)) {
            val s = FootballSettings(sport = Sport.ZAAL, position = pos, maxZoom = 3.0, autoZoom = true, steering = Steering.APP_STEER)
            val director = CameraDirector(s)
            val cam = FakeCam()
            cam.apply(director.start(cam.obs(), 0.0), 0.1)
            val rnd = kotlin.random.Random(7)
            var t = 0.0
            val zooms = mutableListOf<Pair<Double, Double>>()
            while (t < 540.0) {
                t += 0.1
                val base = 0.15 + 0.05 * kotlin.math.sin(t / 20) + (rnd.nextDouble() - 0.5) * 0.04
                val h = base * cam.zoom
                val extent = (0.25 + 0.1 * kotlin.math.sin(t / 13)) * cam.zoom
                val edge = if (extent > 0.95) 0.8 else 0.0
                val p = PlayEstimate(0.5 + 0.1 * kotlin.math.sin(t / 7), 0.5, 0.7, Detection(0.5, 0.5, h / 3, h, 0.8), 0.1, 3, false, edge, 0.5, extent, h)
                val acts = director.tick(p, cam.obs(), t)
                acts.filterIsInstance<DirectorAction.Zoom>().forEach { zooms += t to it.factor }
                cam.apply(acts, 0.1)
            }
            assertTrue(zooms.size <= 25, "$pos: ${zooms.size} zooms in 9 minuten")
            for (i in 1 until zooms.size) {
                val (t0, z0) = zooms[i - 1]
                val (t1, z1) = zooms[i]
                val prev = if (i >= 2) zooms[i - 2].second else 1.0
                val reversal = (z1 - z0) * (z0 - prev) < 0
                assertTrue(!reversal || t1 - t0 >= 5.0, "$pos: omkeer binnen ${"%.1f".format(t1 - t0)} s bij $t1")
            }
            assertTrue(zooms.all { it.second <= 3.0 })
        }
    }

    @Test fun handMovementPausesTheApp() {
        val director = CameraDirector(FootballSettings(sport = Sport.ZAAL, position = CameraPosition.ACHTER_GOAL))
        val cam = FakeCam()
        director.start(cam.obs(), 0.0)
        val play = PlayEstimate(0.5, 0.5, 0.8, Detection(0.5, 0.5, 0.05, 0.15, 0.8), 0.05, 4, false, 0.0, 0.5, 0.2, 0.15)
        var t = 0.0
        repeat(30) { t += 0.1; cam.apply(director.tick(play, cam.obs(), t), 0.1) }
        // Gebruiker draait 20° met de hand.
        cam.yaw -= 20.0
        t += 0.1
        director.tick(play.copy(x = 0.9), cam.obs(), t)
        assertTrue(director.events.any { it.startsWith("HAND draai") }, director.events.toString())
        val during = mutableListOf<DirectorAction>()
        repeat(40) { t += 0.1; during += director.tick(play.copy(x = 0.9), cam.obs(), t) }
        assertTrue(during.none { it is DirectorAction.Stick && it.x != 0.0 || it is DirectorAction.Zoom }, "app wacht: $during")
        repeat(20) { t += 0.1; director.tick(play, cam.obs(), t) }
        assertTrue(director.events.any { it.startsWith("HAND klaar") })
        // Handzoom wordt overgenomen.
        director.events.clear()
        cam.zoom = 2.5
        repeat(5) { t += 0.1; director.tick(play, cam.obs(), t) }
        assertTrue(director.events.any { it.startsWith("HAND zoom") })
    }

    @Test fun steeringUsesAnglesAndLearnedSign() {
        val director = CameraDirector(veld.copy(steering = Steering.APP_STEER))
        director.start(camera(zoom = 3.0), 0.0)
        val anchor = Detection(0.8, 0.5, 0.03, 0.12, 0.8)
        val play = PlayEstimate(0.8, 0.5, 0.8, anchor, 0.05, 6, true, 0.0, 0.8, 0.1)
        val stick = director.tick(play, camera(zoom = 3.0), 0.1).filterIsInstance<DirectorAction.Stick>().single()
        assertTrue(stick.x > 0 && stick.x <= veld.profile.steerMax + 1e-9, "stick ${stick.x}")
        // Dezelfde beeldfout op 1× is in graden groter: hardere stick.
        val wide = CameraDirector(veld.copy(steering = Steering.APP_STEER))
        wide.start(camera(zoom = 1.0), 0.0)
        val stickWide = wide.tick(play, camera(zoom = 1.0), 0.1).filterIsInstance<DirectorAction.Stick>().single()
        assertTrue(stickWide.x > stick.x)
    }

    @Test fun axisLearnerFlipsSignAndEstimatesRate() {
        val learner = AxisLearner()
        repeat(10) { learner.observe(0.5, -1.0, 0.1, steady = true) }
        assertEquals(-1.0, learner.sign)
        assertTrue(learner.signDecided)
        repeat(40) { learner.observe(0.5, -4.0, 0.1, steady = true) } // 40°/s bij halve stick ⇒ 80°/s vol
        assertTrue(abs(learner.degPerSecondAtFullStick - 80.0) < 5.0, "${learner.degPerSecondAtFullStick}")
        assertTrue(learner.stickFor(20.0) < 0)
    }

    @Test fun imageYawLearnerDetectsFlippedConvention() {
        val l = ImageYawSignLearner()
        repeat(10) { l.observe(2.0, 0.03) } // yaw omhoog én spelers naar rechts ⇒ omgekeerd
        assertTrue(l.decided)
        assertEquals(-1.0, l.sign)
    }

    @Test fun fovAndProfiles() {
        assertTrue(abs(CameraDirector.horizontalFovDeg(1.0) - 84.0) < 1e-9)
        assertTrue(abs(CameraDirector.horizontalFovDeg(3.0) - 33.4) < 0.5)
        val zijlijn = FootballSettings(position = CameraPosition.ZIJLIJN, pitchSide = PitchSide.RECHTS).profile
        assertTrue(zijlijn.panLimitRightDeg > zijlijn.panLimitLeftDeg)
        assertTrue(FootballSettings(sport = Sport.ZAAL).profile.defaultZoom < veld.profile.defaultZoom)
        assertEquals(2.0, FootballSettings(maxZoom = 2.0).profile.defaultZoom)
    }
}
