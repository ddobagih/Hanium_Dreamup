package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

class DepthGapPredictionTest {
    @Test fun measuredGapPredictionExpiresAndRecoversWithoutInventingSamples() {
        val fixture = Fixture()
        listOf(4_000, 3_800, 3_600, 3_400).forEachIndexed { i, mm -> fixture.measured(i * 200L, mm) }
        val history = fixture.track.distanceHistory.toList()
        val predicted = fixture.gap(800L)
        assertEquals(DepthAvailability.PREDICTED, predicted.depthAvailability)
        assertEquals(3.2f, predicted.prediction!!.distanceM, 0.001f)
        assertEquals(600L, predicted.depthObservedAtMs)
        assertEquals(200L, predicted.prediction!!.predictionAgeMs)
        assertTrue(predicted.prediction!!.horizonMs in 200L..400L)
        assertNull(predicted.prediction!!.rangeUpperBoundM)
        assertEquals(history, fixture.track.distanceHistory)
        assertNull(predicted.riskDistanceM)
        assertNull(predicted.zDistanceM)
        assertNull(predicted.approachSpeedMps)
        assertNull(predicted.timeToCollisionMs)
        assertEquals(ObjectMotion.UNKNOWN, predicted.objectMotion)
        assertEquals(0, predicted.validSampleCount)
        assertNull(predicted.userFacing.stepsAhead)
        assertFalse(predicted.source.metric)
        val expired = fixture.gap(1_100L)
        assertEquals(DepthAvailability.UNAVAILABLE, expired.depthAvailability)
        assertNull(expired.prediction)
        assertEquals(DepthAvailability.MEASURED, fixture.measured(1_200L, 2_800).depthAvailability)
        assertEquals("track-1", fixture.track.trackId)
    }

    @Test fun repeatedDepthAndRenderingCannotRestartTheObservationAge() {
        val fixture = Fixture()
        (0..3).forEach { fixture.measured(it * 200L, 3_000) }
        fixture.measured(700L, 3_000, depthAtMs = 600L, synchronized = false)
        val first = fixture.gap(750L).prediction!!
        val second = fixture.gap(850L).prediction!!
        assertEquals(600L, first.observedAtMs)
        assertEquals(600L, second.observedAtMs)
        assertEquals(first.horizonMs, second.horizonMs)
        assertEquals(150L, first.predictionAgeMs)
        assertEquals(250L, second.predictionAgeMs)
        assertEquals(DepthAvailability.UNAVAILABLE, fixture.gap(1_100L).depthAvailability)
        fixture.measured(1_150L, 3_000, depthAtMs = 600L, synchronized = true)
        assertEquals(DepthAvailability.UNAVAILABLE, fixture.gap(1_200L).depthAvailability)
    }

    @Test fun unsupportedWithoutPriorDepthAndInsufficientHistoryHaveDistinctStates() {
        val fixture = Fixture()
        assertEquals(DepthAvailability.UNAVAILABLE, fixture.gap(0L).depthAvailability)
        fixture.measured(200L, 3_000)
        fixture.measured(400L, 3_000)
        val gap = fixture.gap(500L)
        assertEquals(DepthAvailability.TEMPORARILY_UNAVAILABLE, gap.depthAvailability)
        assertNull(gap.prediction)
    }

    @Test fun originalDepthCaptureClockIsPreservedWhenCameraTimestampIsLater() {
        val fixture = Fixture()
        (1..4).forEach { fixture.measured(it * 200L, 3_000, depthAtMs = it * 200L - 20L) }
        val gap = fixture.gap(900L)
        assertEquals(780L, gap.depthObservedAtMs)
        assertEquals(780L, gap.prediction!!.observedAtMs)
        assertEquals(120L, gap.prediction!!.predictionAgeMs)
    }

    @Test fun trackingLossOnAReusedMetricImageStillInvalidatesTheGapSeed() {
        val fixture = Fixture()
        (0..3).forEach { fixture.measured(it * 200L, 3_000) }
        fixture.measured(700L, 3_000, depthAtMs = 600L, synchronized = false, trackingQuality = 0f)
        assertNull(fixture.gap(800L).prediction)
    }

    @Test fun olderDepthCannotBeProjectedUsingTheLaterApproachingCameraPose() {
        assertSkewDoesNotPredict(initialDistanceM = 6f, cameraVelocityZ = -3.4f, currentRangeM = 3.11f)
    }

    @Test fun olderDepthCannotMoveAnActuallyDistantObjectInsideTheThreeMeterUpperBound() {
        assertSkewDoesNotPredict(initialDistanceM = 0.16f, cameraVelocityZ = 3.4f, currentRangeM = 3.05f)
    }

    private fun assertSkewDoesNotPredict(initialDistanceM: Float, cameraVelocityZ: Float, currentRangeM: Float) {
        val fixture = Fixture()
        (1..4).forEach { i ->
            val frameAt = i * 200L
            val depthAt = frameAt - 50L
            val mm = ((initialDistanceM + cameraVelocityZ * depthAt / 1_000f) * 1_000f).roundToInt()
            fixture.measured(frameAt, mm, pose = pose(frameAt, cameraVelocityZ * frameAt / 1_000f), depthAtMs = depthAt)
        }
        val gap = fixture.gap(850L, pose(850L, cameraVelocityZ * 0.85f))
        assertEquals(currentRangeM, initialDistanceM + cameraVelocityZ * 0.85f, 0.00001f)
        assertNull("Actual current range is $currentRangeM m", gap.prediction)
        assertEquals(DepthAvailability.UNAVAILABLE, gap.depthAvailability)
    }

    @Test fun independentSampleCadenceChangesTheHorizonButNoGapExceedsTheErrorBudget() {
        fun prediction(cadence: Long): PredictedDepthEstimate {
            val fixture = Fixture()
            (0..3).forEach { fixture.measured(it * cadence, 3_000) }
            return fixture.gap(3L * cadence + 50L).prediction!!
        }
        val fast = prediction(100L)
        val slow = prediction(400L)
        assertEquals(200L, fast.horizonMs)
        assertTrue(slow.horizonMs > fast.horizonMs)
        assertTrue(slow.horizonMs <= 600L)
        assertTrue(slow.errorBoundM <= 0.5f)
    }

    @Test fun currentPoseCompensatesChangedCameraSpeedUsingObservedObjectVelocity() {
        val fixture = Fixture()
        (0..3).forEach { i -> fixture.measured(i * 200L, 4_000 - i * 200, pose = pose(i * 200L, -i * 0.2f)) }
        val history = fixture.track.distanceHistory.toList()
        val predicted = fixture.gap(800L, pose = pose(800L, -1f)).prediction!!
        assertEquals(3f, predicted.distanceM, 0.001f)
        assertNotNull(predicted.rangeUpperBoundM)
        assertEquals(3f + predicted.errorBoundM, predicted.rangeUpperBoundM!!, 0.001f)
        assertEquals(history, fixture.track.distanceHistory)
    }

    @Test fun scalarGapStopsOnCameraRotationBeforeObjectVelocityIsEstablished() {
        val fixture = Fixture(100)
        fixture.geometry = MaskPolygonExtractor().extract(
            DetectionCandidate("car", 0.98f, RectNorm(0.73f, 0.35f, 0.24f, 0.3f)))
        (1..3).forEach { fixture.measured(it * 200L, 3_000, pose = pose(it * 200L)) }
        val point = pose(600L).objectCenterInAnchor(fixture.geometry.centerNorm, 3f)!!
        val angle = 8f * PI.toFloat() / 180f
        val current = pose(650L).copy(forwardX = sin(angle), forwardZ = -cos(angle),
            imageProjection = pose(650L).imageProjection!!.copy(rightX = cos(angle), rightZ = sin(angle)))
        val currentZ = point.dot(Vec3(current.forwardX, current.forwardY, current.forwardZ))
        val currentX = point.dot(Vec3(current.imageProjection!!.rightX, 0f, current.imageProjection!!.rightZ))
        val center = 0.5f + 0.7f * currentX / currentZ
        fixture.geometry = MaskPolygonExtractor().extract(
            DetectionCandidate("car", 0.98f, RectNorm(center - 0.12f, 0.35f, 0.24f, 0.3f)))
        assertEquals(3.179564f, currentZ, 0.00001f)
        val rotated = fixture.gap(650L, current)
        assertTrue(fixture.track.stable)
        assertNull(rotated.prediction)
        assertEquals(DepthAvailability.UNAVAILABLE, rotated.depthAvailability)
        assertNull(fixture.gap(700L, current.copy(timestampMs = 700L)).prediction)
    }

    @Test fun trackingLossReferenceChangePoseLossAndSuddenRotationInvalidatePrediction() {
        for (boundary in listOf("tracking", "reference", "pose", "rotation", "jump")) {
            val fixture = Fixture()
            (0..3).forEach { fixture.measured(it * 200L, 3_000, pose = pose(it * 200L)) }
            val current = when (boundary) {
                "reference" -> pose(800L).copy(referenceId = 2L)
                "pose" -> null
                "rotation" -> pose(800L).copy(forwardX = 1f, forwardZ = 0f)
                "jump" -> pose(800L, -10f)
                else -> pose(800L)
            }
            assertEquals(boundary, DepthAvailability.UNAVAILABLE,
                fixture.gap(800L, current, if (boundary == "tracking") 0f else 1f).depthAvailability)
            assertNull(fixture.gap(900L, pose(900L)).prediction)
        }
    }

    @Test fun visualRotationRepresentativeResetAmbiguityAndCoverageLossDiscardSeed() {
        for (boundary in listOf("rotation", "representative", "ambiguity", "coverage")) {
            val fixture = Fixture()
            (0..3).forEach { fixture.measured(it * 200L, 3_000) }
            when (boundary) {
                "rotation" -> fixture.geometry = fixture.geometry.copy(imageQuarterTurns = 1)
                "representative" -> fixture.track.resetMetricAndSpatialHistoryPreservingTrackId(600L)
                "ambiguity" -> fixture.track.holdMaskAssociation(600L)
                "coverage" -> fixture.track.deferVisualTracking()
            }
            assertNull(boundary, fixture.gap(800L).prediction)
        }
    }

    @Test fun rejectedDepthJumpCannotBecomeShortGapEvidence() {
        val fixture = Fixture()
        (0..3).forEach { fixture.measured(it * 200L, 4_000) }
        val jump = fixture.measured(800L, 500)
        assertEquals(0f, jump.confidence.finalScore, 0f)
        assertNull(fixture.gap(900L).prediction)
    }

    @Test fun fullDepthIsStillMeasuredAndCannotReuseRawSlopeOnSourceChange() {
        val fixture = Fixture()
        (0..3).forEach { fixture.measured(it * 200L, 3_000) }
        val full = fixture.measured(800L, 3_000, full = true)
        assertEquals(DepthSource.ARCORE_FULL_DEPTH, full.source)
        assertEquals(DepthAvailability.MEASURED, full.depthAvailability)
        assertNull(fixture.gap(900L).prediction)
    }

    @Test fun nearDistanceAndNoisyRelativeMotionCannotClaimSmallPredictionError() {
        val near = Fixture()
        (0..3).forEach { near.measured(it * 200L, 300) }
        assertNull(near.gap(650L).prediction)
        val noisy = Fixture()
        listOf(3_000, 3_500, 2_800, 3_200).forEachIndexed { i, mm -> noisy.measured(i * 200L, mm) }
        assertNull(noisy.gap(700L).prediction)
    }

    private class Fixture(private val imageSize: Int = 10) {
        val tracker = ObjectTracker()
        val estimator = ObjectDepthEstimator(tracker = tracker)
        var geometry = MaskPolygonExtractor().extract(DetectionCandidate("car", 0.95f, RectNorm(0f, 0f, 1f, 1f)))
        private val fixtureMapper = LetterboxCoordinateMapper(
            ModelInputTransform(imageSize, imageSize, imageSize, imageSize, 1f, 0f, 0f), ImageSize(imageSize, imageSize))
        lateinit var track: TrackState
        fun measured(at: Long, mm: Int, pose: CameraPoseEvidence? = null, depthAtMs: Long = at,
                     synchronized: Boolean = true, full: Boolean = false, trackingQuality: Float = 1f): TrackedObjectDepth {
            track = tracker.update(listOf(geometry), at).single()
            val depth = DepthImage16(imageSize, imageSize, IntArray(imageSize * imageSize) { mm })
            return estimator.estimate(ObjectDepthInput(at, at, geometry, track, fixtureMapper,
                rawDepth = depth.takeUnless { full },
                rawConfidence = if (full) null else ConfidenceImage8(imageSize, imageSize, ByteArray(imageSize * imageSize) { 255.toByte() }),
                rawDepthTimestampNs = (depthAtMs * 1_000_000L + 1L).takeUnless { full },
                rawDepthMatchesCameraFrame = synchronized && !full,
                fullDepth = depth.takeIf { full }, fullDepthTimestampNs = (depthAtMs * 1_000_000L + 1L).takeIf { full },
                fullDepthMatchesCameraFrame = synchronized && full, requireIndependentDepthObservation = true,
                motionContext = MotionContext(cameraPoseEvidence = pose, trackingQuality = trackingQuality)))
        }
        fun gap(at: Long, pose: CameraPoseEvidence? = null, trackingQuality: Float = 1f): TrackedObjectDepth {
            track = tracker.update(listOf(geometry), at).single()
            return estimator.estimateUnavailable(ObjectDepthInput(at, at, geometry, track, fixtureMapper,
                motionContext = MotionContext(cameraPoseEvidence = pose, trackingQuality = trackingQuality)))
        }
    }

    companion object {
        private fun pose(at: Long, z: Float = 0f) = CameraPoseEvidence(1L, at, 0f, 0f, z, 0f, 0f, -1f,
            CameraImageProjection(10, 10, 7f, 7f, 5f, 5f, 1f, 0f, 0f, 0f, 1f, 0f))
    }
}
