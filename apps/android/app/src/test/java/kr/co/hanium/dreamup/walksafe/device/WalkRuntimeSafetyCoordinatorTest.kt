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
    fun productionProfileDoesNotAuthorizeOutputWithoutApprovedTimingThresholds() {
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
        assertFalse(result.safetyOutputsAllowed)
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
            assertEquals(expectedCause.name, if (expectedCause == WalkRuntimeSafetyStopCause.EPOCH_MISMATCH) 0 else 1, callbacks.size)
            assertFalse(expectedCause.name, result.safetyOutputsAllowed)
        }

        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        coordinator.beginEpoch(EPOCH_1)
        coordinator.observe(observation(observedAtElapsedRealtimeMs = 1_000L))
        val clockRollback = coordinator.observe(
            observation(observedAtElapsedRealtimeMs = 999L),
        )
        assertFalse(WalkRuntimeSafetyStopCause.INVALID_TIME in clockRollback.causes)
        assertTrue(callbacks.isEmpty())
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
            WalkRuntimeSafetyDisposition.AWAITING_EVIDENCE,
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

    @Test
    fun freshResourceTicksDoNotRenewTheLastSensorEvidenceDeadline() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        val sensorEvidence = observation()
        coordinator.beginEpoch(sensorEvidence.epoch)
        assertTrue(coordinator.observe(sensorEvidence).safetyOutputsAllowed)

        // The real resource mapper carries no camera, GPS, TTS or frame timing evidence.
        val boundaryTick = resourceObservation(EPOCH_1, 1_050L, 1_100L)
        assertEquals(1_050L, boundaryTick.observedAtElapsedRealtimeMs)
        assertEquals(1_100L, boundaryTick.receivedAtElapsedRealtimeMs)
        val boundary = coordinator.observe(boundaryTick)
        assertEquals(EPOCH_1, boundary.epoch)
        assertEquals(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT, boundary.disposition)

        val expired = coordinator.observe(resourceObservation(EPOCH_1, 1_101L, 1_101L))
        assertEquals(EPOCH_1, expired.epoch)
        assertEquals(WalkRuntimeSafetyDisposition.AWAITING_EVIDENCE, expired.disposition)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.EVIDENCE_EXPIRED), expired.causes)
        assertFalse(expired.safetyOutputsAllowed)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun lateReceiptOfTheOriginalSensorObservationCannotRenewExpiredEvidence() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        val sensorEvidence = observation()
        coordinator.beginEpoch(sensorEvidence.epoch)
        assertTrue(coordinator.observe(sensorEvidence).safetyOutputsAllowed)
        val expired = coordinator.observe(resourceObservation(EPOCH_1, 1_101L, 1_110L))
        assertEquals(setOf(WalkRuntimeSafetyStopCause.EVIDENCE_EXPIRED), expired.causes)
        assertFalse(expired.safetyOutputsAllowed)

        // Only receipt changes: epoch, original source time and capture identity stay unchanged.
        val delayedSensorEvidence = sensorEvidence.copy(receivedAtElapsedRealtimeMs = 1_111L)
        assertEquals(sensorEvidence.epoch, delayedSensorEvidence.epoch)
        assertEquals(sensorEvidence.observedAtElapsedRealtimeMs, delayedSensorEvidence.observedAtElapsedRealtimeMs)
        assertEquals(sensorEvidence.frameCapturedAtElapsedRealtimeMs, delayedSensorEvidence.frameCapturedAtElapsedRealtimeMs)
        val delayed = coordinator.observe(delayedSensorEvidence)
        assertEquals(EPOCH_1, delayed.epoch)
        assertEquals(WalkRuntimeSafetyDisposition.AWAITING_EVIDENCE, delayed.disposition)
        assertEquals(setOf(WalkRuntimeSafetyStopCause.EVIDENCE_EXPIRED), delayed.causes)
        assertFalse(delayed.safetyOutputsAllowed)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun freshResourceTicksCannotSupplySensorEvidenceForANewEpoch() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        val oldSensorEvidence = observation()
        coordinator.beginEpoch(EPOCH_1)
        assertTrue(coordinator.observe(oldSensorEvidence).safetyOutputsAllowed)
        coordinator.beginEpoch(EPOCH_2)

        val resourceOnly = coordinator.observe(resourceObservation(EPOCH_2, 1_001L, 1_001L))
        assertEquals(EPOCH_2, resourceOnly.epoch)
        assertEquals(WalkRuntimeSafetyDisposition.AWAITING_EVIDENCE, resourceOnly.disposition)
        assertFalse(resourceOnly.safetyOutputsAllowed)
        listOf(
            oldSensorEvidence.copy(receivedAtElapsedRealtimeMs = 1_002L),
            resourceObservation(EPOCH_1, 1_003L, 1_003L),
        ).forEach { oldEpochEvidence ->
            val ignored = coordinator.observe(oldEpochEvidence)
            assertEquals(EPOCH_2, ignored.epoch)
            assertEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION, ignored.disposition)
            assertFalse(ignored.safetyOutputsAllowed)
        }
        assertFalse(coordinator.evaluate(1_003L).safetyOutputsAllowed)

        val current = coordinator.observe(observation(epoch = EPOCH_2, observedAtElapsedRealtimeMs = 1_050L))
        assertEquals(EPOCH_2, current.epoch)
        assertEquals(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT, current.disposition)
        assertTrue(current.safetyOutputsAllowed)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun retiredRuntimeIgnoresQueuedUnsafeResultsWithoutBlockingTheNextWalk() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        coordinator.beginEpoch(EPOCH_1)
        assertTrue(coordinator.observe(observation()).safetyOutputsAllowed)

        assertTrue(coordinator.retireEpoch(EPOCH_1))
        assertFalse(coordinator.retireEpoch(EPOCH_1))
        val late = coordinator.observe(observation(cameraTrusted = false, inferenceLatencyMs = 1_000L))
        assertEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION, late.disposition)
        assertEquals(null, late.epoch)
        val idle = coordinator.evaluate(2_000L)
        assertEquals(null, idle.epoch)
        assertFalse(idle.safetyOutputsAllowed)
        assertEquals(null, coordinator.latchedStopStatus())
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
        assertTrue(callbacks.isEmpty())

        assertEquals(
            WalkRuntimeSafetyDisposition.AWAITING_EVIDENCE,
            coordinator.beginEpoch(EPOCH_2).disposition,
        )
        assertFalse(coordinator.evaluate(2_000L).safetyOutputsAllowed)
        assertTrue(coordinator.observe(observation(epoch = EPOCH_2, observedAtElapsedRealtimeMs = 2_000L)).safetyOutputsAllowed)
        assertFalse(coordinator.retireEpoch(EPOCH_1))
        val old = coordinator.observe(observation(riskTrusted = false))
        assertEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION, old.disposition)
        assertEquals(EPOCH_2, old.epoch)
        assertTrue(coordinator.evaluate(2_000L).safetyOutputsAllowed)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun retirementRequiresTheExactEpochAndCannotTouchANewerGenerationOrClosedOwner() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = configuredCoordinator(callbacks)
        val newerEpoch = WalkRuntimeEpoch(EPOCH_1.walkSessionId, 1L)
        coordinator.beginEpoch(EPOCH_1)
        assertTrue(coordinator.observe(observation()).safetyOutputsAllowed)
        assertFalse(coordinator.retireEpoch(newerEpoch))
        assertFalse(coordinator.retireEpoch(EPOCH_2))
        assertTrue(coordinator.evaluate(1_000L).safetyOutputsAllowed)

        assertTrue(coordinator.retireEpoch(EPOCH_1))
        coordinator.beginEpoch(newerEpoch)
        assertTrue(coordinator.observe(observation(epoch = newerEpoch)).safetyOutputsAllowed)
        assertFalse(coordinator.retireEpoch(EPOCH_1))
        assertEquals(newerEpoch, coordinator.evaluate(1_000L).epoch)
        assertTrue(coordinator.evaluate(1_000L).safetyOutputsAllowed)
        coordinator.close()
        assertFalse(coordinator.retireEpoch(newerEpoch))
        assertFalse(coordinator.observe(observation(epoch = newerEpoch)).safetyOutputsAllowed)
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun retirementCannotEraseALatchedStopOrAcknowledgeItsPendingCleanup() {
        val callbacks = mutableListOf<WalkRuntimeSafetyStop>()
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val retries = mutableListOf<Runnable>()
        val exhausted = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(
            thresholdProfile = null,
            onSafeStopWithCompletion = { stop, completed ->
                callbacks += stop
                acknowledgements += completed
            },
            scheduleStopRetry = { retries += it; true },
            onStopDeliveryExhausted = exhausted::add,
        )
        coordinator.beginEpoch(EPOCH_1)
        coordinator.observe(observation(riskTrusted = false))
        val pending = coordinator.latchedStopStatus()
        assertFalse(coordinator.retireEpoch(EPOCH_1))
        assertEquals(pending, coordinator.latchedStopStatus())
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        assertEquals(1, callbacks.size)

        acknowledgements.single()(false)
        assertFalse(coordinator.retireEpoch(EPOCH_1))
        assertTrue(coordinator.stopCleanupBlocksNewWalk)
        retries.single().run()
        assertEquals(2, callbacks.size)
        acknowledgements.last()(true)
        val complete = coordinator.latchedStopStatus()
        assertTrue(checkNotNull(complete).cleanupComplete)
        assertFalse(coordinator.stopCleanupBlocksNewWalk)
        assertFalse(coordinator.retireEpoch(EPOCH_1))
        assertEquals(complete, coordinator.latchedStopStatus())
        assertTrue(exhausted.isEmpty())

        coordinator.beginEpoch(EPOCH_2)
        assertEquals(WalkRuntimeSafetyDisposition.NOT_CONFIGURED, coordinator.evaluate(2_000L).disposition)
        assertFalse(coordinator.retireEpoch(EPOCH_1))
        assertEquals(EPOCH_2, coordinator.evaluate(2_000L).epoch)
    }

    @Test
    fun cleanupStageQueriesFollowPendingAcknowledgementsAndRetainFailedRelease() {
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        assertFalse(checkpoints.allStagesComplete())
        assertFalse(checkpoints.hasUncertainStages())

        assertTrue(checkpoints.runStage("outputs") {})
        assertTrue(checkpoints.allStagesComplete())
        assertTrue(checkpoints.beginAsync("location_release"))
        assertFalse(checkpoints.allStagesComplete())
        assertFalse(checkpoints.hasUncertainStages())
        assertTrue(checkpoints.finishAsync("location_release", true))
        assertTrue(checkpoints.allStagesComplete())
        assertFalse(checkpoints.hasUncertainStages())
        assertFalse(checkpoints.finishAsync("location_release", false))
        assertTrue(checkpoints.allStagesComplete())
        assertFalse(checkpoints.hasUncertainStages())

        assertTrue(checkpoints.beginAsync("camera_release"))
        assertFalse(checkpoints.allStagesComplete())
        assertFalse(checkpoints.hasUncertainStages())
        assertTrue(checkpoints.finishAsync("camera_release", false))
        assertFalse(checkpoints.allStagesComplete())
        assertTrue(checkpoints.hasUncertainStages())
        assertFalse(checkpoints.finishAsync("camera_release", true))
        assertFalse(checkpoints.beginAsync("camera_release"))
        assertFalse(checkpoints.allStagesComplete())
        assertTrue(checkpoints.hasUncertainStages())
    }

    private fun resourceObservation(
        epoch: WalkRuntimeEpoch,
        observedAtElapsedRealtimeMs: Long,
        receivedAtElapsedRealtimeMs: Long,
    ) = WalkSessionDeviceResourceSnapshot(
        batteryNotLow = true,
        privateStorageAboveSystemLow = true,
        thermalBelowCritical = true,
        thermalThrottled = false,
        checkedAtElapsedRealtimeMs = WalkSessionResourceQuery.entries.associateWith {
            observedAtElapsedRealtimeMs
        },
    ).toRuntimeSafetyObservation(epoch, observedAtElapsedRealtimeMs, receivedAtElapsedRealtimeMs)

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
        cameraTrusted: Boolean? = true,
        depthTrusted: Boolean? = true,
        gpsTrusted: Boolean? = true,
        riskTrusted: Boolean? = true,
        ttsTrusted: Boolean? = true,
        batteryCritical: Boolean? = false,
        storageCritical: Boolean? = false,
        thermalCritical: Boolean? = false,
        frameCapturedAtElapsedRealtimeMs: Long? = observedAtElapsedRealtimeMs,
        inferenceLatencyMs: Long? = 0L,
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
