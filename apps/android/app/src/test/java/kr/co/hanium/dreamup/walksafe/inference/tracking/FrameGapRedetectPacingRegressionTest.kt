package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.inference.InferenceLoadObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-27 / S10-20: real tracker outputs drive the real pacing policy through Main's request condition.
 * A stationary estimator isolates frame admission and scheduling from image matching/native timing.
 * These synthetic clock gaps do not measure GC, thermal performance, UI delay or device latency.
 */
class FrameGapRedetectPacingRegressionTest {
    @Test
    fun exact120MsOnBothClocksTracksButEither121MsGapFailsBeforeMotionEstimation() {
        val config = VisualTrackingConfig()
        assertEquals(120L, config.maxFrameGapMs)
        for ((elapsedGap, cameraGap) in listOf(120L to 120L, 121L to 120L, 120L to 121L)) {
            val estimator = StationaryEstimator()
            val tracker = tracker(estimator)
            val source = offer(tracker, 0L, 0L)
            val target = offer(tracker, 1L, elapsedGap, cameraGap)
            val result = tracker.trackFrom(source, listOf(candidate()), target)
            val observation = result.observations.single()
            assertEquals(source, observation.detectorSourceKey)
            assertEquals(target, observation.trackedTargetKey)
            if (elapsedGap == 120L && cameraGap == 120L) {
                assertEquals(VisualTrackingStatus.TRACKED, observation.status)
                assertNull(observation.failure)
                assertEquals(candidate(), observation.geometry)
                assertFalse(result.redetectRequested)
                assertTrue(result.redetectReasons.isEmpty())
                assertEquals(1, estimator.advanceCalls)
                val pacing = completedPacing()
                assertFalse(requestFrom(result, pacing, target.capturedAtElapsedRealtimeMs))
                assertEquals(1_250L, pacing.snapshot().nextEligibleAtElapsedRealtimeMs)
                assertFalse(pacing.isDue(target.capturedAtElapsedRealtimeMs))
            } else {
                assertFrameGap(result)
                assertEquals("The gap must be rejected before advancing image features", 0, estimator.advanceCalls)
            }
        }
    }

    @Test
    fun frameGapSignalAdvancesNormalScheduleButKeepsCompletionCooldownAndSingleSlot() {
        val result = gapResult()
        assertFrameGap(result)
        // The completed work ended just before the gap was observed; its cooldown still applies.
        val pacing = completedPacing(completedAtMs = 1_100L)
        val normal = completedPacing(completedAtMs = 1_100L)
        assertEquals(1_250L, normal.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertTrue(requestFrom(result, pacing, 1_121L))
        val next = pacing.snapshot().nextEligibleAtElapsedRealtimeMs
        assertEquals(1_125L, next)
        assertTrue(next < normal.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertEquals(250L, pacing.snapshot().targetIntervalMs)
        assertFalse("A request changes scheduling, without admitting detector work", pacing.snapshot().inFlight)
        assertFalse(requestFrom(result, pacing, 1_122L))
        assertNull(pacing.tryStart(next - 1L))
        assertNull(normal.tryStart(next))
        val earlyTicket = checkNotNull(pacing.tryStart(next))
        assertTrue(pacing.ownsSlot(earlyTicket))
        assertEquals(next + 250L, pacing.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertFalse(requestFrom(result, pacing, next))
        assertNull(pacing.tryStart(next))
    }

    @Test
    fun defaultThreeObjectBudgetRequestsEarlierDetectionForTheFourthCandidate() {
        val tracker = tracker()
        val candidates = listOf(
            candidate(.1f, .1f), candidate(.6f, .1f),
            candidate(.1f, .6f), candidate(.6f, .6f),
        )
        assertEquals(3, VisualTrackingConfig().maxTrackedObjects)
        val source = offer(tracker, 0L, 0L)
        val exact = tracker.trackFrom(source, candidates, source, prioritySourceIndices = listOf(0, 1, 2, 3))
        assertEquals(4, exact.observations.count { it.status == VisualTrackingStatus.TRACKED })
        assertFalse(exact.redetectRequested)
        val target = offer(tracker, 1L, 100L)
        val result = tracker.trackFrom(source, candidates, target, prioritySourceIndices = listOf(0, 1, 2, 3))
        assertEquals(listOf(0, 1, 2, 3), result.observations.map { it.sourceIndex })
        assertEquals(3, result.observations.count { it.status == VisualTrackingStatus.TRACKED })
        val excluded = result.observations[3]
        assertEquals(VisualTrackingStatus.LOST, excluded.status)
        assertEquals(VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED, excluded.failure)
        assertNull(excluded.geometry)
        assertEquals(setOf(VisualRedetectReason.UNTRACKED_CANDIDATES), result.redetectReasons)
        val pacing = completedPacing()
        assertEquals(1_250L, pacing.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertTrue(requestFrom(result, pacing, target.capturedAtElapsedRealtimeMs))
        assertEquals(1_100L, pacing.snapshot().nextEligibleAtElapsedRealtimeMs)
        assertNull(pacing.tryStart(1_099L))
        assertTrue(pacing.ownsSlot(checkNotNull(pacing.tryStart(1_100L))))
    }

    @Test
    fun frameGapCannotStartASecondDetectorWhileItsPhysicalTaskStillOwnsTheTicket() {
        val result = gapResult()
        assertFrameGap(result)
        val pacing = AdaptiveInferencePacingPolicy()
        val running = checkNotNull(pacing.tryStart(START_MS))
        val before = pacing.snapshot()
        assertFalse(requestFrom(result, pacing, 1_121L))
        assertEquals(before, pacing.snapshot())
        assertTrue(pacing.ownsSlot(running))
        assertFalse(pacing.isDue(1_250L))
        assertNull("Even the normal deadline cannot overlap the active task", pacing.tryStart(1_250L))
        assertTrue(pacing.complete(running, 1_260L, inferenceDurationMs = 250L, thermalThrottled = false))
        assertFalse(pacing.snapshot().inFlight)
    }

    @Test
    fun frameGapRequestPreservesKnownThermalLoadFreshnessAndInvalidTimingHolds() {
        val result = gapResult()
        assertFrameGap(result)
        for (hold in listOf("thermal", "load", "freshness", "invalid timing")) {
            val pacing = AdaptiveInferencePacingPolicy(maximumFreshFrameAgeMs = if (hold == "freshness") 50L else 800L)
            val ticket = checkNotNull(pacing.tryStart(START_MS))
            val completed = pacing.complete(
                ticket, 1_100L,
                inferenceDurationMs = if (hold == "invalid timing") -1L else 80L,
                thermalThrottled = hold == "thermal",
                loadObservation = if (hold == "load") InferenceLoadObservation(
                    observedAtElapsedRealtimeMs = 1_090L,
                    depthProcessingMs = 40L,
                    depthBudgetMs = 20L,
                ) else null,
            )
            assertEquals(hold, hold != "invalid timing", completed)
            when (hold) {
                "thermal" -> assertTrue(pacing.snapshot().thermalHoldActive)
                "load" -> assertTrue(pacing.snapshot().observedLoadPressure > 0.0)
                "freshness" -> assertEquals(true, pacing.snapshot().overFreshnessBudget)
                else -> assertEquals(1L, pacing.snapshot().invalidSamples)
            }
            val before = pacing.snapshot()
            assertFalse(hold, requestFrom(result, pacing, 1_121L))
            assertEquals(hold, before, pacing.snapshot())
            assertNull(hold, pacing.tryStart(before.nextEligibleAtElapsedRealtimeMs - 1L))
        }
    }

    @Test
    fun shorterLaterGapsDoNotReviveTheLostBatchButAFreshDetectorBatchCanTrackAgain() {
        val tracker = tracker()
        val candidates = listOf(candidate())
        val source = offer(tracker, 0L, 0L)
        val gapTarget = offer(tracker, 1L, 121L)
        assertFrameGap(tracker.trackFrom(source, candidates, gapTarget))
        val freshSource = offer(tracker, 2L, 221L)
        assertFrameGap(tracker.trackFrom(source, candidates, freshSource))
        val fresh = tracker.trackFrom(freshSource, candidates, freshSource)
        assertEquals(VisualTrackingStatus.TRACKED, fresh.observations.single().status)
        assertFalse(fresh.redetectRequested)
        val target = offer(tracker, 3L, 271L)
        val recovered = tracker.trackFrom(freshSource, candidates, target)
        assertEquals(VisualTrackingStatus.TRACKED, recovered.observations.single().status)
        assertNull(recovered.observations.single().failure)
        assertEquals(freshSource, recovered.observations.single().detectorSourceKey)
        assertFalse(recovered.redetectRequested)
    }

    /** Same condition as MainActivity's trackingResult.redetectRequested -> requestEarlyStart. */
    private fun requestFrom(result: VisualTrackingResult, pacing: AdaptiveInferencePacingPolicy, nowMs: Long): Boolean =
        if (result.redetectRequested) pacing.requestEarlyStart(nowMs) else false

    private fun completedPacing(completedAtMs: Long = 1_060L) = AdaptiveInferencePacingPolicy().also { pacing ->
        val ticket = checkNotNull(pacing.tryStart(START_MS))
        assertTrue(pacing.complete(ticket, completedAtMs, inferenceDurationMs = 50L, thermalThrottled = false))
    }

    private fun gapResult(): VisualTrackingResult {
        val tracker = tracker()
        val source = offer(tracker, 0L, 0L)
        val target = offer(tracker, 1L, 121L)
        return tracker.trackFrom(source, listOf(candidate()), target)
    }

    private fun assertFrameGap(result: VisualTrackingResult) {
        val observation = result.observations.single()
        assertEquals(VisualTrackingStatus.LOST, observation.status)
        assertEquals(VisualTrackingFailure.FRAME_GAP, observation.failure)
        assertNull(observation.geometry)
        assertEquals(setOf(VisualRedetectReason.TRACK_LOST), result.redetectReasons)
        assertTrue(result.redetectRequested)
    }

    private fun tracker(estimator: StationaryEstimator = StationaryEstimator()) = InterFrameDetectionTracker(
        VisualTrackingConfig(), clockNanos = { 0L }, estimator = estimator,
    )

    private fun offer(tracker: InterFrameDetectionTracker, id: Long, elapsedMs: Long, cameraElapsedMs: Long = elapsedMs): VisualFrameKey {
        val key = VisualFrameKey(
            epoch = 1L, frameId = id, cameraTimestampNs = (START_MS + cameraElapsedMs) * 1_000_000L,
            capturedAtElapsedRealtimeMs = START_MS + elapsedMs, geometryVersion = 1L,
        )
        val frame = GrayTrackingFrame.copyOf(key, EDGE, EDGE, ByteArray(EDGE * EDGE) { 128.toByte() })
        assertTrue("Frame $id must be admitted", tracker.offerFrame(frame).accepted)
        return key
    }

    private fun candidate(x: Float = .4f, y: Float = .4f) = DetectionCandidate(
        className = "person", detectionConfidence = .87f, bboxNorm = RectNorm(x, y, .2f, .2f),
        polygonNorm = emptyList(), maskAreaNorm = .04f,
    )

    private class StationaryEstimator : VisualMotionEstimator {
        var advanceCalls = 0
            private set

        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate, budget: TrackingWorkBudget): List<VisualMotionFeature> =
            (0 until 6).map { index ->
                val box = detection.bboxNorm
                VisualMotionFeature(
                    ((box.x + box.width * (.2f + (index % 3) * .3f)) * frame.width).toInt(),
                    ((box.y + box.height * (.2f + (index / 3) * .6f)) * frame.height).toInt(),
                )
            }

        override fun advance(
            source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
            detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
            budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?,
        ): VisualMotionEstimate {
            advanceCalls++
            return VisualMotionEstimate(features, VisualAffineTransform(), quality = .95f, residual = 0f)
        }
    }

    private companion object {
        const val START_MS = 1_000L
        const val EDGE = 32
    }
}
