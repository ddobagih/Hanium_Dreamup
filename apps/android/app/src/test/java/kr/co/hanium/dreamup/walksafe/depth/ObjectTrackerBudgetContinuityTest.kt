package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

class ObjectTrackerBudgetContinuityTest {
    private val detections = List(4) { DetectionCandidate("person", .95f, RectNorm(.12f + it * .18f, .35f, .13f, .30f)) }
    private val extractor = MaskPolygonExtractor()
    private val mapper = LetterboxCoordinateMapper(ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))

    @Test fun fullSourcesKeepAlternatingIdsAndWarningsWhileDeferredBoxesNeverReachCurrentDepth() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        val ids = Array(4) { mutableSetOf<String>() }
        val warningCounts = IntArray(4)
        repeat(10) { batch ->
            val source = key(1_000L + batch * 400L)
            val selected = setOf(0, 1, 2 + batch % 2)
            repeat(3) { frame ->
                val target = key(source.capturedAtElapsedRealtimeMs + 50L + frame * 50L)
                val output = process(pipeline, source, target, selected)
                assertEquals(3, output.size)
                for (item in output) {
                    val index = detections.indices.minBy { kotlin.math.abs(detections[it].bboxNorm.center.x + .01f - item.centerNorm.x) }
                    assertTrue(index in selected)
                    assertEquals(detections[index].bboxNorm.center.x + .01f, item.centerNorm.x, .001f)
                    assertEquals(batch + 1, item.trackAgeFrames)
                    assertEquals(target.frameId, item.frameId)
                    ids[index] += item.trackId
                    if (batch >= 2 && item.userFacing.message != null && item.trackStableMs >= 700L) warningCounts[index]++
                }
                assertEquals(4, tracker.activeTracks().size)
                val deferredIndex = (0..3).single { it !in selected }
                val held = tracker.activeTracks().minBy {
                    kotlin.math.abs(it.latestDetectionGeometry!!.centerNorm.x - detections[deferredIndex].bboxNorm.center.x)
                }
                assertEquals(source.frameId / 1_000_000L, held.lastSeenAtMs)
                assertEquals(0, held.missedFrames)
            }
        }
        assertEquals(listOf(1, 1, 1, 1), ids.map { it.size })
        assertEquals(listOf(24, 24, 12, 12), warningCounts.toList())
    }

    @Test fun delayedNewDetectorCaptureDoesNotRewindCurrentGeometryOrItsClock() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        val first = process(pipeline, key(1_000L), key(1_300L), setOf(0, 1, 2)).first()
        val next = process(pipeline, key(1_200L), key(1_400L), setOf(0, 1, 2)).first()
        assertEquals(first.trackId, next.trackId)
        val track = tracker.activeTracks().single { it.trackId == next.trackId }
        assertEquals(2, track.ageFrames)
        assertEquals(1_200L, track.lastDetectionAtMs)
        assertEquals(1_400L, track.lastSeenAtMs)
        assertEquals(.01f + detections[0].bboxNorm.center.x, track.latestGeometry!!.centerNorm.x, .001f)
    }

    @Test fun confirmedFlowFailureStillRequiresANewIdentityEvenIfLaterMarkedBudgetDeferred() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)
        val first = process(pipeline, key(1_000L), key(1_050L), setOf(0, 1, 2)).first()
        process(pipeline, key(1_000L), key(1_100L), setOf(1, 2), failed = setOf(0))
        process(pipeline, key(1_000L), key(1_150L), setOf(1, 2))
        assertTrue(process(pipeline, key(1_000L), key(1_200L), setOf(0, 1, 2)).none { it.trackId == first.trackId })
        val rediscovered = process(pipeline, key(1_300L), key(1_350L), setOf(0, 1, 2)).first()
        assertNotEquals(first.trackId, rediscovered.trackId)
        assertEquals(1, rediscovered.trackAgeFrames)
    }

    @Test fun deferredCrossingCannotInheritEitherAmbiguousIdentity() {
        val tracker = ObjectTracker()
        fun shapes(xs: List<Float>) = xs.mapIndexed { index, x ->
            IndexedObjectGeometry(index, extractor.extract(DetectionCandidate("person", .9f, RectNorm(x, .35f, .20f, .3f))))
        }
        val first = tracker.updateSourceWithAssignments(shapes(listOf(.20f, .60f)), 1_000L)
        val ids = first.map { it.track.trackId }.toSet()
        assertTrue(tracker.applyTrackedObservations(emptyList(), 1_050L, ids).isEmpty())
        val crossing = tracker.updateSourceWithAssignments(shapes(listOf(.38f, .42f)), 1_400L)
        assertTrue(crossing.none { it.track.trackId in ids })
        assertTrue(crossing.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    @Test fun expiredSourceGapAndRealDetectorAbsenceEndDeferredIdentity() {
        for (absent in listOf(false, true)) {
            val tracker = ObjectTracker()
            val shape = IndexedObjectGeometry(0, extractor.extract(detections[0]))
            val first = tracker.updateSourceWithAssignments(listOf(shape), 1_000L).single().track
            tracker.applyTrackedObservations(emptyList(), 1_050L, setOf(first.trackId))
            if (absent) tracker.updateSourceWithAssignments(emptyList(), 1_400L)
            val next = tracker.updateSourceWithAssignments(listOf(shape), if (absent) 1_800L else 2_501L).single().track
            assertNotEquals(first.trackId, next.trackId)
            assertEquals(1, next.ageFrames)
        }
    }

    @Test fun repeatedOrOlderSourceCannotAddSemanticConfirmation() {
        val tracker = ObjectTracker()
        val shape = IndexedObjectGeometry(0, extractor.extract(detections[0]))
        val first = tracker.updateSourceWithAssignments(listOf(shape), 1_000L).single().track
        assertTrue(tracker.updateSourceWithAssignments(listOf(shape), 1_000L).isEmpty())
        assertTrue(tracker.updateSourceWithAssignments(listOf(shape), 999L).isEmpty())
        assertEquals(1, first.ageFrames)
    }

    @Test fun continuousCurrentFlowKeepsIdentityWhenTheObjectMovedBeyondTheSourceMatchingRadius() {
        val tracker = ObjectTracker()
        fun shape(x: Float) = extractor.extract(DetectionCandidate("person", .9f, RectNorm(x, .35f, .13f, .3f)))
        val first = tracker.updateSourceWithAssignments(listOf(IndexedObjectGeometry(0, shape(.05f))), 1_000L).single().track
        repeat(7) { frame ->
            assertEquals(first, tracker.applyTrackedObservations(
                listOf(first.trackId to shape(.09f + frame * .04f)), 1_050L + frame * 50L).single())
        }
        val source = IndexedObjectGeometry(0, shape(.37f))
        val current = IndexedObjectGeometry(0, shape(.41f))
        val next = tracker.updateSourceWithAssignments(listOf(source), 1_400L, listOf(current), 1_450L).single().track
        assertEquals(first.trackId, next.trackId)
        assertEquals(2, next.ageFrames)
        assertEquals(next, tracker.applyTrackedObservations(listOf(next.trackId to current.geometry), 1_450L).single())
        assertEquals(.475f, next.latestGeometry!!.centerNorm.x, .001f)
    }

    @Test fun deferredCoverageKeepsTheDepthInformationClockWhenMotionSamplesAreCleared() {
        val tracker = ObjectTracker()
        val geometry = extractor.extract(detections[0])
        val shape = IndexedObjectGeometry(0, geometry)
        val track = tracker.updateSourceWithAssignments(listOf(shape), 1_000L).single().track
        tracker.recordDistance(track, 1f, DepthSource.ARCORE_RAW_DEPTH, .9f, 1_000L,
            depthObservationTimestampNs = 1_000_000_000L, requireIndependentDepthObservation = true)
        assertEquals(1, track.distanceHistory.size)
        tracker.applyTrackedObservations(emptyList(), 1_050L, setOf(track.trackId))
        assertTrue(track.distanceHistory.isEmpty())
        assertEquals(track.trackId, tracker.updateSourceWithAssignments(listOf(shape), 1_100L).single().track.trackId)
        tracker.applyTrackedObservations(listOf(track.trackId to geometry), 1_150L)
        tracker.recordDistance(track, 1f, DepthSource.ARCORE_RAW_DEPTH, .9f, 1_150L,
            depthObservationTimestampNs = 1_000_000_000L, requireIndependentDepthObservation = true)
        assertTrue(track.distanceHistory.isEmpty())
        assertNull(tracker.approachKinematics(track, 1f, DepthSource.ARCORE_RAW_DEPTH).approachSpeedMps)
    }

    private fun key(at: Long) = VisualFrameKey(1, at * 1_000_000L, at * 1_000_000L, at, 1)
    private fun process(pipeline: ObjectDepthRuntimePipeline, source: VisualFrameKey, target: VisualFrameKey,
                        selected: Set<Int>, failed: Set<Int> = emptySet()): List<TrackedObjectDepth> {
        val observations = detections.mapIndexed { index, original ->
            val tracked = index in selected
            VisualTrackingObservation(index, source, target,
                if (tracked) VisualTrackingStatus.TRACKED else VisualTrackingStatus.LOST,
                if (tracked) original.copy(bboxNorm = original.bboxNorm.copy(x = original.bboxNorm.x + .01f)) else null,
                if (tracked) 1f else 0f,
                if (tracked) null else if (index in failed) VisualTrackingFailure.TOO_FEW_FEATURES else VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED,
                null, 8, if (tracked) 8 else 0, null, false)
        }
        val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
            VisualTrackingResult(source, target, observations, VisualTrackingMetrics(0L, 0, 0L, false, 1, 0)),
            detections, true, target.capturedAtElapsedRealtimeMs))
        val depth = DepthFrameSnapshot(target.frameId, DepthImage16(100, 100, IntArray(10_000) { 1_000 }),
            ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
            rawDepthTimestampNs = target.cameraTimestampNs, cameraImageTimestampNs = target.cameraTimestampNs)
        return pipeline.processTrackedObservation(depth, observation, mapper, target.frameId, target.capturedAtElapsedRealtimeMs)
    }
}
