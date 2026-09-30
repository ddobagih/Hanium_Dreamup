package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

/** Drives the real depth producer (tracker → pipeline → candidate) into the final feedback gate. */
class S11UrgentFeedbackProducerProbeTest {
    private val side = 100
    private val mapper = LetterboxCoordinateMapper(ModelInputTransform(side, side, side, side, 1f, 0f, 0f), ImageSize(side, side))

    @Test fun aSingleNearDetectionAndRepeatedDetectorSourceCannotManufactureFastEvidence() {
        val single = produce("single-", listOf(800), 200L).single()
        assertEquals(MessageLevel.STOP, single.userFacing.messageLevel)
        assertNull(WalkSafeFeedbackPolicy().evaluate(single.toFeedbackCandidate(), true, single.timestampMs + 50_000L))
        val repeated = produce("repeated-", listOf(2700, 2100, 1500, 900), 200L, repeatSource = true).last()
        assertEquals(1, repeated.trackAgeFrames)
        assertNull(repeated.timeToCollisionMs)
        assertNull(WalkSafeFeedbackPolicy().evaluate(repeated.toFeedbackCandidate(), true, repeated.timestampMs + 50_000L))
    }

    @Test fun currentProducerUrgencySurvivesInitialSelectionConversionAndFinalQueue() {
        val later = produce("later-", listOf(1700, 1400, 1100, 800), 300L).last()
        val sooner = produce("sooner-", listOf(3600, 2700, 1800, 900), 300L, detectionConfidence = .99f).last()
        assertEquals(MessageLevel.STOP, later.userFacing.messageLevel)
        assertEquals(MessageLevel.STOP, sooner.userFacing.messageLevel)
        assertTrue(sooner.confidence.finalScore < later.confidence.finalScore)
        assertTrue(sooner.riskDistanceM!! > later.riskDistanceM!!)
        assertTrue(sooner.timeToCollisionMs!! < later.timeToCollisionMs!!)
        val outputs = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(later, sooner))
        assertEquals(sooner.trackId, outputs.first().trackId)
        val candidates = outputs.map { requireNotNull(it.toFeedbackCandidate()) }
        // The production coordinator also orders by level/confidence before handing over.
        val coordinatorOrder = candidates.sortedWith(compareByDescending<FeedbackCandidate> { it.level.ordinal }.thenByDescending { it.confidence })
        assertEquals(later.trackId, coordinatorOrder.first().trackId)
        val action = WalkSafeFeedbackPolicy().evaluateCandidates(coordinatorOrder, true, sooner.timestampMs + 50_000L)
        println("S32 later=${later.timeToCollisionMs}/${later.riskDistanceM}/${later.confidence.finalScore} sooner=${sooner.timeToCollisionMs}/${sooner.riskDistanceM}/${sooner.confidence.finalScore} selected=${action?.trackId}")
        assertEquals(sooner.trackId, action?.trackId)
    }

    @Test fun currentProducerSameStopProximityWorseningReplaysBeforeTrackCooldown() {
        val outputs = produce("worse-", listOf(2400, 2250, 2100, 1950, 1800, 1650, 1500, 1350, 1200), 300L)
        val policy = WalkSafeFeedbackPolicy()
        val deliveries = mutableListOf<TrackedObjectDepth>()
        for (output in outputs) {
            val now = output.timestampMs + 50_000L
            val action = policy.evaluate(output.toFeedbackCandidate(), true, now)
            println("S33 at=${output.timestampMs} confidence=${output.confidence.finalScore} ttc=${output.timeToCollisionMs} distance=${output.riskDistanceM} action=${action?.level}")
            if (action != null) {
                assertEquals(MessageLevel.STOP, action.level)
                assertTrue(policy.claimFeedbackDelivery(output.trackId, now, now))
                assertTrue(policy.confirmFeedbackDelivery(output.trackId, now, now))
                deliveries += output
            }
        }
        assertEquals(2, deliveries.size)
        assertEquals(deliveries[0].trackId, deliveries[1].trackId)
        assertTrue(deliveries[1].timestampMs - deliveries[0].timestampMs < 2_500L)
        assertTrue(deliveries[0].riskDistanceM!! > 1.2f)
        assertTrue(deliveries[1].riskDistanceM!! <= 1.2f)
    }

    private fun produce(prefix: String, depths: List<Int>, interval: Long,
                        repeatSource: Boolean = false, detectionConfidence: Float = 1f): List<TrackedObjectDepth> {
        val pipeline = ObjectDepthRuntimePipeline(tracker = ObjectTracker(trackIdPrefix = prefix))
        val detection = DetectionCandidate("person", detectionConfidence, RectNorm(.1f, .1f, .8f, .8f))
        return depths.mapIndexed { index, mm ->
            val at = 1_000L + index * interval
            val target = key(at)
            val source = if (repeatSource) key(1_000L) else target
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                VisualTrackingResult(source, target, listOf(VisualTrackingObservation(
                    0, source, target, VisualTrackingStatus.TRACKED, detection, 1f, null,
                    Point2(0f, 0f), 8, 8, 0f, false,
                )), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
                listOf(detection), true, target.capturedAtElapsedRealtimeMs,
            ))
            val snapshot = DepthFrameSnapshot(target.frameId,
                DepthImage16(side, side, IntArray(side * side) { mm }),
                ConfidenceImage8(side, side, ByteArray(side * side) { 255.toByte() }), null,
                rawDepthTimestampNs = target.cameraTimestampNs,
                cameraImageTimestampNs = target.cameraTimestampNs,
                rawConfidenceTimestampNs = target.cameraTimestampNs)
            pipeline.processTrackedObservation(snapshot, observation, mapper, target.frameId,
                target.capturedAtElapsedRealtimeMs).single()
        }
    }
    private fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L, at * 1_000_000L + 400_000L, at + 50_000L, 3L)
}
