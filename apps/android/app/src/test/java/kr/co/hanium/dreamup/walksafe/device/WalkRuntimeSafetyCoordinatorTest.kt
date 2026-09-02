package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRuntimeSafetyCoordinatorTest {
    @Test
    fun approvedThresholdProfileRejectsMissingOrInvalidValues() {
        listOf(
            Triple("", 100L, 200L),
            Triple(" approval ", 100L, 200L),
            Triple("approval", 0L, 200L),
            Triple("approval", 100L, 0L),
        ).forEach { (approvalId, frameAgeMs, inferenceMs) ->
            assertThrows(IllegalArgumentException::class.java) {
                ApprovedWalkRuntimeSafetyThresholdProfile(
                    approvalProfileId = approvalId,
                    maximumFrameAgeMs = frameAgeMs,
                    maximumInferenceLatencyMs = inferenceMs,
                )
            }
        }
    }

    @Test
    fun productionProfileAllowsSafeTestFlowWithoutApprovedTimingThresholds() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(
            thresholdProfile = WalkRuntimeSafetyCoordinator.productionThresholdProfile,
            onSafeStop = callbacks::add,
        )

        assertFalse(coordinator.configured)
        assertEquals(
            WalkRuntimeSafetyDisposition.NOT_CONFIGURED,
            coordinator.beginEpoch(EPOCH_1).disposition,
        )
        val result = coordinator.observe(
            observation(
                epoch = EPOCH_1,
                frameCapturedAtElapsedRealtimeMs = 0L,
                inferenceLatencyMs = Long.MAX_VALUE,
            ),
        )

        assertEquals(WalkRuntimeSafetyDisposition.NOT_CONFIGURED, result.disposition)
        assertTrue(result.safetyOutputsAllowed)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun unconfiguredProfileStillLatchesThresholdIndependentUnsafeCauses() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(
            thresholdProfile = WalkRuntimeSafetyCoordinator.productionThresholdProfile,
            onSafeStop = callbacks::add,
        )
        coordinator.beginEpoch(EPOCH_1)

        val result = coordinator.observe(
            observation(
                cameraTrusted = false,
                batteryCritical = true,
                frameCapturedAtElapsedRealtimeMs = 0L,
                inferenceLatencyMs = Long.MAX_VALUE,
            ),
        )

        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, result.disposition)
        assertEquals(
            setOf(
                WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST,
                WalkRuntimeSafetyStopCause.BATTERY_CRITICAL,
            ),
            result.causes,
        )
        assertFalse(result.safetyOutputsAllowed)
        assertEquals(1, callbacks.size)
    }

    @Test
    fun everyCriticalTrustLossLatchesSafeStop() {
        val observations = mapOf(
            WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST to observation(cameraTrusted = false),
            WalkRuntimeSafetyStopCause.DEPTH_TRUST_LOST to observation(depthTrusted = false),
            WalkRuntimeSafetyStopCause.GPS_TRUST_LOST to observation(gpsTrusted = false),
            WalkRuntimeSafetyStopCause.RISK_TRUST_LOST to observation(riskTrusted = false),
            WalkRuntimeSafetyStopCause.TTS_TRUST_LOST to observation(ttsTrusted = false),
        )

        observations.forEach { (cause, input) ->
            val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
            val coordinator = configuredCoordinator(callbacks)
            coordinator.beginEpoch(EPOCH_1)

            val result = coordinator.observe(input)

            assertEquals(cause.name, setOf(cause), result.causes)
            assertEquals(cause.name, 1, callbacks.size)
            assertFalse(cause.name, result.safetyOutputsAllowed)
        }
    }

    @Test
    fun everyCriticalResourceStateLatchesSafeStop() {
        val observations = mapOf(
            WalkRuntimeSafetyStopCause.BATTERY_CRITICAL to observation(batteryCritical = true),
            WalkRuntimeSafetyStopCause.STORAGE_CRITICAL to observation(storageCritical = true),
            WalkRuntimeSafetyStopCause.THERMAL_CRITICAL to observation(thermalCritical = true),
        )

        observations.forEach { (cause, input) ->
            val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
            val coordinator = configuredCoordinator(callbacks)
            coordinator.beginEpoch(EPOCH_1)

            val result = coordinator.observe(input)

            assertEquals(cause.name, setOf(cause), result.causes)
            assertEquals(cause.name, 1, callbacks.size)
        }
    }

    @Test
    fun frameAgeAndInferenceLatencyUseApprovedInclusiveBoundaries() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        coordinator.beginEpoch(EPOCH_1)

        val boundary = coordinator.observe(
            observation(
                observedAtElapsedRealtimeMs = 1_000L,
                frameCapturedAtElapsedRealtimeMs = 900L,
                inferenceLatencyMs = 200L,
            ),
        )
        val exceeded = coordinator.observe(
            observation(
                observedAtElapsedRealtimeMs = 2_000L,
                frameCapturedAtElapsedRealtimeMs = 1_899L,
                inferenceLatencyMs = 201L,
            ),
        )

        assertEquals(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT, boundary.disposition)
        assertEquals(
            setOf(
                WalkRuntimeSafetyStopCause.FRAME_AGE_EXCEEDED,
                WalkRuntimeSafetyStopCause.INFERENCE_LATENCY_EXCEEDED,
            ),
            exceeded.causes,
        )
        assertEquals(1, callbacks.size)
    }

    @Test
    fun invalidTimesAndEpochMismatchFailClosed() {
        val cases = listOf(
            observation(observedAtElapsedRealtimeMs = -1L) to WalkRuntimeSafetyStopCause.INVALID_TIME,
            observation(
                observedAtElapsedRealtimeMs = 10L,
                frameCapturedAtElapsedRealtimeMs = 11L,
            ) to WalkRuntimeSafetyStopCause.INVALID_TIME,
            observation(inferenceLatencyMs = -1L) to WalkRuntimeSafetyStopCause.INVALID_TIME,
            observation(epoch = EPOCH_2) to WalkRuntimeSafetyStopCause.EPOCH_MISMATCH,
        )

        cases.forEach { (input, expectedCause) ->
            val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
            val coordinator = configuredCoordinator(callbacks)
            coordinator.beginEpoch(EPOCH_1)

            val result = coordinator.observe(input)

            assertTrue(expectedCause.name, expectedCause in result.causes)
            assertEquals(expectedCause.name, 1, callbacks.size)
            assertFalse(expectedCause.name, result.safetyOutputsAllowed)
        }

        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        coordinator.beginEpoch(EPOCH_1)
        coordinator.observe(observation(observedAtElapsedRealtimeMs = 1_000L))
        val clockRollback = coordinator.observe(
            observation(observedAtElapsedRealtimeMs = 999L),
        )
        assertTrue(WalkRuntimeSafetyStopCause.INVALID_TIME in clockRollback.causes)
        assertEquals(1, callbacks.size)
    }

    @Test
    fun recoveryNeverUnlatchesSameEpochAndCallbackRunsOnlyOnce() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        coordinator.beginEpoch(EPOCH_1)

        coordinator.observe(observation(cameraTrusted = false))
        assertEquals(
            WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED,
            coordinator.beginEpoch(EPOCH_1).disposition,
        )
        val recovered = coordinator.observe(
            observation(
                cameraTrusted = true,
                depthTrusted = true,
                gpsTrusted = true,
                riskTrusted = true,
                ttsTrusted = true,
                batteryCritical = false,
                storageCritical = false,
                thermalCritical = false,
            ),
        )
        coordinator.observe(observation(ttsTrusted = false))

        assertEquals(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, recovered.disposition)
        assertTrue(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST in recovered.causes)
        assertEquals(1, callbacks.size)
    }

    @Test
    fun aNewEpochGetsItsOwnSingleLatch() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        coordinator.beginEpoch(EPOCH_1)
        coordinator.observe(observation(cameraTrusted = false))

        assertEquals(
            WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,
            coordinator.beginEpoch(EPOCH_2).disposition,
        )
        coordinator.observe(observation(epoch = EPOCH_2, ttsTrusted = false))

        assertEquals(2, callbacks.size)
        assertEquals(listOf(EPOCH_1, EPOCH_2), callbacks.map(WalkRuntimeSafetyStop::epoch))
    }

    @Test
    fun transportFailuresAreReturnedOnlyAsDeferredWork() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        coordinator.beginEpoch(EPOCH_1)
        val deferred = WalkRuntimeDeferredFailure.entries.toSet()

        val result = coordinator.observe(observation(deferredFailures = deferred))

        assertEquals(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT, result.disposition)
        assertEquals(deferred, result.deferredFailures)
        assertTrue(result.causes.isEmpty())
        assertTrue(result.safetyOutputsAllowed)
        assertTrue(callbacks.isEmpty())
    }

    private fun configuredCoordinator(
        callbacks: MutableList<WalkRuntimeSafetyStop>,
    ) = WalkRuntimeSafetyCoordinator(
        thresholdProfile = ApprovedWalkRuntimeSafetyThresholdProfile(
            approvalProfileId = "approved-profile",
            maximumFrameAgeMs = 100L,
            maximumInferenceLatencyMs = 200L,
        ),
        onSafeStop = callbacks::add,
    )

    private fun observation(
        epoch: WalkRuntimeEpoch = EPOCH_1,
        observedAtElapsedRealtimeMs: Long = 1_000L,
        cameraTrusted: Boolean? = null,
        depthTrusted: Boolean? = null,
        gpsTrusted: Boolean? = null,
        riskTrusted: Boolean? = null,
        ttsTrusted: Boolean? = null,
        batteryCritical: Boolean? = null,
        storageCritical: Boolean? = null,
        thermalCritical: Boolean? = null,
        frameCapturedAtElapsedRealtimeMs: Long? = null,
        inferenceLatencyMs: Long? = null,
        deferredFailures: Set<WalkRuntimeDeferredFailure> = emptySet(),
    ) = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
        cameraTrusted = cameraTrusted,
        depthTrusted = depthTrusted,
        gpsTrusted = gpsTrusted,
        riskTrusted = riskTrusted,
        ttsTrusted = ttsTrusted,
        batteryCritical = batteryCritical,
        storageCritical = storageCritical,
        thermalCritical = thermalCritical,
        frameCapturedAtElapsedRealtimeMs = frameCapturedAtElapsedRealtimeMs,
        inferenceLatencyMs = inferenceLatencyMs,
        deferredFailures = deferredFailures,
    )

    private companion object {
        val EPOCH_1 = WalkRuntimeEpoch("walk-1", 0L)
        val EPOCH_2 = WalkRuntimeEpoch("walk-2", 0L)
    }
}
