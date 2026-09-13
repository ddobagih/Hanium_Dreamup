package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveInferencePacingPolicyTest {
    @Test
    fun latestFrameOnlyAdmissionNeverQueuesWhileAnyWorkIsInFlight() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(1_000L))

        assertNull(policy.tryStart(1_250L))
        assertNull(policy.tryStart(5_000L))
        assertFalse(policy.isDue(5_000L))
        assertTrue(policy.complete(ticket, 5_000L, inferenceDurationMs = 3_900L))
        assertFalse(policy.isDue(5_199L))
        assertNotNull(policy.tryStart(5_200L))
    }

    @Test
    fun fastDeviceNeedsSustainedSamplesAndRecoversInBoundedSteps() {
        val policy = AdaptiveInferencePacingPolicy()

        repeat(3) { completeNext(policy, endToEndMs = 40L) }
        assertEquals(250L, policy.snapshot().targetIntervalMs)
        assertEquals(3, policy.snapshot().recoverySamples)
        completeNext(policy, endToEndMs = 40L)
        assertEquals(200L, policy.snapshot().targetIntervalMs)
        repeat(4) { completeNext(policy, endToEndMs = 40L) }
        assertEquals(150L, policy.snapshot().targetIntervalMs)
        repeat(20) { completeNext(policy, endToEndMs = 40L) }
        assertEquals(100L, policy.snapshot().targetIntervalMs)
        assertEquals(25L, policy.snapshot().cooldownMs)
    }

    @Test
    fun steadySlowDeviceAdaptsToMeasuredEndToEndCost() {
        val policy = AdaptiveInferencePacingPolicy()
        val completedAtMs = completeNext(policy, endToEndMs = 400L, inferenceMs = 20L)

        assertEquals(500L, policy.snapshot().targetIntervalMs)
        assertEquals(100L, policy.snapshot().cooldownMs)
        assertEquals(400L, policy.snapshot().lastEndToEndMs)
        assertEquals(20L, policy.snapshot().lastInferenceMs)
        assertEquals(InferencePacingReason.MEASURED_BACKOFF, policy.snapshot().reason)
        assertFalse(policy.isDue(completedAtMs + 99L))
        assertTrue(policy.isDue(completedAtMs + 100L))
    }

    @Test
    fun durationBeyondMaximumIntervalStillLeavesBoundedIdleTime() {
        val policy = AdaptiveInferencePacingPolicy()
        val completedAtMs = completeNext(policy, endToEndMs = 2_000L)

        assertEquals(600L, policy.snapshot().targetIntervalMs)
        assertEquals(200L, policy.snapshot().cooldownMs)
        assertFalse(policy.isDue(completedAtMs))
        assertFalse(policy.isDue(completedAtMs + 199L))
        assertTrue(policy.isDue(completedAtMs + 200L))
        assertTrue(policy.snapshot().overFreshnessBudget == true)
        assertEquals(InferencePacingReason.OVER_FRESHNESS_BUDGET, policy.snapshot().reason)
    }

    @Test
    fun isolatedSpikeBacksOffWithoutPermanentlyChoosingTheSlowestInterval() {
        val policy = AdaptiveInferencePacingPolicy()
        repeat(12) { completeNext(policy, endToEndMs = 40L) }

        completeNext(policy, endToEndMs = 800L)
        assertEquals(300L, policy.snapshot().targetIntervalMs)
        assertEquals(230.0, checkNotNull(policy.snapshot().smoothedEndToEndMs), 0.001)
        assertEquals(0L, policy.snapshot().staleSamples)
        repeat(3) { completeNext(policy, endToEndMs = 40L) }
        assertEquals(300L, policy.snapshot().targetIntervalMs)
        repeat(40) { completeNext(policy, endToEndMs = 40L) }
        assertEquals(100L, policy.snapshot().targetIntervalMs)
    }

    @Test
    fun smallTimingJitterDoesNotAlternatePacingEverySample() {
        val policy = AdaptiveInferencePacingPolicy()
        completeNext(policy, endToEndMs = 200L)

        repeat(20) {
            completeNext(policy, endToEndMs = if (it % 2 == 0) 190L else 205L)
            assertEquals(250L, policy.snapshot().targetIntervalMs)
        }
    }

    @Test
    fun staleWorkCannotEarnFasterPacingEvenWhenComputeCostIsSmall() {
        val policy = AdaptiveInferencePacingPolicy(maximumFreshFrameAgeMs = 20L)

        repeat(20) { completeNext(policy, endToEndMs = 40L) }

        assertEquals(250L, policy.snapshot().targetIntervalMs)
        assertEquals(20L, policy.snapshot().staleSamples)
        assertEquals(0, policy.snapshot().recoverySamples)
        assertTrue(policy.snapshot().overFreshnessBudget == true)
    }

    @Test
    fun freshnessLimitDoesNotExpandToMatchRepeatedSlowWork() {
        val policy = AdaptiveInferencePacingPolicy(maximumFreshFrameAgeMs = 800L)

        repeat(10) { completeNext(policy, endToEndMs = 801L) }

        assertEquals(10L, policy.snapshot().staleSamples)
        assertEquals(InferencePacingReason.OVER_FRESHNESS_BUDGET, policy.snapshot().reason)
        completeNext(policy, endToEndMs = 800L)
        assertEquals(10L, policy.snapshot().staleSamples)
        assertFalse(policy.snapshot().overFreshnessBudget == true)
    }

    @Test
    fun measuredThermalThrottlingRaisesFloorAndMissingInformationCannotClearIt() {
        val policy = AdaptiveInferencePacingPolicy()
        completeNext(policy, endToEndMs = 40L, thermalThrottled = true)

        assertEquals(500L, policy.snapshot().targetIntervalMs)
        assertEquals(InferencePacingReason.THERMAL_BACKOFF, policy.snapshot().reason)
        repeat(20) { completeNext(policy, endToEndMs = 40L, thermalThrottled = null) }
        assertEquals(500L, policy.snapshot().targetIntervalMs)
        assertNull(policy.snapshot().thermalThrottled)
        assertTrue(policy.snapshot().thermalHoldActive)
        repeat(3) { completeNext(policy, endToEndMs = 40L, thermalThrottled = false) }
        assertEquals(500L, policy.snapshot().targetIntervalMs)
        completeNext(policy, endToEndMs = 40L, thermalThrottled = false)
        assertEquals(450L, policy.snapshot().targetIntervalMs)
        assertFalse(policy.snapshot().thermalHoldActive)
    }

    @Test
    fun missingThermalSensorDoesNotPretendTheDeviceIsThrottled() {
        val policy = AdaptiveInferencePacingPolicy()
        repeat(12) { completeNext(policy, endToEndMs = 40L, thermalThrottled = null) }

        assertEquals(100L, policy.snapshot().targetIntervalMs)
        assertFalse(policy.snapshot().thermalHoldActive)
        assertNull(policy.snapshot().thermalThrottled)
    }

    @Test
    fun unavailableModelTimingDoesNotKeepAnOldSlowInferenceEstimateForever() {
        val policy = AdaptiveInferencePacingPolicy()
        completeNext(policy, endToEndMs = 400L, inferenceMs = 400L)

        repeat(80) { completeNext(policy, endToEndMs = 40L, inferenceMs = null) }

        assertEquals(100L, policy.snapshot().targetIntervalMs)
        assertNull(policy.snapshot().lastInferenceMs)
        assertNull(policy.snapshot().smoothedInferenceMs)
    }

    @Test
    fun resetInvalidatesOldSamplesButPreservesThePhysicalWorkerUntilItsCompletion() {
        val policy = AdaptiveInferencePacingPolicy()
        completeNext(policy, endToEndMs = 400L)
        val oldTicket = checkNotNull(policy.tryStart(1_000L))

        policy.reset()

        assertTrue(policy.snapshot().inFlight)
        assertNull(policy.tryStart(10_000L))
        assertFalse(policy.complete(oldTicket, 10_000L, thermalThrottled = true))
        assertFalse(policy.snapshot().inFlight)
        assertEquals(0L, policy.snapshot().completedSamples)
        assertEquals(250L, policy.snapshot().targetIntervalMs)
        assertNull(policy.snapshot().smoothedEndToEndMs)
        assertFalse(policy.snapshot().thermalHoldActive)
        assertNotNull(policy.tryStart(10_000L))
    }

    @Test
    fun resetDoesNotTreatKnownDeviceThrottlingAsACooledDevice() {
        val policy = AdaptiveInferencePacingPolicy()
        completeNext(policy, endToEndMs = 40L, thermalThrottled = true)

        policy.reset()
        assertEquals(500L, policy.snapshot().targetIntervalMs)
        assertTrue(policy.snapshot().thermalHoldActive)
        assertEquals(InferencePacingReason.THERMAL_BACKOFF, policy.snapshot().reason)
        repeat(12) { completeNext(policy, endToEndMs = 40L, thermalThrottled = null) }

        assertEquals(500L, policy.snapshot().targetIntervalMs)
        assertTrue(policy.snapshot().thermalHoldActive)
        assertEquals(InferencePacingReason.THERMAL_BACKOFF, policy.snapshot().reason)
    }

    @Test
    fun cancelledFramesDoNotCountAsFastMeasurementsOrCauseARetryBurst() {
        val policy = AdaptiveInferencePacingPolicy()
        repeat(3) { completeNext(policy, endToEndMs = 40L) }
        val startedAtMs = policy.snapshot().nextEligibleAtElapsedRealtimeMs
        val ticket = checkNotNull(policy.tryStart(startedAtMs))

        assertTrue(policy.cancel(ticket))
        assertFalse(policy.isDue(startedAtMs + 249L))
        assertTrue(policy.isDue(startedAtMs + 250L))
        assertEquals(0, policy.snapshot().recoverySamples)
        assertEquals(3L, policy.snapshot().completedSamples)
        completeNext(policy, endToEndMs = 40L)
        assertEquals(250L, policy.snapshot().targetIntervalMs)
    }

    @Test
    fun duplicateAndForeignCompletionCannotReleaseTheCurrentWorker() {
        val policy = AdaptiveInferencePacingPolicy()
        val first = checkNotNull(policy.tryStart(0L))
        assertTrue(policy.complete(first, 40L))
        val second = checkNotNull(policy.tryStart(250L))
        val foreign = checkNotNull(AdaptiveInferencePacingPolicy().tryStart(250L))

        assertFalse(policy.complete(first, 300L))
        assertFalse(policy.cancel(foreign))
        assertFalse(policy.complete(foreign, 300L))
        assertTrue(policy.snapshot().inFlight)
        assertTrue(policy.complete(second, 300L))
        assertEquals(2L, policy.snapshot().completedSamples)
    }

    @Test
    fun negativeOrRegressingMonotonicTimeCannotAdvanceTheScheduler() {
        val policy = AdaptiveInferencePacingPolicy()
        assertNull(policy.tryStart(-1L))
        val ticket = checkNotNull(policy.tryStart(1_000L))

        assertFalse(policy.complete(ticket, 999L))
        assertEquals(1L, policy.snapshot().invalidSamples)
        assertEquals(0L, policy.snapshot().completedSamples)
        assertFalse(policy.isDue(999L))
        assertFalse(policy.isDue(1_249L))
        assertTrue(policy.isDue(1_250L))
        assertEquals(InferencePacingReason.INVALID_TIMING, policy.snapshot().reason)
    }

    @Test
    fun negativeInferenceTimingIsNotUsedAsFastHeadroom() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(0L))

        assertFalse(policy.complete(ticket, 40L, inferenceDurationMs = -1L))

        assertEquals(1L, policy.snapshot().invalidSamples)
        assertEquals(250L, policy.snapshot().targetIntervalMs)
        assertNull(policy.snapshot().smoothedEndToEndMs)
    }

    @Test
    fun veryLargeMonotonicValuesDoNotOverflowTheNextAdmissionIntoThePast() {
        val policy = AdaptiveInferencePacingPolicy()
        val ticket = checkNotNull(policy.tryStart(Long.MAX_VALUE - 10L))

        assertTrue(policy.complete(ticket, Long.MAX_VALUE))

        assertEquals(Long.MAX_VALUE, policy.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertFalse(policy.isDue(0L))
    }

    @Test
    fun observedUiContentionContinuouslyIncreasesHeadroomWithoutQueueingAnotherTask() {
        val policy = AdaptiveInferencePacingPolicy()
        repeat(12) { completeNext(policy, 80L) }
        val baseline = policy.snapshot()

        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, uiDispatchDelayMs = 90L, uiDispatchBudgetMs = 20L)
        }

        assertEquals(4.0, policy.snapshot().workHeadroomMultiplier, 0.001)
        assertEquals(400L, policy.snapshot().targetIntervalMs)
        assertTrue(policy.snapshot().cooldownMs > baseline.cooldownMs)
        assertEquals(InferencePacingReason.OBSERVED_LOAD_BACKOFF, policy.snapshot().reason)
        val ticket = checkNotNull(policy.tryStart(policy.snapshot().nextEligibleAtElapsedRealtimeMs))
        assertNull(policy.tryStart(ticket.startedAtElapsedRealtimeMs + 1_000L))
        assertTrue(policy.cancel(ticket))
    }

    @Test
    fun actualCameraModeBudgetTreatsThirtyFpsAsHealthyAndMeasuresRelativeDelay() {
        val policy = AdaptiveInferencePacingPolicy()
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, cameraFrameIntervalMs = 33L, expectedCameraFrameIntervalMs = 33L)
        }
        assertEquals(1.0, policy.snapshot().workHeadroomMultiplier, 0.001)

        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, cameraFrameIntervalMs = 55L, expectedCameraFrameIntervalMs = 33L)
        }
        assertEquals(55.0 / 33.0, policy.snapshot().workHeadroomMultiplier, 0.001)
    }

    @Test
    fun missingModeBudgetDoesNotInventPressureAndUnmeasuredFeatureCannotClearObservedPressure() {
        val policy = AdaptiveInferencePacingPolicy()
        completeWithLoad(policy) { now -> InferenceLoadObservation(now, cameraFrameIntervalMs = 100L) }
        assertEquals(0.0, policy.snapshot().observedLoadPressure, 0.001)
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, trackingProcessingMs = 60L, trackingBudgetMs = 30L)
        }
        repeat(12) {
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, uiDispatchDelayMs = 5L, uiDispatchBudgetMs = 20L)
            }
        }
        assertEquals(1.0, policy.snapshot().observedLoadPressure, 0.001)
        assertEquals(0, policy.snapshot().loadRecoverySamples)
    }

    @Test
    fun loadRecoveryRequiresFreshRepeatedEvidenceAndRecoversInBoundedSteps() {
        val policy = AdaptiveInferencePacingPolicy()
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, depthProcessingMs = 60L, depthBudgetMs = 30L)
        }
        repeat(3) {
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, depthProcessingMs = 15L, depthBudgetMs = 30L)
            }
        }
        assertEquals(1.0, policy.snapshot().observedLoadPressure, 0.001)
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, depthProcessingMs = 15L, depthBudgetMs = 30L)
        }
        assertEquals(0.75, policy.snapshot().observedLoadPressure, 0.001)
        repeat(24) {
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, depthProcessingMs = 15L, depthBudgetMs = 30L)
            }
        }
        assertEquals(0.0, policy.snapshot().observedLoadPressure, 0.001)
        assertEquals(100L, policy.snapshot().targetIntervalMs)
    }

    @Test
    fun oneHealthyFeatureObservationCannotBeReusedToEarnFourRecoverySamples() {
        val policy = AdaptiveInferencePacingPolicy()
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, trackingProcessingMs = 60L, trackingBudgetMs = 30L)
        }
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, trackingProcessingMs = 15L, trackingBudgetMs = 30L)
        }
        repeat(8) {
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 20L)
            }
        }
        assertEquals(1.0, policy.snapshot().observedLoadPressure, 0.001)
        assertEquals(1, policy.snapshot().loadRecoverySamples)
    }

    @Test
    fun asynchronousUiAndCameraFeaturesEachNeedTheirOwnFourNewRecoverySamples() {
        val policy = AdaptiveInferencePacingPolicy()
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, uiDispatchDelayMs = 60L, uiDispatchBudgetMs = 30L)
        }
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, cameraFrameIntervalMs = 66L, expectedCameraFrameIntervalMs = 33L)
        }
        repeat(3) {
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 30L)
            }
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, cameraFrameIntervalMs = 33L, expectedCameraFrameIntervalMs = 33L)
            }
        }
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 30L)
        }
        assertEquals(1.0, policy.snapshot().observedLoadPressure, 0.001)
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, cameraFrameIntervalMs = 33L, expectedCameraFrameIntervalMs = 33L)
        }
        assertEquals(0.75, policy.snapshot().observedLoadPressure, 0.001)
        repeat(12) {
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 30L)
            }
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, cameraFrameIntervalMs = 33L, expectedCameraFrameIntervalMs = 33L)
            }
        }
        assertEquals(0.0, policy.snapshot().observedLoadPressure, 0.001)
    }

    @Test
    fun independentlyCapturedChannelsAtTheSameMillisecondDoNotRejectOneAnother() {
        val policy = AdaptiveInferencePacingPolicy()
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, uiDispatchDelayMs = 60L, uiDispatchBudgetMs = 30L)
        }
        val firstObservedAtMs = policy.snapshot().lastEndToEndMs!!
        completeWithLoad(policy) { _ ->
            InferenceLoadObservation(firstObservedAtMs, trackingProcessingMs = 90L, trackingBudgetMs = 30L)
        }
        assertEquals(2.0, policy.snapshot().observedLoadPressure, 0.001)
    }

    @Test
    fun staleWorkerCannotEarnLoadRecoveryFromOtherwiseHealthyFeatureDurations() {
        val policy = AdaptiveInferencePacingPolicy(maximumFreshFrameAgeMs = 100L)
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, uiDispatchDelayMs = 60L, uiDispatchBudgetMs = 30L)
        }
        repeat(8) {
            val ticket = checkNotNull(policy.tryStart(policy.snapshot().nextEligibleAtElapsedRealtimeMs))
            val completedAtMs = ticket.startedAtElapsedRealtimeMs + 101L
            assertTrue(policy.complete(ticket, completedAtMs, loadObservation =
                InferenceLoadObservation(completedAtMs, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 30L)))
        }
        assertEquals(1.0, policy.snapshot().observedLoadPressure, 0.001)
        assertEquals(0, policy.snapshot().loadRecoverySamples)
        assertEquals(8L, policy.snapshot().staleSamples)
    }

    @Test
    fun oldRepeatedFutureAndInvalidObservationsCannotErasePressure() {
        val policy = AdaptiveInferencePacingPolicy()
        var highObservedAtMs = 0L
        completeWithLoad(policy) { now ->
            highObservedAtMs = now
            InferenceLoadObservation(now, uiDispatchDelayMs = 60L, uiDispatchBudgetMs = 20L)
        }
        repeat(4) {
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(highObservedAtMs, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 20L)
            }
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now + 1L, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 20L)
            }
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now - 1_001L, uiDispatchDelayMs = 1L, uiDispatchBudgetMs = 20L)
            }
            completeWithLoad(policy) { now ->
                InferenceLoadObservation(now, uiDispatchDelayMs = -1L, uiDispatchBudgetMs = 0L)
            }
            completeNext(policy, 80L)
        }
        assertEquals(2.0, policy.snapshot().observedLoadPressure, 0.001)
        assertEquals(0, policy.snapshot().loadRecoverySamples)
    }

    @Test
    fun sessionResetClearsOldFeaturePressureWithoutLosingSingleFlightOwnership() {
        val policy = AdaptiveInferencePacingPolicy()
        completeWithLoad(policy) { now ->
            InferenceLoadObservation(now, uiDispatchDelayMs = Long.MAX_VALUE, uiDispatchBudgetMs = 1L)
        }
        val ticket = checkNotNull(policy.tryStart(policy.snapshot().nextEligibleAtElapsedRealtimeMs))
        policy.reset()
        assertNull(policy.tryStart(ticket.startedAtElapsedRealtimeMs + 5_000L))
        assertFalse(policy.complete(ticket, ticket.startedAtElapsedRealtimeMs + 100L))
        assertEquals(1.0, policy.snapshot().workHeadroomMultiplier, 0.001)
    }

    private fun completeWithLoad(
        policy: AdaptiveInferencePacingPolicy,
        observation: (Long) -> InferenceLoadObservation,
    ) {
        val startMs = policy.snapshot().nextEligibleAtElapsedRealtimeMs
        val ticket = checkNotNull(policy.tryStart(startMs))
        val finishMs = startMs + 80L
        assertTrue(policy.complete(ticket, finishMs, loadObservation = observation(finishMs)))
    }

    private fun completeNext(
        policy: AdaptiveInferencePacingPolicy,
        endToEndMs: Long,
        inferenceMs: Long? = endToEndMs,
        thermalThrottled: Boolean? = null,
    ): Long {
        val startedAtMs = policy.snapshot().nextEligibleAtElapsedRealtimeMs
        val ticket = checkNotNull(policy.tryStart(startedAtMs))
        val completedAtMs = startedAtMs + endToEndMs
        assertTrue(policy.complete(ticket, completedAtMs, inferenceMs, thermalThrottled))
        return completedAtMs
    }
}
