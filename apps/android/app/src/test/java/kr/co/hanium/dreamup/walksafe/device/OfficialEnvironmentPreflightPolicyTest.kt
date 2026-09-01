package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialEnvironmentPreflightPolicyTest {
    @Test
    fun poorAccuracyKeepsItsReasonAndLaterAccurateSamplePasses() {
        val poor = assess(accuracyMeters = 15.718)
        val accurate = assess(accuracyMeters = 12.1, observedAtMs = NOW_MS + 1_000L)

        assertEquals(EnvironmentEvidenceStatus.FAIL, poor.status)
        assertEquals("GPS_ACCURACY_OUTSIDE_APPROVED_RANGE", poor.detail)
        assertEquals(
            EnvironmentEvidenceStatus.PASS,
            OfficialEnvironmentGpsPreflightPolicy.selectEvidence(
                previous = poor,
                candidate = accurate,
                nowElapsedRealtimeMs = NOW_MS + 1_000L,
            ).status,
        )
    }

    @Test
    fun recentPassingSampleSurvivesTransientPoorFixButNotItsFreshnessLimit() {
        val passing = assess(accuracyMeters = 10.0)
        val poor = assess(accuracyMeters = 30.0, observedAtMs = NOW_MS + 1_000L)

        assertEquals(
            passing,
            OfficialEnvironmentGpsPreflightPolicy.selectEvidence(
                previous = passing,
                candidate = poor,
                nowElapsedRealtimeMs = NOW_MS + 1_000L,
            ),
        )
        assertEquals(
            poor,
            OfficialEnvironmentGpsPreflightPolicy.selectEvidence(
                previous = passing,
                candidate = poor,
                nowElapsedRealtimeMs = NOW_MS + MAX_AGE_MS + 1L,
            ),
        )
    }

    @Test
    fun mockAndInvalidCoordinatesAreUntrustedWithoutHidingValidAccuracyFailures() {
        assertEquals("TRUSTED_FIX_UNAVAILABLE", assess(mock = true).detail)
        assertEquals("TRUSTED_FIX_UNAVAILABLE", assess(latitude = Double.NaN).detail)
        assertEquals(
            "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE",
            assess(accuracyMeters = 15.001).detail,
        )
    }

    @Test
    fun newerMockFixImmediatelyRevokesRecentPass() {
        val passing = assess(accuracyMeters = 10.0)
        val mock = assess(mock = true, observedAtMs = NOW_MS + 1_000L)

        assertEquals(
            mock,
            OfficialEnvironmentGpsPreflightPolicy.selectEvidence(
                previous = passing,
                candidate = mock,
                nowElapsedRealtimeMs = NOW_MS + 1_000L,
            ),
        )
    }

    @Test
    fun invalidFuturePreviousEvidenceCannotBlockLaterValidFix() {
        val poisoned = assess().copy(
            observedAtElapsedRealtimeMs = NOW_MS + MAX_AGE_MS,
            status = EnvironmentEvidenceStatus.UNKNOWN,
            detail = "STALE_OR_INVALID_TIMESTAMP",
        )
        val valid = assess(observedAtMs = NOW_MS + 1_000L)

        assertEquals(
            valid,
            OfficialEnvironmentGpsPreflightPolicy.selectEvidence(
                previous = poisoned,
                candidate = valid,
                nowElapsedRealtimeMs = NOW_MS + 1_000L,
            ),
        )
    }

    @Test
    fun cameraGateStabilizesBothEntryAndTransientFailureEdge() {
        val gate = CameraPreflightStabilityGate(
            requiredPasses = 3,
            requiredFailuresAfterPass = 2,
        )

        assertEquals(
            CameraPreflightStabilityDecision.STABILIZING,
            gate.observe(EnvironmentEvidenceStatus.PASS, transientQualityFailure = false),
        )
        assertEquals(
            CameraPreflightStabilityDecision.STABILIZING,
            gate.observe(EnvironmentEvidenceStatus.PASS, transientQualityFailure = false),
        )
        assertEquals(
            CameraPreflightStabilityDecision.ACCEPT,
            gate.observe(EnvironmentEvidenceStatus.PASS, transientQualityFailure = false),
        )
        assertEquals(
            CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS,
            gate.observe(EnvironmentEvidenceStatus.FAIL, transientQualityFailure = true),
        )
        assertEquals(
            CameraPreflightStabilityDecision.ACCEPT,
            gate.observe(EnvironmentEvidenceStatus.PASS, transientQualityFailure = false),
        )
        assertEquals(
            CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS,
            gate.observe(EnvironmentEvidenceStatus.FAIL, transientQualityFailure = true),
        )
        assertEquals(
            CameraPreflightStabilityDecision.ACCEPT,
            gate.observe(EnvironmentEvidenceStatus.FAIL, transientQualityFailure = true),
        )
        assertEquals(
            CameraPreflightStabilityDecision.STABILIZING,
            gate.observe(EnvironmentEvidenceStatus.PASS, transientQualityFailure = false),
        )
        assertEquals(1, gate.consecutivePasses)
    }

    private fun assess(
        accuracyMeters: Double = 10.0,
        observedAtMs: Long = NOW_MS,
        latitude: Double = 37.0,
        longitude: Double = 127.0,
        mock: Boolean = false,
    ) = OfficialEnvironmentGpsPreflightPolicy.assess(
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = observedAtMs,
        sample = OfficialEnvironmentGpsSample(
            observedAtElapsedRealtimeMs = observedAtMs,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyMeters = accuracyMeters,
            mock = mock,
        ),
        approvedProfile = PROFILE,
    )

    private companion object {
        const val NOW_MS = 100_000L
        const val MAX_AGE_MS = 10_000L
        val EPOCH = WalkRuntimeEpoch("walk-preflight", 1L)
        val PROFILE = ApprovedOfficialEnvironmentProfile(
            profileId = "official-test",
            cameraQualityProfileId = "camera-test",
            maximumGpsHorizontalAccuracyMeters = 15.0,
            maximumMeasuredEvidenceAgeMs = MAX_AGE_MS,
            maximumRuntimeRetryAttempts = 2,
        )
    }
}
