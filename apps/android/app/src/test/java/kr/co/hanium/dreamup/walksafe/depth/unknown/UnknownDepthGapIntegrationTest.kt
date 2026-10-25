package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import org.junit.Assert.*
import org.junit.Test

/** Exercises frozen images, mask sampling, identity association and depth state together. */
class UnknownDepthGapIntegrationTest {
    @Test fun missingMaterialPointKeepsScalarPredictionButCannotInventARadialDisplayBound() {
        val fixture = UnknownProducerFixture()
        (0..6).forEach { fixture.process(it * 200L) }
        val frame = fixture.process(1_400L, millimeters = null)
        val output = frame.result.objects.single()
        assertEquals(DepthAvailability.PREDICTED, output.depthAvailability)
        assertEquals(2f, requireNotNull(output.prediction).distanceM, .001f)
        assertNull(output.prediction!!.rangeUpperBoundM)
        assertFalse(frame.result.observations.single().walkingSelection!!.show)
        assertNonMetric(output)
    }

    @Test fun wrongCaptureRotationOrPointOutsideTheActualMaskCannotSeedSpatialPrediction() {
        for (invalid in listOf("capture", "rotation", "mask", "image", "geometry")) {
            val fixture = UnknownProducerFixture(knownPhysicalPoint = true)
            (0..6).forEach { index ->
                val at = index * 200L
                val evidence = fixture.point(at)
                val rejected = when (invalid) {
                    "capture" -> fixture.point(at - 50L)
                    "rotation" -> evidence.copy(imageQuarterTurns = 1)
                    "mask" -> evidence.copy(point = evidence.point.copy(imagePointNorm = Point2(.1f, .1f)))
                    "image" -> evidence.copy(point = evidence.point.copy(targetKey =
                        evidence.point.targetKey.copy(cameraTimestampNs = evidence.point.targetKey.cameraTimestampNs + 1L)))
                    else -> evidence.copy(token = fixture.token(at).let { token ->
                        kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken(
                            token.source, token.sessionEpoch, token.frameId, token.cameraTimestampNs,
                            token.cpuImageTimestampNs, token.capturedElapsedNs, "different-geometry", token.width, token.height)
                    })
                }
                val measured = fixture.process(at, pointEvidence = rejected).result.objects.single()
                assertEquals(invalid, DepthAvailability.MEASURED, measured.depthAvailability)
                assertEquals(invalid, 2f, measured.riskDistanceM!!, .001f)
                assertEquals(invalid, ObjectMovementDirection.UNKNOWN, measured.motionEstimate.direction)
            }
            val frame = fixture.process(1_400L, millimeters = null, pointEvidence = null)
            assertNull(invalid, frame.result.objects.single().prediction!!.rangeUpperBoundM)
            assertFalse(invalid, frame.result.observations.single().walkingSelection!!.show)
        }
    }

    @Test fun duplicatedPointIdentityAcrossDistinctMasksKeepsDistanceWithoutForgingMotion() {
        val fixture = UnknownProducerFixture()
        var clock = 100_050L
        val pipeline = UnknownObjectDepthPipeline(elapsedRealtimeMs = { clock })
        val id = VisualMaterialPointId()
        val masks = listOf(fixture.rectangle(4, 8, 18, 24), fixture.rectangle(22, 8, 36, 24))
        (0..6).forEach { index ->
            val at = index * 200L
            val token = fixture.token(at)
            val capture = requireNotNull(FrozenUnknownDepthCapture.freeze(token, fixture.snapshot(at),
                fixture.matrix(), token.frameId))
            val sample = fixture.point(at)
            val evidence = mapOf(
                0 to sample.copy(point = sample.point.copy(id = id, imagePointNorm = Point2(.275f, .5f))),
                1 to sample.copy(point = sample.point.copy(id = id, imagePointNorm = Point2(.725f, .5f))),
            )
            clock = token.capturedElapsedNs / 1_000_000L + 50L
            val outputs = pipeline.process(capture, token, masks, clock, materialPointsByMaskIndex = evidence).objects
            assertEquals(2, outputs.size)
            outputs.forEach { output ->
                assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
                assertEquals(2f, output.riskDistanceM!!, .001f)
                assertEquals(ObjectMovementDirection.UNKNOWN, output.motionEstimate.direction)
            }
        }
    }

    @Test fun completeDepthGapPreservesIdentityAndSourceClockThenExpiresAndRecovers() {
        val fixture = UnknownProducerFixture(knownPhysicalPoint = true)
        val measured = (0..6).map { fixture.process(it * 200L).result.objects.single() }
        assertTrue(measured.all { it.depthAvailability == DepthAvailability.MEASURED })
        assertEquals(1, measured.map { it.trackId }.distinct().size)
        val last = measured.last()
        val gapFrame = fixture.process(1_400L, millimeters = null)
        assertNull(gapFrame.capture.snapshot.rawDepth)
        assertNull(gapFrame.capture.snapshot.fullDepth)
        assertNull(gapFrame.capture.depth)
        assertNull(gapFrame.capture.fullDepth)
        val gap = gapFrame.result.objects.single()
        assertEquals(last.trackId, gap.trackId)
        assertEquals(DepthAvailability.PREDICTED, gap.depthAvailability)
        assertEquals(last.depthObservedAtMs, gap.depthObservedAtMs)
        val prediction = requireNotNull(gap.prediction)
        assertEquals(last.timestampMs, prediction.observedAtMs)
        assertEquals(200L, prediction.predictionAgeMs)
        assertTrue(prediction.horizonMs in 200L..600L)
        assertTrue(prediction.errorBoundM in 0f..0.5f)
        assertEquals(2f, prediction.distanceM, 0.001f)
        assertNotNull(prediction.rangeUpperBoundM)
        assertTrue(requireNotNull(prediction.rangeUpperBoundM) <= 3f)
        assertNonMetric(gap)
        assertTrue(gapFrame.result.observations.single().walkingSelection!!.show)
        assertFalse(gapFrame.result.observations.single().walkingSelection!!.warningCandidate)
        assertTrue(gap.trackId in gapFrame.result.retainedProximityRegionIds)

        val later = fixture.process(1_450L, millimeters = null).result.objects.single()
        assertEquals(prediction.observedAtMs, later.depthObservedAtMs)
        assertEquals(250L, requireNotNull(later.prediction).predictionAgeMs)
        assertEquals(prediction.horizonMs, later.prediction!!.horizonMs)
        assertNonMetric(later)
        val expiredFrame = fixture.process(1_850L, millimeters = null)
        val expired = expiredFrame.result.objects.single()
        assertEquals(last.trackId, expired.trackId)
        assertEquals(DepthAvailability.UNAVAILABLE, expired.depthAvailability)
        assertNull(expired.prediction)
        assertNonMetric(expired)
        assertFalse(expiredFrame.result.observations.single().walkingSelection!!.show)

        val recovery = fixture.process(2_000L).result.objects.single()
        assertEquals(last.trackId, recovery.trackId)
        assertEquals(DepthAvailability.MEASURED, recovery.depthAvailability)
        assertEquals(recovery.timestampMs, recovery.depthObservedAtMs)
        assertEquals(2f, requireNotNull(recovery.zDistanceM), 0.001f)
        assertNull(recovery.prediction)
    }

    @Test fun oneMeasuredFrameCanOnlyBecomeTemporaryGapAndNeverInventsHistory() {
        val fixture = UnknownProducerFixture()
        // Establish identity with real mask observations before its first measurable depth.
        (0..3).forEach { fixture.process(it * 200L, millimeters = null) }
        val measured = fixture.process(800L).result.objects.single()
        val gap = fixture.process(850L, millimeters = null).result.objects.single()
        assertEquals(measured.trackId, gap.trackId)
        assertEquals(DepthAvailability.TEMPORARILY_UNAVAILABLE, gap.depthAvailability)
        assertEquals(measured.depthObservedAtMs, gap.depthObservedAtMs)
        assertNull(gap.prediction)
        assertNonMetric(gap)
    }

    @Test fun neverMeasuredMaskCannotClaimThreeMeterEligibility() {
        val fixture = UnknownProducerFixture()
        (0..5).forEach { index ->
            val frame = fixture.process(index * 200L, millimeters = null)
            val output = frame.result.objects.single()
            assertEquals(DepthAvailability.UNAVAILABLE, output.depthAvailability)
            assertNull(output.depthObservedAtMs)
            assertNull(output.prediction)
            assertNonMetric(output)
            assertFalse(frame.result.observations.single().walkingSelection!!.show)
            assertTrue(frame.result.proximityObjects.isEmpty())
        }
    }

    @Test fun missingCurrentCalibrationAndChangedPoseReferenceInvalidateGapSeed() {
        for (boundary in listOf("calibration", "reference")) {
            val fixture = UnknownProducerFixture()
            (0..6).forEach { fixture.process(it * 200L) }
            val frame = fixture.process(1_400L, millimeters = null,
                matrix = if (boundary == "calibration") null else fixture.matrix(),
                pose = fixture.pose(1_400L).let { if (boundary == "reference") it.copy(referenceId = 99L) else it })
            val output = frame.result.objects.single()
            assertEquals(boundary, DepthAvailability.UNAVAILABLE, output.depthAvailability)
            assertNull(boundary, output.prediction)
            assertNonMetric(output)
            assertFalse(frame.result.observations.single().walkingSelection!!.show)
        }
    }

    @Test fun asynchronousPoseClockCannotSeedPredictionFromFreshCpuDepth() {
        val fixture = UnknownProducerFixture()
        (0..6).forEach { index ->
            val atMs = index * 200L
            val token = fixture.token(atMs, cpuClockOffsetMs = -20L)
            val snapshot = fixture.snapshot(atMs, pose = fixture.pose(atMs + 20L), cpuClockOffsetMs = -20L)
            // Pose and Frame share the AR clock. A different pose time must be rejected before
            // mask sampling can seed the depth-gap history, even with a fresh CPU/depth pair.
            assertNull(FrozenUnknownDepthCapture.freeze(token, snapshot, fixture.matrix(), token.frameId))
        }
        val frame = fixture.process(1_400L, millimeters = null)
        val output = frame.result.objects.single()
        assertEquals(DepthAvailability.UNAVAILABLE, output.depthAvailability)
        assertNull(output.prediction)
        assertNonMetric(output)
    }

    @Test fun aDifferentCpuClockOriginDoesNotMakeAnAlignedFramePoseAsynchronous() {
        val fixture = UnknownProducerFixture()
        val measured = (0..6).map { fixture.process(it * 200L, cpuClockOffsetMs = -20L).result.objects.single() }
        assertTrue(measured.all { it.depthAvailability == DepthAvailability.MEASURED })
        val frame = fixture.process(1_400L, millimeters = null, cpuClockOffsetMs = -20L)
        val output = frame.result.objects.single()
        assertEquals(measured.last().trackId, output.trackId)
        assertEquals(DepthAvailability.PREDICTED, output.depthAvailability)
        assertEquals(fixture.sourceMs(1_200L), output.depthObservedAtMs)
        assertEquals(200L, requireNotNull(output.prediction).predictionAgeMs)
        assertNonMetric(output)
    }

    @Test fun producerAndDelayedCallbackBothExpirePredictionUsingElapsedDeliveryTime() {
        val fixture = UnknownProducerFixture(knownPhysicalPoint = true)
        (0..6).forEach { fixture.process(it * 200L) }
        val frame = fixture.process(1_250L, millimeters = null)
        val output = frame.result.objects.single()
        val prediction = requireNotNull(output.prediction)
        assertEquals(50L, prediction.predictionAgeMs)
        assertTrue(frame.result.observations.single().walkingSelection!!.show)
        val capturedAt = frame.result.token.capturedElapsedNs / 1_000_000L
        val deadline = requireNotNull(DepthPredictionPresentation.validUntilMs(prediction, capturedAt))
        for (at in listOf(deadline - 1L, deadline)) {
            val current = frame.result.forDisplayAt(at)
            assertEquals(prediction, current.objects.single().prediction)
            assertEquals(output.depthObservedAtMs, current.objects.single().depthObservedAtMs)
            assertTrue(current.observations.single().walkingSelection!!.show)
            assertTrue(output.trackId in current.retainedProximityRegionIds)
        }
        for (at in listOf(deadline + 1L, capturedAt + 500L)) {
            val expired = frame.result.forDisplayAt(at)
            assertEquals(DepthAvailability.UNAVAILABLE, expired.objects.single().depthAvailability)
            assertNull(expired.objects.single().prediction)
            assertEquals(output.depthObservedAtMs, expired.objects.single().depthObservedAtMs)
            assertFalse(expired.observations.single().walkingSelection!!.show)
            assertFalse(output.trackId in expired.retainedProximityRegionIds)
            assertNonMetric(expired.objects.single())
        }
        // Completion inside process() itself must apply the same deadline, before the caller renders.
        val slowFixture = UnknownProducerFixture(knownPhysicalPoint = true)
        (0..6).forEach { slowFixture.process(it * 200L) }
        val slow = slowFixture.process(1_250L, millimeters = null, deliveryDelayMs = 500L).result
        assertNull(slow.rejectionReason) // 500 ms is inside the separate 1,000 ms frame-age gate.
        assertNull(slow.objects.single().prediction)
        assertEquals(DepthAvailability.UNAVAILABLE, slow.objects.single().depthAvailability)
        assertFalse(slow.observations.single().walkingSelection!!.show)
        assertFalse(slow.objects.single().trackId in slow.retainedProximityRegionIds)
    }

    @Test fun presentationDeadlineTransfersRemainingLifetimeWithoutMixingClockOrigins() {
        val prediction = PredictedDepthEstimate(2f, 600L, 50L, .1f, 400L, 2.1f)
        val capturedAtElapsed = 100_650L
        assertEquals(101_000L, DepthPredictionPresentation.validUntilMs(prediction, capturedAtElapsed))
        assertTrue(DepthPredictionPresentation.isCurrent(prediction, capturedAtElapsed, 100_999L))
        assertTrue(DepthPredictionPresentation.isCurrent(prediction, capturedAtElapsed, 101_000L))
        assertFalse(DepthPredictionPresentation.isCurrent(prediction, capturedAtElapsed, 101_001L))
        assertFalse(DepthPredictionPresentation.isCurrent(prediction, capturedAtElapsed, 101_150L))
        assertFalse(DepthPredictionPresentation.isCurrent(prediction, capturedAtElapsed, capturedAtElapsed - 1L))
        assertNull(DepthPredictionPresentation.validUntilMs(prediction, Long.MAX_VALUE - 10L))
    }

    private fun assertNonMetric(output: TrackedObjectDepth) {
        assertEquals(DepthSource.UNKNOWN, output.source)
        assertNull(output.zDistanceM)
        assertNull(output.rayDistanceM)
        assertNull(output.riskDistanceM)
        assertNull(output.approachSpeedMps)
        assertNull(output.timeToCollisionMs)
        assertEquals(Trend.UNKNOWN, output.trend)
        assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
        assertEquals(0, output.validSampleCount)
        assertNull(output.userFacing.message)
        assertNull(output.userFacing.stepsAhead)
        assertFalse(UnknownObjectFeedbackPolicy.hasMeasuredDistance(output))
    }
}
