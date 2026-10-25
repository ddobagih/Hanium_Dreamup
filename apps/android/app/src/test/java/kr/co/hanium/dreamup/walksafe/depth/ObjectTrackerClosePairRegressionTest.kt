package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-20: clear parallel correspondence must not be lost to weaker neighboring candidates. */
class ObjectTrackerClosePairRegressionTest {
    @Test
    fun closeParallelPairKeepsPositionBoundIdsThroughReorderedDetectorCaptures() {
        val tracker = ObjectTracker()
        val first = tracker.updateWithAssignments(pair(0f), 1_000L)
        val ids = first.associate { it.sourceDetectionIndex to it.track.trackId }
        for (step in 1..3) {
            // Keep semantic source indices while reversing the detector's list ordering.
            val detections = pair(step * .02f).let { if (step % 2 == 1) it.reversed() else it }
            val next = tracker.updateWithAssignments(detections, 1_000L + step * 100L)
            assertEquals(ids, next.associate { it.sourceDetectionIndex to it.track.trackId })
            assertEquals(listOf(step + 1, step + 1), next.map { it.track.ageFrames })
            if (step >= 2) assertTrue(next.all { it.track.stable })
        }
    }

    @Test
    fun delayedClosePairUsesPrecedingCaptureEvidenceWithoutRewindingLaterFlow() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(pair(0f), 1_000L)
        val ids = first.associate { it.sourceDetectionIndex to it.track.trackId }
        assertEquals(2, tracker.applyTrackedObservations(first.map { assignment ->
            assignment.track.trackId to shape(.20f + assignment.sourceDetectionIndex * .15f + .02f)
        }, 1_050L).size)

        val delayed = tracker.updateSourceWithAssignments(pair(.04f).reversed(), 1_100L,
            pair(.06f).reversed(), 1_150L)
        assertEquals(ids, delayed.associate { it.sourceDetectionIndex to it.track.trackId })
        assertTrue(delayed.all { it.track.ageFrames == 2 })
        assertEquals(2, tracker.applyTrackedObservations(delayed.map { assignment ->
            assignment.track.trackId to shape(.20f + assignment.sourceDetectionIndex * .15f + .06f)
        }, 1_150L).size)
        val next = tracker.updateSourceWithAssignments(pair(.08f), 1_200L, pair(.10f), 1_250L)
        assertEquals(ids, next.associate { it.sourceDetectionIndex to it.track.trackId })
        assertTrue(next.all { it.track.ageFrames == 3 && it.track.stable })
    }

    @Test
    fun overlappingPairStillStartsNewIdsEvenWhenEachCostWinnerIsStrong() {
        val tracker = ObjectTracker()
        fun overlapPair(shift: Float) = listOf(.20f, .32f).mapIndexed { index, x ->
            IndexedObjectGeometry(index, shape(x + shift))
        }
        val oldIds = tracker.updateWithAssignments(overlapPair(0f), 1_000L).map { it.track.trackId }.toSet()
        val current = tracker.updateWithAssignments(overlapPair(.02f), 1_100L)

        assertEquals(2, current.size)
        assertTrue(current.none { it.track.trackId in oldIds })
        assertTrue(current.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    @Test
    fun strongCostsFromDifferentReferenceTimesDoNotManufactureASharedTrajectory() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(pair(0f), 1_000L)
        val ids = first.map { it.track.trackId }.toSet()
        val left = first.single { it.sourceDetectionIndex == 0 }.track
        val right = first.single { it.sourceDetectionIndex == 1 }.track
        assertEquals(1, tracker.applyTrackedObservations(
            listOf(left.trackId to shape(.22f)), 1_050L,
            budgetDeferredTrackIds = setOf(right.trackId),
        ).size)

        val current = tracker.updateSourceWithAssignments(pair(.02f), 1_100L, pair(.04f), 1_150L)
        assertEquals(2, current.size)
        assertTrue(current.none { it.track.trackId in ids })
        assertTrue(current.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    private fun pair(shift: Float) = listOf(.20f, .35f).mapIndexed { index, x ->
        IndexedObjectGeometry(index, shape(x + shift))
    }

    private fun shape(x: Float): ObjectGeometry {
        val bbox = RectNorm(x, .35f, .20f, .30f)
        return ObjectGeometry("person", .9f, bbox, bboxPolygon(bbox, erosionRatio = 0f), bbox.area,
            bbox.center, Point2(bbox.center.x, bbox.y + bbox.height))
    }
}
