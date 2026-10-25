package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.DepthAvailability
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidReportCandidatePolicyTest {
    private val policy = AndroidReportCandidatePolicy()

    @Test fun captureRejectsFutureGpsNegativeClockAndRequestRegression() {
        val current = input()
        assertNotNull(policy.prepare(current))
        assertNull(policy.prepare(current.copy(location = current.location!!.copy(elapsedRealtimeMs = 1_901L))))
        assertNull(policy.prepare(current.copy(capturedAtElapsedRealtimeMs = -1L)))
        assertNull(policy.prepare(current.copy(location = current.location!!.copy(elapsedRealtimeMs = -1L))))
        assertNull(policy.prepare(current.copy(requestElapsedRealtimeMs = 1_899L)))
        assertNull(policy.prepare(current.copy(capturedAtElapsedRealtimeMs = null)))
    }

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
    fun gpsIsLabelledAsTheCaptureDevicePositionWithItsAccuracy() {
        val metadata = requireNotNull(policy.prepare(input())).metadata
        assertEquals("CAPTURE_DEVICE", metadata.getString("location_kind"))
        val gps = metadata.getJSONObject("gps")
        assertEquals(setOf("latitude", "longitude", "accuracy_m"), gps.keys().asSequence().toSet())
        assertEquals(false, metadata.has("facility_location"))
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

    @Test
    fun requiresExplicitPassingCoordinateGate() {
        for (gate in listOf(null, "", "fail", "unknown", "PASS")) {
            org.junit.Assert.assertNull(policy.prepare(input().copy(coordinateGateStatus = gate)))
        }
        org.junit.Assert.assertNotNull(policy.prepare(input().copy(coordinateGateStatus = "pass")))
    }

    @Test
    fun inferredAndNonAndroidSourcesCannotBeLabelledSensorDepth() {
        for (source in listOf(
            DepthSource.UNKNOWN,
            DepthSource.OBJECT_SIZE_PRIOR,
            DepthSource.POLYGON_TREND_PSEUDO_DEPTH,
            DepthSource.MONOCULAR_METRIC_DEPTH,
            DepthSource.WEBXR_DEPTH,
        )) {
            org.junit.Assert.assertNull(policy.prepare(input(depth = depth().copy(source = source))))
        }
        for (source in listOf(DepthSource.ARCORE_RAW_DEPTH, DepthSource.ARCORE_FULL_DEPTH)) {
            org.junit.Assert.assertNotNull(policy.prepare(input(depth = depth().copy(source = source))))
        }
    }

    @Test
    fun requiresMeasuredFinitePositiveDistance() {
        for (availability in DepthAvailability.values().filter { it != DepthAvailability.MEASURED }) {
            org.junit.Assert.assertNull(policy.prepare(input(
                depth = depth().copy(depthAvailability = availability),
            )))
        }
        for (distance in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            org.junit.Assert.assertNull(policy.prepare(input(
                depth = depth().copy(riskDistanceM = distance),
            )))
        }
    }

    @Test
    fun respectsExplicitDepthExpiryWithoutInventingANewFreshnessWindow() {
        val valid = input()
        org.junit.Assert.assertNull(policy.prepare(valid.copy(
            depth = valid.depth.copy(sourceValidUntilElapsedRealtimeMs = valid.requestElapsedRealtimeMs - 1L),
        )))
        org.junit.Assert.assertNotNull(policy.prepare(valid.copy(
            depth = valid.depth.copy(sourceValidUntilElapsedRealtimeMs = valid.requestElapsedRealtimeMs + 1L),
        )))
        org.junit.Assert.assertNotNull(policy.prepare(valid))
    }

    @Test
    fun nonFiniteReportNumbersAreRejectedBeforeJsonConstructionForEveryTrigger() {
        for (trigger in listOf("auto", "voice", "on_screen")) {
            val valid = input(trigger = trigger)
            for (number in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                org.junit.Assert.assertNull(policy.prepare(valid.copy(threshold = number)))
                org.junit.Assert.assertNull(policy.prepare(valid.copy(heading = number)))
                org.junit.Assert.assertNull(policy.prepare(valid.copy(depthValidSampleRatio = number)))
                org.junit.Assert.assertNull(policy.prepare(valid.copy(
                    depth = valid.depth.copy(detectionConfidence = number),
                )))
                org.junit.Assert.assertNull(policy.prepare(valid.copy(
                    depth = valid.depth.copy(validSampleRatio = number),
                )))
                org.junit.Assert.assertNull(policy.prepare(valid.copy(
                    depth = valid.depth.copy(confidence = valid.depth.confidence.copy(sampleQuality = number)),
                )))
            }
        }
    }

    @Test
    fun outOfRangeNumbersCannotBeClampedIntoValidEvidence() {
        val valid = input(trigger = "voice")
        for (number in listOf(-0.01f, 1.01f)) {
            org.junit.Assert.assertNull(policy.prepare(valid.copy(threshold = number)))
            org.junit.Assert.assertNull(policy.prepare(valid.copy(depthValidSampleRatio = number)))
            org.junit.Assert.assertNull(policy.prepare(valid.copy(
                depth = valid.depth.copy(detectionConfidence = number),
            )))
            org.junit.Assert.assertNull(policy.prepare(valid.copy(
                depth = valid.depth.copy(validSampleRatio = number),
            )))
            val confidence = valid.depth.confidence
            for (invalid in listOf(
                confidence.copy(sourceQuality = number),
                confidence.copy(sampleQuality = number),
                confidence.copy(depthQuality = number),
                confidence.copy(detectionQuality = number),
                confidence.copy(trackingQuality = number),
                confidence.copy(motionQuality = number),
                confidence.copy(freshnessQuality = number),
                confidence.copy(corridorQuality = number),
                confidence.copy(hardGate = number),
            )) {
                org.junit.Assert.assertNull(policy.prepare(valid.copy(
                    depth = valid.depth.copy(confidence = invalid),
                )))
            }
        }
        org.junit.Assert.assertNull(policy.prepare(valid.copy(depthSampleCount = -1)))
        org.junit.Assert.assertNull(policy.prepare(valid.copy(
            depth = valid.depth.copy(validSampleCount = -1),
        )))
    }

    @Test
    fun rejectsNonFiniteNonPositiveAndOutOfImageBoxes() {
        val valid = input()
        val box = valid.depth.bboxNorm
        val malformed = mutableListOf(
            box.copy(x = -0.01f), box.copy(y = -0.01f),
            box.copy(width = 0f), box.copy(height = 0f),
            box.copy(width = -0.1f), box.copy(height = -0.1f),
            box.copy(x = 0.9f, width = 0.2f), box.copy(y = 0.9f, height = 0.2f),
        )
        for (number in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            malformed += listOf(box.copy(x = number), box.copy(y = number),
                box.copy(width = number), box.copy(height = number))
        }
        for (invalid in malformed) {
            org.junit.Assert.assertNull(policy.prepare(valid.copy(
                depth = valid.depth.copy(bboxNorm = invalid),
            )))
        }
    }

    @Test
    fun rejectsTinyPositiveExtentsBeyondRightAndBottomEdges() {
        val valid = input()
        val box = valid.depth.bboxNorm
        for (overflow in listOf(
            box.copy(x = 1f, width = 1e-8f),
            box.copy(y = 1f, height = 1e-8f),
        )) {
            org.junit.Assert.assertNull(policy.prepare(valid.copy(
                depth = valid.depth.copy(bboxNorm = overflow),
            )))
        }
    }

    @Test
    fun acceptsExactBinaryImageBoundaries() {
        val valid = input()
        val box = valid.depth.bboxNorm
        for (boundary in listOf(
            box.copy(x = 0f, y = 0f, width = 1f, height = 1f),
            box.copy(x = 0.5f, y = 0.5f, width = 0.5f, height = 0.5f),
        )) {
            org.junit.Assert.assertNotNull(policy.prepare(valid.copy(
                depth = valid.depth.copy(bboxNorm = boundary),
            )))
        }
    }

    @Test
    fun preservesExistingUnitIntervalBoundariesWithoutAddingQualityThresholds() {
        val valid = input(trigger = "voice")
        for (boundary in listOf(0f, 1f)) {
            org.junit.Assert.assertNotNull(policy.prepare(valid.copy(
                threshold = boundary,
                depthValidSampleRatio = boundary,
                depth = valid.depth.copy(
                    detectionConfidence = boundary,
                    bboxNorm = valid.depth.bboxNorm.copy(x = 0f, y = 0f, width = 1f, height = 1f),
                ),
            )))
        }
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
            capturedAtElapsedRealtimeMs = 1_900L,
            trigger = trigger,
            reporterUserId = "user-123",
            traceId = "trace-1",
            deviceGateAllowsReports = deviceGateAllowsReports,
            detectionAgeMs = detectionAgeMs,
            coordinateGateStatus = "pass",
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
            userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
            trackAgeFrames = trackAgeFrames,
            trackStableMs = trackStableMs,
        )
    }
}
