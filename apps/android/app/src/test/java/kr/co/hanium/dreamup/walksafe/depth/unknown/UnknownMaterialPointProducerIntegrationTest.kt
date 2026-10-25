package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.UnknownMaterialPointTestScene
import org.junit.Assert.*
import org.junit.Test

/** Connects actual producer output to the unchanged production Unknown depth pipeline. */
class UnknownMaterialPointProducerIntegrationTest {
    @Test fun previousEmptyMapCounterexampleNowReceivesRealFlowPointWithTheSameDepthCapture() {
        val fixture = Fixture()
        val rows = (0L..1_800L step 200L).map { fixture.process(it) }
        val latest = rows.last()
        assertEquals(ObjectMovementDirection.UNKNOWN, latest.withoutPoint.motionEstimate.direction)
        assertNull(latest.withoutPoint.motionEstimate.objectSpeedMps)
        assertEquals(ObjectMovementDirection.STATIONARY, latest.withPoint.motionEstimate.direction)
        assertEquals(0f, latest.withPoint.motionEstimate.objectSpeedMps!!, .0001f)
        assertEquals(1, rows.mapNotNull { it.point?.point?.id }.distinct().size)
        assertEquals(1, rows.map { it.withPoint.trackId }.distinct().size)
        assertTrue(rows.all { it.withPoint.depthAvailability == DepthAvailability.MEASURED })
        assertEquals(latest.withoutPoint.riskDistanceM!!, latest.withPoint.riskDistanceM!!, .001f)
        assertEquals(2f, latest.withPoint.riskDistanceM!!, .001f)
        assertEquals(fixture.scene.token(1_800L).cameraTimestampNs / 1_000_000L,
            latest.withPoint.motionEstimate.observedAtMs)
        assertEquals(fixture.scene.token(1_800L).cpuImageTimestampNs,
            latest.point!!.point.targetKey.cameraTimestampNs)
    }

    @Test fun actualMaskOwnershipLossRestartsMotionWhileKeepingMeasuredScalarDistance() {
        val fixture = Fixture()
        val warm = (0L..1_200L step 200L).map { fixture.process(it) }.last()
        assertEquals(ObjectMovementDirection.STATIONARY, warm.withPoint.motionEstimate.direction)
        val lost = fixture.process(1_400L, hole = 18 to 15)
        assertNull(lost.point)
        assertEquals(warm.withPoint.trackId, lost.withPoint.trackId)
        assertEquals(DepthAvailability.MEASURED, lost.withPoint.depthAvailability)
        assertEquals(2f, lost.withPoint.riskDistanceM!!, .001f)
        assertEquals(ObjectMovementDirection.UNKNOWN, lost.withPoint.motionEstimate.direction)
        assertNull(lost.withPoint.motionEstimate.objectSpeedMps)
        assertNull(fixture.process(1_600L).point)
        val fresh = fixture.process(1_800L)
        assertNotSame(warm.point!!.point.id, fresh.point!!.point.id)
        assertEquals(ObjectMovementDirection.UNKNOWN, fresh.withPoint.motionEstimate.direction)
        assertNull(fresh.withPoint.motionEstimate.objectSpeedMps)
    }

    @Test fun realLkPointLossDuringDepthGapKeepsScalarPredictionAndCannotRestoreOldSpatialBound() {
        val fixture = Fixture()
        val warm = (0L..1_200L step 200L).map { fixture.process(it) }.last()
        assertEquals(ObjectMovementDirection.STATIONARY, warm.withPoint.motionEstimate.direction)
        fixture.scene.flow.pointLostAt = 1_350L
        val lost = fixture.process(1_400L, millimeters = null)
        assertNull(lost.point)
        assertScalarGap(lost.withPoint, warm.withPoint.depthObservedAtMs!!, 200L)
        assertEquals(lost.withoutPoint.prediction!!.distanceM, lost.withPoint.prediction!!.distanceM, .001f)
        assertNull(lost.withPoint.prediction!!.rangeUpperBoundM)
        val recovered = fixture.process(1_425L, millimeters = null)
        assertNotSame(warm.point!!.point.id, recovered.point!!.point.id)
        assertScalarGap(recovered.withPoint, warm.withPoint.depthObservedAtMs!!, 225L)
        assertEquals(recovered.withoutPoint.prediction!!.distanceM, recovered.withPoint.prediction!!.distanceM, .001f)
        assertNull(recovered.withPoint.prediction!!.rangeUpperBoundM)
        assertFalse(lost.result.observations.single().walkingSelection!!.show)
        assertFalse(recovered.result.observations.single().walkingSelection!!.show)
        val expired = fixture.process(1_600L, millimeters = null)
        assertTrue(400L > lost.withPoint.prediction!!.horizonMs)
        assertEquals(DepthAvailability.UNAVAILABLE, expired.withoutPoint.depthAvailability)
        assertEquals(expired.withoutPoint.depthAvailability, expired.withPoint.depthAvailability)
        assertNull(expired.withPoint.prediction)
    }

    @Test fun stableMeasuredPointAllowsTheExistingBoundWithoutRefreshingItsCaptureDeadline() {
        val fixture = Fixture()
        val warm = (0L..1_200L step 200L).map { fixture.process(it) }.last()
        val gap = fixture.process(1_400L, millimeters = null)
        assertSame(warm.point!!.point.id, gap.point!!.point.id)
        assertScalarGap(gap.withPoint, warm.withPoint.depthObservedAtMs!!, 200L)
        assertNotNull(gap.withPoint.prediction!!.rangeUpperBoundM)
        assertNull(gap.withoutPoint.prediction!!.rangeUpperBoundM)
        val deadline = requireNotNull(DepthPredictionPresentation.validUntilMs(gap.withPoint.prediction!!,
            gap.result.token.capturedElapsedNs / 1_000_000L))
        assertNull(gap.result.forDisplayAt(deadline + 1L).objects.single().prediction)
    }

    private fun assertScalarGap(output: TrackedObjectDepth, observedAt: Long, ageMs: Long) {
        assertEquals(DepthAvailability.PREDICTED, output.depthAvailability)
        assertEquals(observedAt, output.depthObservedAtMs)
        assertEquals(observedAt, output.prediction!!.observedAtMs)
        assertEquals(ageMs, output.prediction!!.predictionAgeMs)
        assertEquals(2f, output.prediction!!.distanceM, .001f)
        assertEquals(ObjectMovementDirection.UNKNOWN, output.motionEstimate.direction)
        assertNull(output.timeToCollisionMs)
    }

    private class Fixture {
        val scene = UnknownMaterialPointTestScene()
        private var nowMs = 100_100L
        private var offeredUntil = -25L
        private val production = UnknownObjectDepthPipeline(elapsedRealtimeMs = { nowMs })
        private val oldEmptyMapPath = UnknownObjectDepthPipeline(elapsedRealtimeMs = { nowMs })
        fun process(at: Long, millimeters: Int? = 2_000, hole: Pair<Int, Int>? = null): Seen {
            scene.offerThrough(offeredUntil + 25L, at + 100L, step = 25L)
            offeredUntil = at + 100L
            val mask = scene.mask(hole = hole)
            val tracking = scene.consume(at, listOf(mask))
            val capture = scene.capture(at, millimeters)
            nowMs = capture.token.capturedElapsedNs / 1_000_000L + 100L
            val result = production.process(capture, capture.token, listOf(mask), nowMs,
                materialPointsByMaskIndex = tracking.materialPointsByMaskIndex)
            val old = oldEmptyMapPath.process(capture, capture.token, listOf(mask), nowMs)
            return Seen(result.objects.single(), old.objects.single(), tracking.materialPointsByMaskIndex[0], result)
        }
    }
    private data class Seen(val withPoint: TrackedObjectDepth, val withoutPoint: TrackedObjectDepth,
        val point: UnknownMaterialPointObservation?, val result: UnknownDepthFrameResult)
}
