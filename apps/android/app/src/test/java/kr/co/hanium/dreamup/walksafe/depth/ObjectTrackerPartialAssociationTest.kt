package kr.co.hanium.dreamup.walksafe.depth

import java.util.Random
import kr.co.hanium.dreamup.walksafe.PrimaryTrackingPriorityPolicy
import kr.co.hanium.dreamup.walksafe.UprightCameraImage
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

class ObjectTrackerPartialAssociationTest {
    @Test
    fun alternatingSelectionPreservesClearAnchorsWithoutResolvingTheRemainingAmbiguousMatch() {
        val tracker = ObjectTracker()
        val anchoredIds = mutableMapOf<Int, String>()
        var previousReplacementId: String? = null
        repeat(8) { batch ->
            val replacementIndex = if (batch % 2 == 0) 2 else 3
            val indices = listOf(replacementIndex, 1, 0)
            val assigned = tracker.updateWithAssignments(
                indices.map { IndexedObjectGeometry(it, shape(.12f + it * .18f, .13f)) },
                batch * 400L,
            ).associate { it.sourceDetectionIndex to it.track }

            for (index in 0..1) {
                val track = assigned.getValue(index)
                assertEquals(anchoredIds.getOrPut(index) { track.trackId }, track.trackId)
                assertEquals(batch + 1, track.ageFrames)
                assertEquals(batch >= 2, track.stable)
            }
            val replacement = assigned.getValue(replacementIndex)
            assertNotEquals(previousReplacementId, replacement.trackId)
            assertEquals(1, replacement.ageFrames)
            assertFalse(replacement.stable)
            previousReplacementId = replacement.trackId
        }
    }

    @Test
    fun reducedSelectionPreservesAHighOverlapAnchorWithOnlyCenterDistanceCompetitors() {
        val tracker = ObjectTracker()
        val initial = tracker.updateWithAssignments(
            listOf(IndexedObjectGeometry(0, shape(.20f)), IndexedObjectGeometry(1, shape(.45f))), 0L,
        ).associate { it.sourceDetectionIndex to it.track }
        val next = tracker.updateWithAssignments(listOf(IndexedObjectGeometry(4, shape(.21f))), 400L).single().track

        assertEquals(initial.getValue(0).trackId, next.trackId)
        assertEquals(2, next.ageFrames)
        assertEquals(1, initial.getValue(1).missedFrames)
    }

    @Test
    fun partialAnchorWithAnActualOverlapCompetitorDoesNotInheritEitherIdentity() {
        val tracker = ObjectTracker()
        val originalIds = tracker.update(listOf(shape(.20f), shape(.37f)), 0L).map { it.trackId }.toSet()
        val next = tracker.update(listOf(shape(.20f), shape(.80f)), 400L).filter { it.missedFrames == 0 }

        assertTrue(next.none { it.trackId in originalIds })
        assertTrue(next.all { it.ageFrames == 1 && !it.stable })
    }

    @Test
    fun clearPartialAnchorDoesNotResolveAnUnrelatedCrossing() {
        val tracker = ObjectTracker()
        val first = tracker.updateWithAssignments(
            listOf(IndexedObjectGeometry(0, shape(.01f, .10f)),
                IndexedObjectGeometry(1, shape(.30f)), IndexedObjectGeometry(2, shape(.70f))), 0L,
        ).associate { it.sourceDetectionIndex to it.track.trackId }
        val next = tracker.updateWithAssignments(
            listOf(IndexedObjectGeometry(0, shape(.01f, .10f)),
                IndexedObjectGeometry(1, shape(.45f)), IndexedObjectGeometry(2, shape(.55f))), 400L,
        ).associate { it.sourceDetectionIndex to it.track }

        assertEquals(first.getValue(0), next.getValue(0).trackId)
        for (index in 1..2) {
            assertFalse(next.getValue(index).trackId in first.values)
            assertFalse(next.getValue(index).stable)
        }
    }

    @Test
    fun alternatingDepthPriorityKeepsCurrentTrackWarningCandidatesAndFeedback() {
        val pixels = ByteArray(192 * 192).also { Random(0x571a).nextBytes(it) }
        val visual = InterFrameDetectionTracker(
            VisualTrackingConfig(maxFeaturesPerObject = 8, backend = VisualTrackingBackend.PATCH_DIAGNOSTIC),
            clockNanos = { 0L },
        )
        val current = ObjectDepthRuntimePipeline(tracker = ObjectTracker(), rawDepthMotionOnly = true)
        val frozen = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val feedback = WalkSafeFeedbackPolicy()
        val detections = List(4) { index ->
            DetectionCandidate("person", .95f,
                UprightCameraImage.toSensor(RectNorm(.12f + index * .18f, .35f, .13f, .3f), 0))
        }
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(192, 192, 192, 192, 1f, 0f, 0f), ImageSize(192, 192))
        fun key(at: Long) = VisualFrameKey(1, at * 1_000_000L, at * 1_000_000L, at, 1)
        fun offer(key: VisualFrameKey) {
            assertTrue(visual.offerFrame(GrayTrackingFrame.copyOf(key, 192, 192, pixels)).accepted)
        }
        fun depth(key: VisualFrameKey, batch: Int): DepthFrameSnapshot {
            val distances = intArrayOf(700, 800, if (batch % 2 == 0) 1000 else 1150, if (batch % 2 == 0) 1150 else 1000)
            val data = IntArray(192 * 192) { pixel ->
                val x = (pixel % 192) / 192f
                val index = detections.indexOfFirst { x >= it.bboxNorm.x && x < it.bboxNorm.x + it.bboxNorm.width }
                if (index < 0) 8000 else distances[index]
            }
            return DepthFrameSnapshot(key.frameId, DepthImage16(192, 192, data),
                ConfidenceImage8(192, 192, ByteArray(192 * 192) { 255.toByte() }), null,
                rawDepthTimestampNs = key.cameraTimestampNs, cameraImageTimestampNs = key.cameraTimestampNs)
        }
        var warningCandidates = 0
        var actions = 0
        var anchoredIds = emptyList<String>()
        repeat(14) { batch ->
            val source = key(1_000L + batch * 400L)
            offer(source)
            val frozenOutput = frozen.process(depth(source, batch), source.frameId,
                source.capturedAtElapsedRealtimeMs, detections, mapper = mapper)
            val priority = PrimaryTrackingPriorityPolicy.sourceIndices(detections, frozenOutput, source.frameId, 50L)
            repeat(7) { frame ->
                val target = key(source.capturedAtElapsedRealtimeMs + 50L + frame * 50L)
                offer(target)
                val tracked = visual.trackFrom(source, detections, target, prioritySourceIndices = priority)
                assertEquals(listOf(0, 1, if (batch % 2 == 0) 2 else 3),
                    tracked.observations.filter { it.status == VisualTrackingStatus.TRACKED }.map { it.sourceIndex })
                val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                    tracked, detections, true, target.capturedAtElapsedRealtimeMs))
                val output = current.processTrackedObservation(depth(target, batch), observation, mapper,
                    target.frameId, target.capturedAtElapsedRealtimeMs)
                val anchors = output.sortedBy { it.centerNorm.x }.take(2)
                if (anchoredIds.isEmpty()) anchoredIds = anchors.map { it.trackId }
                assertEquals(anchoredIds, anchors.map { it.trackId })
                assertTrue(anchors.all { it.trackAgeFrames == batch + 1 })
                warningCandidates += output.count {
                    it.userFacing.message != null && it.trackAgeFrames >= 3 && it.trackStableMs >= 700L
                }
                if (feedback.evaluateCandidates(output.mapNotNull { it.toFeedbackCandidate() }, true,
                        target.capturedAtElapsedRealtimeMs) != null) actions++
            }
        }
        // All three current objects stabilize, including the alternating coverage object.
        assertEquals(252, warningCandidates)
        assertTrue(actions > 0)
    }

    private fun shape(x: Float, width: Float = .20f): ObjectGeometry {
        val box = RectNorm(x, .35f, width, .3f)
        return ObjectGeometry(className = "person", detectionConfidence = .9f, bboxNorm = box,
            polygonNorm = bboxPolygon(box, erosionRatio = 0f), maskAreaNorm = box.area,
            centerNorm = Point2(x + width / 2, .5f), bottomContactNorm = Point2(x + width / 2, .65f))
    }
}
