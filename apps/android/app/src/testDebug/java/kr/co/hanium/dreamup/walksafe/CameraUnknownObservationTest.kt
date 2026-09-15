package kr.co.hanium.dreamup.walksafe

import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.BinaryImageMask
import kr.co.hanium.dreamup.walksafe.depth.ConfidenceImage8
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthImage16
import kr.co.hanium.dreamup.walksafe.depth.MaskAssociationStatus
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.depth.unknown.FrozenUnknownDepthCapture
import kr.co.hanium.dreamup.walksafe.depth.unknown.MaskDepthEstimator
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownDepthObservation
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownDepthFrameResult
import kr.co.hanium.dreamup.walksafe.depth.unknown.UnknownObjectDepthPipeline
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import org.junit.Assert.*
import org.junit.Test

class CameraUnknownObservationTest {
    private fun observation(): UnknownDepthObservation {
        val mask = BinaryImageMask.fromPackedRoi(40, 40, 5, 5, 20, 20, ByteArray(50) { -1 })
        val identity = MaskDepthEstimator.FrameIdentity("test", "frame", 1_000_000_000L, "camera")
        val calibration = MaskDepthEstimator.Calibration.affine(identity, 40, 40, 40, 40,
            doubleArrayOf(.025, 0.0, 0.0, 0.0, .025, 0.0), "synthetic_calibration", null)
        val adapter = object : MaskDepthEstimator.ImageMask {
            override fun imageWidth() = 40
            override fun imageHeight() = 40
            override fun minX() = 5
            override fun minY() = 5
            override fun maxXExclusive() = 25
            override fun maxYExclusive() = 25
            override fun contains(x: Int, y: Int) = mask.contains(x, y)
        }
        val frame = MaskDepthEstimator.DepthFrame.raw(identity, identity.cameraTimestampNs(), "camera",
            IntArray(1600) { 8000 }, ByteArray(1600) { -1 }, calibration, identity)
        val result = MaskDepthEstimator().associate(listOf(MaskDepthEstimator.Detection("mask", "unknown", identity, adapter)), frame).single()
        assertEquals(MaskDepthEstimator.Status.KNOWN, result.status)
        return UnknownDepthObservation(0, mask, result, "track", MaskAssociationStatus.MATCHED)
    }

    @Test fun partialCoverageNeverDisplaysTheSamplerDiagnosticAsWholeDistance() {
        val observation = observation().copy(suppressionReason = "mask_outside_calibrated_depth_coverage")
        assertNotNull(observation.depth.axialDepthM)
        assertNull(CameraUnknownObservation.distanceM(observation))
    }

    @Test fun supportedNearSurfaceUsesItsOwnMaskDistanceAndFeedbackRegion() {
        val original = observation()
        val nearby = BinaryImageMask.fromPackedRoi(40, 40, 5, 5, 10, 10, ByteArray(13) { -1 })
        val observation = original.copy(proximityDistanceM = 1f, proximityRegionId = "region",
            proximityMask = nearby)
        assertEquals(1.0, CameraUnknownObservation.distanceM(observation)!!, 0.0)
        assertSame(nearby, CameraUnknownObservation.mask(observation))
        assertEquals("region", CameraUnknownObservation.feedbackId(observation))
        assertEquals(8.0, CameraUnknownObservation.distanceM(original)!!, 0.0)
    }

    @Test fun realMixedDepthPipelineDisplaysOnlyTheSupportedNearSurfaceBounds() {
        val result = mixedResult()
        val observation = result.observations.single()
        val displayedMask = CameraUnknownObservation.mask(observation)

        assertEquals(1.0, CameraUnknownObservation.distanceM(observation)!!, 0.0)
        assertEquals(45, displayedMask.left)
        assertEquals(21, displayedMask.top)
        assertEquals(10, displayedMask.width)
        assertEquals(58, displayedMask.height)
        assertFalse(displayedMask.contains(30, 40))
        assertTrue(displayedMask.contains(50, 40))
        assertNotNull(CameraUnknownObservation.feedbackId(observation))
        assertNull(observation.metricExtent)
    }

    @Test fun separateWarningKeepsItsOwnDistanceAndBoundsBesideTheNearerDisplayPatch() {
        val result = mixedResult()
        val observation = result.observations.single()
        val warning = result.proximityObjects.single().copy(trackId = "independent-warning-region",
            bboxNorm = RectNorm(.6f, .4f, .1f, .2f), riskDistanceM = 2f, walkingObstacleCandidate = true,
            userFacing = UserFacingDepth(null, MessageLevel.WARNING, "전방 물체. 멈출 준비를 하세요."))
        val additional = CameraUnknownObservation.unrepresentedWarnings(listOf(observation), listOf(warning)).single()
        assertSame(warning, additional)
        assertEquals(2f, additional.riskDistanceM!!, 0f)
        assertEquals(.6f, additional.bboxNorm.x, 0f)
        assertEquals(1.0, CameraUnknownObservation.distanceM(observation)!!, 0.0)
        assertEquals(45, CameraUnknownObservation.mask(observation).left)
        assertNotEquals(CameraUnknownObservation.feedbackId(observation), warning.trackId)
        assertTrue(CameraUnknownObservation.unrepresentedWarnings(listOf(observation),
            listOf(warning.copy(trackId = requireNotNull(CameraUnknownObservation.feedbackId(observation))))).isEmpty())
        assertTrue(CameraUnknownObservation.unrepresentedWarnings(listOf(observation),
            listOf(warning.copy(walkingObstacleCandidate = false))).isEmpty())
        val displayed = CameraUnknownObservation.selectForDisplay(listOf(observation), listOf(warning), listOf(warning))
        assertEquals(2, displayed.size)
        assertSame(warning, displayed[0].output)
        assertEquals(2.0, displayed[0].distanceM!!, 0.0)
        assertEquals(warning.bboxNorm, displayed[0].bboxNorm)
        assertSame(observation, displayed[1].observation)
        assertNull(displayed[1].output)
        assertEquals(1.0, displayed[1].distanceM!!, 0.0)
        assertEquals(.45f, displayed[1].bboxNorm.x, 0f)
    }

    @Test fun finalDisplayCapKeepsSixStopsAheadOfSixAdditionalCautions() {
        val result = mixedResult()
        val template = result.proximityObjects.single()
        val stops = (0..5).map { index -> template.copy(trackId = "stop-$index", riskDistanceM = 2f,
            walkingObstacleCandidate = true, userFacing = UserFacingDepth(null, MessageLevel.STOP, "멈추세요.")) }
        val observations = stops.mapIndexed { index, output ->
            result.observations.single().copy(proximityRegionId = output.trackId, proximityDistanceM = 2f,
                proximityMask = BinaryImageMask.fromPackedRoi(100, 100, index * 12, 30, 10, 10, ByteArray(13) { -1 }))
        }
        val cautions = (0..5).map { index -> template.copy(trackId = "caution-$index", riskDistanceM = 1f,
            walkingObstacleCandidate = true, userFacing = UserFacingDepth(null, MessageLevel.CAUTION, "주의하세요.")) }
        val allOutputs = stops + cautions
        val displayed = CameraUnknownObservation.selectForDisplay(observations, allOutputs, allOutputs)
        assertEquals(6, displayed.size)
        assertEquals(stops.map { it.trackId }, displayed.map { it.feedbackId })
        assertTrue(displayed.all { it.output?.userFacing?.messageLevel == MessageLevel.STOP })
    }

    @Test fun observationAndAdditionalWarningShareTtcThenDistancePriority() {
        val result = mixedResult()
        val observation = result.observations.single()
        val represented = result.proximityObjects.single().copy(riskDistanceM = 1f, timeToCollisionMs = 2_000L,
            walkingObstacleCandidate = true, userFacing = UserFacingDepth(null, MessageLevel.WARNING, "주의하세요."))
        val additional = represented.copy(trackId = "additional-warning", riskDistanceM = 2f, timeToCollisionMs = 1_000L)
        val outputs = listOf(represented, additional)
        assertEquals(additional.trackId, CameraUnknownObservation.selectForDisplay(listOf(observation), outputs, outputs,
            limit = 1).single().feedbackId)
        val sameTtc = listOf(represented, additional.copy(timeToCollisionMs = represented.timeToCollisionMs))
        assertEquals(represented.trackId, CameraUnknownObservation.selectForDisplay(listOf(observation), sameTtc, sameTtc,
            limit = 1).single().feedbackId)
    }

    private fun mixedResult(): UnknownDepthFrameResult {
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1L,
            10_000_000_000L, 10_000_000_000L, 8_000_000_000L, 100_000_000_000L, "camera", 100, 100)
        val samples = IntArray(10_000) { 8000 }
        for (y in 20 until 80) for (x in 45 until 55) samples[y * 100 + x] = 1000
        val snapshot = DepthFrameSnapshot(frameTimestampNs = token.cameraTimestampNs,
            rawDepth = DepthImage16(100, 100, samples),
            rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { -1 }),
            fullDepth = null,
            rawDepthTimestampNs = token.cpuImageTimestampNs,
            rawConfidenceTimestampNs = token.cpuImageTimestampNs,
            cameraImageTimestampNs = token.cpuImageTimestampNs)
        val capture = requireNotNull(FrozenUnknownDepthCapture.freeze(token, snapshot,
            doubleArrayOf(.01, 0.0, 0.0, 0.0, .01, 0.0, 0.0, 0.0, 1.0), token.frameId))
        val bits = BitSet(3600).apply { set(0, 3600) }
        val constructor = InstanceMask::class.java.declaredConstructors.single().apply { isAccessible = true }
        val instance = constructor.newInstance(100, 100, 20, 20, 80, 80, 3600, 17,
            20f, 20f, 80f, 80f, .99f, bits) as InstanceMask
        return UnknownObjectDepthPipeline({ 100_050L }).process(capture, token, listOf(instance), 100_050L)
    }
}
