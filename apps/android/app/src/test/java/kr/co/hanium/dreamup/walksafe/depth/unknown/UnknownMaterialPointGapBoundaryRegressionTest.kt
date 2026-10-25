package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import org.junit.Assert.*
import org.junit.Test

/** Returning an old opaque ID does not restore a trajectory invalidated during this depth gap. */
class UnknownMaterialPointGapBoundaryRegressionTest {
    @Test fun anOldPointCannotRestoreItsSpatialSeedAfterMissingOwnership() = assertBoundary { null }

    @Test fun anOldPointCannotRestoreItsSpatialSeedAfterADifferentPointOwnedTheGap() = assertBoundary { fixture ->
        fixture.point(1_400L).let { it.copy(point = it.point.copy(id = VisualMaterialPointId())) }
    }

    private fun assertBoundary(point: (UnknownProducerFixture) -> UnknownMaterialPointObservation?) {
        val fixture = UnknownProducerFixture(knownPhysicalPoint = true)
        val measured = (0..6).map { fixture.process(it * 200L).result.objects.single() }
        assertTrue(measured.all { it.depthAvailability == DepthAvailability.MEASURED })
        assertEquals(ObjectMovementDirection.STATIONARY, measured.last().motionEstimate.direction)
        val lost = fixture.process(1_400L, millimeters = null, pointEvidence = point(fixture))
        assertScalarPrediction(fixture, lost.result.objects.single(), 200L)
        assertNull("Current lost ownership first removes the spatial bound",
            lost.result.objects.single().prediction!!.rangeUpperBoundM)
        assertFalse(lost.result.observations.single().walkingSelection!!.show)

        val returned = fixture.process(1_450L, millimeters = null, pointEvidence = fixture.point(1_450L))
        val output = returned.result.objects.single()
        assertEquals(measured.last().trackId, output.trackId)
        assertScalarPrediction(fixture, output, 250L)
        assertNull("An old point ID cannot restore velocity without new independently measured depth",
            output.prediction!!.rangeUpperBoundM)
        assertFalse(returned.result.observations.single().walkingSelection!!.show)
        assertFalse(output.trackId in returned.result.retainedProximityRegionIds)
    }

    private fun assertScalarPrediction(fixture: UnknownProducerFixture, output: TrackedObjectDepth, ageMs: Long) {
        assertEquals(DepthAvailability.PREDICTED, output.depthAvailability)
        val prediction = requireNotNull(output.prediction)
        assertEquals(fixture.sourceMs(1_200L), output.depthObservedAtMs)
        assertEquals(fixture.sourceMs(1_200L), prediction.observedAtMs)
        assertEquals(ageMs, prediction.predictionAgeMs)
        assertEquals(2f, prediction.distanceM, .001f)
        assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
        assertNull(output.motionEstimate.observedAtMs)
        assertNull(output.riskDistanceM)
        assertNull(output.timeToCollisionMs)
        assertNull(output.userFacing.message)
    }
}
