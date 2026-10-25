package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class OfficialEnvironmentCameraObserverTest {
    @Test fun threePreflightPassesCommitUsableEnvironmentEvidenceWithoutPhysicalOcclusionEstimate() {
        val f = Fixture(requiredCameraPasses = 3)
        f.profile = ApprovedCameraFrameQualityProfile("camera", 0.2, 0.1, 45.0, -45.0, 45.0, 30_000L)
        assertTrue(f.preflight.controller.startCamera(f.epoch))
        val generation = f.preflight.controller.cameraGeneration
        val profile = ApprovedOfficialEnvironmentProfile("gps", "camera", 12.0, 30_000L, 2)
        val gps = OfficialEnvironmentPolicy.assessGpsQuality(f.epoch, 3_000L,
            GpsQualityObservation(f.epoch, 2_000L, true, 4.0), profile)
        for (index in 1..3) {
            val sample = f.sample(1_000L + index * 500L).copy(
                normalizedBrightness = 0.412, occludedFraction = null,
                nearBlackFraction = 0.03, mountPitchDegrees = 4.8,
            )
            assertSame(sample, f.observer.observe(sample, generation))
            val expected = if (index < 3) EnvironmentEvidenceStatus.UNKNOWN else EnvironmentEvidenceStatus.PASS
            assertEquals(expected, f.evidence.cameraEvidence?.status)
            val assessment = OfficialEnvironmentPolicy.assess(f.epoch, 3_000L, gps,
                f.evidence.cameraEvidence, null, profile, usageLimitsAcknowledged = true)
            assertEquals(expected, assessment.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
            assertEquals(index == 3, assessment.canStartWalk)
        }
        assertEquals(300L, f.preflight.controller.cameraAnalysisIntervalMs())
        assertEquals(EnvironmentEvidenceStatus.PASS, f.evidence.cameraEvidence?.status)
        assertNull(f.evidence.cameraDiagnosticObservation?.occludedFraction)
    }

    @Test fun acceptedQualityAndMountingCommitShareOneLockAndPrecedeInlineUi() {
        val f = Fixture()
        val observation = f.sample()
        assertSame(observation, f.observer.observe(observation))
        assertEquals(listOf("commit", "mount_ui", "readiness_ui"), f.events)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.evidence.cameraEvidence?.status)
        assertEquals("PASSED", f.evidence.cameraDiagnosticReason)
    }

    @Test fun staleEpochCannotCommitMountingOrOverwriteDiagnostics() {
        val f = Fixture()
        assertNull(f.observer.observe(f.sample().copy(epoch = f.epoch.copy(recoveryGeneration = 0L))))
        assertTrue(f.events.isEmpty())
        assertNull(f.evidence.cameraEvidence)
    }

    @Test fun earlierFrameCannotOverwriteNewerEvidenceOrCauseAnotherMountingFault() {
        val f = Fixture()
        f.observer.observe(f.sample(2_000L))
        f.events.clear()
        assertNull(f.observer.observe(f.sample(1_000L).copy(normalizedBrightness = 0.01)))
        assertEquals(2_000L, f.evidence.cameraEvidence?.observedAtElapsedRealtimeMs)
        assertEquals("PASSED", f.evidence.cameraDiagnosticReason)
        assertTrue(f.events.isEmpty())
    }

    @Test fun newerMountingGenerationInvalidatesAQueuedUiContinuation() {
        val f = Fixture()
        f.inline = false
        f.observer.observe(f.sample())
        f.generation++
        f.queued!!.invoke()
        assertEquals(listOf("commit"), f.events)
    }

    @Test fun diagnosticReasonIsCommittedBeforeAnInlineReadinessRefresh() {
        val f = Fixture()
        f.expectedReason = "CAMERA_BIND_FAILED"
        f.observer.observe(f.sample().copy(frameAvailable = false), diagnosticReasonOverride = f.expectedReason)
        assertEquals("CAMERA_BIND_FAILED", f.evidence.cameraDiagnosticReason)
        assertEquals(listOf("commit", "mount_ui", "readiness_ui"), f.events)
    }

    @Test fun sameObserverSkipsCommitOnlyWhileCurrentBypassIsEnabled() {
        val f = Fixture()
        assertEquals(0, f.bypassReads)
        f.observer.observe(f.sample())
        f.events.clear()
        f.bypass = true
        val skipped = f.sample(2_000L).copy(frameAvailable = false)
        assertSame(skipped, f.observer.observe(skipped))
        assertTrue(f.events.isEmpty())
        assertEquals(1_000L, f.evidence.cameraEvidence?.observedAtElapsedRealtimeMs)
        assertEquals("PASSED", f.evidence.cameraDiagnosticReason)
        f.bypass = false
        f.observer.observe(f.sample(3_000L))
        assertEquals(listOf("commit", "mount_ui", "readiness_ui"), f.events)
        assertEquals(3_000L, f.evidence.cameraEvidence?.observedAtElapsedRealtimeMs)
    }

    private class Fixture(requiredCameraPasses: Int = 2) : OfficialEnvironmentCameraObservationFacts, OfficialEnvironmentMountingObservationPort,
        OfficialEnvironmentCameraObservationEffects {
        val preflight = OfficialEnvironmentPreflightControllerTest.Fixture(requiredCameraPasses = requiredCameraPasses)
        val runtime = OfficialEnvironmentRuntimeControllerTest.Fixture()
        val evidence = preflight.evidence
        val epoch = preflight.epoch
        val events = mutableListOf<String>()
        var generation = 1L
        var inline = true
        var queued: (() -> Unit)? = null
        var expectedReason: String? = null
        var bypass = false
        var bypassReads = 0
        var profile = ApprovedCameraFrameQualityProfile("camera", 0.2, 0.8, 60.0, 60.0, 120.0, 30_000L)
        private var latest: CameraFrameQualityAssessment? = null
        val observer = OfficialEnvironmentCameraObserver(evidence, preflight.controller, runtime.controller, this, this, this,
            { 3_000L }, { action -> if (inline) action() else queued = action }, { bypassReads++; bypass })
        fun sample(at: Long = 1_000L) = CameraFrameQualityObservation(epoch, at, true, 0.5, 0.0, 0.0, 90.0)
        override fun currentEpoch() = epoch
        override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = false
        override fun walkRuntimeActive() = false
        override fun resumeActivationPending(epoch: WalkRuntimeEpoch) = false
        override fun cameraProfile() = profile
        override fun mountingCameraProfileId() = "camera"
        override fun observationGeneration() = generation
        override fun cameraHandoffStartedAt(epoch: WalkRuntimeEpoch): Long? = null
        override fun completeCameraHandoffIfCurrent(epoch: WalkRuntimeEpoch) { fail() }
        override fun latestCameraAssessment() = latest
        override fun commitCameraAssessment(epoch: WalkRuntimeEpoch, assessment: CameraFrameQualityAssessment, stability: CameraPreflightStabilityDecision, mountingFault: Boolean): Long {
            assertTrue(Thread.holdsLock(evidence.observationLock))
            events += "commit"
            latest = assessment
            return 1L
        }
        override fun runtimeRetryArmedAt(): Long? = null
        override fun applyRuntimeCameraAssessment(retryRequested: Boolean, assessment: CameraFrameQualityAssessment, sequence: Long, generation: Long, isFault: Boolean): Boolean { fail(); return false }
        override fun finishRuntimeRetryIfCurrent(armedAtMs: Long?) { fail() }
        override fun recordAppliedFaultIfCurrent(generation: Long, sequence: Long) { fail() }
        override fun postResumeActivation(epoch: WalkRuntimeEpoch) { fail() }
        override fun preflightDiagnostic(observation: CameraFrameQualityObservation, assessment: CameraFrameQualityAssessment, generation: Long) {}
        override fun updateMountingUi() { events += "mount_ui" }
        override fun refreshReadinessUi() { events += "readiness_ui"; expectedReason?.let { assertEquals(it, evidence.cameraDiagnosticReason) } }
    }
}
