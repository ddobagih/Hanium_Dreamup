package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** A short measured fluctuation stays silent; an actual feature restriction is explained. */
class RuntimeEnvironmentWarningContinuityTest {
    @Test fun gpsGraceDoesNotWarnOrCancelAndTheConfirmedRestrictionIsExplained() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.seedGpsGrace()
        f.controller.applyDecision(f.decision())
        assertTrue(f.events.isEmpty())
        assertNull(f.recheckWarningCurrent)
        assertTrue(f.controller.outputsAllowed)
        f.now = 4_600L
        f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.GPS_QUALITY)))
        assertTrue(f.events.contains("gps_restrict"))
        assertTrue(f.events.contains("speech"))
        assertTrue(requireNotNull(f.recheckWarningCurrent)())
        requireNotNull(f.recheckWarningDone).invoke()
        f.events.clear()
        repeat(30) { f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.GPS_QUALITY))) }
        assertTrue("the corrective explanation must not be restarted each frame", f.events.isEmpty())
    }

    @Test fun cameraBrightnessFluctuationDoesNotInterruptNormalRouteSpeech() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        seedCameraGrace(f)
        f.controller.applyDecision(f.decision())
        repeat(30) { f.controller.applyDecision(f.decision()) }
        assertTrue(f.events.isEmpty())
        assertNull(f.recheckWarningCurrent)
        assertTrue(f.controller.outputsAllowed)
        f.now = 3_100L
        f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)))
        assertEquals(listOf("report:measured_environment_feature_limited", "status", "speech"), f.events)
    }

    @Test fun repeatedShortAccuracyFluctuationsRecoverWithoutWarningChatter() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.seedGpsGrace()
        val bad = requireNotNull(f.evidence.gpsQualityGate.latestRawObservation())
        repeat(20) { cycle ->
            f.now = 2_000L + cycle * 1_000L
            f.evidence.gpsQualityGate.observe(bad.copy(observedAtElapsedRealtimeMs = f.now,
                status = EnvironmentEvidenceStatus.PASS, detail = "GPS_QUALITY_PASSED"), f.now)
            f.controller.applyDecision(f.decision())
            f.now += 400L
            f.evidence.gpsQualityGate.observe(bad.copy(observedAtElapsedRealtimeMs = f.now), f.now)
            f.controller.applyDecision(f.decision())
        }
        assertTrue("usable fixes between short dips do not justify a failure or recovery announcement", f.events.isEmpty())
    }

    @Test fun simultaneousShortGpsAndCameraFluctuationsStaySilent() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.seedGpsGrace()
        seedCameraGrace(f)
        f.controller.applyDecision(f.decision())
        assertTrue(f.events.isEmpty())
        assertTrue(f.controller.outputsAllowed)
        assertEquals(4_600L, f.evidence.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(3_100L, f.evidence.cameraQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun persistentSuppressionDoesNotCancelTheCorrectiveExplanationEachFrame() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        val d = f.decision(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,
            setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY))
        f.controller.applyDecision(d)
        f.events.clear()
        repeat(60) { f.controller.applyDecision(d) }
        assertTrue("restriction is a transition, not a repeated actuator cleanup", f.events.isEmpty())
        assertFalse(f.controller.outputsAllowed)
    }

    @Test fun recoveryFromAllOutputSuppressionRestoresNavigationAndExplainsItOnce() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.controller.applyDecision(f.decision(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,
            setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY)))
        f.events.clear()
        f.controller.applyDecision(f.decision())
        assertTrue(f.controller.outputsAllowed)
        assertEquals(listOf("gps_resume", "status", "speech"), f.events)
        f.events.clear()
        f.controller.applyDecision(f.decision())
        assertTrue(f.events.isEmpty())
    }

    @Test fun partialRecoveryExplainsTheRemainingRestrictionWithoutAnAllClear() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.controller.applyDecision(f.decision(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,
            setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY)))
        f.events.clear()
        f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)))
        assertTrue(f.events.contains("speech"))
        assertTrue(requireNotNull(f.recheckWarningCurrent)())
        assertFalse(f.lastSpeech.contains("다시 확인되었습니다"))
    }

    @Test fun aRestrictedGpsRecoveryDoesNotAnnounceAllClearDuringACameraRecheck() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.GPS_QUALITY)))
        seedCameraGrace(f)
        f.events.clear()
        f.controller.applyDecision(f.decision())
        assertEquals(listOf("gps_resume"), f.events)
    }

    @Test fun queuedRestrictionWithoutNativeDoneRemainsEligibleForAnExplanation() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.GPS_QUALITY)))
        val queued = requireNotNull(f.recheckWarningCurrent)
        assertTrue(queued())
        // The speech owner was busy: no native DONE was received for this request.
        f.current = false
        assertFalse("a queued warning must retire when its runtime epoch retires", queued())
    }

    @Test fun retiredEpochCannotWarnOrResumeTheCurrentWalk() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        f.seedGpsGrace()
        f.current = false
        f.controller.applyDecision(f.decision())
        assertTrue(f.events.isEmpty())
        assertFalse(f.controller.outputsAllowed)
    }

    @Test fun aQualifiedAngleBoundaryRecheckIsSilentWhileTheCurrentCameraFrameStaysRestricted() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        val raw = seedCameraBoundary(f)
        f.controller.applyDecision(f.decision(unavailable = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)))
        assertTrue(f.controller.outputsAllowed)
        assertFalse(f.events.toString(), f.events.contains("speech"))
        assertFalse(f.events.toString(), f.events.contains("voice_cancel"))
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.evidence.cameraQualityGate.current(f.now)?.status)
        assertEquals(raw, f.evidence.cameraQualityGate.latestRawObservation())
        f.events.clear()
        f.now = 2_000L
        f.evidence.cameraQualityGate.observe(raw.copy(observedAtElapsedRealtimeMs = f.now,
            status = EnvironmentEvidenceStatus.PASS, detail = "PASSED"), f.now)
        f.controller.applyDecision(f.decision())
        assertTrue("an unannounced short recheck does not need an all-clear announcement", f.events.isEmpty())
    }

    @Test fun aPersistingAngleBoundaryRecheckExplainsTheHardRestrictionAtTheOriginalDeadline() {
        val f = OfficialEnvironmentRuntimeControllerTest.Fixture()
        seedCameraBoundary(f)
        val restricted = f.decision(unavailable = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY))
        f.controller.applyDecision(restricted)
        f.events.clear()
        f.now = 3_100L
        f.controller.applyDecision(restricted)
        assertTrue("continuing restriction needs a correction even though the feature was already withheld", f.events.contains("speech"))
        assertEquals("MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE", f.evidence.cameraQualityGate.current(f.now)?.detail)
        requireNotNull(f.recheckWarningDone).invoke()
        f.events.clear()
        f.controller.applyDecision(restricted)
        assertTrue(f.events.isEmpty())
    }

    private fun seedCameraBoundary(f: OfficialEnvironmentRuntimeControllerTest.Fixture): MeasuredEnvironmentEvidence {
        val pass = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY, f.epoch,
            1_000L, EnvironmentEvidenceStatus.PASS, "gps", 30_000L, "PASSED")
        f.evidence.cameraQualityGate.observe(pass, 1_000L)
        val raw = pass.copy(observedAtElapsedRealtimeMs = 1_600L, status = EnvironmentEvidenceStatus.FAIL,
            detail = "MOUNT_ANGLE_NEAR_BOUNDARY")
        f.evidence.cameraQualityGate.observe(raw, 1_600L)
        return raw
    }

    private fun seedCameraGrace(f: OfficialEnvironmentRuntimeControllerTest.Fixture) {
        val pass = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY, f.epoch,
            1_000L, EnvironmentEvidenceStatus.PASS, "gps", 30_000L, "CAMERA_QUALITY_PASSED")
        f.evidence.cameraQualityGate.observe(pass, 1_000L)
        f.evidence.cameraQualityGate.observe(pass.copy(observedAtElapsedRealtimeMs = 1_600L,
            status = EnvironmentEvidenceStatus.FAIL, detail = "BRIGHTNESS_OUTSIDE_APPROVED_RANGE"), 1_600L)
    }
}
