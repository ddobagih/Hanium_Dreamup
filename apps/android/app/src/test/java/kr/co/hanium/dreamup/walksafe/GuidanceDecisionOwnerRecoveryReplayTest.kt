package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual positioning, environment, guidance and command-output owners; native callbacks are controlled leaves. */
class GuidanceDecisionOwnerRecoveryReplayTest {
    @After fun resetCapacity() = GatewayCapacityProcessState.resetForTests()

    @Test fun gpsRestrictionRetiresTheOutputOwnerAndTheSameRerouteQuestionCompletesUnderANewCommandOwner() {
        GuidanceFullJourneyReplayHarness(caseId = "reroute_question_owner_gps_recovery").use { f ->
            val journey = GuidanceFullJourneyReplayTest()
            journey.admitAndInstall(f)
            val cue = journey.firstCue(f)
            f.runtime.start(cue.id); f.advance(0); f.assertNativeRouteStartAccepted(cue)
            f.advance(100); f.runtime.done(cue.id); f.advance(0)
            f.assertNativeRouteCompletionAcknowledged(cue)

            for (north in listOf(10.0, 20.0, 30.0)) walkSample(f, 0.0, north, 0.0)
            for (east in listOf(-10.0, -20.0, -30.0, -40.0, -50.0)) walkSample(f, east, 30.0, 270.0)
            repeat(2) {
                if (f.graph.run.navigator.pendingUserDecision() != RouteNavigatorUserDecision.REROUTE) {
                    walkSample(f, -50.0, 30.0, 270.0, walking = false)
                }
            }
            assertEquals(RouteNavigatorUserDecision.REROUTE, f.graph.run.navigator.pendingUserDecision())
            val token = requireNotNull(f.graph.run.navigator.pendingDecisionToken())
            val oldQuestion = awaitQuestion(f, afterRequestCount = 0)
            val oldFeedbackGeneration = f.currentFeedbackGeneration
            val oldCommandGeneration = f.output.currentCommandGeneration()
            val oldOutputOwner = f.output.captureCommandSpeechResponseOwner()
            assertTrue(oldOutputOwner.isCurrent(f.output))
            assertEquals(token, f.graph.guidance.value.decisionSpeechState.pendingToken)
            f.runtime.start(oldQuestion.id); f.advance(0)
            assertTrue(f.output.currentActuatorOrNull()?.isAppSpeechActive() == true)
            assertTrue(f.output.hasPendingCommandResponse())
            assertNull("Native START must not acknowledge the unanswered route question",
                f.graph.guidance.value.decisionSpeechState.announcedToken)

            // Preserve fresh camera/heading and normal notice callbacks throughout GPS remeasurement.
            val firstBadAt = f.now
            f.gps(-50.0, 30.0, accuracy = 50f); f.observeCameraFrame()
            val restrictedAt = firstBadAt + GuidanceRecoveryTiming.GPS_GRACE_MS + 300L
            while (f.now < restrictedAt) {
                f.advance(minOf(300L, restrictedAt - f.now))
                f.observeCameraFrame(); f.heading(270.0)
                f.stationaryStepCounterObservation()
                f.gps(-50.0, 30.0, accuracy = 50f)
                completeOtherSpeech(f)
            }
            assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED, f.recordSensorEvidence().gpsStage)
            assertTrue("The real environment effect must advance the output lifetime", f.currentFeedbackGeneration > oldFeedbackGeneration)
            assertTrue(f.eventCount("ENVIRONMENT_GPS_RESTRICTED") > 0)
            assertFalse(oldOutputOwner.isCurrent(f.output))
            assertTrue("Closing the old actuator must cancel its incomplete native question", oldQuestion.cancelled)
            assertFalse("The old native question deliberately receives no DONE before recovery", oldQuestion.done)
            assertEquals(token, f.graph.run.navigator.pendingDecisionToken())
            assertEquals(WalkSessionState.ACTIVE, f.walk.state)
            assertTrue(f.graph.run.navigator.hasRoute())
            assertNotNull("Controlled notice START/DONE must keep the native output engine ready",
                f.output.currentActuatorOrNull()?.readySpeechEngineGenerationOrNull())

            val beforeRecovery = f.runtime.spoken.size
            f.advance(1)
            freshOffRouteInputs(f)
            val recovered = awaitQuestion(f, afterRequestCount = beforeRecovery)
            assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.FRESH, f.recordSensorEvidence().gpsStage)
            assertTrue(f.eventCount("ENVIRONMENT_GPS_RESUMED") > 0)
            assertEquals("Recovery must retain the same unanswered route choice", token, f.graph.run.navigator.pendingDecisionToken())
            assertEquals(oldQuestion.message, recovered.message)
            assertNotEquals(oldQuestion.id, recovered.id)
            val recoveredCommandGeneration = f.output.currentCommandGeneration()
            val recoveredOutputOwner = f.output.captureCommandSpeechResponseOwner()
            assertTrue(recoveredCommandGeneration > oldCommandGeneration)
            assertTrue(recoveredOutputOwner.isCurrent(f.output))
            assertTrue(f.output.hasPendingCommandResponse())
            assertEquals(token, f.graph.guidance.value.decisionSpeechState.pendingToken)
            assertNull(f.graph.guidance.value.decisionSpeechState.announcedToken)

            f.runtime.start(recovered.id); f.advance(0)
            assertTrue("The new command must reach the actual actuator's accepted START", f.output.currentActuatorOrNull()?.isAppSpeechActive() == true)
            assertFalse(recovered.cancelled)
            // The native runtime retains each utterance's own listener, even after actuator replacement.
            f.runtime.done(oldQuestion.id); f.runtime.error(oldQuestion.id); f.runtime.stopCallback(oldQuestion.id)
            f.advance(0)
            assertEquals(recoveredCommandGeneration, f.output.currentCommandGeneration())
            assertTrue(recoveredOutputOwner.isCurrent(f.output))
            assertTrue("Retired native terminals cannot complete the current command", f.output.hasPendingCommandResponse())
            assertEquals(token, f.graph.guidance.value.decisionSpeechState.pendingToken)
            assertNull(f.graph.guidance.value.decisionSpeechState.announcedToken)

            f.advance(100); f.runtime.done(recovered.id); f.advance(0)
            assertTrue(recovered.started && recovered.done && !recovered.cancelled)
            assertFalse(f.output.hasPendingCommandResponse())
            assertNull(f.graph.guidance.value.decisionSpeechState.pendingToken)
            assertEquals("Only current native DONE acknowledges the route question", token,
                f.graph.guidance.value.decisionSpeechState.announcedToken)
            val completedQuestionCount = f.runtime.spoken.count { isRerouteQuestion(it) }
            repeat(3) { f.advance(300); freshOffRouteInputs(f) }
            assertEquals("Fresh inputs must not replay a completed question for the same decision",
                completedQuestionCount, f.runtime.spoken.count { isRerouteQuestion(it) })
            assertEquals(token, f.graph.run.navigator.pendingDecisionToken())
            f.recordSpeechReadinessEvidence(); f.assertSameHomeOwner()
        }
    }

    private fun walkSample(f: GuidanceFullJourneyReplayHarness, east: Double, north: Double, facing: Double,
        walking: Boolean = true) {
        if (walking) f.beginWalkingStepInputs()
        f.advance(3_000); f.observeCameraFrame(); f.heading(facing)
        if (walking) f.walkingSteps(14) else f.stationaryStepCounterObservation()
        f.gps(east, north)
        completeOtherSpeech(f)
        f.advance(300); f.runGatewayRequests(); f.runIo(); f.drainUi()
        f.recordRouteProgress(); f.assertSameHomeOwner()
    }

    private fun freshOffRouteInputs(f: GuidanceFullJourneyReplayHarness) {
        f.observeCameraFrame(); f.heading(270.0); f.stationaryStepCounterObservation(); f.gps(-50.0, 30.0)
        completeOtherSpeech(f)
        f.advance(0)
    }

    private fun awaitQuestion(f: GuidanceFullJourneyReplayHarness, afterRequestCount: Int): GuidanceFullJourneyReplayHarness.NativeSpoken {
        fun pendingQuestion() = f.runtime.spoken.drop(afterRequestCount).firstOrNull {
            isRerouteQuestion(it) && !it.done && !it.cancelled
        }
        val deadline = f.now + 5_000L
        while (pendingQuestion() == null && f.now < deadline) {
            f.advance(300); freshOffRouteInputs(f)
        }
        f.recordSpeechReadinessEvidence()
        assertNotNull("Fresh inputs and the actual retry scheduler must submit the same native route question", pendingQuestion())
        return requireNotNull(pendingQuestion())
    }

    /** Keep only the actual unanswered decision unfinished; preceding notices receive explicit native terminals. */
    private fun completeOtherSpeech(f: GuidanceFullJourneyReplayHarness) {
        f.runtime.spoken.filter { !it.done && !it.cancelled && !isRerouteQuestion(it) }.toList().forEach {
            f.runtime.start(it.id); f.advance(0); f.runtime.done(it.id); f.advance(0)
        }
    }

    private fun isRerouteQuestion(spoken: GuidanceFullJourneyReplayHarness.NativeSpoken) =
        spoken.message.contains("새 경로 요청, 위치 다시 확인, 길안내 종료")

}
