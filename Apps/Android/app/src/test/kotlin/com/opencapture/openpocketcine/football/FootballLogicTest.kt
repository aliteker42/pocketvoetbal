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
        val finder = PlayFinder(veld.profile)
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

    @Test fun nothingSeenMeansNoConfidence() {
        val finder = PlayFinder(veld.profile)
        val play = finder.update(SceneObservation(emptyList(), emptyList(), null, 0.0))
        assertEquals(0.0, play.confidence)
        assertNull(play.anchor)
    }

    @Test fun lonelyKeeperDoesNotPullThePlay() {
        val finder = PlayFinder(veld.profile)
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
