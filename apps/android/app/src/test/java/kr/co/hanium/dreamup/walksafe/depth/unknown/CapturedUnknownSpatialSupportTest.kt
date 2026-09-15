package kr.co.hanium.dreamup.walksafe.depth.unknown

import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.sqrt

class CapturedUnknownSpatialSupportTest {
    @Test fun actualDepthCellCentersUseCapturedCropAndRgbIntrinsicsAcrossDifferentGrids() {
        val fixture = UnknownProducerFixture(160, 120, 40, 30)
        val pose = fixture.pose(0L).copy(positionX = 1f, positionY = 1.5f, positionZ = 2f,
            forwardX = -1f, forwardZ = 0f,
            imageProjection = CameraImageProjection(160, 120, 120f, 100f, 80f, 60f,
                0f, 0f, -1f, 0f, 1f, 0f))
        val crop = fixture.matrix().also { it[2] = .1 }
        val frame = fixture.process(0L, pose = pose, matrix = crop,
            mask = fixture.rectangle(56, 32, 104, 88))
        val observation = frame.result.observations.single()
        assertEquals(MaskDepthEstimator.Status.KNOWN, observation.depth.status)
        val points = CapturedUnknownSpatialSupport(frame.capture, null).points(observation.depth)
        assertTrue(points.size >= 8)
        for (point in points) {
            // Captured H is u = x / 160 + .1, v = y / 120. Pixel centers invert to
            // x = 4 * depthX + 2 - 16 and y = 4 * depthY + 2, not RGB-grid indices.
            assertEquals(2, (point.imageX + 16) % 4)
            assertEquals(2, point.imageY % 4)
            assertEquals(-1f, point.pointInAnchor.x, 0.00001f)
            assertEquals(1.5f - (point.imageY - 60f) * 2f / 100f, point.pointInAnchor.y, 0.00001f)
            assertEquals(2f - (point.imageX - 80f) * 2f / 120f, point.pointInAnchor.z, 0.00001f)
        }
        assertTrue(points.all { observation.mask.contains(it.imageX, it.imageY) })
    }

    @Test fun offAxisAxialDepthBelowThreeMetersCannotAdmitRadiallyDistantSurface() {
        val fixture = UnknownProducerFixture(80, 60)
        val mask = fixture.rectangle(53, 15, 64, 45)
        val far = (0..5).map { fixture.process(it * 200L, 2_950, mask = mask) }.last()
        val observation = far.result.observations.single()
        assertEquals(MaskDepthEstimator.Status.KNOWN, observation.depth.status)
        assertTrue(requireNotNull(observation.depth.axialDepthM) < 3.0)
        // Erosion leaves x >= 54.5; even its closest possible y is > 3 m radially.
        val expectedMinimum = sqrt(2.95f * 2.95f + ((54.5f - 40f) * 2.95f / 64f).let { it * it })
        assertTrue(expectedMinimum > 3f)
        assertTrue(requireNotNull(observation.spatialEvidence?.nearestReliableRangeM) > 3f)
        assertFalse(observation.walkingSelection!!.show)
        assertFalse(observation.walkingSelection!!.warningCandidate)
        assertFalse(far.result.objects.single().walkingObstacleCandidate)
        assertNull(far.result.objects.single().userFacing.message)

        val nearFixture = UnknownProducerFixture(80, 60)
        val near = (0..5).map { nearFixture.process(it * 200L, 2_700, mask = mask) }.last()
        assertTrue(requireNotNull(near.result.observations.single().spatialEvidence?.nearestReliableRangeM) < 3f)
        assertTrue(near.result.observations.single().walkingSelection!!.show)
        assertTrue(near.result.objects.single().walkingObstacleCandidate)
    }

    @Test fun physicalThreeMeterBoundaryIsInclusiveAndThreePointZeroZeroOneIsExcluded() {
        for ((mm, expectedShow) in listOf(3_000 to true, 3_001 to false)) {
            val fixture = UnknownProducerFixture()
            val frame = (0..5).map { index ->
                val at = index * 200L
                val pose = fixture.pose(at)
                fixture.process(at, mm, pose = pose.copy(imageProjection = pose.imageProjection!!.copy(fx = 1_000_000f, fy = 1_000_000f)))
            }.last()
            assertEquals(mm / 1_000f, frame.result.observations.single().spatialEvidence!!.nearestReliableRangeM!!, 0.000001f)
            assertEquals("depth=$mm mm", expectedShow, frame.result.observations.single().walkingSelection!!.show)
        }
    }

    @Test fun mismatchedProjectionOrSingularCalibrationCannotProduceSpatialMetricEvidence() {
        for (boundary in listOf("projection", "singular")) {
            val fixture = UnknownProducerFixture()
            val pose = fixture.pose(0L).let {
                if (boundary == "projection") it.copy(imageProjection = it.imageProjection!!.copy(imageWidth = 80)) else it
            }
            val frame = fixture.process(0L, pose = pose,
                matrix = if (boundary == "singular") DoubleArray(9) else fixture.matrix())
            val observation = frame.result.observations.single()
            assertTrue(boundary, CapturedUnknownSpatialSupport(frame.capture, null).points(observation.depth).isEmpty())
            assertNull(boundary, observation.spatialEvidence?.nearestReliableRangeM)
            assertFalse(boundary, observation.walkingSelection!!.show)
        }
    }

    @Test fun mismatchedConfidenceGridAndForeignMaskGeometryAreRejected() {
        val fixture = UnknownProducerFixture()
        val token = fixture.token(0L)
        val snapshot = fixture.snapshot(0L).copy(rawConfidence = ConfidenceImage8(20, 16, ByteArray(320)))
        assertNull(FrozenUnknownDepthCapture.freeze(token, snapshot, fixture.matrix(), token.frameId))
        val foreign = UnknownProducerFixture(80, 60).rectangle(20, 10, 60, 50)
        val rejected = fixture.process(0L, mask = foreign).result
        assertEquals("mask_image_dimensions_mismatch", rejected.rejectionReason)
        assertTrue(rejected.objects.isEmpty())
        assertTrue(rejected.observations.isEmpty())
    }

    @Test fun actualResamplingRetainsTwoCentimeterProtrusionBetweenUniformSamplePositions() {
        val fixture = UnknownProducerFixture(128, 128)
        val mask = fixture.rectangle(34, 40, 94, 80)
        val pose = fixture.pose(0L).copy(positionY = 1.5f, forwardY = -1f, forwardZ = 0f,
            imageProjection = CameraImageProjection(128, 128, 64f, 48f, 64f, 96f,
                1f, 0f, 0f, 0f, 0f, -1f),
            horizontalPlaneCandidates = listOf(CapturedHorizontalPlane(7L, fixture.sourceMs(0L),
                listOf(Vec3(-4f, 0f, -4f), Vec3(4f, 0f, -4f), Vec3(4f, 0f, 4f), Vec3(-4f, 0f, 4f)))))
        val floor = fixture.process(0L, 1_500, mask = mask, pose = pose)
        val observation = floor.result.observations.single()
        assertEquals(MaskDepthEstimator.Status.KNOWN, observation.depth.status)
        val support = CapturedUnknownSpatialSupport(floor.capture, Vec3(0f, 0f, -1f))
        assertEquals(UnknownSpatialDisposition.FLAT_FLOOR,
            support.evaluate(observation.depth, true, observation.mask)!!.disposition)
        val indices = observation.depth.components.single().inlierDepthPixelIndices()
        assertTrue(indices.size > 512)
        val step = ceil(indices.size / 256.0).toInt()
        val bump = indices.withIndex().first { (ordinal, cell) ->
            ordinal % step != 0 && cell % 128 in 60..68 && cell / 128 in 55..65
        }.value
        val raisedFixture = UnknownProducerFixture(128, 128)
        val depths = IntArray(128 * 128) { 1_500 }.also { it[bump] = 1_480 }
        val raised = raisedFixture.process(0L, 1_500, mask = mask, pose = pose, values = depths)
        val raisedObservation = raised.result.observations.single()
        assertTrue("The real robust estimator must still represent this supported low protrusion",
            bump in raisedObservation.depth.components.single().inlierDepthPixelIndices())
        val raisedSupport = CapturedUnknownSpatialSupport(raised.capture, Vec3(0f, 0f, -1f))
        val sampled = raisedSupport.points(raisedObservation.depth, raisedObservation.mask)
        assertTrue("Uniform-only resampling would lose this cell", sampled.any { it.imageX == bump % 128 && it.imageY == bump / 128 })
        assertEquals(.02f, sampled.maxOf { it.pointInAnchor.y }, .001f)
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            raisedSupport.evaluate(raisedObservation.depth, true, raisedObservation.mask)!!.disposition)

        // Also let the producer derive travel direction from same-reference camera displacement.
        // The floor and raised scene use identical poses, masks, calibration and sampling cadence.
        fun movingPose(index: Int): CameraPoseEvidence = pose.copy(timestampMs = fixture.sourceMs(index * 200L),
            positionZ = -index * .06f, horizontalPlaneCandidates = pose.horizontalPlaneCandidates.map {
                it.copy(captureTimestampMs = fixture.sourceMs(index * 200L))
            })
        val floorAfterTravel = (1..5).map { index ->
            fixture.process(index * 200L, 1_500, mask = mask, pose = movingPose(index))
        }.last()
        val raisedAfterTravel = (1..5).map { index ->
            raisedFixture.process(index * 200L, 1_500, mask = mask, pose = movingPose(index), values = depths)
        }.last()
        assertEquals(UnknownSpatialDisposition.FLAT_FLOOR,
            floorAfterTravel.result.observations.single().spatialEvidence!!.disposition)
        assertFalse(floorAfterTravel.result.observations.single().walkingSelection!!.show)
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE,
            raisedAfterTravel.result.observations.single().spatialEvidence!!.disposition)
        assertTrue(raisedAfterTravel.result.observations.single().walkingSelection!!.show)
        assertTrue(raisedAfterTravel.result.observations.single().walkingSelection!!.warningCandidate)
    }

    @Test fun robustRejectedProtrusionStillPreventsFloorExclusionWithoutReplacingMeasuredRange() {
        val fixture = UnknownProducerFixture(128, 128)
        val mask = fixture.rectangle(34, 40, 94, 80)
        val bump = 60 * 128 + 64
        val depths = IntArray(128 * 128) { 1_500 }.also { it[bump] = 1_200 }
        val frame = (0..5).map { index ->
            val at = index * 200L
            val pose = fixture.pose(at).copy(positionY = 1.5f, positionZ = -index * .06f,
                forwardY = -1f, forwardZ = 0f,
                imageProjection = CameraImageProjection(128, 128, 64f, 48f, 64f, 96f,
                    1f, 0f, 0f, 0f, 0f, -1f),
                horizontalPlaneCandidates = listOf(CapturedHorizontalPlane(7L, fixture.sourceMs(at),
                    listOf(Vec3(-4f, 0f, -4f), Vec3(4f, 0f, -4f), Vec3(4f, 0f, 4f), Vec3(-4f, 0f, 4f)))))
            fixture.process(at, 1_500, pose = pose, mask = mask, values = depths)
        }.last()
        val observation = frame.result.observations.single()
        assertEquals(MaskDepthEstimator.Status.KNOWN, observation.depth.status)
        assertFalse(bump in observation.depth.components.single().inlierDepthPixelIndices())
        assertEquals(1.5, observation.depth.axialDepthM!!, 0.00001)
        val support = CapturedUnknownSpatialSupport(frame.capture, Vec3(0f, 0f, -1f))
        val robustPoints = support.points(observation.depth, observation.mask)
        assertTrue(robustPoints.all { kotlin.math.abs(it.pointInAnchor.y) < .001f })
        val robustOnly = UnknownSpatialRelevance.evaluate(robustPoints.map { it.spatial() },
            UnknownSpatialContext(frame.capture.motionContext.cameraPoseEvidence!!, Vec3(0f, 1f, 0f),
                frame.capture.frameTimestampMs, true, true, Vec3(0f, 0f, -1f)))
        assertEquals(robustOnly.nearestReliableRangeM!!, observation.spatialEvidence!!.nearestReliableRangeM!!, .00001f)
        assertEquals(UnknownSpatialDisposition.CORRIDOR_OBSTACLE, observation.spatialEvidence!!.disposition)
        assertTrue(observation.walkingSelection!!.show)
        assertTrue(observation.walkingSelection!!.warningCandidate)
    }

    @Test fun smallContiguousNearSurfaceCreatesScopedWarningAfterThreeIndependentFrames() {
        for (fullOnly in listOf(false, true)) {
            val fixture = UnknownProducerFixture(200, 200)
            val values = nearSurfaceDepths { x, y -> x in 94..105 && y in 94..105 }
            val frames = listOf(0L, 350L, 700L).map { at ->
                fixture.process(at, 3_200, mask = fixture.rectangle(40, 60, 160, 140),
                    values = values, fullOnly = fullOnly)
            }
            assertTrue(frames.take(2).all { frame -> frame.result.proximityObjects.none { it.walkingObstacleCandidate } })
            val result = frames.last().result
            val observation = result.observations.single()
            assertEquals("fullOnly=$fullOnly", MaskDepthEstimator.Status.PARTIAL, observation.depth.status)
            assertNull(observation.depth.axialDepthM)
            val layer = observation.depth.nearerLayers.single()
            assertEquals(144, layer.inlierPixels)
            assertTrue(layer.interiorFraction < .10)
            assertEquals(2.5, layer.axialDepthM, 0.0001)
            assertTrue(requireNotNull(observation.spatialEvidence?.nearestReliableRangeM) <= 3f)
            assertTrue(observation.walkingSelection!!.show)
            assertTrue(observation.walkingSelection!!.warningCandidate)
            val region = result.proximityObjects.single()
            assertNotEquals(observation.trackId, region.trackId)
            assertEquals(if (fullOnly) DepthSource.ARCORE_FULL_DEPTH else DepthSource.ARCORE_RAW_DEPTH, region.source)
            assertEquals(3, region.trackAgeFrames)
            assertEquals(700L, region.trackStableMs)
            assertEquals(144, region.validSampleCount)
            assertEquals(2.5f, requireNotNull(region.riskDistanceM), .0001f)
            assertTrue(region.walkingObstacleCandidate)
            assertNotNull(region.userFacing.message)
            assertNull(region.approachSpeedMps)
            assertNull(region.timeToCollisionMs)
            assertTrue(result.objects.all { !it.walkingObstacleCandidate && it.riskDistanceM == null })
        }
    }

    @Test fun scatteredNearNoiseAndSubminimumContiguousRegionsCannotCreateWarnings() {
        val cases: List<Pair<String, (Int, Int) -> Boolean>> = listOf(
            "144 isolated pixels" to { x, y -> x in 76..120 && y in 76..120 && (x - 76) % 4 == 0 && (y - 76) % 4 == 0 },
            "29 contiguous pixels" to { x, y -> (x in 97..101 && y in 97..101) || (x in 97..100 && y == 102) },
        )
        for (fullOnly in listOf(false, true)) for ((name, foreground) in cases) {
            val fixture = UnknownProducerFixture(200, 200)
            val values = nearSurfaceDepths(foreground)
            assertEquals(if (name.startsWith("144")) 144 else 29, values.count { it == 2_500 })
            listOf(0L, 350L, 700L).forEach { at ->
                val result = fixture.process(at, 3_200, mask = fixture.rectangle(40, 60, 160, 140),
                    values = values, fullOnly = fullOnly).result
                assertTrue("$name fullOnly=$fullOnly", result.observations.single().depth.nearerLayers.isEmpty())
                assertTrue(result.proximityObjects.isEmpty())
                assertFalse(result.observations.single().walkingSelection!!.show)
                assertTrue(result.objects.all { !it.walkingObstacleCandidate && it.userFacing.message == null })
            }
        }
    }

    @Test fun lowConfidenceLocalPatchAndSubminimumFullDepthPatchCannotCreateWarnings() {
        for (fullOnly in listOf(false, true)) {
            val fixture = UnknownProducerFixture(200, 200)
            val foreground: (Int, Int) -> Boolean = if (fullOnly) {
                { x, y -> x in 97..103 && y in 97..103 } // 49 < 50 smoothed-depth cells.
            } else { { x, y -> x in 94..105 && y in 94..105 } }
            val values = nearSurfaceDepths(foreground)
            val confidence = ByteArray(200 * 200) { index ->
                (if (foreground(index % 200, index / 200)) 191 else 255).toByte()
            }
            val result = listOf(0L, 350L, 700L).map { at ->
                fixture.process(at, 3_200, mask = fixture.rectangle(40, 60, 160, 140),
                    values = values, fullOnly = fullOnly, confidenceValues = confidence)
            }.last().result
            assertTrue("fullOnly=$fullOnly", result.observations.single().depth.nearerLayers.isEmpty())
            assertTrue(result.proximityObjects.isEmpty())
            assertFalse(result.observations.single().walkingSelection!!.show)
            assertTrue(result.objects.all { !it.walkingObstacleCandidate && it.userFacing.message == null })
        }
    }

    @Test fun exactlyThirtyRawAndFiftyFullSupportedCellsReachMeasuredRegionEligibility() {
        for (fullOnly in listOf(false, true)) {
            val fixture = UnknownProducerFixture(200, 200)
            val rows = if (fullOnly) 10 else 6
            val expectedCount = if (fullOnly) 50 else 30
            val foreground: (Int, Int) -> Boolean = { x, y -> x in 97..101 && y in 95 until 95 + rows }
            val values = nearSurfaceDepths(foreground)
            val confidence = ByteArray(200 * 200) { index ->
                (if (foreground(index % 200, index / 200)) 192 else 255).toByte()
            }
            val result = listOf(0L, 350L, 700L).map { at ->
                fixture.process(at, 3_200, mask = fixture.rectangle(40, 60, 160, 140),
                    values = values, fullOnly = fullOnly, confidenceValues = confidence)
            }.last().result
            assertEquals(expectedCount, result.observations.single().depth.nearerLayers.single().inlierPixels)
            val region = result.proximityObjects.single()
            assertEquals(expectedCount, region.validSampleCount)
            assertTrue(UnknownObjectFeedbackPolicy.hasMeasuredDistance(region))
            assertTrue("fullOnly=$fullOnly", result.observations.single().walkingSelection!!.show)
            assertTrue("fullOnly=$fullOnly", region.walkingObstacleCandidate)
            assertNotNull(region.userFacing.message)
        }
    }

    private fun nearSurfaceDepths(foreground: (Int, Int) -> Boolean) =
        IntArray(200 * 200) { if (foreground(it % 200, it / 200)) 2_500 else 3_200 }
}

/** Synthetic raw arrays and camera calibration; all output evidence is produced by production code. */
internal class UnknownProducerFixture(
    val width: Int = 40,
    val height: Int = 32,
    val depthWidth: Int = width,
    val depthHeight: Int = height,
) {
    private var clock = 100_050L
    private val pipeline = UnknownObjectDepthPipeline(elapsedRealtimeMs = { clock })
    fun sourceMs(atMs: Long) = 10_000L + atMs
    fun token(atMs: Long, cpuClockOffsetMs: Long = 0L) = FastSamFrameToken(
        FastSamFrameToken.Source.LIVE_CAMERA, 1L, sourceMs(atMs) * 1_000_000L,
        sourceMs(atMs) * 1_000_000L, (sourceMs(atMs) + cpuClockOffsetMs) * 1_000_000L,
        (100_000L + atMs) * 1_000_000L, "producer-calibration", width, height)
    fun matrix() = doubleArrayOf(1.0 / width, 0.0, 0.0, 0.0, 1.0 / height, 0.0, 0.0, 0.0, 1.0)
    fun pose(atMs: Long) = CameraPoseEvidence(7L, sourceMs(atMs), 0f, 1.5f, 0f, 0f, 0f, -1f,
        CameraImageProjection(width, height, width * .8f, height.toFloat(), width / 2f, height / 2f,
            1f, 0f, 0f, 0f, 1f, 0f), gravityUpInAnchor = Vec3(0f, 1f, 0f))
    fun snapshot(atMs: Long, millimeters: Int? = 2_000, pose: CameraPoseEvidence = pose(atMs),
                 values: IntArray? = null, cpuClockOffsetMs: Long = 0L): DepthFrameSnapshot {
        val token = token(atMs, cpuClockOffsetMs)
        return DepthFrameSnapshot(token.cameraTimestampNs,
            millimeters?.let { mm -> DepthImage16(depthWidth, depthHeight, values ?: IntArray(depthWidth * depthHeight) { mm }) },
            millimeters?.let { ConfidenceImage8(depthWidth, depthHeight, ByteArray(depthWidth * depthHeight) { 255.toByte() }) },
            fullDepth = null, rawDepthTimestampNs = token.cpuImageTimestampNs.takeIf { millimeters != null },
            cameraPoseEvidence = pose, cameraImageTimestampNs = token.cpuImageTimestampNs,
            rawConfidenceTimestampNs = token.cpuImageTimestampNs.takeIf { millimeters != null })
    }
    fun rectangle(left: Int = width / 4, top: Int = height / 4,
                  right: Int = width * 3 / 4, bottom: Int = height * 3 / 4): InstanceMask {
        val bits = BitSet((right - left) * (bottom - top)).also { it.set(0, (right - left) * (bottom - top)) }
        return InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible = true }
            .newInstance(width, height, left, top, right, bottom, bits.cardinality(), 17,
                left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), .99f, bits) as InstanceMask
    }
    data class Frame(val capture: FrozenUnknownDepthCapture, val result: UnknownDepthFrameResult)
    fun process(atMs: Long, millimeters: Int? = 2_000, pose: CameraPoseEvidence = pose(atMs),
                matrix: DoubleArray? = matrix(), mask: InstanceMask = rectangle(), values: IntArray? = null,
                cpuClockOffsetMs: Long = 0L, deliveryDelayMs: Long = 50L, fullOnly: Boolean = false,
                confidenceValues: ByteArray? = null): Frame {
        val token = token(atMs, cpuClockOffsetMs)
        var snapshot = snapshot(atMs, millimeters, pose, values, cpuClockOffsetMs)
        if (confidenceValues != null) snapshot = snapshot.copy(rawConfidence = ConfidenceImage8(depthWidth, depthHeight, confidenceValues))
        if (fullOnly) snapshot = snapshot.copy(rawDepth = null, rawConfidence = null,
            fullDepth = snapshot.rawDepth, fullDepthTimestampNs = token.cpuImageTimestampNs,
            rawDepthTimestampNs = null, rawConfidenceTimestampNs = null)
        val capture = requireNotNull(FrozenUnknownDepthCapture.freeze(token,
            snapshot, matrix, token.frameId))
        clock = token.capturedElapsedNs / 1_000_000L + deliveryDelayMs
        return Frame(capture, pipeline.process(capture, token, listOf(mask), clock))
    }
}
