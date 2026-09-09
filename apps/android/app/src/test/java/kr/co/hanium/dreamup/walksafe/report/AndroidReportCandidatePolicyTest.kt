package kr.co.hanium.dreamup.walksafe.report

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidReportCandidatePolicyTest {
    private val policy = AndroidReportCandidatePolicy()

    @Test
    fun preparesOnlyAndroidDamageCandidateWithGpsAndAllowedModel() {
        val candidate = policy.prepare(input())

        assertNotNull(candidate)
        val metadata = candidate!!.metadata
        assertEquals("android", metadata.getString("source"))
        assertEquals("custom_tactile", metadata.getString("model_key"))
        assertEquals("android/custom_tactile", metadata.getString("source_model"))
        assertEquals(0.35, metadata.getDouble("threshold_used"), 0.0001)
        assertEquals(1, metadata.getInt("model_class_id"))
        assertEquals("damaged_tactile_block", metadata.getString("class_name"))
        assertEquals("trace-1", metadata.getString("trace_id"))
        assertEquals("user-123", metadata.getString("reporter_user_id"))
        assertEquals("2026-07-01T12:00:00Z", metadata.getString("captured_at"))
        assertEquals(181.0, metadata.getDouble("heading"), 0.0001)
        assertEquals(false, metadata.getBoolean("fallback_used"))
        assertEquals("legacy_two_model", metadata.getString("loaded_model_key"))
        assertEquals("legacy_loaded", metadata.getString("model_load_reason"))
        assertEquals("0123456789abcdef0123456789abcdef01234567", metadata.getString("source_commit"))
    }

    @Test
    fun rejectsNormalTactileBlockCocoMissingGpsAndGateFailure() {
        assertNull(policy.prepare(input(depth = depth(className = "normal_tactile_block"))))
        assertNull(policy.prepare(input(modelKey = "coco_general")))
        assertNull(policy.prepare(input(location = null)))
        assertNull(policy.prepare(input(deviceGateAllowsReports = false)))
    }

    @Test
    fun rejectsReportLocationAboveBackendAccuracyLimitOrAfterFreshnessWindow() {
        assertNull(policy.prepare(input(location = TrustedLocation(37.0, 127.0, 15.1f, 1_000L))))
        assertNull(
            policy.prepare(
                input(
                    location = TrustedLocation(37.0, 127.0, 5f, 1_000L),
                    requestElapsedRealtimeMs = 11_001L,
                ),
            ),
        )
    }

    @Test
    fun rejectsMissingNegativeOrStaleDetectionAge() {
        assertNull(policy.prepare(input(detectionAgeMs = null)))
        assertNull(policy.prepare(input(detectionAgeMs = -1L)))
        assertNotNull(
            policy.prepare(
                input(detectionAgeMs = AndroidReportCandidatePolicy.REPORT_MAX_DETECTION_AGE_MS),
            ),
        )
        assertNull(
            policy.prepare(
                input(detectionAgeMs = AndroidReportCandidatePolicy.REPORT_MAX_DETECTION_AGE_MS + 1L),
            ),
        )
    }

    @Test
    fun normalizesHeading360ToZero() {
        val candidate = policy.prepare(input(heading = 360f))

        assertNotNull(candidate)
        assertEquals(0.0, candidate!!.metadata.getDouble("heading"), 0.0001)
    }

    @Test
    fun acceptsExplicitDebugMarkerButRejectsMalformedSourceCommit() {
        assertNotNull(policy.prepare(input(sourceCommit = "unverified")))
        assertNull(policy.prepare(input(sourceCommit = "not-a-commit")))
    }

    @Test
    fun automaticReportRequiresBothThreeFramesAndSevenHundredMilliseconds() {
        assertNull(policy.prepare(input(depth = depth(trackAgeFrames = 1, trackStableMs = 900L))))
        assertNull(policy.prepare(input(depth = depth(trackAgeFrames = 2, trackStableMs = 900L))))
        assertNull(policy.prepare(input(depth = depth(trackAgeFrames = 3, trackStableMs = 699L))))
        assertNotNull(policy.prepare(input(depth = depth(trackAgeFrames = 3, trackStableMs = 700L))))
    }

    @Test
    fun automaticReportConfidenceMatchesSecuredBackendBoundary() {
        assertNull(policy.prepare(input(depth = depth(detectionConfidence = 0.699f))))
        assertNotNull(policy.prepare(input(depth = depth(detectionConfidence = 0.70f))))
        assertNull(policy.prepare(input(depth = depth(detectionConfidence = Float.NaN))))
    }

    @Test
    fun explicitVoiceReportDoesNotRequireAutomaticTrackingStability() {
        assertNotNull(
            policy.prepare(
                input(
                    depth = depth(trackAgeFrames = 1, trackStableMs = 0L),
                    trigger = AndroidReportCandidatePolicy.TRIGGER_VOICE,
                ),
            ),
        )
        assertNull(policy.prepare(input(trigger = "manual")))
    }

    @Test
    fun explicitOnScreenReportPreservesItsTriggerInMetadata() {
        assertEquals("on_screen", AndroidReportCandidatePolicy.TRIGGER_ON_SCREEN)
        val candidate = policy.prepare(
            input(
                depth = depth(trackAgeFrames = 1, trackStableMs = 0L),
                trigger = AndroidReportCandidatePolicy.TRIGGER_ON_SCREEN,
            ),
        )

        assertNotNull(candidate)
        assertEquals("on_screen", candidate!!.metadata.getString("trigger"))
        assertEquals(false, candidate.metadata.getBoolean("auto_reported"))
    }

    private fun input(
        depth: TrackedObjectDepth = depth(),
        location: TrustedLocation? = TrustedLocation(37.0, 127.0, 8f, 1_000L),
        modelKey: String? = "custom_tactile",
        deviceGateAllowsReports: Boolean = true,
        heading: Float? = 181f,
        requestElapsedRealtimeMs: Long = 2_000L,
        sourceCommit: String = "0123456789abcdef0123456789abcdef01234567",
        trigger: String = AndroidReportCandidatePolicy.TRIGGER_AUTO,
        detectionAgeMs: Long? = 100L,
    ): AndroidReportCandidateInput {
        return AndroidReportCandidateInput(
            depth = depth,
            location = location,
            modelKey = modelKey,
            sourceModel = "android/$modelKey",
            runtimeMode = "android",
            modelConfigSha256 = "config-sha",
            modelVersion = "0.1.0",
            sourceCommit = sourceCommit,
            threshold = 0.35f,
            capturedAtMs = 1_782_907_200_000L,
            requestElapsedRealtimeMs = requestElapsedRealtimeMs,
            trigger = trigger,
            reporterUserId = "user-123",
            traceId = "trace-1",
            deviceGateAllowsReports = deviceGateAllowsReports,
            detectionAgeMs = detectionAgeMs,
            heading = heading,
            fallbackUsed = false,
            loadedModelKey = "legacy_two_model",
            modelLoadReason = "legacy_loaded",
        )
    }

    private fun depth(
        className: String = "damaged_tactile_block",
        trackAgeFrames: Int = 3,
        trackStableMs: Long = 700L,
        detectionConfidence: Float = 0.91f,
    ): TrackedObjectDepth {
        return TrackedObjectDepth(
            frameId = 1L,
            timestampMs = 1_000L,
            trackId = "track-1",
            className = className,
            detectionConfidence = detectionConfidence,
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
            userFacing = UserFacingDepth(MessageLevel.NONE, null),
            trackAgeFrames = trackAgeFrames,
            trackStableMs = trackStableMs,
        )
    }
}
