package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityPolicy
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeController
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeEffects
import kr.co.hanium.dreamup.walksafe.navigation.RouteClockReferenceKind
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingReason
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteDirectionGraceMode
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingDirection
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingGuidance
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingSensorQuality
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingSource
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.RouteSpeechCueToken
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteGuidePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityCompletion
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeAction
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentUserConfirmation
import kr.co.hanium.dreamup.walksafe.session.PriorityUserAgeBand
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Reuses the real SensorState/Main/Guide/Nav/Output/Actuator composition without changing its
 * scheduler. Sensor conversion and native sound are controlled leaves, not Android or audible proof.
 * The fixed fresh GPS and qualified upright vectors isolate a 0.2-degree clock-boundary wobble.
 */
class CalibratedFallbackClockBoundaryRegressionTest {
    @After fun retireActor() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun freshFallbackAtTheClockBoundaryStartsAndCompletesThenStaysHealthyForTwoMinutes() = withHarness { h ->
        h.prepareLongRuntime()
        val seen = mutableSetOf<String>()
        val sampledClocks = mutableSetOf<Int>()
        var completedDirection = false
        var playingAction: Any? = null
        var playingCue: RouteSpeechCueToken? = null
        var startedAt = 0L
        val initialStops = h.stops
        val began = h.now
        repeat(400) { tick ->
            h.boundaryTick(sampledClocks)
            h.publishQualifiedCameraAndRenewLeaseIfDue()
            // A stationary position is still measured afresh through the real positioning path.
            if (tick % 3 == 2) h.publishStationaryGps()
            h.assertRuntimeHealthy()
            if (playingAction != null && h.now - startedAt >= 3_000L) {
                h.native("done", playingAction)
                assertEquals(h.diagnostic(), playingCue, h.completedCue)
                completedDirection = true
                playingAction = null
                playingCue = null
            }
            val newActions = h.spoken.filter { h.id(it) !in seen }
            for (action in newActions) {
                seen += h.id(action)
                assertDirectionAction(h.message(action))
                assertNull("The current real ACTION must finish before another starts: ${h.diagnostic()}", playingAction)
                val cue = requireNotNull(h.pendingCue)
                h.native("start", action)
                assertEquals("A fresh tiny boundary change must preserve native START: ${h.diagnostic()}", cue, h.pendingCue)
                assertTrue(requireNotNull(h.output.currentActuatorOrNull()).isAppSpeechActive())
                playingAction = action
                playingCue = cue
                startedAt = h.now
            }
            if (tick == 19) assertTrue("Fresh calibrated fallback must deliver a direction within six seconds: ${h.diagnostic()}", completedDirection)
            assertEquals(h.diagnostic(), RouteDirectionGraceMode.NORMAL, h.nav.currentDirectionGraceSnapshot()!!.mode)
            assertEquals("A 0.2-degree wobble must not cancel a queued or started direction: ${h.diagnostic()}", initialStops, h.stops)
        }
        assertEquals(120_000L, h.now - began)
        assertEquals("The real trend must cross both sides of the 15-degree rounding boundary",
            setOf(12, 1), sampledClocks)
        assertTrue(completedDirection)
        assertNull("The stationary boundary run must not leave an unfinished native ACTION", playingAction)
        assertEquals(133, h.freshGpsCount)
        assertTrue("The 60-second lease must be renewed through the actual authority", h.renewals >= 3)
    }

    @Test fun boundaryWobbleKeepsThePlayingPacketAndDoesNotWarnAfterItsDone() = withHarness { h ->
        h.warmOneClock()
        val action = h.spoken.single()
        assertDirectionAction(h.message(action))
        val cue = requireNotNull(h.pendingCue)
        h.native("start", action)
        val stops = h.stops
        val sampledClocks = mutableSetOf<Int>()
        repeat(16) {
            h.boundaryTick(sampledClocks)
            assertEquals("Qualified adjacent clock jitter must not stop the playing sentence", stops, h.stops)
            assertEquals("The original playing packet must keep ownership", cue, h.pendingCue)
        }
        assertEquals(setOf(12, 1), sampledClocks)
        h.native("done", action)
        assertEquals("Native DONE must complete the original direction packet", cue, h.completedCue)
        // Once ACTION is idle, an unnecessary soft-fault notice can no longer hide behind it.
        repeat(3) { h.boundaryTick(sampledClocks) }
        assertEquals("Boundary jitter must not enqueue a direction-recheck notice after DONE", 1, h.spoken.size)
        assertEquals(stops, h.stops)
    }

    @Test fun aFreshActualReversalStillCancelsTheBoundaryClockAndRejectsLateDone() = withHarness { h ->
        h.warmOneClock()
        val action = h.spoken.single()
        val cue = requireNotNull(h.pendingCue)
        h.native("start", action)
        val stops = h.stops
        h.advance(20L)
        h.sample(165.1)
        assertTrue(h.nav.hasHardInvalidatedDirectionClaim(h.now, h.current.observation))
        h.publish()
        assertEquals(stops + 1, h.stops)
        h.native("done", action)
        assertNotEquals("A real reversal must never complete the old clock", cue, h.completedCue)
    }

    @Test fun aSmallCoarseDirectionChangeStillRequiresTwoFreshObservations() {
        val f = ClockComparisonFixture(24.9)
        val changed = f.observation(25.1, 1_100L)
        assertEquals(RouteFacingDirection.FRONT, RouteFacingGuidance.evaluate(0.0, f.initial, 1_000L).direction)
        assertEquals(RouteFacingDirection.RIGHT, RouteFacingGuidance.evaluate(0.0, changed, 1_100L).direction)
        assertNull(f.nav.retryGuidance(1_100L, changed)!!.instruction)
        assertNull("Polling the same sample must not confirm a new coarse direction",
            f.nav.retryGuidance(1_150L, changed)!!.instruction)
        assertNotNull(f.nav.retryGuidance(1_200L, f.observation(25.1, 1_200L))!!.instruction)
    }

    @Test fun aLargerAdjacentClockChangeStillRequiresItsOwnSecondSample() {
        val f = ClockComparisonFixture(12.0)
        val changed = f.observation(18.0, 1_100L)
        assertNull(f.nav.retryGuidance(1_100L, changed)!!.instruction)
        assertNull(f.nav.retryGuidance(1_150L, changed)!!.instruction)
        assertNotNull(f.nav.retryGuidance(1_200L, f.observation(18.0, 1_200L))!!.instruction)
    }

    @Test fun aSmallNominalBoundaryChangeKeepsTheOriginalQueuedClockAndLease() {
        val f = ClockComparisonFixture(14.9)
        val cue = f.nav.retryGuidance(1_100L, f.observation(14.9, 1_100L))!!
        assertTrue(cue.instruction.orEmpty().contains("약 12시 방향"))
        assertTrue(f.nav.reserveInstruction(cue))
        val changed = f.observation(15.1, 1_200L)
        assertEquals(1, RouteFacingGuidance.evaluate(0.0, changed, 1_200L).clockHour)
        assertFalse(f.nav.hasHardInvalidatedDirectionClaim(1_200L, changed))
        assertTrue(f.nav.isInstructionCurrent(cue, 1_200L, changed))
        assertTrue(f.nav.prepareFreshDirectionStart(cue, 1_200L, changed))
        assertTrue(cue.instruction.orEmpty().contains("약 12시 방향"))
        assertEquals(1_600L, cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
    }

    @Test fun aLargerAdjacentClockChangeCannotStartTheOriginalQueuedClock() {
        val f = ClockComparisonFixture(12.0)
        val cue = f.nav.retryGuidance(1_100L, f.observation(12.0, 1_100L))!!
        assertTrue(f.nav.reserveInstruction(cue))
        for (at in listOf(1_200L, 1_300L)) {
            val changed = f.observation(18.0, at)
            assertEquals(1, RouteFacingGuidance.evaluate(0.0, changed, at).clockHour)
            assertFalse(f.nav.isInstructionCurrent(cue, at, changed))
            assertFalse(f.nav.prepareFreshDirectionStart(cue, at, changed))
        }
    }

    @Test fun smallSuccessiveChangesCannotDriftBeyondTheOriginalOfferedAngle() {
        val f = ClockComparisonFixture(14.9)
        val cue = f.nav.retryGuidance(1_100L, f.observation(14.9, 1_100L))!!
        assertTrue(f.nav.reserveInstruction(cue))
        for ((relative, at) in listOf(18.0 to 1_200L, 19.0 to 1_300L)) {
            val near = f.observation(relative, at)
            assertTrue(f.nav.isInstructionCurrent(cue, at, near))
            assertTrue(f.nav.prepareFreshDirectionStart(cue, at, near))
        }
        val beyondOriginal = f.observation(21.0, 1_400L)
        assertFalse(f.nav.isInstructionCurrent(cue, 1_400L, beyondOriginal))
        assertFalse(f.nav.prepareFreshDirectionStart(cue, 1_400L, beyondOriginal))
    }

    @Test fun evenASmallCoarseChangeCannotStartTheOriginalQueuedClock() {
        val f = ClockComparisonFixture(24.9)
        val cue = f.nav.retryGuidance(1_100L, f.observation(24.9, 1_100L))!!
        assertTrue(f.nav.reserveInstruction(cue))
        for (at in listOf(1_200L, 1_300L)) {
            val right = f.observation(25.1, at)
            assertEquals(RouteFacingDirection.RIGHT, RouteFacingGuidance.evaluate(0.0, right, at).direction)
            assertFalse(f.nav.isInstructionCurrent(cue, at, right))
            assertFalse(f.nav.prepareFreshDirectionStart(cue, at, right))
        }
    }

    @Test fun aStaleBoundarySampleCannotStartOrRenewTheQueuedClock() {
        val f = ClockComparisonFixture(14.9)
        val cue = f.nav.retryGuidance(1_100L, f.observation(14.9, 1_100L))!!
        assertTrue(f.nav.reserveInstruction(cue))
        val stale = f.observation(15.1, 1_200L)
        assertFalse(RouteFacingGuidance.isUsableObservation(stale, 1_701L))
        assertFalse(f.nav.isInstructionCurrent(cue, 1_701L, stale))
        assertFalse(f.nav.prepareFreshDirectionStart(cue, 1_701L, stale))
        assertEquals(1_600L, cue.speechCueToken!!.facingValidUntilElapsedRealtimeMs)
    }

    @Test fun circularWrapsKeepTheExistingSameClockConfirmation() {
        for ((first, second, expectedClock) in listOf(Triple(-0.1, 0.1, 12), Triple(-179.9, 179.9, 6))) {
            val f = ClockComparisonFixture(first)
            val next = f.observation(second, 1_100L)
            assertEquals(expectedClock, RouteFacingGuidance.evaluate(0.0, next, 1_100L).clockHour)
            val cue = f.nav.retryGuidance(1_100L, next)!!
            assertNotNull(cue.instruction)
            assertTrue(f.nav.reserveInstruction(cue))
            assertTrue(f.nav.isInstructionCurrent(cue, 1_100L, next))
        }
    }

    private fun assertDirectionAction(message: String) {
        assertTrue("A healthy clock-boundary sample must not produce a warning: $message",
            message.contains("시 방향") && message.contains("미터"))
        assertFalse(message, message.contains("다시 확인"))
        assertFalse(message, message.contains("방향이 확인되면"))
    }

    private fun withHarness(test: (Harness) -> Unit) {
        val h = Harness()
        try { test(h) } finally { h.output.closeActuator() }
    }

    /** These controls isolate confirmation and the original offered packet's START contract. */
    private class ClockComparisonFixture(initialRelativeDegrees: Double) {
        private fun point(north: Double) = RoutePoint(north / 111_194.92664455874, 0.0)
        val nav = RouteNavigator().apply {
            setRoute(WalkingRoute("SYNTHETIC", WalkingRouteSummary(200, 200),
                listOf(point(0.0), point(200.0)),
                listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 100, turnType = 11))))
        }
        val initial = observation(initialRelativeDegrees, 1_000L)
        init {
            val location = point(50.0).let { TrustedLocation(it.latitude, it.longitude, 2f, 1_000L) }
            assertNull(nav.update(location, 1_000L, false, facingObservation = initial).instruction)
        }
        fun observation(relativeDegrees: Double, at: Long) = RouteFacingObservation(
            degreesTrueNorth = (360.0 - relativeDegrees) % 360.0,
            headingAccuracyDegrees = null,
            observedAtElapsedRealtimeMs = at,
            source = RouteFacingSource.GRAVITY_MAGNETIC,
            sensorQuality = RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS,
        )
    }

    /** Reflection reuses the existing private integration fixture; no shared fixture is edited. */
    private class Harness {
        private val delegate = Class.forName(CalibratedFallbackNativeGuidanceContinuityTest::class.java.name + "$" + "Harness")
            .getDeclaredConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Double::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(false, false, 345.1, false, 40L)
        val output = read<SpeechOutputController>(delegate, "output")
        val nav = read<RouteNavigator>(delegate, "nav")
        val now get() = call(delegate, "getNow") as Long
        val current get() = read<RouteCompassHeadingResult>(delegate, "current")
        val spoken get() = call(delegate, "getSpoken") as List<*>
        val stops get() = call(delegate, "getStops") as Int
        val pendingCue get() = read<RouteSpeechCueToken?>(nav, "pendingSpeechCue")
        val completedCue get() = read<RouteSpeechCueToken?>(nav, "completedSpeechCue")
        private var boundarySample = 0
        private val fixture = read<Any>(delegate, "f")
        private val base = read<MainActivityContinuousLocationStartBehaviorTest.Fixture>(fixture, "base")
        private val environment = MainActivityControllerTestFixture.controller(base.main,
            "officialEnvironmentRuntimeController") as OfficialEnvironmentRuntimeController
        private val evidence = MainActivityControllerTestFixture.controller(base.main,
            "officialEnvironmentEvidenceStore") as OfficialEnvironmentEvidenceStore
        private val statuses = mutableListOf<String>()
        private var cameraObservedAt = -1L
        private var gpsObservedAt = 6_000L
        var freshGpsCount = 0
            private set
        var renewals = 0
            private set

        fun prepareLongRuntime() {
            // The short fixture seeds only its initial guard. User confirmations and education
            // are fixed input assumptions; measured GPS/camera statuses are never injected.
            val pass = EnvironmentEvidenceStatus.PASS
            put(evidence, "userConfirmation", OfficialEnvironmentUserConfirmation(base.epoch,
                pass, pass, pass, pass, pass, pass, pass))
            val education = PriorityUserOnboardingSnapshot(
                ageBand = PriorityUserAgeBand.VERIFIED_14_PLUS,
                educationReviewed = true, safePracticePlaceConfirmed = true,
                completedPractices = PriorityUserPractice.entries.toSet(),
                phonePostureAcknowledged = true, practiceNecessityReviewed = true,
                educationAccepted = true, usageConditionsAcknowledged = true,
                appUsageReviewed = true, appUsageAccepted = true, hazardResponseConfirmed = true,
            )
            assertTrue(education.nativeEducationComplete)
            base.set("priorityUserOnboardingPolicy", PriorityUserOnboardingPolicy(education))
            base.set("priorityUserOnboardingActorId", base.field("reporterUserId"))
            val gps = MainActivityControllerTestFixture.controller(base.main, "continuousGpsSession")
            put(gps, "clock", { now })
            val effects = read<OfficialEnvironmentRuntimeEffects>(environment, "effects")
            put(environment, "effects", object : OfficialEnvironmentRuntimeEffects by effects {
                // This JVM fixture has no Android Views. Every restriction and speech effect
                // still uses the real implementation; an unexpected UI status fails below.
                override fun showStatus(title: String, detail: String) { statuses += "$title: $detail" }
            })
            gpsObservedAt = requireNotNull(evidence.gpsEvidence?.observedAtElapsedRealtimeMs)
            publishQualifiedCameraAndRenewLeaseIfDue()
            assertRuntimeHealthy()
        }

        fun publishQualifiedCameraAndRenewLeaseIfDue() {
            // Converted camera input is a controlled SDK leaf. The normal policy, freshness,
            // evidence gate and runtime guard evaluate it; the product posture policy is intact.
            val observation = CameraFrameQualityObservation(base.epoch, now,
                frameAvailable = true, normalizedBrightness = 0.6, occludedFraction = 0.0,
                angularShakeDegreesPerSecond = 0.0, mountPitchDegrees = 0.0)
            val profile = requireNotNull(WalkSafeEnvironmentProfiles.active(BuildConfig.DEBUG)).cameraFrameQuality
            val assessment = CameraFrameQualityPolicy.assess(base.epoch, now, observation, profile)
            assertEquals(EnvironmentEvidenceStatus.PASS, assessment.status)
            assertTrue(base.main.observeRuntimeRawCameraAssessment(assessment, now))
            cameraObservedAt = now
            environment.applyCurrentAssessment()

            // Normal server renew responses are a transport leaf. Keep the same real lease,
            // epoch and fencing owner through the controller instead of disabling expiration.
            val authority = base.field("gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val held = requireNotNull(authority.activeLeaseOrNull(base.epoch))
            if (requireNotNull(authority.safeRemainingMsOrNull(base.epoch)) <= 30_000L) {
                val operation = requireNotNull(authority.beginRenew(base.epoch))
                val deadline = now + 60_000L
                val serverNow = held.serverTimeEpochMs + deadline - held.localDeadlineElapsedMs
                assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeRenew(operation,
                    held.copy(result = "RENEWED", serverTimeEpochMs = serverNow,
                        leaseExpiresAtEpochMs = serverNow + 60_000L, localDeadlineElapsedMs = deadline)))
                renewals += 1
            }
        }

        fun assertRuntimeHealthy() {
            val decision = requireNotNull(environment.guard).decision()
            assertEquals("$decision statuses=$statuses", OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
            assertTrue("$decision statuses=$statuses", decision.navigationOutputsAllowed)
            assertTrue("$decision statuses=$statuses", decision.unavailableFactors.isEmpty())
            assertTrue("Unexpected environment status: $statuses", statuses.isEmpty())
            assertEquals(EnvironmentEvidenceStatus.PASS, evidence.gpsEvidence?.status)
            assertEquals(gpsObservedAt, evidence.gpsEvidence?.observedAtElapsedRealtimeMs)
            assertTrue(now - gpsObservedAt in 0L..1_000L)
            assertEquals(EnvironmentEvidenceStatus.PASS, evidence.cameraEvidence?.status)
            assertEquals(cameraObservedAt, evidence.cameraEvidence?.observedAtElapsedRealtimeMs)
            assertNotNull((base.field("gatewayWalkAuthorityController") as GatewayWalkAuthorityController)
                .activeLeaseOrNull(base.epoch))
        }

        fun advance(ms: Long) { call(delegate, "advance", ms) }
        fun sample(heading: Double) { call(delegate, "sample", Float.NaN, heading, 40L) }
        fun publish() { call(delegate, "publish") }
        fun native(name: String, action: Any?) { call(delegate, "native", name, requireNotNull(action)) }
        fun message(action: Any?) = read<String>(requireNotNull(action), "message")
        fun id(action: Any?) = read<String>(requireNotNull(action), "id")
        fun publishStationaryGps() {
            val measuredAt = now - 100L
            assertTrue(measuredAt > gpsObservedAt)
            call(fixture, "publishWalkingGps", 0.0, 5.0)
            gpsObservedAt = measuredAt
            freshGpsCount += 1
        }

        fun diagnostic(): String {
            val observation = current.observation
            val facing = RouteFacingGuidance.evaluate(0.0, observation, now)
            return "now=$now sample=${observation?.observedAtElapsedRealtimeMs}" +
                " raw=${observation?.degreesTrueNorth} trend=${observation?.headingTrend}" +
                " facing=$facing pending=$pendingCue completed=$completedCue" +
                " failed=${read<RouteSpeechCueToken?>(nav, "failedSpeechCue")}" +
                " grace=${nav.currentDirectionGraceSnapshot()} stops=$stops"
        }

        fun warmOneClock() {
            repeat(31) {
                advance(20L)
                sample(345.1)
            }
            publish()
            assertEquals(1, spoken.size)
        }

        fun boundaryTick(sampledClocks: MutableSet<Int>) {
            repeat(15) {
                advance(20L)
                boundarySample += 1
                sample(if (boundarySample % 2 == 1) 344.9 else 345.1)
                val observation = requireNotNull(current.observation)
                assertEquals(RouteCompassHeadingReason.VALID, current.reason)
                assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
                assertEquals(40L, now - observation.observedAtElapsedRealtimeMs)
                assertNull(observation.headingAccuracyDegrees)
                assertNull(observation.reportedHeadingAccuracyDegrees)
            }
            val facing = RouteFacingGuidance.evaluate(0.0, current.observation, now)
            assertEquals(RouteFacingDirection.FRONT, facing.direction)
            assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, facing.clockReferenceKind)
            sampledClocks += requireNotNull(facing.clockHour)
            publish()
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> read(o: Any, name: String): T =
            o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T
        private fun put(o: Any, name: String, value: Any?) {
            o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, value)
        }
        private fun call(o: Any, name: String, vararg args: Any): Any? {
            val method = o.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            try { return method.apply { isAccessible = true }.invoke(o, *args) }
            catch (e: InvocationTargetException) { throw e.targetException }
        }
    }
}
