package kr.co.hanium.dreamup.walksafe.depth.unknown

import java.util.BitSet
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class UnknownProximityRegressionTest {
    private var now = 100_050L
    private fun pipeline() = UnknownObjectDepthPipeline({ now })
    private fun token(i: Int, epoch: Long = 1L) = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA,
        epoch, 10_000_000_000L + i * 250_000_000L, 10_000_000_000L + i * 250_000_000L,
        8_000_000_000L + i * 250_000_000L, 100_000_000_000L + i * 250_000_000L, "audit", 100, 100)
    private fun mask(left: Int = 20, right: Int = 80): InstanceMask {
        val bits = BitSet((right - left) * 60).also { it.set(0, (right - left) * 60) }
        val constructor = InstanceMask::class.java.declaredConstructors.single().also { it.isAccessible = true }
        return constructor.newInstance(100, 100, left, 20, right, 80, bits.cardinality(), 17,
            left.toFloat(), 20f, right.toFloat(), 80f, .99f, bits) as InstanceMask
    }
    private fun capture(t: FastSamFrameToken, mixed: Boolean = false, noise: Boolean = false,
                        crop: Boolean = false, full: Boolean = false, repeatedRaw: Boolean = false,
                        dual: Boolean = false, sameLayer: Boolean = false): FrozenUnknownDepthCapture {
        val mm = IntArray(10_000) { if (mixed || noise || dual) 8000 else 1000 }
        if (mixed) for (y in 20 until 80) for (x in 45 until 55) mm[y * 100 + x] = 1000
        if (noise) for (y in 22 until 78 step 2) for (x in 22 until 78 step 2) mm[y * 100 + x] = 1000
        if (dual) for (y in 20 until 80) {
            for (x in 12 until if (sameLayer) 24 else 22) mm[y * 100 + x] = if (sameLayer) 1000 else 500
            for (x in 45 until 55) mm[y * 100 + x] = 1000
        }
        val depthTime = if (repeatedRaw) token(0).cpuImageTimestampNs else t.cpuImageTimestampNs
        val snapshot = DepthFrameSnapshot(frameTimestampNs = t.cameraTimestampNs,
            rawDepth = DepthImage16(100, 100, mm), rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { -1 }),
            fullDepth = if (full) DepthImage16(100, 100, IntArray(10_000) { 8000 }) else null,
            rawDepthTimestampNs = depthTime, rawConfidenceTimestampNs = depthTime,
            cameraImageTimestampNs = t.cpuImageTimestampNs, fullDepthTimestampNs = t.cpuImageTimestampNs,
            cameraPoseEvidence = CameraPoseEvidence(1, t.cameraTimestampNs / 1_000_000L, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(100, 100, 100f, 100f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f)))
        return requireNotNull(FrozenUnknownDepthCapture.freeze(t, snapshot,
            doubleArrayOf(.01, 0.0, if (crop) .4 else 0.0, 0.0, .01, 0.0, 0.0, 0.0, 1.0), t.frameId))
    }
    private fun run(p: UnknownObjectDepthPipeline, i: Int, masks: List<InstanceMask> = listOf(mask()),
                    mixed: Boolean = false, noise: Boolean = false, crop: Boolean = false,
                    full: Boolean = false, repeatedRaw: Boolean = false, epoch: Long = 1L,
                    dual: Boolean = false, sameLayer: Boolean = false): UnknownDepthFrameResult {
        val t = token(i, epoch); now = t.capturedElapsedNs / 1_000_000L + 50L
        return p.process(capture(t, mixed, noise, crop, full, repeatedRaw, dual, sameLayer), t, masks, now)
    }

    @Test fun significantNearLayerCannotBecomeWholeFarDistanceOrBeSmoothedAway() {
        val result = run(pipeline(), 0, mixed = true, full = true)
        val observation = result.observations.single()
        assertEquals("ARCORE_RAW_DEPTH", observation.depth.source)
        assertEquals(MaskDepthEstimator.Status.PARTIAL, observation.depth.status)
        assertTrue(observation.depth.componentDepthConflict)
        assertNull(observation.depth.axialDepthM)
        assertEquals(580, observation.depth.nearerLayers.single().inlierPixels)
        assertEquals(1f, observation.proximityDistanceM!!, 0f)
        assertTrue(observation.walkingSelection!!.show)
        assertNull(observation.metricExtent)
        assertNull(result.objects.single().riskDistanceM)
        assertFalse(result.objects.single().walkingObstacleCandidate)
        assertFalse(result.proximityObjects.single().walkingObstacleCandidate)
        assertFalse(observation.proximityMask!!.contains(30, 40))
        assertTrue(observation.proximityMask.contains(50, 40))
    }

    @Test fun scatteredNearOutliersCannotBecomeProximityEvenWhenNumerous() {
        val result = run(pipeline(), 0, noise = true)
        val observation = result.observations.single()
        assertEquals(MaskDepthEstimator.Status.KNOWN, observation.depth.status)
        assertEquals(8.0, observation.depth.axialDepthM!!, 0.0)
        assertTrue(observation.depth.nearerLayers.isEmpty())
        assertTrue(result.proximityObjects.isEmpty())
        assertFalse(observation.walkingSelection!!.show)
    }

    @Test fun nearerSideLayerCannotSuppressAnotherSupportedForwardLayer() {
        val p = pipeline()
        val result = (0..4).map { run(p, it, masks = listOf(mask(10, 90)), dual = true) }.last()
        val observation = result.observations.single()
        assertEquals(listOf(.5, 1.0), observation.depth.nearerLayers.map { it.axialDepthM })
        assertEquals(1f, observation.proximityDistanceM!!, 0f)
        assertTrue(observation.walkingSelection!!.show)
        assertTrue(result.proximityObjects.single().walkingObstacleCandidate)
        assertNull(observation.depth.axialDepthM)
        assertTrue(observation.mask.contains(15, 40))
        assertFalse(observation.proximityMask!!.contains(15, 40))
        assertTrue(observation.proximityMask.contains(50, 40))
    }

    @Test fun proximityMaskBoundsEncloseOnlyItsMeasuredForegroundAndKeepOriginalObservation() {
        val result = run(pipeline(), 0, mixed = true)
        val observation = result.observations.single()
        val surface = requireNotNull(observation.proximityMask)
        assertEquals(listOf(45, 21, 10, 58), listOf(surface.left, surface.top, surface.width, surface.height))
        assertEquals(580, surface.area)
        assertEquals(listOf(20, 20, 60, 60), listOf(observation.mask.left, observation.mask.top,
            observation.mask.width, observation.mask.height))
        assertEquals(3600, observation.mask.area)
        val bbox = result.proximityObjects.single().bboxNorm
        assertEquals(surface.left / 100f, bbox.x, 0f)
        assertEquals(surface.top / 100f, bbox.y, 0f)
        assertEquals(surface.width / 100f, bbox.width, 0f)
        assertEquals(surface.height / 100f, bbox.height, 0f)
    }

    @Test fun largerSideRegionAtSameDepthCannotDiscardIndependentlySupportedForwardRegion() {
        val p = pipeline()
        val result = (0..4).map { run(p, it, masks = listOf(mask(10, 90)), dual = true, sameLayer = true) }.last()
        val observation = result.observations.single()
        assertEquals(listOf(580, 696), observation.depth.nearerLayers.map { it.inlierPixels }.sorted())
        assertEquals(1f, observation.proximityDistanceM!!, 0f)
        assertTrue(observation.walkingSelection!!.show)
        assertTrue(result.proximityObjects.single().walkingObstacleCandidate)
        assertFalse(observation.proximityMask!!.contains(15, 40))
        assertTrue(observation.proximityMask.contains(50, 40))
    }

    @Test fun mergedNearMaskShowsImmediatelyAndRepeatedRegionCanWarnWithoutAnObjectId() {
        val p = pipeline()
        (0..4).forEach { run(p, it, listOf(mask(10, 50), mask(40, 80))) }
        val merged = (5..9).map { run(p, it, listOf(mask(25, 65))) }
        assertTrue(merged.all { it.objects.isEmpty() && it.observations.single().trackId == null })
        assertTrue(merged.all { it.observations.single().associationStatus == MaskAssociationStatus.AMBIGUOUS })
        assertTrue(merged.all { it.observations.single().walkingSelection!!.show })
        assertFalse(merged.first().proximityObjects.single().walkingObstacleCandidate)
        assertTrue(merged.last().proximityObjects.single().walkingObstacleCandidate)
        assertEquals(1, merged.map { it.proximityObjects.single().trackId }.distinct().size)
        assertTrue(merged.all { it.proximityObjects.single().let { o ->
            o.trend == Trend.UNKNOWN && o.approachSpeedMps == null && o.timeToCollisionMs == null &&
                o.objectMotion == ObjectMotion.UNKNOWN
        } })
        assertNotNull(merged.last().proximityObjects.single().userFacing.message)
    }

    @Test fun repeatedNearLayerUsesFreshCurrentDistanceWithoutWholeExtentOrMotion() {
        val p = pipeline()
        val results = (0..4).map { run(p, it, mixed = true) }
        assertTrue(results.last().proximityObjects.single().walkingObstacleCandidate)
        assertTrue(results.last().proximityObjects.single().confidence.finalScore >= .55f)
        assertTrue(results.all { it.observations.single().metricExtent == null })
        assertTrue(results.all { it.proximityObjects.single().timeToCollisionMs == null })
        p.missed()
        val afterMiss = run(p, 5, mixed = true)
        assertEquals(1, afterMiss.proximityObjects.single().trackAgeFrames)
        assertFalse(afterMiss.proximityObjects.single().walkingObstacleCandidate)
    }

    @Test fun proximityUsesExistingAdmissionAndGlobalDeliveryCooldown() {
        val p = pipeline(); val admission = UnknownObjectFeedbackPolicy(); val queue = WalkSafeFeedbackPolicy()
        val epoch = WalkRuntimeEpoch("proximity-test", 1)
        fun admit(result: UnknownDepthFrameResult, at: Long = now) = admission.admit(
            result.objects + result.proximityObjects, result.token.frameId, result.token.cameraTimestampNs / 1_000_000L,
            result.token.capturedElapsedNs, now, epoch, epoch, at)
        assertNull(admit(run(p, 0, mixed = true)))
        (1..3).forEach { run(p, it, mixed = true) }
        val result = run(p, 4, mixed = true)
        val batch = requireNotNull(admit(result))
        val candidates = batch.outputs.mapNotNull { it.toFeedbackCandidate() }
        val first = requireNotNull(queue.evaluateCandidates(candidates, true, now))
        assertTrue(queue.claimFeedbackDelivery(first.trackId, now))
        assertTrue(queue.confirmFeedbackDelivery(first.trackId, now, now + 10))
        val next = requireNotNull(admit(run(p, 5, mixed = true)))
        assertNull(queue.evaluateCandidates(next.outputs.mapNotNull { it.toFeedbackCandidate() }, true, now))
        assertNull(admit(result, result.token.capturedElapsedNs / 1_000_000L + 801L))
    }

    @Test fun partialCoverageRetainsDiagnosticsWithoutClaimingThreeMeterDisplayOrWholeMeasurements() {
        val p = pipeline()
        val result = (0..4).map { run(p, it, crop = true) }.last()
        val observation = result.observations.single()
        // Whole-mask range is unconfirmed: preserve its diagnostics, but repetition cannot admit it.
        assertFalse(observation.walkingSelection!!.show)
        assertFalse(observation.walkingSelection.warningCandidate)
        assertEquals("depth_unconfirmed", observation.walkingSelection.reason)
        assertNull(observation.metricExtent)
        assertNull(result.objects.single().riskDistanceM)
        assertNull(observation.proximityDistanceM)
        assertTrue(result.proximityObjects.isEmpty())
        assertFalse(result.objects.single().walkingObstacleCandidate)
    }

    @Test fun repeatedDepthEpochAndExpiredFramesCannotBorrowRegionStability() {
        val p = pipeline()
        (0..4).forEach { run(p, it, mixed = true) }
        assertTrue(run(p, 5, mixed = true, repeatedRaw = true).proximityObjects.isEmpty())
        assertEquals(1, run(p, 6, mixed = true).proximityObjects.single().trackAgeFrames)
        assertEquals(1, run(p, 7, mixed = true, epoch = 2).proximityObjects.single().trackAgeFrames)
        val t = token(8, 2); now = t.capturedElapsedNs / 1_000_000L + 1001
        val expired = p.process(capture(t, mixed = true), t, listOf(mask()), now)
        assertTrue(expired.proximityObjects.isEmpty())
        assertEquals("source_frame_expired", expired.rejectionReason)
        assertEquals(1, run(p, 9, mixed = true, epoch = 2).proximityObjects.single().trackAgeFrames)
    }
}
