package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-21: continuity thresholds are per class (FP-021), and an uncertain continuation starts a
 * new observation number (FP-019 design_rules[3]). A class flicker at the same box therefore
 * restarts identity instead of carrying one class's stability or distance history into another.
 *
 * S11-21 (11.txt #21): spatial identity and class are separated only inside the compatible vehicle
 * group (car, passenger_car, bus, truck: one spoken label "차량" and risk meaning). A flicker inside the
 * group keeps the spatial ID, stability and distance history and accumulates the class evidence.
 * Classes whose risk or report meaning differs (person/bicycle, normal/damaged tactile paving,
 * moving/abandoned e-scooter, motorcycle) still start a new ID.
 */
class ObjectTrackerClassChangeIdentityTest {
    @Test
    fun classFlickerAtTheSameBoxNeverCarriesStabilityOrDistanceHistory() {
        val tracker = ObjectTracker()
        var person: TrackState? = null
        (0 until 5).forEach { index ->
            val at = index * 100L
            person = tracker.update(listOf(shape("person")), at).single { it.missedFrames == 0 }
            assertTrue(tracker.recordDistance(requireNotNull(person), 3.0f - index * 0.1f,
                DepthSource.ARCORE_RAW_DEPTH, 0.9f, at))
        }
        val stablePerson = requireNotNull(person)
        assertTrue(stablePerson.stable)
        assertEquals(5, stablePerson.distanceHistory.size)

        val bicycle = tracker.update(listOf(shape("bicycle")), 500L).single { it.missedFrames == 0 }
        assertNotEquals(stablePerson.trackId, bicycle.trackId)
        assertEquals(1, bicycle.ageFrames)
        assertFalse(bicycle.stable)
        assertTrue(bicycle.distanceHistory.isEmpty())
        assertNull(tracker.approachKinematics(bicycle, 2.4f, DepthSource.ARCORE_RAW_DEPTH).timeToCollisionMs)
        // The former class is no longer observed; it cannot be matched to the other label.
        assertTrue(stablePerson.missedFrames > 0)

        val personAgain = tracker.update(listOf(shape("person")), 600L).single { it.missedFrames == 0 }
        assertNotEquals(stablePerson.trackId, personAgain.trackId)
        assertNotEquals(bicycle.trackId, personAgain.trackId)
        assertEquals(1, personAgain.ageFrames)
        assertFalse(personAgain.stable)
        assertTrue(personAgain.distanceHistory.isEmpty())
    }

    @Test
    fun normalAndDamagedTactileLabelsAreNeverJoinedIntoOneIdentity() {
        val tracker = ObjectTracker()
        val normal = (0 until 3).map { index ->
            tracker.update(listOf(shape("linear_tactile_paving")), index * 100L).single { it.missedFrames == 0 }
        }.last()
        assertTrue(normal.stable)
        val damaged = tracker.update(listOf(shape("damaged_linear_tactile_paving")), 300L)
            .single { it.missedFrames == 0 }
        assertNotEquals(normal.trackId, damaged.trackId)
        assertEquals(1, damaged.ageFrames)
        assertFalse(damaged.stable)
    }

    @Test
    fun aCompatibleVehicleClassFlickerKeepsTheSpatialIdStabilityAndDistanceHistory() {
        val tracker = ObjectTracker()
        var truck: TrackState? = null
        (0 until 4).forEach { index ->
            val at = index * 100L
            truck = tracker.update(listOf(shape("truck")), at).single { it.missedFrames == 0 }
            assertTrue(tracker.recordDistance(requireNotNull(truck), 6.0f - index * 0.2f, DepthSource.ARCORE_RAW_DEPTH, 0.9f, at))
        }
        val stableTruck = requireNotNull(truck)
        assertTrue(stableTruck.stable)
        val bus = tracker.update(listOf(shape("bus")), 400L).single { it.missedFrames == 0 }
        assertEquals(stableTruck.trackId, bus.trackId)
        assertEquals(5, bus.ageFrames)
        assertTrue(bus.stable)
        assertEquals(4, bus.distanceHistory.size)
        // Class evidence accumulates inside the group: four truck frames outweigh one bus frame.
        assertEquals("truck", bus.accumulatedClassName)
        (5 until 10).forEach { index -> tracker.update(listOf(shape("bus")), index * 100L) }
        assertEquals("bus", bus.accumulatedClassName)
    }

    @Test
    fun motorcycleAndEScooterStatesStayOutsideTheCompatibleGroup() {
        listOf("truck" to "motorcycle", "moving_e_scooter" to "abandoned_e_scooter", "car" to "person").forEach { (from, to) ->
            val tracker = ObjectTracker()
            val first = (0 until 3).map { index ->
                tracker.update(listOf(shape(from)), index * 100L).single { it.missedFrames == 0 }
            }.last()
            val next = tracker.update(listOf(shape(to)), 300L).single { it.missedFrames == 0 }
            assertNotEquals("$from -> $to", first.trackId, next.trackId)
            assertEquals(1, next.ageFrames)
        }
    }

    @Test
    fun pipelineOutputsKeepTheAccumulatedClassAcrossACompatibleFlicker() {
        val pipeline = ObjectDepthRuntimePipeline()
        val snapshot = DepthFrameSnapshot(frameTimestampNs = 1L, rawDepth = null, rawConfidence = null, fullDepth = null)
        val outputs = listOf("truck", "truck", "truck", "bus").mapIndexed { index, className ->
            pipeline.processPrealignedForTest(
                snapshot.copy(frameTimestampNs = index + 1L), index + 1L, index * 100L,
                listOf(DetectionCandidate(className, 0.9f, RectNorm(0.3f, 0.3f, 0.2f, 0.3f))),
            ).single()
        }
        assertEquals(1, outputs.map { it.trackId }.distinct().size)
        assertEquals(listOf("truck", "truck", "truck", "truck"), outputs.map { it.className })
    }

    private fun shape(className: String): ObjectGeometry =
        MaskPolygonExtractor().extract(DetectionCandidate(className, 0.9f, RectNorm(0.3f, 0.3f, 0.2f, 0.3f)))
}
