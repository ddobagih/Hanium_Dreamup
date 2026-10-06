package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import kr.co.hanium.dreamup.walksafe.ReportStaticSourceInspector
import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S15-17: every refused candidate carries a distinct, value-free diagnostic reason. */
class AndroidReportCandidateRejectionTest {
    private val policy = AndroidReportCandidatePolicy()

    @Test
    fun eachFailedCheckReportsItsOwnReasonInsteadOfAnAnonymousNull() {
        val valid = input()
        val depth = valid.depth
        val cases = listOf(
            valid.copy(coordinateGateStatus = null) to AndroidReportCandidateRejection.COORDINATE_GATE_NOT_PASSED,
            valid.copy(coordinateGateStatus = "fail") to AndroidReportCandidateRejection.COORDINATE_GATE_NOT_PASSED,
            valid.copy(depth = depth.copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH)) to
                AndroidReportCandidateRejection.DEPTH_SOURCE_NOT_SENSOR,
            valid.copy(depth = depth.copy(depthAvailability = DepthAvailability.values().first {
                it != DepthAvailability.MEASURED
            })) to AndroidReportCandidateRejection.DEPTH_NOT_MEASURED,
            valid.copy(depth = depth.copy(riskDistanceM = null)) to AndroidReportCandidateRejection.DISTANCE_INVALID,
            valid.copy(depth = depth.copy(riskDistanceM = -1f)) to AndroidReportCandidateRejection.DISTANCE_INVALID,
            valid.copy(depth = depth.copy(sourceValidUntilElapsedRealtimeMs = valid.requestElapsedRealtimeMs - 1L)) to
                AndroidReportCandidateRejection.DEPTH_EXPIRED,
            valid.copy(threshold = Float.NaN) to AndroidReportCandidateRejection.NUMBER_INVALID,
            valid.copy(heading = Float.POSITIVE_INFINITY) to AndroidReportCandidateRejection.NUMBER_INVALID,
            valid.copy(depth = depth.copy(detectionConfidence = 1.2f)) to AndroidReportCandidateRejection.NUMBER_INVALID,
            valid.copy(depth = depth.copy(bboxNorm = RectNorm(0.9f, 0.3f, 0.2f, 0.2f))) to
                AndroidReportCandidateRejection.BBOX_INVALID,
            valid.copy(depth = depth.copy(bboxNorm = RectNorm(0.2f, 0.3f, -0.1f, 0.2f))) to
                AndroidReportCandidateRejection.BBOX_INVALID,
            valid.copy(deviceGateAllowsReports = false) to AndroidReportCandidateRejection.DEVICE_GATE_BLOCKED,
            valid.copy(depth = depth.copy(className = "tactile_block")) to
                AndroidReportCandidateRejection.CLASS_NOT_REPORTABLE,
            valid.copy(trigger = "unknown") to AndroidReportCandidateRejection.TRIGGER_NOT_ALLOWED,
            valid.copy(depth = depth.copy(trackAgeFrames = 2)) to AndroidReportCandidateRejection.AUTOMATIC_TRACK_UNSTABLE,
            valid.copy(depth = depth.copy(detectionConfidence = 0.69f)) to
                AndroidReportCandidateRejection.AUTOMATIC_CONFIDENCE_LOW,
            valid.copy(modelKey = null) to AndroidReportCandidateRejection.MODEL_NOT_ALLOWED,
            valid.copy(modelKey = "coco") to AndroidReportCandidateRejection.MODEL_NOT_ALLOWED,
            valid.copy(detectionAgeMs = null) to AndroidReportCandidateRejection.DETECTION_AGE_INVALID,
            valid.copy(detectionAgeMs = 1_201L) to AndroidReportCandidateRejection.DETECTION_AGE_INVALID,
            valid.copy(location = null) to AndroidReportCandidateRejection.LOCATION_UNTRUSTED,
            valid.copy(location = TrustedLocation(37.0, 127.0, 16f, 1_000L)) to
                AndroidReportCandidateRejection.LOCATION_UNTRUSTED,
            valid.copy(threshold = null) to AndroidReportCandidateRejection.NUMBER_INVALID,
            valid.copy(sourceModel = " ") to AndroidReportCandidateRejection.SOURCE_MODEL_MISSING,
            valid.copy(modelKey = "walkmate_21cls") to AndroidReportCandidateRejection.MODEL_CLASS_UNMAPPED,
            valid.copy(sourceCommit = "not-a-commit") to AndroidReportCandidateRejection.SOURCE_COMMIT_INVALID,
        )
        for ((input, reason) in cases) {
            assertEquals(input.toString(), AndroidReportCandidateResult.Rejected(reason), policy.evaluate(input))
            assertNull(policy.prepare(input))
        }
        val ready = policy.evaluate(valid) as AndroidReportCandidateResult.Ready
        assertEquals(policy.prepare(valid)?.metadata?.toString(), ready.candidate.metadata.toString())
    }

    @Test
    fun diagnosticCodesAreDistinctFixedTokensWithoutInputValues() {
        val codes = AndroidReportCandidateRejection.entries.map { it.diagnosticCode }
        assertEquals(codes.size, codes.toSet().size)
        for (code in codes) assertTrue(code, Regex("[a-z_]{3,40}").matches(code))
    }

    @Test
    fun mainActivityStatusKeepsTheRejectionReasonButNoInputValue() {
        val main = File(MAIN_PATH).readText()
        val prepare = ReportStaticSourceInspector.functionBlock(main, "private fun prepareReportCandidate")
        assertTrue(prepare.contains("reportCandidatePolicy.evaluate("))
        assertFalse(prepare.contains("reportCandidatePolicy.prepare("))
        assertTrue(
            prepare.contains("\"reportCandidate=blocked:candidate_\${evaluation.reason.diagnosticCode}\""),
        )
        assertFalse(prepare.contains("return if (candidate == null)"))
    }

    private fun input(): AndroidReportCandidateInput = AndroidReportCandidateInput(
        depth = depth(),
        location = TrustedLocation(37.0, 127.0, 8f, 1_000L),
        modelKey = "custom_tactile",
        sourceModel = "android/custom_tactile",
        runtimeMode = "android",
        modelConfigSha256 = "config-sha",
        modelVersion = "0.1.0",
        sourceCommit = "0123456789abcdef0123456789abcdef01234567",
        threshold = 0.35f,
        capturedAtMs = 1_782_907_200_000L,
        requestElapsedRealtimeMs = 2_000L,
        trigger = AndroidReportCandidatePolicy.TRIGGER_AUTO,
        reporterUserId = "user-123",
        traceId = "trace-1",
        deviceGateAllowsReports = true,
        detectionAgeMs = 100L,
        capturedAtElapsedRealtimeMs = 1_900L,
        coordinateGateStatus = "pass",
        heading = 181f,
    )

    private fun depth(): TrackedObjectDepth = TrackedObjectDepth(
        frameId = 1L,
        timestampMs = 1_000L,
        trackId = "track-1",
        className = "damaged_tactile_block",
        detectionConfidence = 0.91f,
        bboxNorm = RectNorm(0.2f, 0.3f, 0.4f, 0.2f),
        polygonNorm = emptyList(),
        maskAreaNorm = 0.08f,
        centerNorm = Point2(0.4f, 0.4f),
        bottomContactNorm = null,
        source = DepthSource.ARCORE_RAW_DEPTH,
        zDistanceM = 1.1f,
        rayDistanceM = null,
        groundDistanceM = null,
        riskDistanceM = 1.0f,
        validSampleCount = 40,
        validSampleRatio = 0.8f,
        depthMedianM = 1.1f,
        depthP20M = 1.0f,
        depthIqrM = 0.1f,
        trend = Trend.STABLE,
        approachScore = 0f,
        approachSpeedMps = null,
        timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(
            sourceQuality = 1f,
            sampleQuality = 1f,
            depthQuality = 1f,
            detectionQuality = 1f,
            trackingQuality = 1f,
            motionQuality = 1f,
            freshnessQuality = 1f,
            corridorQuality = 1f,
        ),
        userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
        trackAgeFrames = 3,
        trackStableMs = 700L,
    )

    private companion object {
        const val MAIN_PATH = "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
    }
}
