package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.toFeedbackCandidate
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class NamedAmbiguityPresencePipelineRegressionTest {
    @Test
    fun stableNamedCrossingRefusesIdentityWhileSameCaptureDecodedRegionReachesSharedQueue() {
        val decoder = FastSamDecoder()
        val prototypes = FloatArray(FastSamDecoder.PROTO_SIZE * FastSamDecoder.PROTO_SIZE * FastSamDecoder.MASK_CHANNELS)
        // Actual public decoder input: positive channel0 logits, every other coefficient/channel zero.
        for (pixel in 0 until FastSamDecoder.PROTO_SIZE * FastSamDecoder.PROTO_SIZE) {
            prototypes[pixel * FastSamDecoder.MASK_CHANNELS] = 1f
        }
        val separate = decode(decoder, prototypes, listOf(10 to 50, 40 to 80))
        val merged = decode(decoder, prototypes, listOf(25 to 65))
        assertEquals(2, separate.size)
        assertEquals(1, merged.size)
        assertTrue(merged.single().contains(45, 50))
        assertTrue(merged.single().contains(55, 50))

        val namedTracker = ObjectTracker()
        val namedPipeline = ObjectDepthRuntimePipeline(tracker = namedTracker, rawDepthMotionOnly = true)
        var nowElapsedMs = CAPTURE_BASE_MS
        val unknownPipeline = UnknownObjectDepthPipeline({ nowElapsedMs })
        val admission = UnknownObjectFeedbackPolicy()
        val walkEpoch = WalkRuntimeEpoch("named-ambiguity-presence", 1)
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))
        val beforeCrossing = namedBoxes(.20f, .60f)
        val crossing = namedBoxes(.36f, .44f)
        var establishedIds = emptySet<String>()
        val mergedResults = mutableListOf<UnknownDepthFrameResult>()
        var finalCapture: FrozenUnknownDepthCapture? = null
        var finalNamed = emptyList<TrackedObjectDepth>()

        for (index in 0..9) {
            val token = token(index)
            val snapshot = snapshot(token)
            nowElapsedMs = token.capturedElapsedNs / 1_000_000L + 50L
            assertTrue(snapshot.hasFreshMetricRawDepth)
            assertEquals(token.cpuImageTimestampNs, snapshot.rawDepthTimestampNs)
            assertEquals(token.cpuImageTimestampNs, snapshot.rawConfidenceTimestampNs)
            assertEquals(token.cpuImageTimestampNs, snapshot.cameraImageTimestampNs)
            val capture = requireNotNull(FrozenUnknownDepthCapture.freeze(token, snapshot,
                doubleArrayOf(.01, 0.0, 0.0, 0.0, .01, 0.0, 0.0, 0.0, 1.0), token.frameId))
            val currentNamed = namedPipeline.process(snapshot, token.frameId, capture.frameTimestampMs,
                detections = if (index == 9) crossing else beforeCrossing,
                mapper = mapper, mapperFrameId = token.frameId,
                capturedAtElapsedRealtimeMs = token.capturedElapsedNs / 1_000_000L)
            assertEquals(2, currentNamed.size)
            assertEquals(setOf(0, 1), currentNamed.map { it.sourceDetectionIndex }.toSet())
            assertTrue(currentNamed.all { it.source == DepthSource.ARCORE_RAW_DEPTH &&
                it.depthAvailability == DepthAvailability.MEASURED && it.validSampleCount >= 30 &&
                it.riskDistanceM != null && it.confidence.hardGate > 0f })
            if (index == 0) establishedIds = currentNamed.map { it.trackId }.toSet()
            if (index in 2..8) {
                assertEquals(establishedIds, currentNamed.map { it.trackId }.toSet())
                val stable = namedTracker.activeTracks().filter { it.missedFrames == 0 }
                assertEquals(2, stable.size)
                assertTrue("the pre-crossing objects must already be stable", stable.all {
                    it.ageFrames >= 3 && it.stable && !it.idSwitchSuspected
                })
            }
            if (index == 9) {
                val currentTracks = namedTracker.activeTracks().filter { it.missedFrames == 0 }
                assertEquals(2, currentTracks.size)
                assertTrue(currentTracks.none { it.trackId in establishedIds })
                assertTrue(currentTracks.all { it.ageFrames == 1 && !it.stable && !it.idSwitchSuspected })
                // Old tracks still exist with one miss: no epoch/expiry/gap reset manufactured these IDs.
                val oldTracks = namedTracker.activeTracks().filter { it.trackId in establishedIds }
                assertEquals(2, oldTracks.size)
                assertTrue(oldTracks.all { it.missedFrames == 1 && it.lastSeenAtMs == this.token(8).cameraTimestampNs / 1_000_000L })
                assertEquals(this.token(8).cameraTimestampNs + FRAME_INTERVAL_NS, token.cameraTimestampNs)
                assertTrue(currentNamed.all { it.trackAgeFrames == 1 && it.trackStableMs == 0L &&
                    it.trend == Trend.UNKNOWN && it.approachSpeedMps == null && it.timeToCollisionMs == null })
                finalCapture = capture
                finalNamed = currentNamed
            }

            val unknown = unknownPipeline.process(capture, token, if (index < 5) separate else merged, nowElapsedMs)
            assertNull(unknown.rejectionReason)
            if (index >= 5) {
                mergedResults += unknown
                val observation = unknown.observations.single()
                assertNull(observation.trackId)
                assertEquals(MaskAssociationStatus.AMBIGUOUS, observation.associationStatus)
                assertTrue(unknown.objects.isEmpty())
                assertEquals(MaskDepthEstimator.Status.KNOWN, observation.depth.status)
                assertEquals("ARCORE_RAW_DEPTH", observation.depth.source)
                assertEquals(token.cpuImageTimestampNs, observation.depth.depthTimestampNs)
                assertTrue(observation.depth.newDepthInformation)
                assertTrue(observation.walkingSelection!!.show)
                val presence = unknown.proximityObjects.single()
                assertEquals(observation.proximityRegionId, presence.trackId)
                assertTrue(presence.trackId.startsWith("unknown-proximity-"))
                assertFalse(presence.trackId in establishedIds)
                assertEquals(Trend.UNKNOWN, presence.trend)
                assertNull(presence.approachSpeedMps)
                assertNull(presence.timeToCollisionMs)
                assertNull(presence.userFacing.stepsAhead)
                assertEquals(ObjectMotion.UNKNOWN, presence.objectMotion)
                assertEquals(ObjectMotionEstimate(), presence.motionEstimate)
                if (index == 5) {
                    assertEquals(1, presence.trackAgeFrames)
                    assertFalse(presence.walkingObstacleCandidate)
                    assertNull(admit(admission, unknown, walkEpoch, walkEpoch, nowElapsedMs))
                }
            }
        }

        val last = mergedResults.last()
        val capture = requireNotNull(finalCapture)
        val observation = last.observations.single()
        val regionMask = requireNotNull(observation.proximityMask)
        val originalMask = merged.single()
        assertSame(capture.token, last.token)
        assertEquals(1L, last.token.sessionEpoch)
        assertEquals("presence-sensor-100x100:q0", last.token.geometryId)
        assertEquals(0, capture.imageQuarterTurns)
        assertEquals(100, regionMask.originalWidth)
        assertEquals(100, regionMask.originalHeight)
        assertEquals(1, mergedResults.map { it.proximityObjects.single().trackId }.distinct().size)
        assertEquals(5, last.proximityObjects.single().trackAgeFrames)
        assertTrue(last.proximityObjects.single().trackStableMs >= 700L)
        assertTrue(last.proximityObjects.single().walkingObstacleCandidate)

        // Same actual foreground, not an unrelated Unknown warning elsewhere in the frame.
        for (y in 0 until 100) for (x in 0 until 100) {
            assertEquals(originalMask.contains(x, y), observation.mask.contains(x, y))
            assertEquals(originalMask.contains(x, y), regionMask.contains(x, y))
            for (named in finalNamed) {
                val box = named.bboxNorm
                val centerX = (x + .5f) / 100
                val centerY = (y + .5f) / 100
                if (centerX >= box.x && centerX < box.x + box.width &&
                    centerY >= box.y && centerY < box.y + box.height) {
                    assertTrue("crossing named foreground must be in this actual decoded region", regionMask.contains(x, y))
                }
            }
        }
        for (named in finalNamed) {
            assertEquals(last.token.frameId, named.frameId)
            assertEquals(capture.frameTimestampMs, named.timestampMs)
            assertTrue(regionMask.contains((named.centerNorm.x * 100).toInt(), (named.centerNorm.y * 100).toInt()))
        }
        val presence = last.proximityObjects.single()
        assertEquals(UNNAMED_OBSTACLE_CLASS, presence.className)
        assertEquals(last.token.frameId, presence.frameId)
        assertEquals(capture.frameTimestampMs, presence.timestampMs)
        assertTrue(presence.rayDistanceM!! in 0f..3f)
        assertNotNull(presence.userFacing.message)

        val batch = requireNotNull(admit(admission, last, walkEpoch, walkEpoch, nowElapsedMs))
        assertSame(walkEpoch, batch.sourceEpoch)
        assertEquals(last.token.frameId, batch.sourceFrameId)
        assertEquals(last.token.capturedElapsedNs, batch.sourceCapturedAtElapsedRealtimeNs)
        assertTrue(batch.isFreshAt(nowElapsedMs))
        val candidate = batch.outputs.single().toFeedbackCandidate()!!
        assertEquals(presence.trackId, candidate.trackId)
        assertNull(candidate.timeToCollisionMs)
        assertEquals(last.token.capturedElapsedNs / 1_000_000L, candidate.observedAtElapsedRealtimeMs)
        assertTrue(candidate.validUntilMs <= batch.validUntilElapsedRealtimeMs)
        val queue = WalkSafeFeedbackPolicy()
        val reserved = requireNotNull(queue.evaluateCandidates(listOf(candidate), true, nowElapsedMs))
        assertEquals(presence.trackId, reserved.trackId)
        assertEquals(batch.outputs.single().userFacing.message, reserved.message)
        // Use the actual queue cancellation boundary; no fake actor, Image or Main lifecycle is involved.
        queue.cancelFeedbackForTracks(setOf(reserved.trackId))
        assertFalse(queue.claimFeedbackDelivery(reserved, nowElapsedMs, nowElapsedMs))
        assertFalse(queue.confirmFeedbackDelivery(reserved, nowElapsedMs, nowElapsedMs))
        val reofferedAt = nowElapsedMs + 1L
        val reoffered = requireNotNull(queue.evaluateCandidates(listOf(candidate), true, reofferedAt))
        assertTrue(queue.claimFeedbackDelivery(reoffered, reofferedAt, reofferedAt))
        assertTrue(queue.confirmFeedbackDelivery(reoffered, reofferedAt, reofferedAt + 1L))
        assertNull(queue.evaluateCandidates(listOf(candidate), true, reofferedAt + 2L))
        assertNull(admit(admission, last, walkEpoch, WalkRuntimeEpoch("named-ambiguity-presence", 2), nowElapsedMs))
        assertNull(admit(admission, last, walkEpoch, walkEpoch, last.token.capturedElapsedNs / 1_000_000L + 801L))
        assertFalse(batch.isFreshAt(batch.validUntilElapsedRealtimeMs + 1L))
        val duplicate = unknownPipeline.process(capture, last.token, merged, nowElapsedMs)
        assertEquals("duplicate_or_out_of_order_source", duplicate.rejectionReason)
        assertTrue(duplicate.proximityObjects.isEmpty())
        val foreign = token(9, cpuTimestampNs = last.token.cpuImageTimestampNs + 1L)
        assertEquals("capture_token_mismatch", unknownPipeline.process(capture, foreign, merged, nowElapsedMs).rejectionReason)
    }

    private fun decode(decoder: FastSamDecoder, prototypes: FloatArray, boxes: List<Pair<Int, Int>>): List<InstanceMask> {
        val detections = FloatArray(FastSamDecoder.CHANNELS * FastSamDecoder.ANCHORS)
        boxes.forEachIndexed { index, (left, right) ->
            val anchor = 17 + index
            detections[anchor] = (left + right) / 200f
            detections[FastSamDecoder.ANCHORS + anchor] = .5f
            detections[2 * FastSamDecoder.ANCHORS + anchor] = (right - left) / 100f
            detections[3 * FastSamDecoder.ANCHORS + anchor] = .6f
            detections[4 * FastSamDecoder.ANCHORS + anchor] = .99f
            detections[5 * FastSamDecoder.ANCHORS + anchor] = 1f
        }
        val decoded = decoder.decode(detections, prototypes, 100, 100)
        assertEquals(boxes.size, decoded.selectedBeforeEmptyMaskFilter())
        assertEquals(0, decoded.emptyMasksDropped())
        return decoded.instances()
    }

    private fun namedBoxes(firstX: Float, secondX: Float) = listOf(firstX, secondX).map {
        DetectionCandidate("person", .9f, RectNorm(it, .35f, .20f, .30f))
    }

    private fun token(index: Int, cpuTimestampNs: Long = CPU_BASE_NS + index * FRAME_INTERVAL_NS) =
        FastSamFrameToken(FastSamFrameToken.Source.LIVE_CAMERA, 1L,
            CAMERA_BASE_NS + index * FRAME_INTERVAL_NS, CAMERA_BASE_NS + index * FRAME_INTERVAL_NS,
            cpuTimestampNs, CAPTURE_BASE_MS * 1_000_000L + index * FRAME_INTERVAL_NS,
            "presence-sensor-100x100:q0", 100, 100)

    private fun snapshot(token: FastSamFrameToken) = DepthFrameSnapshot(
        frameTimestampNs = token.cameraTimestampNs,
        rawDepth = DepthImage16(100, 100, IntArray(10_000) { 1_000 }),
        rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { -1 }), fullDepth = null,
        rawDepthTimestampNs = token.cpuImageTimestampNs, rawConfidenceTimestampNs = token.cpuImageTimestampNs,
        cameraImageTimestampNs = token.cpuImageTimestampNs,
        cameraPoseEvidence = CameraPoseEvidence(1L, token.cameraTimestampNs / 1_000_000L,
            0f, 0f, 0f, 0f, 0f, -1f,
            CameraImageProjection(100, 100, 100f, 100f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f)),
    )

    private fun admit(policy: UnknownObjectFeedbackPolicy, result: UnknownDepthFrameResult,
        sourceEpoch: WalkRuntimeEpoch, currentEpoch: WalkRuntimeEpoch, nowElapsedMs: Long) = policy.admit(
        result.objects + result.proximityObjects, result.token.frameId, result.token.cameraTimestampNs / 1_000_000L,
        result.token.capturedElapsedNs, nowElapsedMs, sourceEpoch, currentEpoch, nowElapsedMs)

    companion object {
        private const val CAMERA_BASE_NS = 10_000_000_000L
        private const val CPU_BASE_NS = 8_000_000_000L
        private const val CAPTURE_BASE_MS = 100_000L
        private const val FRAME_INTERVAL_NS = 250_000_000L
    }
}
