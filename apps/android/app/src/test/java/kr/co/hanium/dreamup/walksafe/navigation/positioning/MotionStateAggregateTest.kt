package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-045: non-sensitive motion-state aggregate for RAW diagnostics. */
class MotionStateAggregateTest {
    @Test
    fun syntheticStateSequenceProducesExactDurationsTransitionsReasonsAndLatencies() {
        val aggregate = MotionStateAggregate()
        listOf(
            snapshot(0L, UserMovementState.MOVING, UserMotionStateReason.STEP_OBSERVED, step = 0L),
            snapshot(1_000L, UserMovementState.UNKNOWN, UserMotionStateReason.INSUFFICIENT_EVIDENCE),
            // Quiet since 1_200 ms, STATIONARY published at 3_000 ms: entry latency 1_800 ms.
            snapshot(3_000L, UserMovementState.STATIONARY, UserMotionStateReason.STATIONARY_CONFIRMED, candidate = 1_200L),
            snapshot(4_000L, UserMovementState.STATIONARY, UserMotionStateReason.STATIONARY_CONFIRMED, candidate = 1_200L),
            // A step at 4_700 ms is published as MOVING at 5_000 ms: release latency 300 ms.
            snapshot(5_000L, UserMovementState.MOVING, UserMotionStateReason.STEP_OBSERVED, step = 4_700L),
        ).forEach { assertTrue(aggregate.observe(it, collectionAllowed = true)) }

        val summary = requireNotNull(aggregate.summary())
        assertEquals(1_000L, summary.movingMs)
        assertEquals(2_000L, summary.unknownMs)
        assertEquals(2_000L, summary.stationaryMs)
        assertEquals(5, summary.snapshotCount)
        assertEquals(1, summary.unknownSnapshotCount)
        assertEquals(3, summary.transitionCount)
        assertEquals(
            mapOf(
                UserMotionStateReason.STEP_OBSERVED to 2,
                UserMotionStateReason.INSUFFICIENT_EVIDENCE to 1,
                UserMotionStateReason.STATIONARY_CONFIRMED to 2,
            ),
            summary.reasonCounts,
        )
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 0), summary.stationaryEntryLatencyHistogram)
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0), summary.stationaryReleaseLatencyHistogram)
    }

    @Test
    fun withoutConsentOrOutsideAnActiveWalkNothingIsAggregated() {
        val aggregate = MotionStateAggregate()
        assertFalse(aggregate.observe(snapshot(0L, UserMovementState.MOVING), collectionAllowed = false))
        assertFalse(aggregate.observe(snapshot(1_000L, UserMovementState.MOVING), collectionAllowed = false))
        assertNull(aggregate.summary())
    }

    @Test
    fun aPausedGapIsNeverCountedAndEarlierCountsAreDropped() {
        val aggregate = MotionStateAggregate()
        aggregate.observe(snapshot(0L, UserMovementState.MOVING), collectionAllowed = true)
        aggregate.observe(snapshot(1_000L, UserMovementState.MOVING), collectionAllowed = true)
        // PAUSED (or consent withdrawn): the caller reports collection not allowed.
        aggregate.observe(snapshot(2_000L, UserMovementState.MOVING), collectionAllowed = false)
        aggregate.observe(snapshot(60_000L, UserMovementState.MOVING), collectionAllowed = true)
        aggregate.observe(snapshot(61_000L, UserMovementState.MOVING), collectionAllowed = true)

        val summary = requireNotNull(aggregate.summary())
        assertEquals(1_000L, summary.movingMs)
        assertEquals(2, summary.snapshotCount)
    }

    @Test
    fun anExpiredStateCountsAsUnobservedAndANewGenerationIsNotAnInterval() {
        val aggregate = MotionStateAggregate()
        aggregate.observe(snapshot(0L, UserMovementState.MOVING, expiresAfterMs = 500L), collectionAllowed = true)
        aggregate.observe(snapshot(2_000L, UserMovementState.MOVING), collectionAllowed = true)
        aggregate.observe(snapshot(3_000L, UserMovementState.STATIONARY, generation = 2L), collectionAllowed = true)
        aggregate.observe(
            snapshot(3_500L, UserMovementState.STATIONARY, UserMotionStateReason.INVALID_TIME),
            collectionAllowed = true,
        )

        val summary = requireNotNull(aggregate.summary())
        assertEquals(2_000L, summary.unknownMs)
        assertEquals(0L, summary.movingMs)
        assertEquals(0L, summary.stationaryMs)
        assertEquals(0, summary.transitionCount)
        assertEquals(1, summary.reasonCounts[UserMotionStateReason.INVALID_TIME])
    }

    @Test
    fun windowsMergeByAdditionAndTheWindowResetKeepsTheBoundaryInterval() {
        val aggregate = MotionStateAggregate()
        aggregate.observe(snapshot(0L, UserMovementState.MOVING), collectionAllowed = true)
        aggregate.observe(snapshot(1_000L, UserMovementState.MOVING), collectionAllowed = true)
        val first = requireNotNull(aggregate.summary())
        aggregate.resetWindow()
        aggregate.observe(snapshot(1_500L, UserMovementState.MOVING), collectionAllowed = true)
        val second = requireNotNull(aggregate.summary())
        assertEquals(500L, second.movingMs)
        assertEquals(1_500L, first.plus(second).movingMs)
        assertEquals(3, first.plus(second).snapshotCount)
    }

    @Test
    fun theSummaryTypeCarriesNoLocationSpeedOrSensorField() {
        val source = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/MotionStateAggregate.kt",
        ).readText()
        val fields = source.substringAfter("data class MotionStateSummary(").substringBefore(") {")
        listOf("latitude", "longitude", "speed", "heading", "acceleration", "gyroscope", "bearing", "accuracy")
            .forEach { assertFalse(it, fields.contains(it, ignoreCase = true)) }
    }

    private fun snapshot(
        at: Long,
        movement: UserMovementState,
        reason: UserMotionStateReason = UserMotionStateReason.GNSS_MOVEMENT,
        step: Long? = null,
        candidate: Long? = null,
        generation: Long = 1L,
        expiresAfterMs: Long = 2_000L,
    ) = UserMotionStateSnapshot(
        generation = generation,
        movement = movement,
        locomotion = UserLocomotionState.UNKNOWN,
        deviceRotation = UserDeviceRotationState.UNKNOWN,
        reason = reason,
        stateSinceElapsedRealtimeMs = at,
        evaluatedAtElapsedRealtimeMs = at,
        expiresAtElapsedRealtimeMs = at + expiresAfterMs,
        motionObservedAtElapsedRealtimeMs = null,
        speedObservedAtElapsedRealtimeMs = null,
        stepObservedAtElapsedRealtimeMs = step,
        stationaryCandidateSinceElapsedRealtimeMs = candidate,
        zeroVelocityUpdateAllowed = false,
    )
}
