package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSignal
import kr.co.hanium.dreamup.walksafe.device.RunningDetectorReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S14-33-F1: a failed or missing sample-inference verification is an explicit, bounded state. It is
 * retried a fixed number of times, then FAILED, which the device check shows as UNAVAILABLE and
 * which blocks walk entry (FP-039 failure_behavior); "checking" cannot last past the deadline.
 */
class RunningDetectorVerificationRetryTest {
    private val configSha = "c".repeat(64)
    private val passed = RunningDetectorLoadEvidence(
        assetIntegrityVerified = true,
        interpreterLoaded = true,
        sampleInferencePassed = true,
    )
    private val sampleFailed = passed.copy(sampleInferencePassed = false)

    @Test
    fun sampleInferenceFailuresEndInFailedAndBlockTheDeviceCheckAndWalkEntry() {
        val gate = RunningDetectorVerificationGate(maxAttempts = 3, retryDelayMs = 1_000L, pendingDeadlineMs = 15_000L)
        val instance = Any()
        val id = identity(generation = 4L)
        gate.begin(instance, nowMs = 0L)
        assertEquals(RunningDetectorVerificationState.PENDING, gate.state(instance))
        assertEquals(PostLoginDeviceCheckSignal.PENDING, RunningDetectorReadiness.deviceCheckSignal(gate.state(instance)))

        assertEquals(RunningDetectorVerificationStep.RETRY, gate.recordAttempt(instance, id, sampleFailed, 100L))
        assertEquals(RunningDetectorVerificationStep.RETRY, gate.recordAttempt(instance, id, sampleFailed, 1_200L))
        assertEquals(RunningDetectorVerificationStep.FAILED, gate.recordAttempt(instance, id, sampleFailed, 2_300L))

        assertEquals(RunningDetectorVerificationState.FAILED, gate.state(instance))
        assertFalse(gate.allowsOutput(instance, 4L, configSha))
        assertNull(gate.current())
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, RunningDetectorReadiness.deviceCheckSignal(gate.state(instance)))
        assertTrue(RunningDetectorReadiness.blocksWalkEntry(gate.state(instance), detectorAvailable = true, detectorLoadReason = "loaded"))
        // After MainActivity detaches the instance, the load reason keeps walk entry blocked until a reload.
        assertTrue(
            RunningDetectorReadiness.blocksWalkEntry(
                RunningDetectorVerificationState.NONE,
                detectorAvailable = false,
                detectorLoadReason = RunningDetectorReadiness.SAMPLE_INFERENCE_UNVERIFIED_REASON,
            ),
        )
        // A finished verification is never reopened by a late attempt.
        assertEquals(RunningDetectorVerificationStep.STALE, gate.recordAttempt(instance, id, passed, 2_400L))
        assertFalse(gate.allowsOutput(instance, 4L, configSha))
    }

    @Test
    fun aRetryAfterATransientSampleFailureIssuesTheToken() {
        val gate = RunningDetectorVerificationGate(maxAttempts = 3)
        val instance = Any()
        val id = identity(generation = 9L)
        gate.begin(instance, nowMs = 10L)

        assertEquals(RunningDetectorVerificationStep.RETRY, gate.recordAttempt(instance, id, sampleFailed, 20L))
        assertEquals(RunningDetectorVerificationStep.VERIFIED, gate.recordAttempt(instance, id, passed, 1_030L))

        assertNotNull(gate.current())
        assertTrue(gate.allowsOutput(instance, 9L, configSha))
        assertEquals(RunningDetectorVerificationState.VERIFIED, gate.state(instance))
        assertEquals(PostLoginDeviceCheckSignal.READY, RunningDetectorReadiness.deviceCheckSignal(gate.state(instance)))
        assertFalse(RunningDetectorReadiness.blocksWalkEntry(gate.state(instance), detectorAvailable = true, detectorLoadReason = "loaded"))
        // The deadline no longer applies to a verified instance.
        assertFalse(gate.expire(instance, 60_000L))
        assertTrue(gate.allowsOutput(instance, 9L, configSha))
    }

    @Test
    fun checkingIsBoundedByTheDeadlineEvenWhenNoAttemptEverRuns() {
        val gate = RunningDetectorVerificationGate(maxAttempts = 3, pendingDeadlineMs = 15_000L)
        val instance = Any()
        gate.begin(instance, nowMs = 1_000L)

        assertFalse(gate.expire(instance, 15_999L))
        assertEquals(RunningDetectorVerificationState.PENDING, gate.state(instance))
        assertTrue(gate.expire(instance, 16_000L))
        assertFalse("the transition is reported once", gate.expire(instance, 16_001L))
        assertEquals(RunningDetectorVerificationState.FAILED, gate.state(instance))
        assertEquals(PostLoginDeviceCheckSignal.UNAVAILABLE, RunningDetectorReadiness.deviceCheckSignal(gate.state(instance)))
        // An attempt that finally runs after the deadline cannot issue a token.
        assertEquals(
            RunningDetectorVerificationStep.STALE,
            gate.recordAttempt(instance, identity(generation = 1L), passed, 16_500L),
        )
        assertNull(gate.current())
    }

    @Test
    fun aFailedAttemptPastTheDeadlineFailsWithoutAnotherRetry() {
        val gate = RunningDetectorVerificationGate(maxAttempts = 5, pendingDeadlineMs = 2_000L)
        val instance = Any()
        gate.begin(instance, nowMs = 0L)
        assertEquals(RunningDetectorVerificationStep.FAILED, gate.recordAttempt(instance, null, passed, 2_000L))
        // A clock that ran backwards is treated as overdue (fail closed).
        val other = Any()
        gate.begin(other, nowMs = 5_000L)
        assertEquals(RunningDetectorVerificationStep.FAILED, gate.recordAttempt(other, null, passed, 4_000L))
    }

    @Test
    fun successfulSampleCannotBeatAnUndispatchedDeadlineOrBackwardsClock() {
        for (completedAt in listOf(15_999L, 16_000L, 16_001L, 999L)) {
            val gate = RunningDetectorVerificationGate(pendingDeadlineMs = 15_000L)
            val instance = Any()
            gate.begin(instance, nowMs = 1_000L)
            val timely = completedAt == 15_999L
            assertEquals(
                "completion at $completedAt without running expire()",
                if (timely) RunningDetectorVerificationStep.VERIFIED else RunningDetectorVerificationStep.FAILED,
                gate.recordAttempt(instance, identity(4L), passed, completedAt),
            )
            assertEquals(timely, gate.allowsOutput(instance, 4L, configSha))
            if (!timely) {
                assertNull(gate.current())
                assertEquals(RunningDetectorVerificationStep.STALE,
                    gate.recordAttempt(instance, identity(4L), passed, 1_001L))
            }
        }
    }

    @Test
    fun expiredOldSampleCannotExpireOrVerifyTheReplacementInstance() {
        val gate = RunningDetectorVerificationGate(pendingDeadlineMs = 15_000L)
        val old = Any()
        val replacement = Any()
        gate.begin(old, nowMs = 0L)
        gate.begin(replacement, nowMs = 1_000L)
        assertEquals(RunningDetectorVerificationStep.STALE,
            gate.recordAttempt(old, identity(1L), passed, 500_000L))
        assertEquals(RunningDetectorVerificationState.PENDING, gate.state(replacement))
        assertNull(gate.current())
        assertEquals(RunningDetectorVerificationStep.VERIFIED,
            gate.recordAttempt(replacement, identity(2L), passed, 1_001L))
        assertFalse(gate.allowsOutput(old, 1L, configSha))
    }

    @Test
    fun rejectedWorkFailsThePendingVerificationOnce() {
        val gate = RunningDetectorVerificationGate()
        val instance = Any()
        gate.begin(instance, nowMs = 0L)
        assertTrue(gate.fail(instance))
        assertFalse(gate.fail(instance))
        assertEquals(RunningDetectorVerificationState.FAILED, gate.state(instance))
    }

    @Test
    fun anUnnameableRunningModelIsRetriedThenFails() {
        val gate = RunningDetectorVerificationGate(maxAttempts = 2)
        val instance = Any()
        gate.begin(instance, nowMs = 0L)
        assertEquals(RunningDetectorVerificationStep.RETRY, gate.recordAttempt(instance, null, passed, 1L))
        assertEquals(RunningDetectorVerificationStep.FAILED, gate.recordAttempt(instance, null, passed, 2L))
        assertNull(gate.current())
    }

    @Test
    fun aReplacementStartsItsOwnVerificationAndTheOldInstanceIsStale() {
        val gate = RunningDetectorVerificationGate(maxAttempts = 1)
        val old = Any()
        val replacement = Any()
        gate.begin(old, nowMs = 0L)
        gate.begin(replacement, nowMs = 10L)
        assertEquals(RunningDetectorVerificationStep.STALE, gate.recordAttempt(old, identity(generation = 1L), passed, 20L))
        assertFalse(gate.fail(old))
        assertEquals(RunningDetectorVerificationState.NONE, gate.state(old))
        assertEquals(RunningDetectorVerificationState.PENDING, gate.state(replacement))
        assertEquals(RunningDetectorVerificationStep.VERIFIED, gate.recordAttempt(replacement, identity(generation = 2L), passed, 30L))
        assertFalse(gate.allowsOutput(old, 1L, configSha))
        assertTrue(gate.allowsOutput(replacement, 2L, configSha))
        assertEquals(RunningDetectorVerificationState.NONE, gate.state(null))
    }

    @Test
    fun defaultBoundsAreFiniteAndAllowEveryRetryBeforeTheDeadline() {
        val gate = RunningDetectorVerificationGate()
        assertEquals(3, gate.maxAttempts)
        assertTrue(gate.pendingDeadlineMs > gate.maxAttempts * gate.retryDelayMs)
        assertEquals(15_000L, gate.pendingDeadlineMs)
    }

    private fun identity(generation: Long): RunningDetectorIdentity = requireNotNull(
        RunningDetectorIdentity.of(config(), configSha, UNIFIED, "cpu", false, generation),
    )

    private fun config() = TwoModelRuntimeConfig(
        bundleVersion = "bundle-1",
        runtimeSourceModel = null,
        primaryModelKey = UNIFIED,
        fallbackModelKey = null,
        unifiedWalksafe = ModelRuntimeConfig(
            key = UNIFIED,
            asset = "models/unified.tflite",
            inputSize = 768,
            classes = listOf("person", "car"),
            allowlist = setOf("person"),
            thresholds = mapOf("person" to 0.5f, "car" to 0.5f),
            artifactSha256 = "a".repeat(64),
        ),
        customTactile = null,
        cocoGeneral = null,
    )

    private companion object {
        const val UNIFIED = TwoModelRuntimeConfig.UNIFIED_MODEL_KEY
    }
}
