package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeMeasuredQualityGateOrderingRegressionTest {
    @Test
    fun newerFailureRejectsStaleOlderAndDuplicatePass() {
        val gate = RuntimeMeasuredQualityGate()
        val failure = pass(10_100L).copy(
            status = EnvironmentEvidenceStatus.FAIL,
            detail = "FRAME_UNAVAILABLE",
        )
        assertEquals(failure, gate.observe(failure, 10_100L))

        assertEquals(failure, gate.observe(pass(10_000L).copy(maximumEvidenceAgeMs = 50L), 10_101L))
        assertEquals(failure, gate.observe(pass(10_100L).copy(maximumEvidenceAgeMs = 0L), 10_101L))
        assertEquals(failure, gate.current(10_102L))
    }

    @Test
    fun olderGenerationPassCannotReplaceNewerGenerationFailure() {
        val gate = RuntimeMeasuredQualityGate()
        val newerFailure = pass(10_200L).copy(
            epoch = WalkRuntimeEpoch("walk", 2L),
            status = EnvironmentEvidenceStatus.FAIL,
            detail = "FRAME_UNAVAILABLE",
        )
        assertEquals(newerFailure, gate.observe(newerFailure, 10_200L))

        assertEquals(newerFailure, gate.observe(pass(10_100L), 10_201L))
        assertEquals(newerFailure, gate.current(10_201L))
        assertEquals(newerFailure, gate.observe(pass(10_300L), 10_300L))
        assertEquals(newerFailure, gate.current(10_300L))
    }

    @Test
    fun staleOlderAndDuplicatePassDoNotEraseNewerPositive() {
        val gate = RuntimeMeasuredQualityGate()
        val newerPass = pass(10_100L)
        assertEquals(newerPass, gate.observe(newerPass, 10_100L))

        assertEquals(newerPass, gate.observe(pass(10_000L).copy(maximumEvidenceAgeMs = 50L), 10_101L))
        assertEquals(newerPass, gate.observe(pass(10_100L).copy(maximumEvidenceAgeMs = 0L), 10_101L))
        assertEquals(newerPass, gate.current(10_102L))
    }

    @Test
    fun delayedOlderFailureCannotReplaceNewerPositiveOrNewerFailure() {
        val olderFailure = pass(10_000L).copy(
            status = EnvironmentEvidenceStatus.FAIL,
            detail = "FRAME_UNAVAILABLE",
        )
        val newerPass = pass(10_100L)
        val passingGate = RuntimeMeasuredQualityGate()
        assertEquals(newerPass, passingGate.observe(newerPass, 10_100L))
        assertEquals(newerPass, passingGate.observe(olderFailure, 10_101L))
        assertEquals(newerPass, passingGate.current(10_102L))

        val newerFailure = olderFailure.copy(observedAtElapsedRealtimeMs = 10_100L)
        val failingGate = RuntimeMeasuredQualityGate()
        assertEquals(newerFailure, failingGate.observe(newerFailure, 10_100L))
        assertEquals(newerFailure, failingGate.observe(olderFailure, 10_101L))
        assertEquals(newerFailure, failingGate.current(10_102L))
    }

    @Test
    fun equalTimeAndUnknownTimeFailuresStillOverridePositiveEvidence() {
        val positive = pass(10_100L)
        val sameTimeFailure = positive.copy(
            status = EnvironmentEvidenceStatus.FAIL,
            detail = "FRAME_UNAVAILABLE",
        )
        val equalTimeGate = RuntimeMeasuredQualityGate()
        assertEquals(positive, equalTimeGate.observe(positive, 10_100L))
        assertEquals(sameTimeFailure, equalTimeGate.observe(sameTimeFailure, 10_101L))

        val unknownTimeFailure = sameTimeFailure.copy(observedAtElapsedRealtimeMs = null)
        val unknownTimeGate = RuntimeMeasuredQualityGate()
        assertEquals(positive, unknownTimeGate.observe(positive, 10_100L))
        assertEquals(unknownTimeFailure, unknownTimeGate.observe(unknownTimeFailure, 10_101L))
    }

    private fun pass(observedAtMs: Long) = MeasuredEnvironmentEvidence(
        factor = OfficialEnvironmentFactor.CAMERA_QUALITY,
        epoch = WalkRuntimeEpoch("walk", 1L),
        observedAtElapsedRealtimeMs = observedAtMs,
        status = EnvironmentEvidenceStatus.PASS,
        measurementProfileId = "camera-test",
        maximumEvidenceAgeMs = 5_000L,
        detail = "PASSED",
    )
}
