package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.device.ApprovedPhoneMountingProfile
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessmentPhase
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingMethod
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingPolicy
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingStatus
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingUserConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSessionReadinessProducerEvidenceTest {
    private val epoch = WalkRuntimeEpoch("producer-walk", 2L)
    private val environmentProfile = ApprovedOfficialEnvironmentProfile(
        "environment-r001", "camera-r001", 10.0, 10_000L, 2,
    )
    private val mountingProfile = ApprovedPhoneMountingProfile(
        "mounting-r001", "camera-r001", 5_000L, 2, 30_000L,
    )

    @Test
    fun freshEnvironmentAssessmentDoesNotExtendOriginalCameraExpiry() {
        val assessment = environmentAssessment(now = 4_999L)
        assertTrue(assessment.canStartWalk)
        val evidence = assessment.readinessEvidence.values.toList()
        val camera = evidence.single { it.source.endsWith("camera_quality") }
        assertEquals(0L, camera.observedAtElapsedRealtimeMs)
        assertEquals(5_000L, camera.maxAgeMs)
        assertEquals("camera-r001", camera.sourceVersion)

        assertTrue(snapshot(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT, 5_000L, evidence).isReady)
        assertFalse(snapshot(WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT, 5_001L, evidence).isReady)
    }

    @Test
    fun mountingRetainsCameraAndUserConfirmationLifetimesSeparately() {
        val assessment = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.PREFLIGHT,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = 4_999L,
            userConfirmation = confirmation(0L),
            cameraFrameQuality = camera(100L),
            previousState = PhoneMountingPolicy.initialState(epoch, mountingProfile),
        )
        assertTrue(assessment.canStartOrResumeDetection)
        assertEquals(4_999L, assessment.assessedAtElapsedRealtimeMs)
        assertEquals(
            setOf(5_000L, 30_000L),
            assessment.readinessEvidence.map { it.maxAgeMs }.toSet(),
        )
        assertEquals(
            setOf("camera-r001", "mounting-r001"),
            assessment.readinessEvidence.map { it.sourceVersion }.toSet(),
        )

        assertTrue(snapshot(WalkSessionReadinessRequirement.PHONE_MOUNTING, 5_100L, assessment.readinessEvidence).isReady)
        assertFalse(snapshot(WalkSessionReadinessRequirement.PHONE_MOUNTING, 5_101L, assessment.readinessEvidence).isReady)
    }

    @Test
    fun newerCameraCannotExtendAnExpiringUserConfirmation() {
        val profile = mountingProfile.copy(maximumUserConfirmationAgeMs = 5_000L)
        val assessment = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.PREFLIGHT,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = 4_999L,
            userConfirmation = confirmation(0L),
            cameraFrameQuality = camera(4_900L),
            previousState = PhoneMountingPolicy.initialState(epoch, profile),
        )
        assertTrue(assessment.canStartOrResumeDetection)
        assertTrue(snapshot(WalkSessionReadinessRequirement.PHONE_MOUNTING, 5_000L, assessment.readinessEvidence).isReady)
        assertFalse(snapshot(WalkSessionReadinessRequirement.PHONE_MOUNTING, 5_001L, assessment.readinessEvidence).isReady)
    }

    @Test
    fun oldDecisionCannotBeRepackagedWithTheCurrentCollectionTime() {
        val evidence = WalkSessionReadinessEvidence(
            "startup_capability:cached_probe_evaluation", 10_000L, 30_000L, "device-profile-r001",
        )
        val readiness = snapshot(WalkSessionReadinessRequirement.CAMERA, 50_000L, listOf(evidence))

        assertEquals(50_000L, readiness.observations.getValue(WalkSessionReadinessRequirement.CAMERA).observedAtElapsedRealtimeMs)
        assertFalse(readiness.isReady)
        assertEquals(listOf("stale_observation:camera"), readiness.blockingReasons)
    }

    @Test
    fun currentEpochAndFreshTimeDoNotAuthorizeAnOldSourceVersion() {
        val evidence = WalkSessionReadinessEvidence(
            "camera", 1_000L, 5_000L, "camera-r001", requiredVersion = "camera-r002",
        )
        assertFalse(snapshot(WalkSessionReadinessRequirement.CAMERA, 1_001L, listOf(evidence)).isReady)
        assertTrue(
            snapshot(WalkSessionReadinessRequirement.CAMERA, 1_001L, listOf(evidence.copy(sourceVersion = "camera-r002"))).isReady,
        )
    }

    @Test
    fun activeSuppressionAndMountingCorrectionRemainPolicyDecisions() {
        var now = 4_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { epoch.walkSessionId }, clock = { now })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val currentEpoch = lifecycle.snapshot().epoch
        val start = readySnapshot(currentEpoch, now)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(start))
        lifecycle.handle(WalkSessionEvent.StartRequested(lifecycle.snapshot().confirmationToken!!))

        now = 11_000L
        val guard = OfficialEnvironmentRuntimeGuard(currentEpoch, environmentProfile)
        val assessment = environmentAssessment(now).copy(epoch = currentEpoch)
        assertEquals(
            OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,
            guard.onAssessment(assessment, now).action,
        )
        val mounting = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.ACTIVE,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = now,
            userConfirmation = confirmation(0L),
            cameraFrameQuality = camera(100L),
            previousState = PhoneMountingPolicy.initialState(epoch, mountingProfile),
        )
        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, mounting.status)
        assertTrue(mounting.mustSuppressDetectionOutput)

        // Main's ACTIVE readiness represents these policy permissions. The raw evidence remains
        // owned by the separate output/retry gates, rather than authorizing a preflight start.
        lifecycle.handle(WalkSessionEvent.RuntimeReadinessChanged(readySnapshot(currentEpoch, now)))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }

    private fun environmentAssessment(now: Long) = OfficialEnvironmentPolicy.assess(
        currentEpoch = epoch,
        nowElapsedRealtimeMs = now,
        gpsQuality = MeasuredEnvironmentEvidence(
            OfficialEnvironmentFactor.GPS_QUALITY, epoch, 0L, EnvironmentEvidenceStatus.PASS,
            environmentProfile.profileId, 10_000L, "gps",
        ),
        cameraQuality = camera(0L).asMeasuredEnvironmentEvidence(),
        userConfirmation = OfficialEnvironmentUserConfirmation(
            epoch,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            EnvironmentEvidenceStatus.PASS,
        ),
        approvedProfile = environmentProfile,
    )

    private fun camera(at: Long) = CameraFrameQualityAssessment(
        epoch, at, "camera-r001", 5_000L, EnvironmentEvidenceStatus.PASS, CameraFrameQualityReason.PASSED,
    )

    private fun confirmation(at: Long) = PhoneMountingUserConfirmation(
        epoch, at, PhoneMountingMethod.CHEST_FORWARD, postFaultCorrectionConfirmed = false,
    )

    private fun snapshot(
        requirement: WalkSessionReadinessRequirement,
        now: Long,
        evidence: List<WalkSessionReadinessEvidence>,
    ): WalkSessionReadinessSnapshot = readySnapshot(epoch, now, requirement, evidence)

    private fun readySnapshot(
        epoch: WalkRuntimeEpoch,
        now: Long,
        measuredRequirement: WalkSessionReadinessRequirement? = null,
        evidence: List<WalkSessionReadinessEvidence> = emptyList(),
    ): WalkSessionReadinessSnapshot {
        val collector = WalkSessionReadinessCollector(
            epoch, WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL),
        )
        collector.plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch, requirement, WalkSessionReadinessStatus.READY,
                    observedAtElapsedRealtimeMs = now,
                    maxAgeMs = WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS,
                    evidenceVersion = "policy-r001",
                    supportingEvidence = evidence.takeIf { requirement == measuredRequirement }.orEmpty(),
                ),
            )
        }
        return collector.build(now)
    }
}
