package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kotlin.math.cos
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real Main/Guide/Nav/Output/Actuator; converted sensor input and native sound are fixture leaves. */
class CalibratedFallbackNativeGuidanceContinuityTest {
    @After fun retireActor() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun healthyFreshFallbackStartsAndCompletesTheSameClockAndMetresPacket() = withHarness { h ->
        assertTrue(h.spoken.isEmpty())
        h.advance(100L); h.sample(); h.publish()
        val action = h.spoken.single()
        val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
        assertTrue(h.message(action), h.message(action).contains("시 방향"))
        assertFalse(h.message(action), h.message(action).contains("시 사이"))
        assertTrue(h.message(action), h.message(action).contains("미터"))
        assertNull(h.current.observation!!.reportedHeadingAccuracyDegrees)
        assertNull(h.current.observation!!.headingAccuracyDegrees)
        assertEquals(RouteDirectionGraceMode.NORMAL, h.nav.currentDirectionGraceSnapshot()!!.mode)
        h.advance(100L); h.sample(); h.native("start", action)
        val stops = h.stops
        repeat(3) { h.advance(300L); h.sample(); h.publish(); assertEquals(stops, h.stops) }
        assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue"))
        h.native("done", action)
        assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
        assertEquals(cue.facingObservedAtElapsedRealtimeMs!! + 500L, cue.facingValidUntilElapsedRealtimeMs)
    }

    @Test fun exactPlayingClockRecoversWhenFreshNominalFullySupportsItsSameSpokenClock() = withHarness(exact = true) { h ->
        val action = h.spoken.last()
        val original = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
        h.advance(100L); h.sample(3f); h.native("start", action)
        val stops = h.stops
        h.advance(100L); h.sample(); h.publish()
        val firstBad = h.nav.currentDirectionGraceSnapshot()!!
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, firstBad.mode)
        h.advance(100L); h.sample(); h.publish()
        assertEquals(RouteDirectionGraceMode.NORMAL, h.nav.currentDirectionGraceSnapshot()!!.mode)
        assertNull(h.current.observation!!.reportedHeadingAccuracyDegrees)
        while (h.now < firstBad.deadlineElapsedRealtimeMs!! + 100L) {
            h.advance(100L); h.sample(); h.publish()
            assertEquals(stops, h.stops)
            assertEquals(original, read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue"))
            assertEquals(RouteDirectionGraceMode.NORMAL, h.nav.currentDirectionGraceSnapshot()!!.mode)
        }
        assertEquals(original.facingObservedAtElapsedRealtimeMs!! + 500L, original.facingValidUntilElapsedRealtimeMs)
        h.native("done", action)
        assertEquals(original, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
    }

    @Test fun genuineCurrentLowQualityKeepsOnlyTheOriginalFixedGraceThenRejectsLateDone() = withHarness(exact = true) { h ->
        val action = h.spoken.last()
        h.advance(100L); h.sample(3f); h.native("start", action)
        val stops = h.stops
        h.advance(100L)
        h.state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, h.now)
        h.sample(3f); h.publish()
        val episode = h.nav.currentDirectionGraceSnapshot()!!
        assertEquals(RouteDirectionGraceMode.GRACE_RETAINED, episode.mode)
        // This test verifies the immutable deadline, using the composition's unchanged 9-second policy.
        val expectedDeadline = h.now + GuidanceRecoveryTiming.DIRECTION_GRACE_MS
        assertEquals(expectedDeadline, episode.deadlineElapsedRealtimeMs)
        while (h.now < expectedDeadline - 1L) {
            val delay = minOf(300L, expectedDeadline - 1L - h.now)
            // Refresh the real LOW callback at the current receipt time.
            h.state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, h.now)
            h.advance(delay); h.sample(3f)
            // All currently sampled alternatives remain genuinely LOW, so healthy fallback cannot recover.
            for (stream in RouteCompassStream.entries) h.state.onAccuracyChanged(1, stream, EarthOrientationAccuracy.LOW, h.now)
            h.current = h.state.evaluate(h.now, ChestMountedGeomagneticReference(0.0, 53.13, 50.0))
            h.publish()
            assertEquals(RouteCompassHeadingReason.QUALITY_LOW, h.current.reason)
            assertEquals(stops, h.stops)
            assertEquals(episode.deadlineElapsedRealtimeMs, h.nav.currentDirectionGraceSnapshot()!!.deadlineElapsedRealtimeMs)
        }
        h.advance(1L); h.sample(3f)
        for (stream in RouteCompassStream.entries) h.state.onAccuracyChanged(1, stream, EarthOrientationAccuracy.LOW, h.now)
        h.current = h.state.evaluate(h.now, ChestMountedGeomagneticReference(0.0, 53.13, 50.0))
        h.publish()
        assertEquals(stops + 1, h.stops)
        assertEquals(RouteDirectionGraceMode.PROBLEM, h.nav.currentDirectionGraceSnapshot()!!.mode)
        h.native("done", action)
        assertNull(read<RouteSpeechCueToken?>(h.nav, "completedSpeechCue"))
    }

    @Test fun initialJoinClockGivesTheCurrentClosestDistanceWithoutAcceptingAConnector() = withHarness(offRoute = true) { h ->
        assertTrue(h.nav.isWaitingForInitialRouteJoin())
        assertTrue(h.spoken.isEmpty())
        h.advance(100L); h.sample(); h.publish()
        val action = h.spoken.single()
        assertTrue(h.message(action), h.message(action).contains("시 방향"))
        assertFalse(h.message(action), h.message(action).contains("시 사이"))
        assertTrue(h.message(action), h.message(action).contains("직선거리 약 22미터"))
        assertFalse(h.message(action), h.message(action).contains("이동하세요"))
        assertNull(h.nav.currentAcceptedRouteMatchFor(h.fix.elapsedRealtimeMs))
        val target = h.nav.currentRouteApproachTarget(h.now)!!
        assertTrue(target.distanceM in 20.0..24.0)
        assertEquals(h.fix.elapsedRealtimeMs, target.observedAtElapsedRealtimeMs)
        val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
        h.advance(100L); h.sample(); h.native("start", action); h.native("done", action)
        assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
        assertTrue(h.nav.isWaitingForInitialRouteJoin())
        assertNull(h.nav.currentAcceptedRouteMatchFor(h.fix.elapsedRealtimeMs))
    }

    @Test fun walkingSwayKeepsOneClockPacketPlayingWhileRawHeadingStillMoves() = withHarness { h ->
        for (heading in listOf(22.0, 338.0, 22.0, 338.0, 22.0, 338.0, 22.0)) {
            h.advance(100L); h.sample(heading = heading); h.publish()
        }
        val action = h.spoken.single(); val stops = h.stops
        val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
        h.native("start", action)
        for (heading in listOf(338.0, 22.0, 338.0, 22.0)) {
            h.advance(100L); h.sample(heading = heading); h.publish()
            assertEquals(stops, h.stops)
            assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue"))
        }
        assertTrue(h.message(action), h.message(action).contains("약 12시 방향"))
        assertFalse(h.message(action).contains("시 사이"))
        assertEquals(22.0, h.current.observation!!.degreesTrueNorth, 0.001)
        assertNull(h.current.observation!!.headingAccuracyDegrees)
        assertTrue(h.current.observation!!.headingTrend!!.sampleCount > 1)
        h.native("done", action)
        assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
    }

    @Test fun twentyMsSensorSwayWithFortyMsLagKeepsTheThreeHundredMsConsumerSpeaking() {
        val h = Harness(false, false, initialHeading = 85.0, rotateEast = true, lagMs = 40L)
        try {
            val headings = listOf(85.0, 95.0, 88.0, 92.0)
            repeat(15) { i -> h.advance(20L); h.sample(heading = headings[i % 4], lagMs = 40L) }
            h.publish()
            val action = h.spoken.single(); val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
            assertTrue(h.message(action), h.message(action).contains("약 12시 방향"))
            val firstRaw = h.current.observation!!
            assertEquals(40L, h.now - firstRaw.observedAtElapsedRealtimeMs)
            assertNull(firstRaw.headingAccuracyDegrees); assertNull(firstRaw.reportedHeadingAccuracyDegrees)
            assertEquals(firstRaw.observedAtElapsedRealtimeMs, firstRaw.headingTrend!!.latestActualObservedAtMs)
            h.native("start", action); val stops = h.stops
            repeat(3) { group ->
                repeat(15) { i -> h.advance(20L); h.sample(heading = headings[(group * 15 + i) % 4], lagMs = 40L) }
                h.publish()
                assertEquals(stops, h.stops)
                assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue"))
                val raw = h.current.observation!!
                assertEquals(40L, h.now - raw.observedAtElapsedRealtimeMs)
                assertTrue(raw.angularRateDegreesPerSecond!! > raw.headingTrend!!.representativeAngularRateDegreesPerSecond!!)
            }
            h.native("done", action)
            assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
        } finally { h.output.closeActuator() }
    }

    @Test fun aFortyMsOldActualReversalStillRetiresTheSpeakingClockImmediately() = withHarness { h ->
        h.advance(100L); h.sample(); h.publish()
        val action = h.spoken.single()
        h.native("start", action); val stops = h.stops
        h.advance(100L); h.sample(heading = 180.0, lagMs = 40L)
        assertEquals(40L, h.now - h.current.observation!!.observedAtElapsedRealtimeMs)
        assertEquals(180.0, h.current.observation!!.headingTrend!!.representativeDegreesTrueNorth, 0.001)
        assertTrue(h.nav.hasHardInvalidatedDirectionClaim(h.now, h.current.observation))
        h.publish()
        assertEquals(stops + 1, h.stops)
        h.native("done", action)
        assertNull(read<RouteSpeechCueToken?>(h.nav, "completedSpeechCue"))
    }

    @Test fun oneSecondTwentyFiveDegreeSwayKeepsTwelveWithARealSixHundredMsSensorWindow() {
        val h = Harness(false, false, initialHeading = 90.0, rotateEast = true, lagMs = 40L)
        try {
            var elapsed = 0L
            fun stream(count: Int) {
                repeat(count) {
                    elapsed += 20L; h.advance(20L)
                    h.sample(heading = 90.0 + 25.0 * kotlin.math.sin(2.0 * Math.PI * elapsed / 1000.0), lagMs = 40L)
                }
            }
            stream(30); h.publish()
            stream(1); h.publish() // A second real full-window representative confirms the cold clock.
            val action = h.spoken.single(); val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
            assertTrue("${h.message(action)} estimate=${h.current.observation!!.headingTrend}", h.message(action).contains("약 12시 방향"))
            h.native("start", action); val stops = h.stops
            repeat(8) {
                stream(15); h.publish()
                val observation = h.current.observation!!
                val representative = observation.headingTrend!!
                assertEquals(600L, representative.windowSpanMs)
                assertEquals(31, representative.sampleCount)
                assertEquals(40L, h.now - representative.latestActualObservedAtMs)
                val facing = RouteFacingGuidance.evaluate(90.0, observation, h.now)
                assertEquals(12, facing.clockHour)
                assertNull(facing.clockRange)
                assertEquals(stops, h.stops)
                assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue"))
            }
            h.native("done", action)
            assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
        } finally { h.output.closeActuator() }
    }

    @Test fun continuousTenDegreeTurnAndActualSettleCreateTheNewClockWithoutOldMean() = withHarness { h ->
        h.advance(100L); h.sample(); h.publish()
        val initial = h.spoken.single()
        h.native("start", initial); h.native("done", initial)
        var recheckNotice: Any? = null
        repeat(9) { i ->
            h.advance(100L); h.sample(heading = (i + 1) * 10.0); h.publish()
            if (recheckNotice == null && h.spoken.size > 1) {
                val notice = h.spoken.last()
                assertEquals("방향을 다시 확인하고 있습니다.", h.message(notice))
                h.native("start", notice)
                assertTrue(requireNotNull(h.output.currentActuatorOrNull()).isAppSpeechActive())
                recheckNotice = notice
            }
        }
        repeat(2) { h.advance(100L); h.sample(heading = 90.0); h.publish() }
        val observation = h.current.observation!!
        assertEquals(90.0, observation.degreesTrueNorth, 0.001)
        assertEquals(90.0, observation.headingTrend!!.representativeDegreesTrueNorth, 0.001)
        assertNull(observation.headingAccuracyDegrees); assertNull(observation.reportedHeadingAccuracyDegrees)
        val update = h.nav.currentGuidance(h.now, observation)!!
        assertTrue(update.instruction.orEmpty(), update.instruction.orEmpty().contains("약 9시 방향"))
        assertFalse(update.instruction.orEmpty().contains("시 사이"))
        // The actual emitted status owns native speech until its terminal callback. Do not
        // mistake it for the new ACTION or leave its real output owner permanently unfinished.
        val notice = requireNotNull(recheckNotice)
        h.native("done", notice)
        assertFalse(h.guide.recheckNoticeActive)
        // Fresh samples keep the real 250-ms controller retry eligible after the status terminal.
        repeat(15) { h.advance(20L); h.sample(heading = 90.0) }
        h.publish()
        val latest = h.spoken.last()
        assertNotEquals(notice, latest)
        assertTrue(h.message(latest), h.message(latest).contains("약 9시 방향"))
        val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
        h.native("start", latest); h.native("done", latest)
        assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
    }

    @Test fun fastFiveAndOneMsStreamsKeepTheWholeWindowAndCompleteOneClockPacket() {
        for ((cadence, amplitude, count) in listOf(Triple(5L, 20.0, 121), Triple(5L, 25.0, 121), Triple(1L, 5.0, 601))) {
            val h = Harness(false, false, initialHeading = 90.0, rotateEast = true, lagMs = 40L)
            try {
                val began = h.now; var nextSensorDue = began + cadence
                fun consume(after: Long) {
                    val until = began + after
                    while (nextSensorDue <= until) {
                        h.advance(nextSensorDue - h.now)
                        val elapsed = nextSensorDue - began
                        h.sample(heading = 90.0 + amplitude * kotlin.math.sin(2.0 * Math.PI * elapsed / 1000.0), lagMs = 40L)
                        nextSensorDue += cadence
                    }
                    h.advance(until - h.now); h.publish()
                }
                consume(300L); consume(600L); consume(900L)
                val action = h.spoken.single(); val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
                assertTrue(h.message(action), h.message(action).contains("약 12시 방향"))
                assertFalse(h.message(action), h.message(action).contains("시 사이"))
                h.native("start", action); val stops = h.stops
                for (timer in listOf(1200L, 1500L, 1800L, 2100L, 2400L)) {
                    consume(timer)
                    val trend = h.current.observation!!.headingTrend!!
                    assertEquals(600L, trend.windowSpanMs)
                    assertEquals(count, trend.sampleCount)
                    assertTrue(trend.hasCompleteTimeWindow)
                    assertEquals(RouteDirectionGraceMode.NORMAL, h.nav.currentDirectionGraceSnapshot()!!.mode)
                    assertEquals(stops, h.stops)
                    assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue"))
                }
                h.native("done", action)
                assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
            } finally {
                h.output.closeActuator()
                GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            }
        }
    }

    @Test fun irregularNineteenTwentyOneAndTwentyThreeMsStreamsKeepTheTimerConsumerClock() {
        for (cadence in listOf(19L, 21L, 23L)) {
            val h = Harness(false, false, initialHeading = 90.0, rotateEast = true, lagMs = 40L)
            try {
                val began = h.now; var nextSensorDue = began + cadence
                fun consume(after: Long) {
                    val until = began + after
                    while (nextSensorDue <= until) {
                        h.advance(nextSensorDue - h.now)
                        val sensorElapsed = nextSensorDue - began
                        h.sample(heading = 90.0 + 25.0 * kotlin.math.sin(2.0 * Math.PI * sensorElapsed / 1000.0), lagMs = 40L)
                        nextSensorDue += cadence
                    }
                    h.advance(until - h.now); h.publish()
                }
                consume(300L); consume(600L); consume(900L)
                val action = h.spoken.single(); val cue = read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue")
                assertTrue(h.message(action), h.message(action).contains("약 12시 방향"))
                h.native("start", action); val stops = h.stops
                for (timer in listOf(1200L, 1500L, 1800L, 2100L, 2400L)) {
                    consume(timer)
                    val trend = h.current.observation!!.headingTrend!!
                    assertTrue(trend.hasCompleteTimeWindow)
                    assertTrue(trend.windowSpanMs < 600L)
                    assertEquals(stops, h.stops)
                    assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "pendingSpeechCue"))
                }
                h.native("done", action)
                assertEquals(cue, read<RouteSpeechCueToken>(h.nav, "completedSpeechCue"))
            } finally {
                h.output.closeActuator()
                GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            }
        }
    }

    private fun withHarness(exact: Boolean = false, offRoute: Boolean = false, test: (Harness) -> Unit) {
        val h = Harness(exact, offRoute)
        try { test(h) } finally { h.output.closeActuator() }
    }
    private class Harness(exact: Boolean, offRoute: Boolean, initialHeading: Double = 0.0, rotateEast: Boolean = false, lagMs: Long = 0L) {
        val f = Class.forName(WalkingSpeechContinuityReviewTest::class.java.name + "$" + "Fixture")
            .getDeclaredConstructor(Double::class.javaPrimitiveType, Double::class.javaObjectType, Double::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(5.0, 3.0, 0.0)
        val output = read<SpeechOutputController>(f, "output")
        val main = read<MainActivity>(f, "main")
        val guide = read<RouteGuidanceController>(f, "guide")
        val positions = read<PositioningSessionController>(f, "positions")
        val nav = read<RouteNavigator>(f, "navigator")
        val fix = read<TrustedLocation>(f, "fix")
        val clock = read<Any>(f, "clock")
        val now get() = call(clock, "getNow") as Long
        val runtime get() = read<Any>(f, "native")
        val spoken get() = read<List<Any>>(runtime, "spoken")
        val stops get() = read<Int>(runtime, "stops")
        val state = RouteCompassSensorState()
        var current: RouteCompassHeadingResult
        val session: RouteCompassSession
        init {
            // The guide fixture controls native/accessibility leaves after graph construction.
            // Its child owners captured the original ports, so bind the same real-output ports
            // and observation clock here before either child can schedule or deliver a notice.
            val speech = read<RouteGuidanceController.SpeechPort>(guide, "speech")
            val callbacks = read<RouteCallbackScheduler>(guide, "callbacks")
            for (name in listOf("decisionSpeech", "recheckNotices")) {
                val child = read<Any>(guide, name)
                put(child, "speech", speech)
                put(child, "callbacks", callbacks)
                if (name == "recheckNotices") put(child, "elapsedRealtimeMs", { now })
            }
            var route = read<WalkingRoute>(nav, "route")
            if (rotateEast) {
                fun east(p: RoutePoint) = p.copy(latitude = fix.latitude - (p.longitude - fix.longitude) * cos(Math.toRadians(fix.latitude)),
                    longitude = fix.longitude + (p.latitude - fix.latitude) / cos(Math.toRadians(fix.latitude)))
                route = route.copy(polyline = route.polyline.map(::east),
                    guidePoints = route.guidePoints.map { it.copy(point = east(it.point)) })
            }
            if (offRoute) {
                val offset = 22.0 / (111195.0 * cos(Math.toRadians(fix.latitude)))
                fun east(p: RoutePoint) = p.copy(longitude = p.longitude + offset)
                route = route.copy(polyline = route.polyline.map(::east),
                    guidePoints = route.guidePoints.map { it.copy(point = east(it.point)) })
            }
            if (exact) {
                call(f, "startWithNativeIntro")
            } else {
                nav.setRoute(route, waitForInitialRouteJoin = offRoute, announceStartSummary = false)
                read<InstalledRouteStartSpeechCoordinator>(f, "intro").clear()
            }
            call(f, "connectCompassEvents")
            session = read(f, "compassSession")
            state.start(1, now - 100L, RouteCompassStream.entries.toSet())
            current = measured(if (exact) 3f else Float.NaN, initialHeading, lagMs)
            val old = read<RouteGuidanceController.CompassReader>(guide, "compassInput")
            put(guide, "compassInput", object : RouteGuidanceController.CompassReader by old {
                override fun heading(nowMs: Long) = state.evaluate(nowMs, ChestMountedGeomagneticReference(0.0, 53.13, 50.0))
                override fun facing(nowMs: Long) = heading(nowMs).observation
            })
            if (!exact) {
                guide.updateRouteGuidance(fix, positions.rawRouteLocation!!)
                output.ensureFeedbackActuator()
                call(runtime, "ready")
            }
        }
        fun measured(error: Float, heading: Double = 0.0, lagMs: Long = 0L): RouteCompassHeadingResult {
            val observedAt = now - lagMs
            val angle = Math.toRadians(heading)
            val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, observedAt, EarthOrientationAccuracy.HIGH)
            state.onRotationVector(1, RotationMatrix3(kotlin.math.cos(angle).toFloat(), 0f, (-kotlin.math.sin(angle)).toFloat(),
                (-kotlin.math.sin(angle)).toFloat(), 0f, (-kotlin.math.cos(angle)).toFloat(), 0f, 1f, 0f),
                error, observedAt, EarthOrientationAccuracy.HIGH, now)
            state.onVector(1, RouteCompassStream.GRAVITY, gravity, now)
            state.onVector(1, RouteCompassStream.ACCELEROMETER, gravity, now)
            state.onVector(1, RouteCompassStream.MAGNETIC_FIELD,
                RouteCompassVectorSample(-30.0 * kotlin.math.sin(angle), -40.0, -30.0 * kotlin.math.cos(angle), observedAt, EarthOrientationAccuracy.HIGH), now)
            return state.evaluate(now, ChestMountedGeomagneticReference(0.0, 53.13, 50.0))
        }
        fun sample(error: Float = Float.NaN, heading: Double = 0.0, lagMs: Long = 0L) { current = measured(error, heading, lagMs) }
        fun advance(ms: Long) { call(clock, "advanceBy", ms) }
        fun native(name: String, action: Any) { call(runtime, name, read<String>(action, "id")) }
        fun message(action: Any) = read<String>(action, "message")
        fun publish() {
            val event = RouteCompassObservationEvent(1,
                if (current.observation == null) RouteCompassStream.ROTATION_VECTOR else RouteCompassStream.MAGNETIC_FIELD,
                current.observation?.observedAtElapsedRealtimeMs?.times(1000000L), now, current, current.observation == null)
            main.onRouteCompassObservation(event, trackerCurrent = { true }, sdkSourceCurrent = { true },
                post = { call(clock, "post", session, it, 0L) },
                invalidateClaim = { guide.retryCurrentNavigationSpeechForObservation(now, it.receivedAtMs, it.heading, false) },
                schedule = { _, bad -> if (bad == null) guide.retryCurrentNavigationSpeechAfterReady(now)
                    else guide.retryCurrentNavigationSpeechForObservation(now, bad, current) })
            advance(0L)
            guide.retryCurrentNavigationSpeechAfterReady(now)
        }
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> read(o: Any, name: String): T =
            o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T
        private fun put(o: Any, name: String, value: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, value) }
        private fun call(o: Any, name: String, vararg args: Any): Any? {
            val method = o.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            try { return method.apply { isAccessible = true }.invoke(o, *args) }
            catch (e: InvocationTargetException) { throw e.targetException }
        }
    }
}
