package kr.co.hanium.dreamup.walksafe.depth.unknown

import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Exact regression inputs from the independent review, exercised through feedback admission. */
class UnknownIndependentRegionRegressionTest {
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    }
    private var now = 100_050L
    private val epoch = WalkRuntimeEpoch("independent-region", 1)

    private fun mask(boxes: List<Box>): InstanceMask {
        val left = boxes.minOf { it.left }; val top = boxes.minOf { it.top }
        val right = boxes.maxOf { it.right }; val bottom = boxes.maxOf { it.bottom }
        val bits = BitSet((right - left) * (bottom - top))
        for (y in top until bottom) for (x in left until right) {
            if (boxes.any { it.contains(x, y) }) bits.set((y - top) * (right - left) + x - left)
        }
        val constructor = InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible = true }
        return constructor.newInstance(100, 100, left, top, right, bottom, bits.cardinality(), 17,
            left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), .99f, bits) as InstanceMask
    }

    private fun runFrames(boxes: List<Box>, fullOnly: Boolean = false, smoothedFar: Boolean = false,
                          depthMm: (Int, Int) -> Int): List<UnknownDepthFrameResult> {
        val pipeline = UnknownObjectDepthPipeline({ now })
        return (0..5).map { index ->
            val delta = index * 250_000_000L
            val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1L,
                10_000_000_000L + delta, 10_000_000_000L + delta, 8_000_000_000L + delta,
                100_000_000_000L + delta, "independent-region", 100, 100)
            now = token.capturedElapsedNs / 1_000_000L + 50L
            val plane = DepthImage16(100, 100, IntArray(10_000) { depthMm(it % 100, it / 100) })
            val snapshot = DepthFrameSnapshot(frameTimestampNs = token.cameraTimestampNs,
                rawDepth = plane.takeUnless { fullOnly },
                rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { -1 }).takeUnless { fullOnly },
                fullDepth = if (fullOnly) plane else if (smoothedFar)
                    DepthImage16(100, 100, IntArray(10_000) { 8000 }) else null,
                rawDepthTimestampNs = token.cpuImageTimestampNs.takeUnless { fullOnly },
                rawConfidenceTimestampNs = token.cpuImageTimestampNs.takeUnless { fullOnly },
                fullDepthTimestampNs = token.cpuImageTimestampNs,
                cameraImageTimestampNs = token.cpuImageTimestampNs,
                cameraPoseEvidence = CameraPoseEvidence(1, token.cameraTimestampNs / 1_000_000L,
                    0f, 0f, 0f, 0f, 0f, -1f,
                    CameraImageProjection(100, 100, 100f, 100f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f)))
            val capture = requireNotNull(FrozenUnknownDepthCapture.freeze(token, snapshot,
                doubleArrayOf(.01, 0.0, 0.0, 0.0, .01, 0.0, 0.0, 0.0, 1.0), token.frameId))
            pipeline.process(capture, token, listOf(mask(boxes)), now)
        }
    }

    private fun admitted(result: UnknownDepthFrameResult) = UnknownObjectFeedbackPolicy().admit(
        result.objects + result.proximityObjects, result.token.frameId, result.token.cameraTimestampNs / 1_000_000L,
        result.token.capturedElapsedNs, now, epoch, epoch, now)

    private fun assertScopedWarning(result: UnknownDepthFrameResult, distanceM: Float, samples: Int) {
        val observation = result.observations.single()
        assertNull(observation.depth.axialDepthM)
        assertNull(observation.metricExtent)
        assertTrue(result.objects.all { it.riskDistanceM == null && !it.walkingObstacleCandidate })
        val output = result.proximityObjects.single()
        assertEquals(distanceM, output.riskDistanceM!!, 0f)
        assertEquals(samples, output.validSampleCount)
        assertTrue(output.walkingObstacleCandidate)
        assertEquals(6, output.trackAgeFrames)
        assertEquals(1250L, output.trackStableMs)
        assertTrue(output.trackId.startsWith("unknown-proximity-"))
        assertNotEquals(observation.trackId, output.trackId)
        assertEquals(Trend.UNKNOWN, output.trend)
        assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
        assertNull(output.approachSpeedMps)
        assertNull(output.timeToCollisionMs)
        assertEquals(1, admitted(result)!!.outputs.mapNotNull { it.toFeedbackCandidate() }.size)
    }

    @Test fun supportedNearLayerSurvivesTheDominantFractionBoundaryAndBalancedMixture() {
        for ((nearRight, expectedSamples) in listOf(40 to 1102, 42 to 1218, 50 to 1682)) {
            val frames = runFrames(listOf(Box(20, 20, 80, 80)), smoothedFar = true) { x, _ ->
                if (x < nearRight) 1000 else 8000
            }
            val result = frames.last()
            val observation = result.observations.single()
            assertEquals("ARCORE_RAW_DEPTH", observation.depth.source)
            assertEquals(if (nearRight == 40) MaskDepthEstimator.Status.PARTIAL else MaskDepthEstimator.Status.UNKNOWN,
                observation.depth.status)
            assertTrue(observation.depth.componentDepthConflict)
            assertEquals(1.0, observation.depth.nearerLayers.single().axialDepthM, 0.0)
            assertEquals(1f, observation.proximityDistanceM!!, 0f)
            assertTrue(frames.first().observations.single().walkingSelection!!.show)
            assertScopedWarning(result, 1f, expectedSamples)
        }
    }

    @Test fun disconnectedSupportedNearComponentSurvivesTheLargeFarComponent() {
        val near = Box(40, 40, 52, 60)
        val result = runFrames(listOf(near, Box(10, 10, 90, 30))) { x, y ->
            if (near.contains(x, y)) 1000 else 8000
        }.last()
        val observation = result.observations.single()
        assertEquals(listOf(1404, 180), observation.depth.components.map { it.inlierPixels })
        assertTrue(observation.depth.nearerLayers.isEmpty())
        assertEquals(listOf(1), observation.depth.closerComponentIds)
        val displayed = requireNotNull(observation.proximityMask)
        assertEquals(180, displayed.area)
        assertTrue(displayed.contains(45, 45))
        assertFalse(displayed.contains(45, 20))
        assertScopedWarning(result, 1f, 180)
    }

    @Test fun weakNearestDisplayDoesNotStealTheNextRawWarningCandidateOrItsStability() {
        val near = Box(42, 42, 46, 48); val next = Box(50, 42, 55, 50)
        val frames = runFrames(listOf(Box(40, 40, 62, 54))) { x, y -> when {
            near.contains(x, y) -> 1000
            next.contains(x, y) -> 2000
            else -> 8000
        } }
        val result = frames.last(); val observation = result.observations.single()
        assertEquals(listOf(24, 40), observation.depth.nearerLayers.map { it.inlierPixels })
        assertEquals(1f, observation.proximityDistanceM!!, 0f)
        assertEquals(24, observation.proximityMask!!.area)
        assertTrue(observation.proximityMask.contains(43, 43))
        assertFalse(observation.proximityMask.contains(52, 43))
        assertTrue(frames.all { it.observations.single().walkingSelection!!.show })
        assertFalse(frames.first().observations.single().walkingSelection!!.warningCandidate)
        assertEquals(1, frames.map { it.observations.single().proximityRegionId }.distinct().size)
        val output = result.proximityObjects.single()
        assertNotEquals(observation.proximityRegionId, output.trackId)
        assertEquals(.5f, output.bboxNorm.x, 0f)
        assertEquals(.05f, output.bboxNorm.width, 0f)
        assertScopedWarning(result, 2f, 40)
    }

    @Test fun rawSampleThresholdStillBlocksWeakNearestWithoutDiscardingItsDisplay() {
        val near = Box(42, 42, 46, 48)
        val result = runFrames(listOf(Box(40, 40, 62, 54))) { x, y ->
            if (near.contains(x, y)) 1000 else 8000
        }.last()
        val observation = result.observations.single()
        assertEquals(1f, observation.proximityDistanceM!!, 0f)
        assertTrue(observation.walkingSelection!!.show)
        assertFalse(observation.walkingSelection.warningCandidate)
        assertEquals(24, result.proximityObjects.single().validSampleCount)
        assertFalse(result.proximityObjects.single().walkingObstacleCandidate)
        assertNull(admitted(result))
    }

    @Test fun fullSampleThresholdSelectsFiftySamplesAfterKeepingFortyNineSampleDisplay() {
        val near = Box(42, 42, 49, 49); val next = Box(51, 42, 56, 52)
        val result = runFrames(listOf(Box(40, 40, 60, 55)), fullOnly = true) { x, y -> when {
            near.contains(x, y) -> 1000
            next.contains(x, y) -> 2000
            else -> 8000
        } }.last()
        val observation = result.observations.single()
        assertEquals("ARCORE_FULL_DEPTH", observation.depth.source)
        assertEquals(1f, observation.proximityDistanceM!!, 0f)
        assertEquals(49, observation.proximityMask!!.area)
        assertNotEquals(observation.proximityRegionId, result.proximityObjects.single().trackId)
        assertScopedWarning(result, 2f, 50)
    }

    @Test fun fullSampleThresholdStillBlocksFortyNineSamplesWithoutDiscardingItsDisplay() {
        val near = Box(42, 42, 49, 49)
        val result = runFrames(listOf(Box(40, 40, 60, 55)), fullOnly = true) { x, y ->
            if (near.contains(x, y)) 1000 else 8000
        }.last()
        val observation = result.observations.single()
        assertEquals("ARCORE_FULL_DEPTH", observation.depth.source)
        assertEquals(1f, observation.proximityDistanceM!!, 0f)
        assertEquals(49, observation.proximityMask!!.area)
        assertTrue(observation.walkingSelection!!.show)
        assertFalse(observation.walkingSelection.warningCandidate)
        assertFalse(result.proximityObjects.single().walkingObstacleCandidate)
        assertNull(admitted(result))
    }

    @Test fun ambiguousScatteredNearDepthRemainsUnconfirmedWithoutInventedRegions() {
        val result = runFrames(listOf(Box(20, 20, 80, 80)), smoothedFar = true) { x, y ->
            if ((x + y) % 2 == 0) 1000 else 8000
        }.last()
        val observation = result.observations.single()
        assertEquals(MaskDepthEstimator.Status.UNKNOWN, observation.depth.status)
        assertEquals("ARCORE_RAW_DEPTH", observation.depth.source)
        assertTrue(observation.depth.nearerLayers.isEmpty())
        // Unconfirmed scattered samples remain diagnostic; v6 requires measured <=3 m for display.
        assertFalse(observation.walkingSelection!!.show)
        assertEquals("depth_unconfirmed", observation.walkingSelection.reason)
        assertFalse(observation.walkingSelection.warningCandidate)
        assertNull(observation.proximityDistanceM)
        assertTrue(result.proximityObjects.isEmpty())
        assertNull(admitted(result))
    }
}
