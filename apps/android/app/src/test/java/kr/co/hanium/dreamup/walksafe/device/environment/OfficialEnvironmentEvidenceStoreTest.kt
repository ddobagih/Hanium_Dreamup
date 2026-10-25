package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.OfficialEnvironmentGpsSample
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class OfficialEnvironmentEvidenceStoreTest {
    private val epoch = WalkRuntimeEpoch("walk", 1L)
    private val profile = ApprovedOfficialEnvironmentProfile("gps-v1", "camera-v1", 12.0, 30_000L, 2)
    private fun store() = OfficialEnvironmentEvidenceStore(Any(), RuntimeMeasuredQualityGate(3_000L), RuntimeMeasuredQualityGate())
    private fun gps(at: Long, passed: Boolean = true) = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.GPS_QUALITY,
        epoch, at, if (passed) EnvironmentEvidenceStatus.PASS else EnvironmentEvidenceStatus.FAIL,
        profile.profileId, profile.maximumMeasuredEvidenceAgeMs, if (passed) "GPS_QUALITY_PASSED" else "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE")

    @Test fun activatingRuntimeUsesTheSameAdmittedGpsHistoryAndFailureClock() {
        val store = store()
        val pass = gps(1_000L)
        store.gpsQualityGate.observe(pass, 1_000L)
        store.gpsQualityGate.observe(gps(1_600L, false), 1_600L)
        val originalDeadline = store.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs()
        store.prepareGpsForRuntime({ epoch }, 1_700L, { true }) { currentEpoch, now ->
            assertEquals(epoch, currentEpoch)
            store.gpsQualityGate.current(now)
        }
        assertSame(pass, store.gpsEvidence)
        assertEquals(4_600L, originalDeadline)
        assertEquals(originalDeadline, store.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.FAIL, store.gpsQualityGate.current(4_600L)?.status)
    }

    @Test fun repeatedOutliersDoNotRestartTheOriginalThreeSecondGpsGrace() {
        val store = store()
        fun record(at: Long, trusted: Boolean, single: Boolean) = store.recordGpsObservation(epoch, epoch, at, trusted,
            4.0, { at }, { profile }, { true }, { epoch }, { false }, { false }, single) { _, _, _ -> fail() }
        record(1_000L, true, false)
        record(1_600L, false, true)
        record(2_900L, false, true)
        assertEquals(1_000L, store.gpsEvidence?.observedAtElapsedRealtimeMs)
        assertEquals(4_600L, store.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals("GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE", store.gpsQualityGate.current(4_600L)?.detail)
        assertEquals(EnvironmentEvidenceStatus.FAIL, store.gpsQualityGate.current(4_600L)?.status)
    }

    @Test fun wrongEpochCannotReadClockOrOverwriteAdmittedGpsEvidence() {
        val store = store()
        val pass = gps(1_000L)
        store.acceptGpsPreflightSample(pass, 4.0, 1_000L)
        store.recordGpsObservation(epoch, epoch.copy(recoveryGeneration = 0L), 2_000L, false, 200.0,
            { fail("stale observation must not read clock"); 2_000L }, { profile }, { true }, { epoch }, { false }, { true }, false) { _, _, _ -> fail() }
        assertSame(pass, store.gpsEvidence)
    }

    @Test fun preflightCacheSamplesKeepOriginalTimestampAndAreNeverRetaggedFresh() {
        val store = store()
        val sample = OfficialEnvironmentGpsSample(1_000L, 37.5, 127.0, 4.0, false)
        val candidate = checkNotNull(store.assessGpsPreflightSample(epoch, 2_000L, sample, profile))
        assertEquals(1_000L, candidate.observedAtElapsedRealtimeMs)
        assertNull(store.assessGpsPreflightSample(epoch, 1_000_000L, sample, profile))
        assertNull(store.assessGpsPreflightSample(epoch, 500L, sample, profile))
    }

    @Test fun olderManualCandidateCannotMixItsAccuracyIntoRetainedPassDiagnostics() {
        val store = store()
        val pass = gps(2_000L)
        store.acceptGpsPreflightSample(pass, 4.0, 2_000L)
        val rejected = gps(1_000L, false)
        assertSame(pass, store.acceptGpsPreflightSample(rejected, 300.0, 2_000L))
        assertEquals(4.0, store.gpsDiagnosticAccuracyMeters)
        assertEquals("GPS_QUALITY_PASSED", store.gpsDiagnosticDetail)
    }

    @Test fun rawCameraFailureAndRetainedPassStaySeparateWithOriginalCameraGrace() {
        val store = store()
        val pass = CameraFrameQualityAssessment(epoch, 1_000L, "camera-v1", 30_000L,
            EnvironmentEvidenceStatus.PASS, CameraFrameQualityReason.PASSED)
        val bad = pass.copy(observedAtElapsedRealtimeMs = 1_100L, status = EnvironmentEvidenceStatus.FAIL,
            reason = CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE)
        store.observeRuntimeRawCameraAssessment(pass, 1_000L)
        store.observeRuntimeRawCameraAssessment(bad, 1_100L)
        assertEquals(pass.asMeasuredEnvironmentEvidence(), store.cameraQualityGate.current(1_100L))
        assertEquals(bad.asMeasuredEnvironmentEvidence(), store.cameraQualityGate.latestRawObservation())
        assertEquals(2_600L, store.cameraQualityGate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(EnvironmentEvidenceStatus.FAIL, store.cameraQualityGate.current(2_600L)?.status)
    }

    @Test fun evidenceRefreshPrecedesPolicyReadAndMountingRestrictionReadsUnderSharedLock() {
        val store = store()
        val calls = mutableListOf<String>()
        val facts = object : OfficialEnvironmentAssessmentFactsReader {
            override fun continuousSourcePresent() = true
            override fun separateGpsActive() = false
            override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = false
            override fun policyFacts(): OfficialEnvironmentPolicyFacts {
                calls += "policy"
                assertEquals("fresh", store.gpsEvidence?.detail)
                return OfficialEnvironmentPolicyFacts(profile, true)
            }
            override fun locationEnabled() = true
            override fun cameraEnabled() = true
            override fun mountingCameraRestricted(epoch: WalkRuntimeEpoch): Boolean { fail(); return true }
        }
        store.currentAssessment(epoch, 2_000L, facts, { _, _ -> calls += "refresh"; gps(2_000L).copy(detail = "fresh") }) { fail() }
        assertEquals(listOf("refresh", "policy"), calls)
    }

    @Test fun clearingDisplayedEvidenceDoesNotResetTheSharedGpsGate() {
        val store = store()
        val pass = gps(1_000L)
        store.gpsQualityGate.observe(pass, 1_000L)
        store.invalidate()
        assertNull(store.gpsEvidence)
        assertSame(pass, store.gpsQualityGate.current(1_000L))
    }
}
