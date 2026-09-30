package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-11 (originals/11.txt:120-124): depth age is counted from the first independent observation,
 * reprojection is a separate flag, and display, risk guidance and TTC have different allowances.
 */
class DepthAgeBudgetTest {
    private val detection = DetectionCandidate("person", 0.9f, RectNorm(0.35f, 0.35f, 0.30f, 0.30f))
    private val mapper = LetterboxCoordinateMapper(ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))

    @Test
    fun eachUseHasItsOwnAllowanceFromTheOriginalObservation() {
        val fresh = visualSequence { it.cameraTimestampNs }
        assertEquals(false, fresh.depthReprojected)
        val observedAt = requireNotNull(fresh.depthObservedAtMs)
        listOf(DepthUse.PROXIMITY_DISPLAY, DepthUse.RISK_GUIDANCE, DepthUse.TIME_TO_COLLISION).forEach {
            assertTrue(it.name, DepthAgeBudget.admits(it, fresh, observedAt))
        }
        // 900 ms later the same observation may still be shown, but no longer guides.
        assertTrue(DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, fresh, observedAt + 900L))
        assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, fresh, observedAt + 900L))
        assertTrue(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, fresh, observedAt + 800L))
        assertFalse(DepthAgeBudget.admits(DepthUse.PROXIMITY_DISPLAY, fresh, observedAt + 1_201L))
        // No metric distance: nothing is admitted.
        val pseudo = fresh.copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH, riskDistanceM = null)
        DepthUse.values().forEach { assertFalse(it.name, DepthAgeBudget.admits(it, pseudo, observedAt)) }
    }

    @Test
    fun pastFutureAndRepeatedRawAreMarkedReprojectedGiveNoTtcAndCannotExtendTheGuidanceLease() {
        val fresh = visualSequence { it.cameraTimestampNs }
        assertNotNull(fresh.timeToCollisionMs)
        // Past Raw (300 ms old) under a new camera frame.
        val past = visualSequence { key(it.frameId / 1_000_000L - 300L).cameraTimestampNs }
        assertEquals(true, past.depthReprojected)
        assertNull(past.timeToCollisionMs)
        assertFalse(DepthAgeBudget.admits(DepthUse.TIME_TO_COLLISION, past, past.timestampMs))
        // Its guidance lease runs from the old observation (the new camera frame does not extend it).
        assertEquals(key(2_240L).capturedAtElapsedRealtimeMs - 300L + DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
            past.sourceValidUntilElapsedRealtimeMs)
        assertTrue(requireNotNull(past.sourceValidUntilElapsedRealtimeMs) < requireNotNull(fresh.sourceValidUntilElapsedRealtimeMs))
        // Future-stamped Raw is reprojected too: shown and used for guidance, never a TTC.
        val future = visualSequence { key(it.frameId / 1_000_000L + 100L).cameraTimestampNs }
        assertEquals(true, future.depthReprojected)
        assertNull(future.timeToCollisionMs)
        assertTrue(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, future, future.timestampMs))
        // Repeated Raw: the previous frame's depth again.
        val repeated = visualSequence { key(it.frameId / 1_000_000L - 100L).cameraTimestampNs }
        assertEquals(true, repeated.depthReprojected)
        assertNull(repeated.timeToCollisionMs)
    }

    private fun key(atMs: Long) = VisualFrameKey(7L, atMs * 1_000_000L, atMs * 1_000_000L + 400_000L, atMs + 50_000L, 3L)

    private fun observation(source: VisualFrameKey, target: VisualFrameKey) = requireNotNull(
        CurrentTrackedFrameObservation.fromTrackingResult(
            VisualTrackingResult(source, target, listOf(VisualTrackingObservation(
                sourceIndex = 0, detectorSourceKey = source, trackedTargetKey = target,
                status = VisualTrackingStatus.TRACKED, geometry = detection, trackingQuality = 0.7f, failure = null,
                translationNorm = Point2(0f, 0f), originalFeatureCount = 8, survivingFeatureCount = 8,
                residualPx = 0f, polygonConstrained = false,
            )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
            listOf(detection), true, target.capturedAtElapsedRealtimeMs,
        ),
    )

    /** An object approaching at 1 m/s; the last frame's Raw timestamp is chosen by [lastDepth]. */
    private fun visualSequence(lastDepth: (VisualFrameKey) -> Long): TrackedObjectDepth {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        var last: TrackedObjectDepth? = null
        (0..12).forEach { index ->
            val target = key(1_040L + index * 100L)
            val source = key(1_000L + (index / 5) * 500L)
            val depthTimestampNs = if (index == 12) lastDepth(target) else target.cameraTimestampNs
            val snapshot = DepthFrameSnapshot(
                frameTimestampNs = target.frameId,
                rawDepth = DepthImage16(100, 100, IntArray(10_000) { 4_000 - index * 100 }),
                rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
                fullDepth = null,
                rawDepthTimestampNs = depthTimestampNs,
                rawConfidenceTimestampNs = depthTimestampNs,
                cameraImageTimestampNs = target.cameraTimestampNs,
            )
            last = pipeline.processTrackedObservation(
                snapshot, observation(source, target), mapper, target.frameId, target.capturedAtElapsedRealtimeMs,
            ).single()
        }
        return requireNotNull(last)
    }
}
