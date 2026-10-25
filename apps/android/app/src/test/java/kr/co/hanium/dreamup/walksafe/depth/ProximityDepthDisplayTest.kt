package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-11: call the walking overlay's real distance selector using separate capture/UI clocks. */
class ProximityDepthDisplayTest {
    @Test
    fun nineHundredMillisecondDepthMayDisplayAfterItsRiskLeaseEnds() {
        val depth = output(depthObservedAtMs = 9_100L)
        assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, depth, 10_000L))
        assertEquals(59_900L, depth.sourceValidUntilElapsedRealtimeMs)
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_000L))
    }

    @Test
    fun twelveHundredMillisecondDepthIsTheInclusiveDisplayBoundary() {
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(output(depthObservedAtMs = 8_800L), 60_000L, 60_000L))
    }

    @Test
    fun twelveHundredAndOneMillisecondDepthIsNotDisplayed() {
        assertNull(ProximityDepthDisplay.distanceFor(output(depthObservedAtMs = 8_799L), 60_000L, 60_000L))
    }

    @Test
    fun twoSecondDepthCannotBorrowTheNewCameraFramesAge() {
        assertNull(ProximityDepthDisplay.distanceFor(output(depthObservedAtMs = 8_000L), 60_000L, 60_000L))
    }

    @Test
    fun uiQueueDelayCountsAgainstTheOriginalDepthBudget() {
        val depth = output(depthObservedAtMs = 9_000L)
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_200L))
        assertNull(ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_201L))
    }

    @Test
    fun reprojectingSameDepthIntoLaterFramesNeverRenewsItsTime() {
        val first = output(timestampMs = 10_000L, depthObservedAtMs = 9_100L)
        val atBoundary = first.copy(frameId = 10_300_000_000L, timestampMs = 10_300L, depthReprojected = true)
        val expired = atBoundary.copy(frameId = 10_301_000_000L, timestampMs = 10_301L)
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(first, 60_000L, 60_000L))
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(atBoundary, 60_300L, 60_300L))
        assertNull(ProximityDepthDisplay.distanceFor(expired, 60_301L, 60_301L))
    }

    @Test
    fun holdingOrResettingTheSameOutputDoesNotRenewItsCapture() {
        val original = output(depthObservedAtMs = 10_000L)
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(original, 60_000L, 61_200L))
        assertNull(ProximityDepthDisplay.distanceFor(original.copy(trackId = "after-reset"), 60_000L, 61_201L))
    }

    @Test
    fun fullAndRawUseTheSameIndependentDisplayBudget() {
        listOf(DepthSource.ARCORE_RAW_DEPTH, DepthSource.ARCORE_FULL_DEPTH).forEach { source ->
            val depth = output(depthObservedAtMs = 9_100L).copy(source = source)
            assertEquals(source.name, 1.25f, ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_000L))
            assertNull(source.name, ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_301L))
        }
    }

    @Test
    fun cameraTimestampAndElapsedRealtimeAreNotComparedDirectly() {
        val depth = output(timestampMs = 1_000_000_000L, depthObservedAtMs = 999_999_100L)
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_000L))
        assertNull(ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_301L))
    }

    @Test
    fun missingOriginalDepthTimestampIsUnknown() {
        assertNull(ProximityDepthDisplay.distanceFor(output(depthObservedAtMs = null), 60_000L, 60_000L))
    }

    @Test
    fun missingFrameCaptureTimestampIsUnknown() {
        assertNull(ProximityDepthDisplay.distanceFor(output(), null, 60_000L))
    }

    @Test
    fun futureCaptureTimestampIsNotDisplayEvidence() {
        assertNull(ProximityDepthDisplay.distanceFor(output(), 60_001L, 60_000L))
    }

    @Test
    fun futureDepthTimestampIsNotDisplayEvidence() {
        assertNull(ProximityDepthDisplay.distanceFor(output(depthObservedAtMs = 10_001L), 60_000L, 60_000L))
    }

    @Test
    fun negativeOrUnrepresentableCaptureTimesAreUnknown() {
        assertNull(ProximityDepthDisplay.distanceFor(output(), -1L, 60_000L))
        assertNull(ProximityDepthDisplay.distanceFor(output(depthObservedAtMs = -1L), 60_000L, 60_000L))
        assertNull(ProximityDepthDisplay.distanceFor(output(depthObservedAtMs = 8_000L), 1_000L, 1_000L))
    }

    @Test
    fun nonMetricSourcesDoNotBecomeNumericDepthLabels() {
        listOf(DepthSource.OBJECT_SIZE_PRIOR, DepthSource.POLYGON_TREND_PSEUDO_DEPTH, DepthSource.UNKNOWN).forEach { source ->
            assertNull(source.name, ProximityDepthDisplay.distanceFor(output().copy(source = source), 60_000L, 60_000L))
        }
    }

    @Test
    fun invalidDistancesAreUnknown() {
        listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { distance ->
            assertNull("distance=$distance", ProximityDepthDisplay.distanceFor(output().copy(riskDistanceM = distance), 60_000L, 60_000L))
        }
    }

    @Test
    fun displayPreservesTheAlreadySelectedRiskDistanceInterpretation() {
        val selected = output().copy(horizontalDistanceM = 0.75f, riskDistanceKind = RiskDistanceKind.HORIZONTAL_RANGE)
        assertEquals(1.25f, ProximityDepthDisplay.distanceFor(selected, 60_000L, 60_000L))
        assertEquals(2f, selected.rayDistanceM)
        assertEquals(0.6f, selected.groundDistanceM)
        assertEquals(0.75f, selected.horizontalDistanceM)
    }

    @Test
    fun remainingDisplayTimeUsesTheOriginalObservationRatherThanTheRiskLease() {
        val depth = output(depthObservedAtMs = 9_100L)
        assertEquals(300L, ProximityDepthDisplay.remainingMs(depth, 60_000L, 60_000L))
        assertEquals(0L, ProximityDepthDisplay.remainingMs(depth, 60_000L, 60_300L))
        assertNull(ProximityDepthDisplay.remainingMs(depth, 60_000L, 60_301L))
    }

    @Test
    fun earlierNormalizedProducerOriginSurvivesNewFrameAndRenewedRiskMetadata() {
        val earlier = output(depthObservedAtMs = 9_900L).copy(sourceObservedAtElapsedRealtimeMs = 59_000L)
        assertEquals(200L, ProximityDepthDisplay.remainingMs(earlier, 60_000L, 60_000L))
        val laterFrame = earlier.copy(
            frameId = 10_201_000_000L,
            timestampMs = 10_201L,
            trackId = "after-reset",
            sourceValidUntilElapsedRealtimeMs = 61_001L,
        )
        assertNull(ProximityDepthDisplay.distanceFor(laterFrame, 60_201L, 60_201L))
    }

    @Test
    fun laterNormalizedProducerTimestampCannotReplaceAnOlderDepthOrigin() {
        val depth = output(depthObservedAtMs = 9_100L).copy(sourceObservedAtElapsedRealtimeMs = 60_000L)
        assertEquals(0L, ProximityDepthDisplay.remainingMs(depth, 60_000L, 60_300L))
        assertNull(ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_301L))
    }

    @Test
    fun invalidNormalizedProducerOriginIsNotReplacedByCurrentCaptureTime() {
        listOf(-1L, 60_001L).forEach { observedAt ->
            assertNull(ProximityDepthDisplay.distanceFor(output().copy(sourceObservedAtElapsedRealtimeMs = observedAt), 60_000L, 60_000L))
        }
    }

    @Test
    fun queuedUiAndHeldBoxRemoveOnlyTheExpiredActualDistanceField() {
        val depth = output(depthObservedAtMs = 9_000L)
        val producerLabel = ProximityDepthDisplay.labelFor(depth, 60_000L, 60_000L)
        assertTrue(producerLabel.contains(" · 1.25m"))
        val consumerLabel = ProximityDepthDisplay.labelFor(depth, 60_000L, 60_201L)
        assertEquals(producerLabel.replace(" · 1.25m", ""), consumerLabel)
        assertTrue(consumerLabel.contains("person 90% · ARCORE_RAW_DEPTH"))
        assertTrue(consumerLabel.contains(" · samples 80 · motion="))
    }

    @Test
    fun laterPredictionExpiryCallbackCannotRestoreExpiredActualDistance() {
        val depth = output(depthObservedAtMs = 9_000L).copy(
            prediction = PredictedDepthEstimate(0.9f, 9_900L, 100L, 0.2f, 700L),
        )
        val initial = ProximityDepthDisplay.labelFor(depth, 60_000L, 60_000L)
        assertTrue(initial.contains(" · 1.25m"))
        assertTrue(initial.contains(" · 예측 0.90m ±0.20m"))
        val distanceExpiryCallback = ProximityDepthDisplay.labelFor(depth, 60_000L, 60_201L)
        assertFalse(distanceExpiryCallback.contains(" · 1.25m"))
        assertTrue(distanceExpiryCallback.contains(" · 예측 0.90m ±0.20m"))
        val predictionExpiryCallback = ProximityDepthDisplay.labelFor(depth, 60_000L, 60_601L)
        assertFalse(predictionExpiryCallback.contains(" · 1.25m"))
        assertFalse(predictionExpiryCallback.contains(" · 예측"))
    }

    @Test
    fun earlierPredictionExpiryCallbackPreservesStillCurrentActualDistance() {
        val depth = output().copy(prediction = PredictedDepthEstimate(0.9f, 9_900L, 100L, 0.2f, 200L))
        val callbackLabel = ProximityDepthDisplay.labelFor(depth, 60_000L, 60_101L)
        assertTrue(callbackLabel.contains(" · 1.25m"))
        assertFalse(callbackLabel.contains(" · 예측"))
        assertFalse(ProximityDepthDisplay.labelFor(depth, 60_000L, 61_201L).contains(" · 1.25m"))
    }

    @Test
    fun queuedLiveStatusDoesNotFirstShowAConfirmedDistanceAfterTheDisplayDeadline() {
        val depth = output().copy(userFacing = UserFacingDepth(2, MessageLevel.CAUTION, "2보 앞 객체"))
        val initiallyCurrent = ProximityDepthDisplay.summaryFor(depth, 60_000L, 60_000L)
        assertEquals(depth.debugSummaryText(), initiallyCurrent)
        assertTrue(initiallyCurrent.contains("1.25m"))
        val queuedConsumer = ProximityDepthDisplay.summaryFor(depth, 60_000L, 61_201L)
        assertFalse(queuedConsumer.contains("1.25m"))
        assertFalse(queuedConsumer.contains("2보"))
        assertTrue(queuedConsumer.contains("거리 없음"))
        // Capture diagnostics continue to describe the original sample, separately from live UI.
        assertTrue(depth.debugSummaryText().contains("1.25m"))
    }

    @Test
    fun heldLiveGeometryAndSummaryDoNotKeepCurrentMetricNumbersWhenFramesStop() {
        val depth = output(depthObservedAtMs = 9_100L)
        val initialGeometry = ProximityDepthDisplay.geometryFor(depth, 60_000L, 60_000L)
        assertEquals("bbox: c=(0.20,0.45) wh=(0.20,0.50) · samples=80 ratio=90% median=1.40m", initialGeometry)
        assertTrue(ProximityDepthDisplay.summaryFor(depth, 60_000L, 60_300L).contains("1.25m"))
        val heldGeometry = ProximityDepthDisplay.geometryFor(depth, 60_000L, 60_301L)
        assertFalse(heldGeometry.contains("median=1.40m"))
        assertTrue(heldGeometry.contains("median=-"))
        assertFalse(ProximityDepthDisplay.summaryFor(depth, 60_000L, 60_301L).contains("1.25m"))
    }

    @Test
    fun liveRawAndFullStatusKeepTheirDisplayBoundaryAfterRiskGuidanceExpires() {
        listOf(DepthSource.ARCORE_RAW_DEPTH, DepthSource.ARCORE_FULL_DEPTH).forEach { source ->
            val depth = output(depthObservedAtMs = 9_100L).copy(source = source)
            assertFalse(DepthAgeBudget.admits(DepthUse.RISK_GUIDANCE, depth, 10_000L))
            assertTrue(ProximityDepthDisplay.summaryFor(depth, 60_000L, 60_000L).contains("1.25m"))
            assertTrue(ProximityDepthDisplay.geometryFor(depth, 60_000L, 60_300L).contains("median=1.40m"))
            assertTrue(ProximityDepthDisplay.summaryFor(depth, 60_000L, 60_300L).contains("1.25m"))
            assertFalse(ProximityDepthDisplay.geometryFor(depth, 60_000L, 60_301L).contains("1.40m"))
            assertFalse(ProximityDepthDisplay.summaryFor(depth, 60_000L, 60_301L).contains("1.25m"))
        }
    }

    @Test
    fun liveStatusRejectsUnknownOriginsAndInvalidNumbersWithoutMutatingRawSamples() {
        val original = output().copy(userFacing = UserFacingDepth(2, MessageLevel.CAUTION, "2보 앞 객체"))
        listOf(
            original.copy(depthObservedAtMs = null),
            original.copy(sourceObservedAtElapsedRealtimeMs = -1L),
            original.copy(source = DepthSource.UNKNOWN),
            original.copy(riskDistanceM = Float.NaN),
        ).forEach { rejected ->
            val status = ProximityDepthDisplay.summaryFor(rejected, 60_000L, 60_000L)
            assertTrue(status.contains("거리 없음"))
            assertFalse(status.contains("2보"))
            assertTrue(ProximityDepthDisplay.geometryFor(rejected, 60_000L, 60_000L).contains("median=-"))
        }
        assertTrue(original.debugSummaryText().contains("1.25m · 2보"))
        assertEquals(1.4f, original.depthMedianM)
        assertEquals(2, original.userFacing.stepsAhead)
    }

    @Test
    fun liveStatusKeepsEarlierProducerOriginAcrossReframingAndRiskLeaseRenewal() {
        val depth = output().copy(sourceObservedAtElapsedRealtimeMs = 59_000L)
        assertTrue(ProximityDepthDisplay.summaryFor(depth, 60_000L, 60_200L).contains("1.25m"))
        val reframed = depth.copy(frameId = 10_201_000_000L, timestampMs = 10_201L,
            trackId = "new-frame", sourceValidUntilElapsedRealtimeMs = 61_001L)
        assertFalse(ProximityDepthDisplay.summaryFor(reframed, 60_201L, 60_201L).contains("1.25m"))
        assertTrue(ProximityDepthDisplay.geometryFor(reframed, 60_201L, 60_201L).contains("median=-"))
    }

    @Test
    fun qualitativeStatusWithoutNumericDepthIsPreserved() {
        val qualitative = output().copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH, riskDistanceM = null,
            userFacing = UserFacingDepth(null, MessageLevel.CAUTION, "앞쪽 객체 확인 필요"))
        assertTrue(ProximityDepthDisplay.summaryFor(qualitative, 60_000L, 61_201L).contains("앞쪽 객체 확인 필요"))
        assertTrue(ProximityDepthDisplay.geometryFor(qualitative, 60_000L, 61_201L).contains("median=-"))
    }

    @Test
    fun renderAtTheBoundaryThenScheduleAfterExpiryStillRequestsImmediateRecheck() {
        val depth = output()
        val renderedNumericDepth = ProximityDepthDisplay.distanceFor(depth, 60_000L, 61_200L) != null
        assertTrue(renderedNumericDepth)
        assertTrue(ProximityDepthDisplay.summaryFor(depth, 60_000L, 61_200L).contains("1.25m"))
        // updateStatus/renderMainUi may consume this last millisecond before scheduling begins.
        val remainingAtScheduling = ProximityDepthDisplay.remainingMs(depth, 60_000L, 61_201L)
        assertNull(remainingAtScheduling)
        assertEquals(0L, ProximityDepthDisplay.expiryDelayMs(renderedNumericDepth, remainingAtScheduling))
    }

    @Test
    fun initiallyUnknownLiveDistanceDoesNotCreateAnExpiryTimer() {
        val unknown = output(depthObservedAtMs = null)
        val displayedNumericDepth = ProximityDepthDisplay.distanceFor(unknown, 60_000L, 60_000L) != null
        assertFalse(displayedNumericDepth)
        assertNull(ProximityDepthDisplay.expiryDelayMs(displayedNumericDepth,
            ProximityDepthDisplay.remainingMs(unknown, 60_000L, 60_000L)))
        assertNull(ProximityDepthDisplay.expiryDelayMs(false, 300L))
    }

    @Test
    fun stillCurrentNumericStatusSchedulesAfterItsInclusiveDisplayBoundary() {
        val depth = output(depthObservedAtMs = 9_100L)
        val displayedNumericDepth = ProximityDepthDisplay.distanceFor(depth, 60_000L, 60_000L) != null
        assertTrue(displayedNumericDepth)
        assertEquals(291L, ProximityDepthDisplay.expiryDelayMs(displayedNumericDepth,
            ProximityDepthDisplay.remainingMs(depth, 60_000L, 60_010L)))
        assertEquals(1L, ProximityDepthDisplay.expiryDelayMs(displayedNumericDepth,
            ProximityDepthDisplay.remainingMs(depth, 60_000L, 60_300L)))
    }

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
