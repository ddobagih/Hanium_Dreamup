package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustConfig
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.GpsQualityObservation
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSafeEnvironmentProfilesTest {
    @Test
    fun testCandidateIsExplicitlySeparatedFromProductionApproval() {
        val candidate = WalkSafeEnvironmentProfiles.testCandidate

        assertEquals(WalkSafeEnvironmentProfileStatus.TEST_CANDIDATE, candidate.status)
        assertFalse(candidate.approvedForProduction)
        assertNull(WalkSafeEnvironmentProfiles.productionApproved)
        assertNull(WalkSafeEnvironmentProfiles.active(allowTestCandidate = false))
        assertSame(candidate, WalkSafeEnvironmentProfiles.active(allowTestCandidate = true))
        assertTrue(candidate.bundleId.contains("test-candidate"))
        assertTrue(candidate.cameraFrameQuality.profileId.contains("test-candidate"))
        assertTrue(candidate.officialEnvironment.profileId.contains("test-candidate"))
        assertTrue(candidate.phoneMounting.profileId.contains("test-candidate"))
        assertTrue(candidate.runtimeMetric.profileId.contains("test-candidate"))
    }

    @Test
    fun profileBundleUsesMeasurementsInsteadOfDeviceIdentity() {
        val profileFields = WalkSafeEnvironmentProfileBundle::class.java.declaredFields
            .map { it.name.lowercase() }

        assertTrue(profileFields.none { it.contains("manufacturer") })
        assertTrue(profileFields.none { it == "model" || it.contains("device") })

        val candidate = WalkSafeEnvironmentProfiles.testCandidate
        assertEquals(
            candidate.cameraFrameQuality.profileId,
            candidate.officialEnvironment.cameraQualityProfileId,
        )
        assertEquals(
            candidate.cameraFrameQuality.profileId,
            candidate.phoneMounting.cameraFrameQualityProfileId,
        )
    }

    @Test
    fun candidateAndApprovedStatusUseTheSamePolicyInputsAndJudgement() {
        val candidate = WalkSafeEnvironmentProfiles.testCandidate
        val approved = candidate.copy(status = WalkSafeEnvironmentProfileStatus.APPROVED)

        assertSame(candidate.cameraFrameQuality, approved.cameraFrameQuality)
        assertSame(candidate.officialEnvironment, approved.officialEnvironment)
        assertSame(candidate.phoneMounting, approved.phoneMounting)
        assertSame(candidate.runtimeMetric, approved.runtimeMetric)

        val epoch = WalkRuntimeEpoch("walk-1", 1L)
        val cameraObservation = CameraFrameQualityObservation(
            epoch = epoch,
            observedAtElapsedRealtimeMs = NOW_MS,
            frameAvailable = true,
            normalizedBrightness = candidate.cameraFrameQuality.minimumNormalizedBrightness,
            occludedFraction = candidate.cameraFrameQuality.maximumOccludedFraction,
            angularShakeDegreesPerSecond =
                candidate.cameraFrameQuality.maximumAngularShakeDegreesPerSecond,
            mountPitchDegrees = candidate.cameraFrameQuality.minimumMountPitchDegrees,
        )
        val candidateCamera = CameraFrameQualityPolicy.assess(
            currentEpoch = epoch,
            nowElapsedRealtimeMs = NOW_MS,
            observation = cameraObservation,
            approvedProfile = candidate.cameraFrameQuality,
        )
        val approvedCamera = CameraFrameQualityPolicy.assess(
            currentEpoch = epoch,
            nowElapsedRealtimeMs = NOW_MS,
            observation = cameraObservation,
            approvedProfile = approved.cameraFrameQuality,
        )
        assertEquals(candidateCamera, approvedCamera)
        assertEquals(EnvironmentEvidenceStatus.PASS, candidateCamera.status)

        val gpsObservation = GpsQualityObservation(
            epoch = epoch,
            observedAtElapsedRealtimeMs = NOW_MS,
            trustedFixAvailable = true,
            horizontalAccuracyMeters =
                candidate.officialEnvironment.maximumGpsHorizontalAccuracyMeters,
        )
        val candidateGps = OfficialEnvironmentPolicy.assessGpsQuality(
            currentEpoch = epoch,
            nowElapsedRealtimeMs = NOW_MS,
            observation = gpsObservation,
            approvedProfile = candidate.officialEnvironment,
        )
        val approvedGps = OfficialEnvironmentPolicy.assessGpsQuality(
            currentEpoch = epoch,
            nowElapsedRealtimeMs = NOW_MS,
            observation = gpsObservation,
            approvedProfile = approved.officialEnvironment,
        )
        assertEquals(candidateGps, approvedGps)
        assertEquals(EnvironmentEvidenceStatus.PASS, candidateGps.status)

        val confirmation = PhoneMountingUserConfirmation(
            epoch = epoch,
            confirmedAtElapsedRealtimeMs = NOW_MS - 5_000L,
            method = PhoneMountingMethod.CHEST_FORWARD,
            postFaultCorrectionConfirmed = false,
        )
        val candidateMounting = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.PREFLIGHT,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = NOW_MS,
            userConfirmation = confirmation,
            cameraFrameQuality = candidateCamera,
            previousState = PhoneMountingPolicy.initialState(epoch, candidate.phoneMounting),
        )
        val approvedMounting = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.PREFLIGHT,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = NOW_MS,
            userConfirmation = confirmation,
            cameraFrameQuality = approvedCamera,
            previousState = PhoneMountingPolicy.initialState(epoch, approved.phoneMounting),
        )
        assertEquals(candidateMounting, approvedMounting)
        assertEquals(PhoneMountingStatus.SUITABLE, candidateMounting.status)
    }

    @Test
    fun candidateThresholdsMatchExistingLocationAndInteractionContracts() {
        val candidate = WalkSafeEnvironmentProfiles.testCandidate
        val location = LocationTrustConfig()

        assertEquals(
            location.maxAccuracyM.toDouble(),
            candidate.officialEnvironment.maximumGpsHorizontalAccuracyMeters,
            0.0,
        )
        assertEquals(
            location.maxAgeMs,
            candidate.officialEnvironment.maximumMeasuredEvidenceAgeMs,
        )
        assertEquals(-45.0, candidate.cameraFrameQuality.minimumMountPitchDegrees, 0.0)
        assertEquals(45.0, candidate.cameraFrameQuality.maximumMountPitchDegrees, 0.0)
        assertEquals(
            candidate.cameraFrameQuality.maximumEvidenceAgeMs,
            candidate.phoneMounting.maximumEvidenceAgeMs,
        )
        assertTrue(
            candidate.phoneMounting.maximumUserConfirmationAgeMs >
                candidate.phoneMounting.maximumEvidenceAgeMs,
        )
    }

    @Test
    fun runtimeMetricCandidatePreservesExistingMeasurementRequirements() {
        val profile = WalkSafeEnvironmentProfiles.testCandidate.runtimeMetric

        assertEquals(RuntimeMetricPreflightPolicy.MAX_DURATION_MS, profile.maximumDurationMs)
        assertEquals(
            RuntimeMetricPreflightPolicy.MIN_DISTINCT_FRAMES,
            profile.minimumDistinctFrames,
        )
        assertEquals(
            RuntimeMetricPreflightPolicy.MIN_OBSERVATION_SPAN_MS,
            profile.minimumObservationSpanMs,
        )
        assertEquals(
            RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME,
            profile.minimumValidSamplesPerPassingFrame,
        )
        assertEquals(
            RuntimeMetricPreflightPolicy.MIN_PASSING_PERCENT,
            profile.minimumPassingPercent,
        )
        assertEquals(
            RuntimeMetricPreflightPolicy.MIN_VALID_DISTANCE_METERS,
            profile.minimumValidDistanceMeters,
            0.0,
        )
        assertEquals(
            RuntimeMetricPreflightPolicy.MAX_VALID_DISTANCE_METERS,
            profile.maximumValidDistanceMeters,
            0.0,
        )
    }

    @Test
    fun runtimeMetricSessionConsumesTheInjectedCandidateCriteria() {
        val candidate = WalkSafeEnvironmentProfiles.testCandidate.runtimeMetric
        val candidateSession = RuntimeMetricPreflightSession(
            generation = 1L,
            startedAtElapsedRealtimeMs = 0L,
            depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
            profile = candidate,
        )
        val stricterSession = RuntimeMetricPreflightSession(
            generation = 1L,
            startedAtElapsedRealtimeMs = 0L,
            depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
            profile = candidate.copy(
                profileId = "runtime-metric-stricter-test",
                minimumDistinctFrames = candidate.minimumDistinctFrames + 1,
            ),
        )

        repeat(candidate.minimumDistinctFrames) { index ->
            val elapsedMs = index * 112L
            val frame = RuntimeMetricFrameEvidence(
                generation = 1L,
                frameTimestampNanos = 1_000_000_000L + elapsedMs * 1_000_000L,
                observedAtElapsedRealtimeMs = elapsedMs,
                tracking = true,
                metricDepthAvailable = true,
                validMetricSamplesInRange = candidate.minimumValidSamplesPerPassingFrame,
            )
            candidateSession.observe(frame)
            stricterSession.observe(frame)
        }

        assertEquals(RuntimeMetricPreflightStatus.AVAILABLE, candidateSession.result().status)
        assertEquals(RuntimeMetricPreflightStatus.IN_PROGRESS, stricterSession.result().status)
    }

    @Test
    fun inconsistentCrossProfileBindingIsRejected() {
        val candidate = WalkSafeEnvironmentProfiles.testCandidate

        assertThrows(IllegalArgumentException::class.java) {
            candidate.copy(
                officialEnvironment = candidate.officialEnvironment.copy(
                    cameraQualityProfileId = "other-camera-profile",
                ),
            )
        }
    }

    private companion object {
        const val NOW_MS = 10_000L
    }
}
