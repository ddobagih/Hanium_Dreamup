package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.*
import org.junit.Test

class ObjectTrackerHistoryEncapsulationTest {
    @Test
    fun retainedViewsFollowAcceptedFramesAndKeepExistingHistoryCaps() {
        val track = TrackState("track", "person", 0L)
        val boxes = track.bboxHistory
        val centers = track.centerHistory
        val polygons = track.polygonHistory
        val visual = track.visualTrendHistory
        val distances = track.distanceHistory
        val confidences = track.confidenceHistory
        val geometry = geometry()

        repeat(20) { index ->
            val at = index * 100L
            track.markSeen(geometry, at, 3)
            assertTrue(track.recordDistance(observation(at), 1.2f))
        }

        assertSame(boxes, track.bboxHistory)
        assertSame(centers, track.centerHistory)
        assertSame(polygons, track.polygonHistory)
        assertSame(visual, track.visualTrendHistory)
        assertSame(distances, track.distanceHistory)
        assertSame(confidences, track.confidenceHistory)
        assertEquals(12, boxes.size)
        assertEquals(12, centers.size)
        assertEquals(8, polygons.size)
        assertEquals((4..19).map { it * 100L }, visual.map { it.timestampMs })
        assertEquals((8..19).map { it * 100L }, distances.map { it.timestampMs })
        assertEquals(List(12) { .9f }, confidences)
    }

    @Test
    fun castingViewsOrTheirIteratorsCannotAppendReplaceOrRemoveOwnerHistory() {
        val track = TrackState("track", "person", 1_000L)
        val geometry = geometry()
        track.markSeen(geometry, 1_000L, 3)
        val distance = observation(1_000L)
        assertTrue(track.recordDistance(distance, 1.2f))

        assertUnmodifiable(track.bboxHistory, geometry.bboxNorm)
        assertUnmodifiable(track.centerHistory, geometry.centerNorm)
        assertUnmodifiable(track.polygonHistory, geometry.polygonNorm)
        assertUnmodifiable(track.visualTrendHistory, track.visualTrendHistory.single())
        assertUnmodifiable(track.distanceHistory, distance)
        assertUnmodifiable(track.confidenceHistory, .1f)
        assertEquals(listOf(distance), track.distanceHistory)
        assertEquals(listOf(.9f), track.confidenceHistory)
        assertEquals(1, track.ageFrames)
    }

    @Test
    fun nestedPolygonHistoryViewsDoNotExposeMutablePointLists() {
        val track = TrackState("track", "person", 1_000L)
        val geometry = geometry()
        val points = geometry.polygonNorm.toList()
        track.markSeen(geometry, 1_000L, 3)

        assertUnmodifiable(track.polygonHistory.single(), Point2(.1f, .1f))
        assertUnmodifiable(track.visualTrendHistory.single().polygonNorm, Point2(.1f, .1f))
        assertEquals(points, track.polygonHistory.single())
        assertEquals(points, track.visualTrendHistory.single().polygonNorm)
        assertEquals(points, geometry.polygonNorm)
    }

    @Test
    fun sourceOrderingRotationAndMetricResetUpdateTheSameReadViews() {
        val track = TrackState("track", "person", 1_000L)
        val geometry = geometry()
        val boxes = track.bboxHistory
        val visual = track.visualTrendHistory
        val distances = track.distanceHistory
        val confidences = track.confidenceHistory
        track.markSeen(geometry, 1_000L, 3)
        assertTrue(track.recordDistance(observation(1_000L), 1.2f))
        assertTrue(track.markTracked(geometry, 1_200L))
        track.markSourceSeen(geometry, 1_100L, 3)
        assertEquals(listOf(1_000L, 1_200L), visual.map { it.timestampMs })
        assertEquals(1_200L, track.lastSeenAtMs)
        assertEquals(1_100L, track.lastDetectionAtMs)

        track.markSeen(geometry.copy(imageQuarterTurns = 1), 1_300L, 3)
        assertEquals(listOf(geometry.bboxNorm), boxes)
        assertEquals(listOf(1_300L), visual.map { it.timestampMs })
        assertEquals(listOf(1_000L), distances.map { it.timestampMs })
        assertEquals(listOf(.9f), confidences)
        assertFalse(track.resetMetricAndSpatialHistoryPreservingTrackId(1_299L))
        assertEquals(1, distances.size)
        assertTrue(track.resetMetricAndSpatialHistoryPreservingTrackId(1_300L))
        assertTrue(distances.isEmpty())
        assertTrue(confidences.isEmpty())
        assertEquals(listOf(geometry.bboxNorm), boxes)
        assertEquals(listOf(1_300L), visual.map { it.timestampMs })
        assertEquals("track", track.trackId)
    }

    private fun <T> assertUnmodifiable(view: List<T>, replacement: T) {
        val before = view.toList()
        val mutable = view as MutableList<T>
        assertBlocked { mutable.add(replacement) }
        assertBlocked { mutable[0] = replacement }
        assertBlocked { mutable.removeAt(0) }
        assertBlocked { mutable.clear() }
        assertBlocked { mutable.subList(0, 1).clear() }
        val iterator = mutable.listIterator()
        iterator.next()
        assertBlocked { iterator.remove() }
        assertBlocked { iterator.set(replacement) }
        assertBlocked { iterator.add(replacement) }
        assertEquals(before, view)
    }

    private fun assertBlocked(mutate: () -> Unit) {
        try {
            mutate()
            fail("History must be modified only through TrackState")
        } catch (_: UnsupportedOperationException) {
            // Java collection mutation and Kotlin mutable casts share this protection.
        }
    }

    private fun observation(at: Long) = DistanceObservation(at, 3f, DepthSource.ARCORE_RAW_DEPTH, .9f)

    private fun geometry(): ObjectGeometry {
        val box = RectNorm(.4f, .35f, .2f, .3f)
        return ObjectGeometry(
            className = "person", detectionConfidence = .9f, bboxNorm = box,
            polygonNorm = bboxPolygon(box, erosionRatio = 0f).toMutableList(),
            maskAreaNorm = box.area, centerNorm = box.center,
            bottomContactNorm = Point2(box.center.x, box.y + box.height),
        )
    }
}
