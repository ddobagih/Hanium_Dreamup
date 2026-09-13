package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTrackerCurrentObservationTest {
    @Test
    fun currentImageUpdatesDoNotManufactureDetectorConfirmation() {
        val tracker = ObjectTracker()
        val track = tracker.updateWithAssignments(listOf(IndexedObjectGeometry(7, shape())), 0L).single().track
        for (at in 1L..30L) {
            assertEquals(track, tracker.applyTrackedObservations(listOf(track.trackId to shape()), at * 33L).single())
        }
        assertEquals(1, track.ageFrames)
        assertFalse(track.stable)
        assertEquals(0L, track.lastDetectionAtMs)
        assertEquals(990L, track.lastSeenAtMs)

        tracker.updateWithAssignments(listOf(IndexedObjectGeometry(9, shape())), 1_000L)
        tracker.updateWithAssignments(listOf(IndexedObjectGeometry(2, shape())), 1_100L)
        assertEquals(3, track.ageFrames)
        assertTrue(track.stable)
        tracker.applyTrackedObservations(listOf(track.trackId to shape()), 1_133L)
        assertEquals(3, track.ageFrames)
        assertEquals(1_100L, track.lastDetectionAtMs)
    }

    @Test
    fun assignmentsPreserveSourceIndicesAcrossReorderedDetectionsAndPrefixes() {
        val tracker = ObjectTracker(trackIdPrefix = "visual-track-")
        val first = tracker.updateWithAssignments(
            listOf(IndexedObjectGeometry(3, shape(0.2f)), IndexedObjectGeometry(8, shape(0.7f))), 0L,
        ).associate { it.sourceDetectionIndex to it.track.trackId }
        val next = tracker.updateWithAssignments(
            listOf(IndexedObjectGeometry(5, shape(0.7f)), IndexedObjectGeometry(1, shape(0.2f))), 500L,
        ).associate { it.sourceDetectionIndex to it.track.trackId }

        assertEquals(first[3], next[1])
        assertEquals(first[8], next[5])
        assertTrue(first.values.all { it.startsWith("visual-track-") })
        assertEquals("track-1", ObjectTracker().update(listOf(shape()), 0L).single().trackId)
    }

    @Test
    fun missingDuplicateAndWrongClassIdsCannotReviveLostGeometry() {
        listOf("missing", "duplicate", "class").forEach { failure ->
            val tracker = ObjectTracker()
            val track = tracker.update(listOf(shape()), 0L).single()
            val observations = when (failure) {
                "missing" -> emptyList()
                "duplicate" -> listOf(track.trackId to shape(), track.trackId to shape())
                else -> listOf(track.trackId to shape().copy(className = "bicycle"))
            }
            assertTrue(tracker.applyTrackedObservations(observations, 100L).isEmpty())
            assertTrue(track.missedFrames > 0)
            assertTrue(tracker.applyTrackedObservations(listOf(track.trackId to shape()), 200L).isEmpty())
            val replacement = tracker.updateWithAssignments(listOf(IndexedObjectGeometry(0, shape())), 300L).single().track
            assertNotEquals(track.trackId, replacement.trackId)
            assertEquals(1, replacement.ageFrames)
        }
    }

    @Test
    fun regressedCurrentClockEndsContinuityUntilAnotherDetectorObservation() {
        val tracker = ObjectTracker()
        val track = tracker.update(listOf(shape()), 1_000L).single()
        assertTrue(tracker.applyTrackedObservations(listOf(track.trackId to shape()), 999L).isEmpty())
        assertTrue(tracker.applyTrackedObservations(listOf(track.trackId to shape()), 1_100L).isEmpty())
        val replacement = tracker.updateWithAssignments(listOf(IndexedObjectGeometry(0, shape())), 1_200L).single().track
        assertNotEquals(track.trackId, replacement.trackId)
    }

    @Test
    fun ambiguousSourceIndicesDoNotCreateAssignments() {
        val tracker = ObjectTracker()
        val assignments = tracker.updateWithAssignments(
            listOf(IndexedObjectGeometry(2, shape(0.2f)), IndexedObjectGeometry(2, shape(0.7f))), 0L,
        )
        assertTrue(assignments.isEmpty())
        assertTrue(tracker.activeTracks().isEmpty())
    }

    private fun shape(x: Float = 0.4f): ObjectGeometry {
        val box = RectNorm(x, 0.35f, 0.15f, 0.3f)
        return ObjectGeometry(
            className = "person", detectionConfidence = 0.9f, bboxNorm = box,
            polygonNorm = bboxPolygon(box, erosionRatio = 0f), maskAreaNorm = box.area,
            centerNorm = Point2(x + 0.075f, 0.5f), bottomContactNorm = Point2(x + 0.075f, 0.65f),
        )
    }
}
