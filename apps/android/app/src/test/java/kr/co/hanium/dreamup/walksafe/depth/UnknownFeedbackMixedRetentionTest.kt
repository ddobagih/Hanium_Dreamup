package kr.co.hanium.dreamup.walksafe.depth
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test
class UnknownFeedbackMixedRetentionTest {
    private val epoch = WalkRuntimeEpoch("retention-test", 0L)
    @Test fun keepsStopAndNewWarningWithIndependentOriginalSources() {
        val old = batch(0L, listOf(output("unknown-raw")))
        val fresh = batch(300L, listOf(output("unknown-full").copy(source = DepthSource.ARCORE_FULL_DEPTH,
            riskDistanceM = 2f, userFacing = UserFacingDepth(null, MessageLevel.WARNING, "주의"))))
        val result = merge(listOf(old), fresh, setOf("unknown-raw"), 300L)
        assertEquals(2, result.size)
        assertSame(fresh, result.first())
        val retained = result.last()
        assertEquals(old.sourceFrameId, retained.sourceFrameId)
        assertEquals(old.sourceCapturedAtElapsedRealtimeNs, retained.sourceCapturedAtElapsedRealtimeNs)
        assertEquals(old.completedAtElapsedRealtimeMs, retained.completedAtElapsedRealtimeMs)
        assertEquals(old.outputs, retained.outputs)
        assertEquals(NOW + 800L, retained.validUntilElapsedRealtimeMs)
    }
    @Test fun repeatedMixedCapturesDoNotRenewRetainedStop() {
        val first = merge(listOf(batch(0L, listOf(output("unknown-raw")))),
            batch(300L, listOf(output("unknown-full"))), setOf("unknown-raw"), 300L)
        val second = merge(first, batch(500L, listOf(output("unknown-third"))),
            setOf("unknown-raw", "unknown-full"), 500L)
        assertEquals(3, second.size)
        val expired = merge(second, null, setOf("unknown-raw", "unknown-full", "unknown-third"), 801L)
        assertEquals(setOf("unknown-full", "unknown-third"), expired.flatMap { it.outputs }.map { it.trackId }.toSet())
        assertEquals(setOf(NOW + 1100L, NOW + 1300L), expired.map { it.validUntilElapsedRealtimeMs }.toSet())
    }
    @Test fun freshSameIdReplacesPriorEvidenceAndUnsupportedIdsAreRemoved() {
        val old = batch(0L, listOf(output("unknown-raw"), output("unknown-removed")))
        val fresh = batch(300L, listOf(output("unknown-raw")))
        val result = merge(listOf(old), fresh, setOf("unknown-raw"), 300L)
        assertEquals(listOf(fresh), result)
        assertTrue(merge(result, null, emptySet(), 400L).isEmpty())
    }
    @Test fun wrongEpochAndExpiredEvidenceCannotBeRetained() {
        val old = batch(0L, listOf(output("unknown-raw")))
        assertTrue(UnknownObjectFeedbackPolicy.mergeRetainedRegions(listOf(old), old,
            setOf("unknown-raw"), epoch.copy(recoveryGeneration = 1L), NOW + 300L).isEmpty())
        assertTrue(merge(listOf(old), old, setOf("unknown-raw"), 801L).isEmpty())
        assertTrue(merge(listOf(old), old, setOf("unknown-raw"), -1L).isEmpty())
    }
    @Test fun newestOriginalEvidenceWinsIfPreviousBatchesContainDuplicateIds() {
        val old = batch(0L, listOf(output("unknown-raw")))
        val newer = batch(300L, listOf(output("unknown-raw")))
        val result = merge(listOf(old, newer), null, setOf("unknown-raw"), 400L)
        assertEquals(1, result.size)
        assertEquals(newer.sourceFrameId, result.single().sourceFrameId)
        assertEquals(newer.validUntilElapsedRealtimeMs, result.single().validUntilElapsedRealtimeMs)
    }
    private fun merge(old: List<UnknownObjectFeedbackBatch>, fresh: UnknownObjectFeedbackBatch?, ids: Set<String>, deltaMs: Long) =
        UnknownObjectFeedbackPolicy.mergeRetainedRegions(old, fresh, ids, epoch, NOW + deltaMs)
    private fun batch(deltaMs: Long, outputs: List<TrackedObjectDepth>): UnknownObjectFeedbackBatch {
        val frame = FRAME + deltaMs * 1_000_000L
        return UnknownObjectFeedbackBatch(frame, frame / 1_000_000L, (NOW + deltaMs) * 1_000_000L,
            NOW + deltaMs, epoch, outputs.map { it.copy(frameId = frame, timestampMs = frame / 1_000_000L) })
    }
    private fun output(trackId: String = "primary-1"): TrackedObjectDepth {
        val bbox = RectNorm(0.2f, 0.3f, 0.4f, 0.4f)
        return TrackedObjectDepth(
            frameId = FRAME, timestampMs = FRAME / 1_000_000L, trackId = trackId,
            className = if (trackId.startsWith("unknown")) "unnamed-obstacle" else "person",
            detectionConfidence = 0.95f, bboxNorm = bbox, polygonNorm = emptyList(), maskAreaNorm = bbox.area,
            centerNorm = bbox.center, bottomContactNorm = null, source = DepthSource.ARCORE_RAW_DEPTH,
            zDistanceM = 1f, rayDistanceM = 1f, groundDistanceM = 1f, riskDistanceM = 1f,
            validSampleCount = 80, validSampleRatio = 0.9f, depthMedianM = 1f, depthP20M = 1f, depthIqrM = 0.05f,
            trend = Trend.STABLE, approachScore = 0f, approachSpeedMps = null, timeToCollisionMs = null,
            confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.STOP, "전방 물체. 멈추세요. 주변을 확인하세요."),
            trackAgeFrames = 4, trackStableMs = 900L,
        )
    }

    private companion object { const val NOW = 10000L; const val FRAME = 5000000000000L }
}
