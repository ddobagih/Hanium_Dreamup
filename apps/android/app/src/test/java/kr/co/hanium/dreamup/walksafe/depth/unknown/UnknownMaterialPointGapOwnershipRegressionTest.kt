package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import org.junit.Assert.*
import org.junit.Test

/** Actual Unknown producer counter after a previously accepted, explicitly known point trajectory. */
class UnknownMaterialPointGapOwnershipRegressionTest {
    @Test fun aMissingCurrentPointCannotCarryThePreviousSpatialRangeBound() =
        assertLostOwnership { null }

    @Test fun aDifferentPointIdentityCannotCarryThePreviousSpatialRangeBound() =
        assertLostOwnership { it.point(1_400L).let { evidence ->
            evidence.copy(point = evidence.point.copy(id = VisualMaterialPointId()))
        } }

    @Test fun aPreviousCapturePointCannotCarryThePreviousSpatialRangeBound() =
        assertLostOwnership { it.point(1_350L) }

    @Test fun aPointOutsideTheCurrentActualMaskCannotCarryThePreviousSpatialRangeBound() =
        assertLostOwnership { it.point(1_400L).let { evidence ->
            evidence.copy(point = evidence.point.copy(imagePointNorm = Point2(.1f, .1f)))
        } }

    @Test fun theSameCurrentPointPreservesTheNormalBoundedPredictionAndItsOriginalDeadline() {
        val fixture = warm()
        val frame = fixture.process(1_400L, millimeters = null)
        val output = frame.result.objects.single()
        val prediction = requireNotNull(output.prediction)
        assertScalarPrediction(fixture, output)
        assertTrue(requireNotNull(prediction.rangeUpperBoundM) <= 3f)
        assertTrue(frame.result.observations.single().walkingSelection!!.show)
        assertTrue(output.trackId in frame.result.retainedProximityRegionIds)
        assertOriginalExpiry(frame)
    }

    @Test fun losingThePointCannotRenewTheScalarPredictionLifetime() {
        val fixture = warm()
        val frame = fixture.process(1_400L, millimeters = null, pointEvidence = null)
        assertScalarPrediction(fixture, frame.result.objects.single())
        assertOriginalExpiry(frame)
    }

    private fun assertLostOwnership(point: (UnknownProducerFixture) -> UnknownMaterialPointObservation?) {
        val fixture = warm()
        val frame = fixture.process(1_400L, millimeters = null, pointEvidence = point(fixture))
        val output = frame.result.objects.single()
        assertScalarPrediction(fixture, output)
        assertNull("The old point's velocity cannot bound a currently unowned point", output.prediction!!.rangeUpperBoundM)
        assertFalse(frame.result.observations.single().walkingSelection!!.show)
        assertFalse(output.trackId in frame.result.retainedProximityRegionIds)
    }

    private fun warm(): UnknownProducerFixture = UnknownProducerFixture(knownPhysicalPoint = true).also { fixture ->
        val outputs = (0..6).map { fixture.process(it * 200L).result.objects.single() }
        assertTrue(outputs.all { it.depthAvailability == DepthAvailability.MEASURED })
        assertEquals(1, outputs.map { it.trackId }.distinct().size)
        assertEquals(ObjectMovementDirection.STATIONARY, outputs.last().motionEstimate.direction)
        assertEquals(fixture.sourceMs(1_200L), outputs.last().motionEstimate.observedAtMs)
    }

    private fun assertScalarPrediction(fixture: UnknownProducerFixture, output: TrackedObjectDepth) {
        assertEquals(DepthAvailability.PREDICTED, output.depthAvailability)
        val prediction = requireNotNull(output.prediction)
        assertEquals(fixture.sourceMs(1_200L), output.depthObservedAtMs)
        assertEquals(fixture.sourceMs(1_200L), prediction.observedAtMs)
        assertEquals(200L, prediction.predictionAgeMs)
        assertEquals(2f, prediction.distanceM, .001f)
        assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
        assertNull(output.motionEstimate.observedAtMs)
        assertNull(output.timeToCollisionMs)
        assertNull(output.riskDistanceM)
        assertNull(output.userFacing.message)
    }

    private fun assertOriginalExpiry(frame: UnknownProducerFixture.Frame) {
        val output = frame.result.objects.single()
        val prediction = requireNotNull(output.prediction)
        val capturedAt = frame.result.token.capturedElapsedNs / 1_000_000L
        val deadline = requireNotNull(DepthPredictionPresentation.validUntilMs(prediction, capturedAt))
        assertEquals(prediction, frame.result.forDisplayAt(deadline).objects.single().prediction)
        val expired = frame.result.forDisplayAt(deadline + 1L)
        assertEquals(DepthAvailability.UNAVAILABLE, expired.objects.single().depthAvailability)
        assertNull(expired.objects.single().prediction)
        assertEquals(output.depthObservedAtMs, expired.objects.single().depthObservedAtMs)
        assertFalse(expired.observations.single().walkingSelection!!.show)
    }
}
