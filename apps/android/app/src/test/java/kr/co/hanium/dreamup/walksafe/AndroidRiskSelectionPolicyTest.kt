package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidRiskSelectionPolicyTest {
    @Test
    fun keepsStopWarningPriorityWhileSelectingDamagedTactileForReport() {
        val stopPerson = depth(
            className = "person",
            trackId = "person-1",
            messageLevel = MessageLevel.STOP,
            detectionConfidence = 0.80f,
            finalScore = 0.70f,
        )
        val damagedTactile = depth(
            className = "damaged_tactile_block",
            trackId = "damage-1",
            messageLevel = MessageLevel.NONE,
            detectionConfidence = 0.91f,
            finalScore = 0.90f,
        )

        assertEquals(stopPerson, AndroidRiskSelectionPolicy.selectBestOutput(listOf(damagedTactile, stopPerson)))
        assertEquals(damagedTactile, AndroidRiskSelectionPolicy.selectReportOutput(listOf(damagedTactile, stopPerson)))
    }

    @Test
    fun reportOutputIgnoresNormalTactileAndNonMetricDamage() {
        val normalTactile = depth(className = "normal_tactile_block", trackId = "normal-1")
        val pseudoDamage = depth(
            className = "damaged_tactile_block",
            trackId = "damage-pseudo",
            source = DepthSource.OBJECT_SIZE_PRIOR,
        )

        assertNull(AndroidRiskSelectionPolicy.selectReportOutput(listOf(normalTactile, pseudoDamage)))
    }

    @Test
    fun automaticReportSelectionRequiresBothTrackingStabilityGates() {
        val framesOnly = depth(
            className = "damaged_tactile_block",
            trackId = "frames-only",
            trackAgeFrames = 3,
            trackStableMs = 699L,
        )
        val timeOnly = depth(
            className = "damaged_tactile_block",
            trackId = "time-only",
            trackAgeFrames = 2,
            trackStableMs = 900L,
        )
        val stable = depth(
            className = "damaged_tactile_block",
            trackId = "stable",
            trackAgeFrames = 3,
            trackStableMs = 700L,
        )

        assertNull(AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(framesOnly, timeOnly)))
        assertEquals(stable, AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(framesOnly, stable)))
        assertEquals(framesOnly, AndroidRiskSelectionPolicy.selectReportOutput(listOf(framesOnly)))
    }

    @Test
    fun feedbackOrderingKeepsLowerPriorityCandidateForCooldownFallback() {
        val info = depth(
            className = "normal_tactile_block",
            trackId = "info-1",
            messageLevel = MessageLevel.INFO,
            message = "점자블록을 따라 이동하세요.",
        )
        val warning = depth(
            className = "person",
            trackId = "warning-1",
            messageLevel = MessageLevel.WARNING,
            message = "속도를 줄이세요.",
        )

        assertEquals(
            listOf(warning, info),
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(info, warning)),
        )
    }

    private fun depth(
        className: String,
        trackId: String,
        messageLevel: MessageLevel = MessageLevel.NONE,
        detectionConfidence: Float = 0.91f,
        finalScore: Float = 0.85f,
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
        message: String? = null,
        trackAgeFrames: Int = 3,
        trackStableMs: Long = 700L,
    ): TrackedObjectDepth {
        return TrackedObjectDepth(
            frameId = 1L,
            timestampMs = 1_000L,
            trackId = trackId,
            className = className,
            detectionConfidence = detectionConfidence,
            bboxNorm = RectNorm(0.2f, 0.3f, 0.4f, 0.2f),
            polygonNorm = emptyList(),
            maskAreaNorm = 0.08f,
            centerNorm = Point2(0.4f, 0.4f),
            bottomContactNorm = null,
            source = source,
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
                sourceQuality = finalScore,
                sampleQuality = 1f,
                depthQuality = 1f,
                detectionQuality = 1f,
                trackingQuality = 1f,
                motionQuality = 1f,
                freshnessQuality = 1f,
                corridorQuality = 1f,
            ),
            userFacing = UserFacingDepth(null, messageLevel, message),
            trackAgeFrames = trackAgeFrames,
            trackStableMs = trackStableMs,
        )
    }
}
