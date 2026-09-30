package kr.co.hanium.dreamup.walksafe.depth

import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-25: acquisition failures reach the runtime depth gate with cause-specific stop rules. */
class DepthAcquisitionFailureSeverityTest {
    @Test
    fun arCoreFailuresMapToTransientTrackingOrFatalSeverity() {
        fun severityOf(error: Exception) = depthAcquisitionFailureReason(error).severity
        assertEquals(DepthAcquisitionFailureSeverity.TRANSIENT, severityOf(NotYetAvailableException()))
        assertEquals(DepthAcquisitionFailureSeverity.TRANSIENT, severityOf(DeadlineExceededException()))
        assertEquals(DepthAcquisitionFailureSeverity.NOT_TRACKING, severityOf(NotTrackingException()))
        assertEquals(DepthAcquisitionFailureSeverity.FATAL, severityOf(ResourceExhaustedException()))
        assertEquals(DepthAcquisitionFailureSeverity.FATAL, severityOf(IllegalStateException()))
        assertEquals(DepthAcquisitionFailureSeverity.TRANSIENT, DepthAcquisitionFailureReason.PAIR_MISMATCH.severity)
        assertEquals(
            setOf(DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED, DepthAcquisitionFailureReason.ILLEGAL_STATE),
            DepthAcquisitionFailureReason.entries.filter { it.stopsImmediately }.toSet(),
        )
    }

    @Test
    fun countersHandOutTheMostSevereReasonOncePerCheck() {
        val counters = DepthAcquisitionFailureCounters()
        assertNull(counters.consumeMostSevereSinceLastCheck())
        counters.record(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE)
        counters.record(DepthAcquisitionStage.FULL_DEPTH, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED)
        counters.record(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.NOT_TRACKING)
        assertEquals(DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED, counters.consumeMostSevereSinceLastCheck())
        // The next frame starts clean: an old fatal failure never stops a later frame.
        assertNull(counters.consumeMostSevereSinceLastCheck())
        counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.DEADLINE_EXCEEDED)
        counters.record(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.NOT_TRACKING)
        assertEquals(DepthAcquisitionFailureReason.NOT_TRACKING, counters.consumeMostSevereSinceLastCheck())
        // Consuming does not erase the bounded diagnostic counts.
        assertEquals(1, counters.count(DepthAcquisitionStage.FULL_DEPTH, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED))
    }

    @Test
    fun oneNotYetAvailableFrameIsTemporaryUncertaintyWithoutStoppingTheSession() {
        val missing = assess(timestampNs = 200L, nowMs = 1_033L, valid = false,
            failure = DepthAcquisitionFailureReason.NOT_YET_AVAILABLE)
        assertFalse(missing.outputAllowed)
        assertFalse(missing.shouldHandleLoss)
        assertNull(missing.fatalAcquisitionFailure)
    }

    @Test
    fun transientFailurePersistingPastTheExistingFreshnessLimitStops() {
        val persisted = assess(timestampNs = 400L, nowMs = 3_000L, valid = false,
            failure = DepthAcquisitionFailureReason.DEADLINE_EXCEEDED)
        assertFalse(persisted.outputAllowed)
        assertTrue(persisted.shouldHandleLoss)
        assertNull(persisted.fatalAcquisitionFailure)
        val tracking = assess(timestampNs = 300L, nowMs = 1_200L, valid = false,
            failure = DepthAcquisitionFailureReason.NOT_TRACKING)
        assertFalse(tracking.outputAllowed)
        assertFalse(tracking.shouldHandleLoss)
    }

    @Test
    fun resourceExhaustionOrSessionErrorStopsAtOnceEvenWithFreshValidSamples() {
        for (reason in listOf(DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED, DepthAcquisitionFailureReason.ILLEGAL_STATE)) {
            val fatal = assess(timestampNs = 200L, nowMs = 1_033L, valid = true, failure = reason)
            assertFalse(reason.name, fatal.outputAllowed)
            assertFalse(reason.name, fatal.refreshesLastValidTime)
            assertTrue(reason.name, fatal.shouldHandleLoss)
            assertEquals(reason, fatal.fatalAcquisitionFailure)
        }
    }

    @Test
    fun noFailureKeepsTheExistingAssessment() {
        val legacy = assessRuntimeDepthFrame(200L, 100L, 1_033L, 1_000L, true, 2_000L)
        assertEquals(legacy, assess(timestampNs = 200L, nowMs = 1_033L, valid = true, failure = null))
        assertTrue(legacy.outputAllowed)
    }

    private fun assess(timestampNs: Long, nowMs: Long, valid: Boolean, failure: DepthAcquisitionFailureReason?) =
        assessRuntimeDepthFrame(
            frameTimestampNanos = timestampNs,
            previousFrameTimestampNanos = 100L,
            observedAtElapsedRealtimeMs = nowMs,
            lastValidFrameAtElapsedRealtimeMs = 1_000L,
            samplesAreValid = valid,
            staleTimeoutMs = 2_000L,
            acquisitionFailure = failure,
        )
}
