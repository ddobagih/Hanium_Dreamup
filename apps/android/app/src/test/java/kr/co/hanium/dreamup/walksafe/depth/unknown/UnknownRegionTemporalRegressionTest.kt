package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** V3 independent-review scenes and controls for region continuity, never object motion. */
class UnknownRegionTemporalRegressionTest {
    private var now = 100_050L
    private val feedbackEpoch = WalkRuntimeEpoch("region-v3", 1)

    private fun decodedMasks(): List<InstanceMask> {
        val detections = FloatArray(FastSamDecoder.CHANNELS * FastSamDecoder.ANCHORS)
        val prototypes = FloatArray(FastSamDecoder.PROTO_SIZE * FastSamDecoder.PROTO_SIZE * 32)
        for (i in 0 until FastSamDecoder.PROTO_SIZE * FastSamDecoder.PROTO_SIZE) prototypes[i * 32] = 1f
        for (i in 0..1) {
            val left = if (i == 0) 20f else 35f; val right = if (i == 0) 65f else 80f
            detections[i] = (left + right) / 200f
            detections[FastSamDecoder.ANCHORS + i] = .5f
            detections[2 * FastSamDecoder.ANCHORS + i] = (right - left) / 100f
            detections[3 * FastSamDecoder.ANCHORS + i] = .6f
            detections[4 * FastSamDecoder.ANCHORS + i] = .99f - i * .01f
            detections[5 * FastSamDecoder.ANCHORS + i] = 1f
        }
        return FastSamDecoder().decode(detections, prototypes, 100, 100).instances()
    }

    private fun frame(pipeline: UnknownObjectDepthPipeline, atMs: Long, depthMm: Int = 1000,
                      depthAtMs: Long = atMs, masks: List<InstanceMask> = decodedMasks().take(1),
                      quarterTurns: Int = 0, supportTop: Int = 40): UnknownDepthFrameResult {
        val camera = 10_000_000_000L + atMs * 1_000_000L
        val depthTime = 10_000_000_000L + depthAtMs * 1_000_000L
        val token = FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1, camera, camera,
            camera, 100_000_000_000L + atMs * 1_000_000L, "region-v3", 100, 100)
        now = token.capturedElapsedNs / 1_000_000L + 50L
        val snapshot = DepthFrameSnapshot(frameTimestampNs = camera,
            rawDepth = DepthImage16(100, 100, IntArray(10_000) {
                if (it % 100 in 42..57 && it / 100 in supportTop until supportTop + 20) depthMm else 8000
            }), rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { -1 }), fullDepth = null,
            rawDepthTimestampNs = depthTime, rawConfidenceTimestampNs = depthTime,
            cameraImageTimestampNs = camera,
            cameraPoseEvidence = CameraPoseEvidence(1, camera / 1_000_000L, 0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(100, 100, 100f, 100f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f)))
        val capture = requireNotNull(FrozenUnknownDepthCapture.freeze(token, snapshot,
            doubleArrayOf(.01, 0.0, 0.0, 0.0, .01, 0.0, 0.0, 0.0, 1.0), token.frameId,
            imageQuarterTurns = quarterTurns))
        return pipeline.process(capture, token, masks, now)
    }

    private fun warningCount(result: UnknownDepthFrameResult): Int = UnknownObjectFeedbackPolicy().admit(
        result.objects + result.proximityObjects, result.token.frameId, result.token.cameraTimestampNs / 1_000_000L,
        result.token.capturedElapsedNs, now, feedbackEpoch, feedbackEpoch, now
    )?.outputs?.mapNotNull { it.toFeedbackCandidate() }?.size ?: 0

    @Test fun overlappingDecodedMasksShareOneRegionOneCountAndOneFeedbackCandidate() {
        val masks = decodedMasks(); assertEquals(2, masks.size)
        val pipeline = UnknownObjectDepthPipeline({ now })
        val frames = (0..11).map { i -> frame(pipeline, i * 250L, masks = masks).also { result ->
            assertEquals(if (i < 3) 0 else 1, warningCount(result))
            assertEquals(1, result.proximityObjects.size)
            assertEquals(320, result.proximityObjects.single().validSampleCount)
            assertEquals(i + 1, result.proximityObjects.single().trackAgeFrames)
            assertEquals(1, result.observations.map { it.proximityRegionId }.distinct().size)
            assertTrue(result.observations.all { it.proximityDistanceM == 1f })
        } }
        assertEquals(1, frames.map { it.proximityObjects.single().trackId }.distinct().size)
    }

    @Test fun reusedRawFramesPreserveOnlyIndependentEvidenceAndNeverCreateCurrentWarnings() {
        val pipeline = UnknownObjectDepthPipeline({ now })
        val ids = mutableSetOf<String>()
        for (i in 0..11) {
            val result = frame(pipeline, i * 250L, depthAtMs = (i - i % 2) * 250L)
            if (i % 2 == 1) {
                assertTrue(result.proximityObjects.isEmpty())
                assertEquals(0, warningCount(result))
                assertEquals(ids, result.retainedProximityRegionIds)
            } else {
                val region = result.proximityObjects.single(); ids += region.trackId
                assertEquals(i / 2 + 1, region.trackAgeFrames)
                assertEquals(i * 250L, region.trackStableMs)
                assertEquals(if (i < 4) 0 else 1, warningCount(result))
                assertTrue(result.retainedProximityRegionIds.isEmpty())
            }
        }
        assertEquals(1, ids.size)
    }

    @Test fun reusedSupportOnlyAuthorizesTheOriginalUnexpiredFeedbackBatch() {
        val pipeline = UnknownObjectDepthPipeline({ now })
        val measured = (0..3).map { frame(pipeline, it * 250L) }.last()
        val original = requireNotNull(UnknownObjectFeedbackPolicy().admit(measured.proximityObjects,
            measured.token.frameId, measured.token.cameraTimestampNs / 1_000_000L,
            measured.token.capturedElapsedNs, now, feedbackEpoch, feedbackEpoch, now))
        val deadline = original.validUntilElapsedRealtimeMs
        val reused = frame(pipeline, 1000L, depthAtMs = 750L)
        assertEquals(original.outputs.map { it.trackId }.toSet(), reused.retainedProximityRegionIds)
        assertEquals(0, warningCount(reused))
        assertTrue(original.isFreshAt(now))
        assertEquals(deadline, original.validUntilElapsedRealtimeMs)
        assertFalse(original.isFreshAt(deadline + 1L))
        val stale = frame(pipeline, 1250L, depthAtMs = 750L)
        assertTrue(stale.retainedProximityRegionIds.isEmpty())
        assertTrue(frame(pipeline, 1500L, masks = emptyList()).retainedProximityRegionIds.isEmpty())
    }

    @Test fun proximityCorridorUsesCaptureRotationWhileMeasuredPixelsStayInSensorCoordinates() {
        val upright = frame(UnknownObjectDepthPipeline({ now }), 0L, supportTop = 62)
        val rotated = frame(UnknownObjectDepthPipeline({ now }), 0L, quarterTurns = 1, supportTop = 62)
        val a = upright.proximityObjects.single(); val b = rotated.proximityObjects.single()
        assertEquals(a.bboxNorm, b.bboxNorm)
        assertEquals(a.validSampleCount, b.validSampleCount)
        assertEquals(1f, a.confidence.corridorQuality, 0f)
        assertEquals(.45f, b.confidence.corridorQuality, 0f)
    }

    @Test fun approachAtDifferentSamplingIntervalsKeepsCurrentDistanceWithoutMotionAttribution() {
        for (period in listOf(125L, 250L, 500L)) {
            val pipeline = UnknownObjectDepthPipeline({ now })
            val results = (0L..2500L step period).map { at ->
                // Keep the continuity experiment wholly inside the new 3 m region-admission scope.
                frame(pipeline, at, depthMm = 2800 - (at * .8).toInt())
            }
            assertEquals(1, results.map { it.proximityObjects.single().trackId }.distinct().size)
            assertEquals(1, warningCount(results.last()))
            for (result in results) {
                val output = result.proximityObjects.single()
                assertTrue(requireNotNull(output.rayDistanceM) <= 3f)
                assertEquals(result.observations.single().proximityDistanceM, output.riskDistanceM)
                assertEquals(Trend.UNKNOWN, output.trend)
                assertEquals(ObjectMotion.UNKNOWN, output.objectMotion)
                assertNull(output.approachSpeedMps)
                assertNull(output.timeToCollisionMs)
            }
        }
    }

    @Test fun oneNearJumpCannotBorrowMatureInRangeRegionHistory() {
        val pipeline = UnknownObjectDepthPipeline({ now })
        // Previously 4 m, now 2.8 m so both surfaces are eligible for the identity-reset experiment.
        (0..4).forEach { frame(pipeline, it * 250L, depthMm = 2800) }
        val jump = frame(pipeline, 1250L, depthMm = 1000)
        assertEquals(1, jump.proximityObjects.single().trackAgeFrames)
        assertEquals(0, warningCount(jump))
        val returned = frame(pipeline, 1500L, depthMm = 2800)
        assertEquals(1, returned.proximityObjects.single().trackAgeFrames)
        assertEquals(0, warningCount(returned))
    }

    @Test fun supportedRegionBeyondThreeMetersCannotBecomeCurrentDisplayOrWarning() {
        val pipeline = UnknownObjectDepthPipeline({ now })
        for (i in 0..4) {
            val far = frame(pipeline, i * 250L, depthMm = 3001)
            assertTrue(far.proximityObjects.isEmpty())
            assertFalse(far.observations.single().walkingSelection!!.show)
            assertFalse(far.observations.single().walkingSelection!!.warningCandidate)
            assertEquals(0, warningCount(far))
        }
    }

    @Test fun actualMaskLossOrMissingDepthSupportClearsHistoryBetweenFreshSamples() {
        for (loseMask in listOf(true, false)) {
            val pipeline = UnknownObjectDepthPipeline({ now })
            (0..4).forEach { frame(pipeline, it * 250L) }
            frame(pipeline, 1250L, depthMm = if (loseMask) 1000 else 8000,
                masks = if (loseMask) emptyList() else decodedMasks().take(1))
            val returned = frame(pipeline, 1500L)
            assertEquals(1, returned.proximityObjects.single().trackAgeFrames)
            assertEquals(0, warningCount(returned))
            assertTrue(returned.retainedProximityRegionIds.isEmpty())
        }
    }

    @Test fun staleRepeatedDepthCannotBridgeTheNextFreshMeasurement() {
        val pipeline = UnknownObjectDepthPipeline({ now })
        (0..4).forEach { frame(pipeline, it * 250L) }
        for (at in listOf(1250L, 1500L, 1750L, 2000L, 2250L)) {
            val reused = frame(pipeline, at, depthAtMs = 1000L)
            assertTrue(reused.proximityObjects.isEmpty())
            assertEquals(0, warningCount(reused))
        }
        assertEquals(1, frame(pipeline, 2500L).proximityObjects.single().trackAgeFrames)
    }

    private fun sample(index: Int = 0, left: Int = 40, width: Int = 20, depthM: Float = 1f,
                       atMs: Long = 0L, fresh: Boolean = true,
                       source: DepthSource = DepthSource.ARCORE_RAW_DEPTH): UnknownProximityRegions.Sample {
        val mask = BinaryImageMask.fromPackedRoi(100, 100, left, 40, width, 20,
            ByteArray((width * 20 + 7) / 8) { -1 })
        val box = RectNorm(left / 100f, .4f, width / 100f, .2f)
        val geometry = ObjectGeometry(UNNAMED_OBSTACLE_CLASS, .99f, box, bboxPolygon(box, 0f),
            mask.area / 10_000f, box.center, null)
        val stats = DepthStats(mask.area, 1f, depthM, null, null, null, 0f, 0f, 1f, 0f)
        return UnknownProximityRegions.Sample(index, mask, geometry, source, stats, depthM,
            10_000_000_000L + atMs * 1_000_000L, fresh)
    }

    @Test fun separatedOrDifferentDepthSurfacesStayIndependentEvenWithOverlappingBoxes() {
        for (other in listOf(sample(1, left = 61), sample(1, depthM = 2f))) {
            val regions = UnknownProximityRegions()
            var current = emptyList<UnknownProximityRegions.Observation>()
            for (at in 0L..1000L step 250L) current = regions.update(
                listOf(sample(atMs = at), other.copy(depthTimestampNs = 10_000_000_000L + at * 1_000_000L)), at)
            assertEquals(2, current.size)
            assertTrue(current.all { it.count == 5 })
            assertEquals(2, current.map { it.regionId }.distinct().size)
        }
    }

    @Test fun duplicateDepthAndIouThresholdsDoNotAdmitTheirOutsideControls() {
        fun groups(other: UnknownProximityRegions.Sample) =
            UnknownProximityRegions().update(listOf(sample(), other), 0L).size
        assertEquals(1, groups(sample(1, left = 41, depthM = 1.14f)))
        assertEquals(2, groups(sample(1, left = 42, depthM = 1.14f)))
        assertEquals(2, groups(sample(1, left = 41, depthM = 1.16f)))
    }

    @Test fun intermediateOverlappingMaskCannotBridgeDistinctSupportGroups() {
        val current = UnknownProximityRegions().update(listOf(sample(), sample(1, left = 41),
            sample(2, left = 42)), 0L)
        assertEquals(2, current.size)
        assertFalse(current.any { it.sourceIndices.containsAll(listOf(0, 2)) })
        assertEquals(setOf(0, 1, 2), current.flatMap { it.sourceIndices }.toSet())
    }

    @Test fun trueSplitAndMergeResetOnceThenCanAccumulateNewRegionEvidence() {
        val regions = UnknownProximityRegions()
        (0L..750L step 250L).forEach { regions.update(listOf(sample(atMs = it)), it) }
        val split = regions.update(listOf(sample(atMs = 1000L, width = 13),
            sample(1, left = 47, width = 13, atMs = 1000L)), 1000L)
        assertEquals(2, split.size)
        assertTrue(split.all { it.count == 1 })
        val merged = regions.update(listOf(sample(atMs = 1250L)), 1250L).single()
        assertEquals(1, merged.count)
        val next = regions.update(listOf(sample(atMs = 1500L)), 1500L).single()
        assertEquals(merged.regionId, next.regionId)
        assertEquals(2, next.count)
    }

    @Test fun nonIncreasingFrameTimeAndDepthTimeCannotExtendAnOldHistory() {
        for ((frameMs, depthMs) in listOf(750L to 1000L, 700L to 1000L, 1000L to 750L, 1000L to 500L)) {
            val regions = UnknownProximityRegions()
            (0L..750L step 250L).forEach { regions.update(listOf(sample(atMs = it)), it) }
            assertEquals(1, regions.update(listOf(sample(atMs = depthMs)), frameMs).single().count)
        }
    }

    @Test fun rawToFullSourceChangeCannotBorrowIndependentHistoryOrDeduplicateSupport() {
        val regions = UnknownProximityRegions()
        (0L..750L step 250L).forEach { regions.update(listOf(sample(atMs = it)), it) }
        val changed = regions.update(listOf(sample(atMs = 1000L, source = DepthSource.ARCORE_FULL_DEPTH)), 1000L)
        assertEquals(1, changed.single().count)
        assertEquals(2, UnknownProximityRegions().update(listOf(sample(),
            sample(1, source = DepthSource.ARCORE_FULL_DEPTH)), 0L).size)
    }

    @Test fun reusedGeometryMismatchAndLongDuplicateOnlySequenceCannotRefreshEvidenceLifetime() {
        for (changedGeometry in listOf(false, true)) {
            val regions = UnknownProximityRegions()
            (0L..750L step 250L).forEach { regions.update(listOf(sample(atMs = it)), it) }
            val last = if (changedGeometry) 1000L else 1750L
            for (at in 1000L..last step 250L) assertTrue(regions.update(listOf(sample(
                left = if (changedGeometry) 65 else 40, atMs = 750L, fresh = false)), at).isEmpty())
            assertTrue(regions.retainedRegionIds.isEmpty())
            assertEquals(1, regions.update(listOf(sample(atMs = last + 250L)), last + 250L).single().count)
        }
    }

    @Test fun approachEnvelopeAcceptsElapsedTimeButRejectsLargeJumpsAndExpiredGaps() {
        for ((elapsed, distance, expectedCount) in listOf(Triple(250L, 3.68f, 2),
            Triple(500L, 3.36f, 2), Triple(250L, 3.3f, 1), Triple(800L, 2.8f, 1), Triple(801L, 4f, 1))) {
            val regions = UnknownProximityRegions()
            regions.update(listOf(sample(depthM = 4f)), 0L)
            val next = regions.update(listOf(sample(depthM = distance, atMs = elapsed)), elapsed)
            assertEquals(expectedCount, next.single().count)
        }
    }
}
