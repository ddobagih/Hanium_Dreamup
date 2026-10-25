package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class OfficialEnvironmentRuntimeControllerTest {
    @Test fun constructorDoesNotReadSourcesScheduleOrCancelOutput() {
        val f = Fixture()
        assertEquals(0, f.reads)
        assertTrue(f.events.isEmpty())
        assertTrue(f.scheduled.isEmpty())
        assertEquals(0, f.bypassReads)
    }

    @Test fun watchdogUsesTheOriginalGpsFailureDeadlineWithoutRestartingIt() {
        val f = Fixture()
        f.seedGpsGrace()
        f.controller.applyDecision(f.decision())
        assertEquals(2_900L, f.scheduled.single().first)
        assertEquals(4_600L, f.evidence.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
        f.now = 2_000L
        f.controller.scheduleWatchdog(f.epoch)
        assertEquals(2_600L, f.scheduled.last().first)
        assertEquals(4_600L, f.evidence.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun canceledWatchdogCannotCallMountingOrOutputAfterInvalidation() {
        val f = Fixture()
        f.controller.activate()
        val callback = f.scheduled.last().second
        f.controller.reset()
        f.events.clear()
        callback()
        assertTrue(f.events.isEmpty())
        assertFalse(f.controller.outputsAllowed)
    }

    @Test fun firstGpsRestrictionCancelsOutputOnceAndRecoveryResumesNavigation() {
        val f = Fixture()
        val restricted = f.decision(unavailable = setOf(OfficialEnvironmentFactor.GPS_QUALITY))
        f.controller.applyDecision(restricted)
        assertEquals(listOf("report:measured_environment_feature_limited", "gps_restrict", "status", "speech"), f.events)
        f.events.clear()
        f.controller.applyDecision(restricted)
        assertTrue(f.events.isEmpty())
        f.controller.applyDecision(f.decision())
        assertEquals(listOf("gps_resume", "status", "speech"), f.events)
        assertEquals("위치가 다시 확인되었습니다.", f.lastSpeech)
        assertEquals("상태 재확인" to f.lastSpeech, f.lastStatus)
    }

    @Test fun restorationNamesOnlyTheMeasuredFactorsThatRecovered() {
        val camera = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)
        val both = camera + OfficialEnvironmentFactor.GPS_QUALITY
        for ((restored, expected) in listOf(
            camera to "카메라 상태가 다시 확인되었습니다.",
            both to "위치와 카메라 상태가 다시 확인되었습니다.",
            (camera + OfficialEnvironmentFactor.DRY_WEATHER) to "카메라 상태가 다시 확인되었습니다.",
        )) {
            val f = Fixture()
            f.controller.applyDecision(f.decision(unavailable = restored))
            f.events.clear()
            f.controller.applyDecision(f.decision())

            assertEquals("Recovered factors: $restored", expected, f.lastSpeech)
            assertEquals("상태 재확인" to expected, f.lastStatus)
            assertFalse(f.lastSpeech.contains("안내"))
        }
    }

    @Test fun userConfirmationRecoveryDoesNotClaimADeviceMeasurementOrEveryConditionRecovered() {
        val f = Fixture()
        f.controller.applyDecision(f.decision(unavailable = setOf(
            OfficialEnvironmentFactor.BRIGHT_TIME, OfficialEnvironmentFactor.DRY_WEATHER)))
        f.events.clear()
        // The weather confirmation remains unavailable; only one user-confirmed condition changed.
        f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.DRY_WEATHER)))

        assertEquals("사용 환경 확인 내용이 갱신되었습니다.", f.lastSpeech)
        assertEquals("상태 재확인" to f.lastSpeech, f.lastStatus)
        assertEquals(listOf("status", "speech"), f.events)
    }

    @Test fun measuredRestorationKeepsTheSameThirtySecondNoticeInterval() {
        val f = Fixture()
        val cameraRestricted = f.decision(unavailable = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY))
        f.controller.applyDecision(cameraRestricted)
        f.controller.applyDecision(f.decision())
        assertEquals("카메라 상태가 다시 확인되었습니다.", f.lastSpeech)
        val firstRecoveryAt = f.now

        f.now = firstRecoveryAt + 29_999L
        f.controller.applyDecision(cameraRestricted)
        f.events.clear()
        f.controller.applyDecision(f.decision())
        assertEquals(listOf("status"), f.events)

        f.now = firstRecoveryAt + 30_000L
        f.controller.applyDecision(cameraRestricted)
        f.events.clear()
        f.controller.applyDecision(f.decision())
        assertEquals(listOf("status", "speech"), f.events)
        assertEquals("카메라 상태가 다시 확인되었습니다.", f.lastSpeech)
    }

    @Test fun transientAllOutputRestrictionDoesNotRepeatLongSpeechOnEveryAssessment() {
        val f = Fixture()
        val decision = f.decision(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY)
        f.controller.applyDecision(decision)
        f.events.clear()
        f.controller.applyDecision(decision)
        assertTrue(f.events.isEmpty())
        assertFalse(f.controller.outputsAllowed)
    }

    @Test fun terminalCorrectiveSpeechIsCapturedBeforeSafetyCleanupInvalidatesEvidence() {
        val f = Fixture()
        f.controller.applyDecision(f.decision(OfficialEnvironmentRuntimeAction.SAFE_STOP))
        assertEquals(listOf("safe_stop", "status", "speech"), f.events)
        assertTrue(f.lastSpeech.startsWith("measured correction"))
        assertFalse(f.controller.outputsAllowed)
    }

    @Test fun staleRuntimeEpochCannotCancelCurrentOutput() {
        val f = Fixture()
        f.current = false
        f.controller.applyDecision(f.decision(OfficialEnvironmentRuntimeAction.SAFE_STOP))
        assertTrue(f.events.isEmpty())
        assertTrue(f.scheduled.isEmpty())
    }

    @Test fun sameRuntimeReadsCurrentBypassWithoutChangingOutputOrStopFieldsWhileBypassed() {
        val f = Fixture()
        f.controller.applyDecision(f.decision())
        assertTrue(f.controller.outputsAllowed)
        f.events.clear()
        f.bypass = true
        f.controller.applyDecision(f.decision(OfficialEnvironmentRuntimeAction.SAFE_STOP))
        assertTrue(f.events.isEmpty())
        assertTrue(f.controller.outputsAllowed)
        f.bypass = false
        f.controller.applyDecision(f.decision(OfficialEnvironmentRuntimeAction.SAFE_STOP))
        assertEquals(listOf("safe_stop", "status", "speech"), f.events)
        assertFalse(f.controller.outputsAllowed)
    }

    @Test fun bypassActivationAndReadinessDoNotReadLifecycleAndResumeNormalLogicWhenDisabled() {
        val f = Fixture()
        f.bypass = true
        assertTrue(f.controller.activate())
        assertTrue(f.controller.outputsAllowed)
        assertEquals(0, f.reads)
        assertNull(f.controller.guard)
        assertEquals(WalkSessionReadinessStatus.READY to "", f.controller.runtimeReadiness(f.epoch))
        assertEquals(0, f.reads)
        f.bypass = false
        assertNull(f.controller.runtimeReadiness(f.epoch))
        assertTrue(f.reads > 0)
        assertTrue(f.controller.activate())
        assertNotNull(f.controller.guard)
    }

    internal class Fixture : OfficialEnvironmentRuntimeFactsReader, OfficialEnvironmentRuntimeEffects {
        val epoch = WalkRuntimeEpoch("walk", 1L)
        private val profile = ApprovedOfficialEnvironmentProfile("gps", "camera", 12.0, 30_000L, 2)
        val evidence = OfficialEnvironmentEvidenceStore(Any(), RuntimeMeasuredQualityGate(3_000L), RuntimeMeasuredQualityGate())
        var now = 1_700L
        var reads = 0
        var current = true
        var bypass = false
        var bypassReads = 0
        val events = mutableListOf<String>()
        var lastSpeech = ""
        var lastStatus: Pair<String, String>? = null
        var recheckWarningDone: (() -> Unit)? = null
        var recheckWarningCurrent: (() -> Boolean)? = null
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = OfficialEnvironmentRuntimeController(evidence, this, this, { now }, { delay, action -> scheduled += delay to action }, { bypassReads++; bypass })
        fun decision(action: OfficialEnvironmentRuntimeAction = OfficialEnvironmentRuntimeAction.CONTINUE,
            unavailable: Set<OfficialEnvironmentFactor> = emptySet()) = OfficialEnvironmentRuntimeDecision(epoch,
            profile.profileId, action, 0, 2, unavailable, setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY))
        fun seedGpsGrace() {
            val pass = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.GPS_QUALITY, epoch, 1_000L, EnvironmentEvidenceStatus.PASS,
                profile.profileId, 30_000L, "GPS_QUALITY_PASSED")
            evidence.gpsQualityGate.observe(pass, 1_000L)
            evidence.gpsQualityGate.observe(pass.copy(observedAtElapsedRealtimeMs = 1_600L, status = EnvironmentEvidenceStatus.FAIL,
                detail = "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE"), 1_600L)
            evidence.acceptGpsPreflightSample(pass, 4.0, now)
        }
        override fun lifecycle(): OfficialEnvironmentLifecycleFacts { reads++; return OfficialEnvironmentLifecycleFacts(epoch, WalkSessionState.ACTIVE) }
        override fun currentRuntimeEpoch() = epoch
        override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = current && epoch == this.epoch
        override fun profile() = profile
        override fun continuousSourcePresent() = true
        override fun continuousGpsEvidence(epoch: WalkRuntimeEpoch, nowMs: Long) = evidence.gpsQualityGate.current(nowMs)
        override fun latestMountingCameraAssessment(): CameraFrameQualityAssessment? = null
        override fun currentAssessment(epoch: WalkRuntimeEpoch, nowMs: Long) = OfficialEnvironmentAssessment(epoch,
            nowMs, profile.profileId, OfficialEnvironmentFactor.entries.associateWith { EnvironmentEvidenceStatus.PASS })
        override fun locationEnabled() = true
        override fun cameraEnabled() = true
        override fun cameraHandoffEpoch(): WalkRuntimeEpoch? = null
        override fun reassessMountingWithoutExplicitRetry() { events += "mount" }
        override fun invalidateReportOutput(reason: String) { events += "report:$reason" }
        override fun restrictGpsNavigationAndCancelFeedback() { events += "gps_restrict" }
        override fun resumeGpsNavigation() { events += "gps_resume" }
        override fun cancelPendingFeedbackDeliveries() { events += "feedback_cancel" }
        override fun cancelVoiceAndFeedbackActuator() { events += "voice_cancel" }
        override fun showStatus(title: String, detail: String) { events += "status"; lastStatus = title to detail }
        override fun speakStatusExplanation(message: String) { events += "speech"; lastSpeech = message }
        override fun speakRecheckExplanation(message: String, current: () -> Boolean, delivered: () -> Unit) {
            if (!current()) return
            speakStatusExplanation(message)
            recheckWarningCurrent = current
            recheckWarningDone = { if (current()) delivered() }
        }
        override fun failureSpeech(assessment: OfficialEnvironmentAssessment, factors: Set<OfficialEnvironmentFactor>?) = "measured correction"
        override fun locationRecoveryActionMessage() = "retry"
        override fun enterSafetyStop(reason: String) { events += "safe_stop"; evidence.invalidate() }
    }
}
