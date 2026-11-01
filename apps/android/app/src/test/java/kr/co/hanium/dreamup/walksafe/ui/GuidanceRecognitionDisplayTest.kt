package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

class GuidanceRecognitionDisplayTest {
    @Test fun neverCompletedAndCompletedEmptyAreDifferent() {
        assertTrue(GuidanceRecognitionDisplay.status(null, 60_000, true, true).contains("결과 대기"))
        val empty = GuidanceRecognitionDisplay.Frame(60_000, 0, emptyList(), "")
        assertTrue(GuidanceRecognitionDisplay.status(empty, 60_000, true, true).contains("객체 영역 0개"))
    }
    @Test fun stoppedOrFrozenCameraCannotShowOldNumericResults() {
        val item = item(output())
        val frame = GuidanceRecognitionDisplay.Frame(60_000, 1, listOf(item), "")
        assertTrue(GuidanceRecognitionDisplay.status(frame, 60_000, true, true).contains("1.25m"))
        assertFalse(GuidanceRecognitionDisplay.status(frame, 60_000, true, false).contains("1.25m"))
        assertFalse(GuidanceRecognitionDisplay.status(frame, 60_000, false, true).contains("1.25m"))
        assertTrue(GuidanceRecognitionDisplay.status(frame, 61_201, true, true).contains("이전 결과 숨김"))
    }
    @Test fun distanceExpiresByOriginalDepthEvenWhileBoxIsCurrent() {
        val original = output(depthObservedAtMs = 9_000)
        val item = item(original)
        assertEquals(60_200L, item.distanceUntilMs)
        assertTrue(item.label(60_200).contains("1.25m"))
        assertTrue(item.label(60_201).contains("거리 미확인"))
        assertTrue(item.current(60_201))
        assertEquals(1.25f, original.riskDistanceM)
    }
    @Test fun missingOrRejectedDepthCannotBecomeMetricBecauseTheViewerIsOpen() {
        val valid = output()
        for (unavailable in listOf(valid.copy(depthObservedAtMs = null), valid.copy(riskDistanceM = null),
            valid.copy(confidence = valid.confidence.copy(hardGate = 0f)),
            valid.copy(riskDistanceM = Float.NaN))) {
            assertNull(item(unavailable).distanceM)
        }
    }
    @Test fun sameClassObjectsKeepTheirOwnDistancesAndNoInputsAreMutated() {
        val first = output()
        val second = first.copy(trackId = "second", riskDistanceM = 3.5f)
        val items = listOf(item(first), item(second))
        val frame = GuidanceRecognitionDisplay.Frame(60_000, 2, items, "")
        val shown = GuidanceRecognitionDisplay.status(frame, 60_000, true, true)
        assertTrue(shown.contains("1.25m")); assertTrue(shown.contains("3.50m"))
        assertEquals(1.25f, first.riskDistanceM); assertEquals(3.5f, second.riskDistanceM)
    }
    @Test fun unknownKeepsItsOwnShorterSourceDeadlineAndNoClassConfidence() {
        val item = GuidanceRecognitionDisplay.item("unnamed-obstacle", 0.9f, null, 60_000,
            output(), unknown = true, validUntilMs = 60_100)
        assertTrue(item.label(60_100).contains("1.25m"))
        assertFalse(item.label(60_100).contains("분류"))
        assertFalse(item.current(60_101))
        assertFalse(item.label(60_101).contains("1.25m"))
    }
    @Test fun futureOrExpiredFrameCannotBeRelabelledAsLive() {
        val frame = GuidanceRecognitionDisplay.Frame(60_000, 1, listOf(item(output())), "")
        assertTrue(frame.visibleItems(59_999).isEmpty())
        assertTrue(frame.visibleItems(61_201).isEmpty())
    }
    @Test fun fullAndRawRemainDistinguishableFromMeasuredAccuracy() {
        assertTrue(item(output()).label(60_000).contains("판단 거리(Raw)"))
        assertTrue(item(output().copy(source = DepthSource.ARCORE_FULL_DEPTH)).label(60_000).contains("Full 추정"))
    }
    private fun item(output: TrackedObjectDepth) = GuidanceRecognitionDisplay.item(
        output.className, output.detectionConfidence, null, 60_000, output)

    private fun output(timestampMs: Long = 10_000L, depthObservedAtMs: Long? = timestampMs): TrackedObjectDepth {
        val normalizedDepth = depthObservedAtMs?.let { 60_000L - (timestampMs - it) }
        return TrackedObjectDepth(
            frameId = timestampMs * 1_000_000L,
            timestampMs = timestampMs,
            trackId = "original-depth",
            className = "person",
            detectionConfidence = 0.9f,
            bboxNorm = RectNorm(0.1f, 0.2f, 0.2f, 0.5f),
            polygonNorm = emptyList(),
            maskAreaNorm = 0.1f,
            centerNorm = Point2(0.2f, 0.45f),
            bottomContactNorm = null,
            source = DepthSource.ARCORE_RAW_DEPTH,
            zDistanceM = 1.4f,
            rayDistanceM = 2f,
            groundDistanceM = 0.6f,
            riskDistanceM = 1.25f,
            validSampleCount = 80,
            validSampleRatio = 0.9f,
            depthMedianM = 1.4f,
            depthP20M = 1.25f,
            depthIqrM = 0.05f,
            trend = Trend.STABLE,
            approachScore = 0f,
            approachSpeedMps = null,
            timeToCollisionMs = null,
            confidence = DepthConfidenceBreakdown(0.95f, 1f, 1f, 0.9f, 1f, 1f, 1f, 1f),
            userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
            depthObservedAtMs = depthObservedAtMs,
            depthReprojected = depthObservedAtMs != timestampMs,
            sourceObservedAtElapsedRealtimeMs = normalizedDepth,
            sourceMaxAgeMs = DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS,
            sourceValidUntilElapsedRealtimeMs = normalizedDepth?.plus(DepthAgeBudget.RISK_GUIDANCE_MAX_AGE_MS),
        )
    }
}
