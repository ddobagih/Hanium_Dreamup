package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentAssessmentFactsReader
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentLifecycleFacts
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentPolicyFacts
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeController
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeEffects
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentRuntimeFactsReader
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeAction
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentUserConfirmation
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Actual GPS admission/gates/watchdog/runtime and guidance queue/Navigator. The Main effect adapter
 * preserves requestResume -> pause -> feedback generation -> delayed close terminal ordering.
 * Native TTS and Main's Android callbacks are controlled ports, not device playback evidence.
 */
class RouteEnvironmentWarningCancellationRegressionTest {
    @Test
    fun lateActionStopOrErrorWakesTheCurrentGpsWarningWithoutAnotherLocation() {
        val f = Fixture().apply { startAndExpireGpsWhileActionIsReserved() }

        f.action.failed() // STOP and ERROR share the ACTION failure terminal at this boundary.
        f.drainGuidanceCallbacks()

        f.assertCurrentWarningDeliveredOnce()
    }

    @Test
    fun staleControllerCompletionWakesTheCurrentGpsWarningWithoutAcknowledgingOldGuidance() {
        val f = Fixture().apply { startAndExpireGpsWhileActionIsReserved() }

        // Invoke the controller completion port after ownership changes. This is defensive
        // stale-completion coverage, not SpeechOutputController's retired native DONE -> onFailed path.
        f.action.done()
        f.drainGuidanceCallbacks()

        assertTrue("The old owner's DONE must not acknowledge the route instruction", f.g.lastResults.isEmpty())
        f.assertCurrentWarningDeliveredOnce()
    }

    @Test
    fun gpsOnlySuppressionDeliversItsQueuedWarningAfterEitherLateActionTerminal() {
        for (done in listOf(false, true)) {
            val f = Fixture(cameraFeatureEnabled = false)
                .apply { startAndExpireGpsWhileActionIsReserved() }

            // Main's all-output suppression closes the actuator without calling the
            // GPS-specific pause/requestResume effect. Its terminal must still free ACTION.
            if (done) f.action.done() else f.action.failed()
            f.drainGuidanceCallbacks()

            assertTrue("The retired ACTION cannot acknowledge guidance", f.g.lastResults.isEmpty())
            f.assertCurrentWarningDeliveredOnce()
        }
    }

    @Test
    fun cameraOnlyRecoveryReplacesTheQueuedCombinedWarningWithTheRemainingGpsCause() {
        for (done in listOf(false, true)) {
            val f = Fixture().apply { startAndExpireBothWhileActionIsReserved() }
            val combined = f.noticeRequests.single()
            val owner = f.runtime.guard
            val feedbackGeneration = f.g.feedbackGeneration
            assertTrue(combined.message.contains("위치 품질") && combined.message.contains("카메라 품질"))
            assertTrue(combined.current())

            f.recoverCameraOnlyOnRegisteredWatchdog()

            assertSame("Partial recovery must retain the admitted runtime owner", owner, f.runtime.guard)
            assertEquals(feedbackGeneration, f.g.feedbackGeneration)
            assertFalse("The recovered camera must disappear from the queued warning", combined.current())
            assertTrue("The original ACTION still owns its asynchronous terminal", f.g.nav.hasPendingInstruction())
            assertTrue(f.g.statusSent.isEmpty())
            if (done) f.action.done() else f.action.failed()
            f.drainGuidanceCallbacks()

            assertEquals(1, f.g.statusSent.size)
            val warning = f.g.statusSent.single()
            assertTrue("Camera recovery must not overwrite the still-undelivered GPS warning: ${warning.message}",
                warning.message.contains("위치 품질"))
            assertFalse(warning.message.contains("카메라 품질"))
            assertFalse(warning.message.contains("다시 확인되었습니다"))
            f.assertCurrentWarningDeliveredOnce()
        }
    }

    @Test
    fun aPartiallyRecoveredWarningStillRetiresWhenItsFeedbackOwnerChanges() {
        val f = Fixture().apply { startAndExpireBothWhileActionIsReserved() }
        f.recoverCameraOnlyOnRegisteredWatchdog()
        f.g.feedbackGeneration++

        f.action.failed()
        f.drainGuidanceCallbacks()

        assertFalse(f.g.nav.hasPendingInstruction())
        assertTrue("The remaining cause cannot escape its retired feedback owner", f.g.statusSent.isEmpty())
        assertEquals(0, f.warningDeliveries)
    }

    @Test
    fun neitherLateTerminalCanReviveAWarningWhoseFeedbackOwnerWasReplacedAgain() {
        for (done in listOf(false, true)) {
            val f = Fixture().apply { startAndExpireGpsWhileActionIsReserved() }
            f.g.feedbackGeneration++

            if (done) f.action.done() else f.action.failed()
            f.drainGuidanceCallbacks()

            assertFalse(f.g.nav.hasPendingInstruction())
            assertTrue("Retired warning owner must remain silent (DONE=$done)", f.g.statusSent.isEmpty())
            assertEquals(0, f.warningDeliveries)
        }
    }

    @Test
    fun neitherLateTerminalCanSubmitThePendingWarningAfterTheWalkEnds() {
        for (done in listOf(false, true)) {
            val f = Fixture().apply { startAndExpireGpsWhileActionIsReserved() }
            f.g.lifecycle.handle(WalkSessionEvent.EndRequested)
            f.g.run.markInactive()
            f.runtime.reset()

            if (done) f.action.done() else f.action.failed()
            f.drainGuidanceCallbacks()

            assertFalse(f.g.nav.hasPendingInstruction())
            assertTrue("Ended walk must remain silent (DONE=$done)", f.g.statusSent.isEmpty())
            assertEquals(0, f.warningDeliveries)
        }
    }

    private class Fixture(private val cameraFeatureEnabled: Boolean = true) :
        OfficialEnvironmentRuntimeFactsReader, OfficialEnvironmentRuntimeEffects,
        OfficialEnvironmentAssessmentFactsReader {
        val g = GuidanceFixture().apply { captureStatus = true; install() }
        private val epoch = g.epoch
        private val profiles = WalkSafeEnvironmentProfiles.testCandidate
        private val profile = profiles.officialEnvironment
        private val confirmation = OfficialEnvironmentUserConfirmation(epoch,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS)
        private val evidence = OfficialEnvironmentEvidenceStore(Any(),
            RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.GPS_RECHECK_PERIOD_MS,
                GuidanceRecoveryTiming.GPS_RECHECK_PERIODS),
            RuntimeMeasuredQualityGate(GuidanceRecoveryTiming.CAMERA_GRACE_MS))
        private data class Scheduled(val atMs: Long, val action: () -> Unit)
        data class NoticeRequest(val message: String, val current: () -> Boolean)
        private val runtimeCallbacks = mutableListOf<Scheduled>()
        val noticeRequests = mutableListOf<NoticeRequest>()
        private var cameraFramesAvailable = true
        private var warningRequests = 0
        private var closeRequests = 0
        var warningDeliveries = 0
            private set
        lateinit var action: GuidanceFixture.Submission
            private set
        val runtime = OfficialEnvironmentRuntimeController(evidence, this, this, { g.now },
            { delay, callback -> runtimeCallbacks += Scheduled(g.now + delay, callback) }, { false })

        init {
            g.navigationAllowedReader = { runtime.guard?.decision()?.navigationOutputsAllowed == true }
        }

        fun startAndExpireGpsWhileActionIsReserved() {
            startWhileActionIsReserved()

            // Only the already registered runtime callbacks run; no fresh GPS sample is supplied.
            advanceRuntimeTo(g.fix.elapsedRealtimeMs + profile.maximumMeasuredEvidenceAgeMs + 1L)

            val decision = requireNotNull(runtime.guard).decision()
            assertFalse(decision.navigationOutputsAllowed)
            assertEquals(cameraFeatureEnabled, decision.cameraOutputsAllowed)
            assertEquals(cameraFeatureEnabled, runtime.outputsAllowed)
            assertFalse(g.navigationAllowedReader())
            if (cameraFeatureEnabled) assertEquals("environment_gps_quality", g.run.directionPauseReason)
            else assertNull("All-output cancellation does not invent a GPS-only pause", g.run.directionPauseReason)
            assertWarningWaitsForOriginalAction()
        }

        fun startAndExpireBothWhileActionIsReserved() {
            startWhileActionIsReserved()
            // Give the last real camera frame the same expiry as the untouched GPS fix.
            // Both streams then stop, so the registered watchdog enters full suppression once.
            advanceRuntimeTo(g.fix.elapsedRealtimeMs + profile.maximumMeasuredEvidenceAgeMs -
                profiles.cameraFrameQuality.maximumEvidenceAgeMs)
            cameraFramesAvailable = false
            advanceRuntimeTo(g.fix.elapsedRealtimeMs + profile.maximumMeasuredEvidenceAgeMs + 1L)

            val decision = requireNotNull(runtime.guard).decision()
            assertEquals(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY, decision.action)
            assertEquals(setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY),
                decision.unavailableFactors)
            assertFalse(runtime.outputsAllowed)
            assertFalse(decision.navigationOutputsAllowed)
            assertFalse(decision.cameraOutputsAllowed)
            assertWarningWaitsForOriginalAction()
        }

        fun recoverCameraOnlyOnRegisteredWatchdog() {
            cameraFramesAvailable = true
            advanceRuntimeTo(g.now + profile.runtimeRetryIntervalMs)
            val decision = requireNotNull(runtime.guard).decision()
            assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
            assertEquals(setOf(OfficialEnvironmentFactor.GPS_QUALITY), decision.unavailableFactors)
            assertTrue(runtime.outputsAllowed)
            assertTrue(decision.cameraOutputsAllowed)
            assertFalse(decision.navigationOutputsAllowed)
            assertFalse(decision.isTerminal)
        }

        private fun startWhileActionIsReserved() {
            freshCameraFrame()
            evidence.recordGpsObservation(epoch, epoch, g.now, true, 7.0,
                { g.now }, { profile }, ::isRuntimeEpochCurrent, { epoch }, { false },
                { isRuntimeEpochCurrent(epoch) }, false, { admittedEpoch, previous, current ->
                    runtime.reactToAdmittedGpsObservation(admittedEpoch, previous, current)
                })
            assertTrue(runtime.activate())
            assertTrue(requireNotNull(runtime.guard).decision().navigationOutputsAllowed)
            g.controller.updateRouteGuidance(g.fix)
            action = g.sent.single()
            g.validatingStarted = true
            assertTrue("The original ACTION must be valid at playback START", action.current())
            g.validatingStarted = false
            assertTrue(g.nav.hasPendingInstruction())
        }

        private fun assertWarningWaitsForOriginalAction() {
            assertEquals(2, g.feedbackGeneration)
            assertEquals(1, closeRequests)
            assertEquals(1, warningRequests)
            assertFalse(action.current())
            assertTrue("The asynchronous close has not released the actual ACTION yet", g.nav.hasPendingInstruction())
            assertTrue("The new warning must wait behind that reservation", g.statusSent.isEmpty())
            assertEquals(0, warningDeliveries)
            assertTrue("Waiting on ACTION should be resumed by its terminal, not artificial polling", g.pending.isEmpty())
        }

        fun drainGuidanceCallbacks() {
            val throughMs = g.now + 500L
            var count = 0
            while (true) {
                val next = g.pending.filter { it.first <= throughMs }.minByOrNull { it.first } ?: break
                check(++count <= 20) { "Unexpected guidance callback loop" }
                g.pending.remove(next)
                g.now = maxOf(g.now, next.first)
                next.second.run()
            }
        }

        fun assertCurrentWarningDeliveredOnce() {
            assertFalse(g.nav.hasPendingInstruction())
            assertEquals("Late ACTION terminal must resume the current GPS warning", 1, g.statusSent.size)
            val warning = g.statusSent.single()
            assertTrue(warning.message.contains("위치 품질"))
            assertTrue(warning.current())
            assertCurrentNavigationRestricted()
            assertEquals("Acceptance is not delivered warning evidence", 0, warningDeliveries)
            assertEquals(g.fix.elapsedRealtimeMs,
                requireNotNull(evidence.gpsQualityGate.latestRawObservation()).observedAtElapsedRealtimeMs)
            assertNull(g.nav.currentAcceptedRouteMatchFor(g.now))

            action.done()
            action.failed()
            assertTrue("Duplicate old terminals cannot consume the new notice", warning.current())
            warning.done()
            warning.done()
            drainGuidanceCallbacks()
            assertEquals(1, warningDeliveries)
            assertEquals(1, g.statusSent.size)
            assertCurrentNavigationRestricted()
            assertEquals("No new GPS means no new navigation action", 1, g.sent.size)
        }

        private fun assertCurrentNavigationRestricted() {
            assertFalse(requireNotNull(runtime.guard).decision().navigationOutputsAllowed)
            assertFalse("Even the current feedback generation must not resume direction output",
                g.controller.isRouteSpeechCurrent(g.epoch, g.requestGeneration, g.feedbackGeneration))
        }

        private fun advanceRuntimeTo(targetMs: Long) {
            var count = 0
            while (true) {
                val next = runtimeCallbacks.filter { it.atMs <= targetMs }.minByOrNull { it.atMs } ?: break
                check(++count <= 20) { "Unexpected runtime watchdog loop" }
                runtimeCallbacks.remove(next)
                g.now = next.atMs
                freshCameraFrame()
                next.action()
            }
            g.now = targetMs
            freshCameraFrame()
        }

        private fun freshCameraFrame() {
            if (!cameraFeatureEnabled || !cameraFramesAvailable) return
            val frame = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY,
                epoch, g.now, EnvironmentEvidenceStatus.PASS, profile.cameraQualityProfileId,
                profiles.cameraFrameQuality.maximumEvidenceAgeMs, "PASSED")
            evidence.cameraQualityGate.observe(frame, g.now)
            evidence.commitCameraEvidence(frame)
        }

        override fun lifecycle() = g.lifecycle.snapshot().let { OfficialEnvironmentLifecycleFacts(it.epoch, it.state) }
        override fun currentRuntimeEpoch() = g.lifecycle.currentRuntimeEpochOrNull()
        override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = g.lifecycle.isRuntimeEpochCurrent(epoch)
        override fun profile() = profile
        override fun continuousSourcePresent() = true
        override fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long) = evidence.gpsQualityGate.current(nowMs)
        override fun latestMountingCameraAssessment(): CameraFrameQualityAssessment? = null
        override fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long): OfficialEnvironmentAssessment {
            val measured = evidence.currentAssessment(epoch, nowMs, this, ::continuousGpsEvidence, {})
            return OfficialEnvironmentPolicy.assess(epoch, nowMs, evidence.gpsEvidence,
                evidence.cameraEvidence, confirmation, profile, usageLimitsAcknowledged = true)
                .copy(enabledMeasuredFactors = measured.enabledMeasuredFactors,
                    runtimeUnavailableMeasuredFactors = measured.runtimeUnavailableMeasuredFactors,
                    recoverableRuntimeUnavailableMeasuredFactors = measured.recoverableRuntimeUnavailableMeasuredFactors)
        }
        override fun policyFacts() = OfficialEnvironmentPolicyFacts(profile, usageLimitsAcknowledged = true)
        override fun separateGpsActive() = false
        override fun locationEnabled() = true
        override fun cameraEnabled() = cameraFeatureEnabled
        override fun cameraHandoffEpoch() = epoch
        override fun mountingCameraRestricted(epoch: WalkRuntimeEpoch) = false
        override fun reassessMountingWithoutExplicitRetry() = Unit
        override fun invalidateReportOutput(reason: String) = Unit
        override fun restrictGpsNavigationAndCancelFeedback() {
            // Same relevant order as Main. The fake actuator retains the native terminal so
            // the runtime can queue its new-generation explanation before that terminal arrives.
            g.controller.restrictGpsNavigation {
                g.feedbackGeneration++
                closeRequests++
            }
        }
        override fun resumeGpsNavigation() = fail("This scenario supplies no recovery location")
        override fun cancelPendingFeedbackDeliveries() = Unit
        override fun cancelVoiceAndFeedbackActuator() {
            assertFalse("All-output cancellation requires the camera to be unavailable too",
                requireNotNull(runtime.guard).decision().cameraOutputsAllowed)
            g.feedbackGeneration++
            closeRequests++
        }
        override fun showStatus(title: String, detail: String) = Unit
        override fun speakStatusExplanation(message: String) = fail("Active route must use its shared warning queue")
        override fun speakRecheckExplanation(message: String, current: () -> Boolean, delivered: () -> Unit) {
            warningRequests++
            noticeRequests += NoticeRequest(message, current)
            g.controller.offerEnvironmentRecheckNotice(message, current) {
                warningDeliveries++
                delivered()
            }
        }
        override fun failureSpeech(assessment: OfficialEnvironmentAssessment,
            factors: Set<OfficialEnvironmentFactor>?): String {
            val unavailable = factors ?: assessment.blockingFactors
            return listOfNotNull(
                "위치 품질이 확인되지 않아 방향 안내를 잠시 보류합니다."
                    .takeIf { OfficialEnvironmentFactor.GPS_QUALITY in unavailable },
                "카메라 품질이 확인되지 않아 영상 안내를 잠시 보류합니다."
                    .takeIf { OfficialEnvironmentFactor.CAMERA_QUALITY in unavailable },
            ).joinToString(" ")
        }
        override fun locationRecoveryActionMessage() = "새 위치를 기다려 주세요."
        override fun enterSafetyStop(reason: String) = fail("GPS expiry must remain recoverable: $reason")
    }
}
