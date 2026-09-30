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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun feedbackPrefersShorterValidTtcBeforeDistanceAndConfidence() {
        val farHighConfidence = depth(
            className = "person",
            trackId = "far-high-confidence",
            messageLevel = MessageLevel.WARNING,
            message = "warning",
            finalScore = 0.90f,
            timeToCollisionMs = 2_600L,
            riskDistanceM = 3.0f,
        )
        val nearLowerConfidence = depth(
            className = "person",
            trackId = "near-lower-confidence",
            messageLevel = MessageLevel.WARNING,
            message = "warning",
            finalScore = 0.70f,
            timeToCollisionMs = 900L,
            riskDistanceM = 1.0f,
        )

        assertEquals(
            nearLowerConfidence,
            AndroidRiskSelectionPolicy.selectBestOutput(listOf(farHighConfidence, nearLowerConfidence)),
        )
        assertEquals(
            listOf(nearLowerConfidence, farHighConfidence),
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(farHighConfidence, nearLowerConfidence)),
        )

        val fartherSooner = depth(
            className = "person",
            trackId = "farther-sooner",
            messageLevel = MessageLevel.WARNING,
            timeToCollisionMs = 900L,
            riskDistanceM = 4.0f,
            finalScore = 0.70f,
        )
        val closerLater = depth(
            className = "person",
            trackId = "closer-later",
            messageLevel = MessageLevel.WARNING,
            timeToCollisionMs = 2_600L,
            riskDistanceM = 1.0f,
            finalScore = 0.90f,
        )
        assertEquals(fartherSooner, AndroidRiskSelectionPolicy.selectBestOutput(listOf(closerLater, fartherSooner)))
    }

    @Test
    fun feedbackRanksMissingAndInvalidTtcAfterValidTtc() {
        val knownFar = depth(
            className = "person",
            trackId = "known-far",
            messageLevel = MessageLevel.WARNING,
            message = "warning",
            timeToCollisionMs = 2_600L,
            riskDistanceM = 4.0f,
            finalScore = 0.70f,
        )
        val missingNear = depth(
            className = "person",
            trackId = "missing-near",
            messageLevel = MessageLevel.WARNING,
            message = "warning",
            timeToCollisionMs = null,
            riskDistanceM = 0.5f,
            finalScore = 0.90f,
        )
        val invalidNearest = depth(
            className = "person",
            trackId = "invalid-nearest",
            messageLevel = MessageLevel.WARNING,
            message = "warning",
            timeToCollisionMs = -1L,
            riskDistanceM = 0.25f,
            finalScore = 0.95f,
        )

        assertEquals(
            listOf(knownFar, invalidNearest, missingNear),
            AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(listOf(missingNear, knownFar, invalidNearest)),
        )
    }

    @Test
    fun feedbackRanksValidMetricDistanceBeforeInvalidDistanceAndUsesConfidenceForTie() {
        val validDistance = depth(
            className = "person",
            trackId = "valid-distance",
            messageLevel = MessageLevel.WARNING,
            riskDistanceM = 1.0f,
            finalScore = 0.70f,
        )
        val invalidDistance = depth(
            className = "person",
            trackId = "invalid-distance",
            messageLevel = MessageLevel.WARNING,
            riskDistanceM = Float.NaN,
            finalScore = 0.95f,
        )
        val nonMetricDistance = depth(
            className = "person",
            trackId = "non-metric-distance",
            messageLevel = MessageLevel.WARNING,
            source = DepthSource.OBJECT_SIZE_PRIOR,
            riskDistanceM = 0.2f,
            finalScore = 0.90f,
        )

        assertEquals(
            validDistance,
            AndroidRiskSelectionPolicy.selectBestOutput(listOf(invalidDistance, nonMetricDistance, validDistance)),
        )

        val higherConfidenceAtSameDistance = depth(
            className = "person",
            trackId = "higher-confidence-at-same-distance",
            messageLevel = MessageLevel.WARNING,
            riskDistanceM = 1.0f,
            finalScore = 0.90f,
        )
        assertEquals(
            higherConfidenceAtSameDistance,
            AndroidRiskSelectionPolicy.selectBestOutput(listOf(validDistance, higherConfidenceAtSameDistance)),
        )
    }

    @Test
    fun sameAreaNormalAndDamagedPavingCannotAssertDamageInEitherReportPath() {
        // S10-17: the same box was also seen as normal paving, so its damaged state is uncertain.
        val damaged = depth(className = "damaged_tactile_block", trackId = "damage-1")
        val normal = depth(className = "normal_tactile_block", trackId = "normal-1", detectionConfidence = 0.80f)
        assertNull(AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(damaged, normal)))
        assertNull(AndroidRiskSelectionPolicy.selectReportOutput(listOf(damaged, normal)))
        assertTrue(AndroidRiskSelectionPolicy.hasConflictingReportDamage(listOf(damaged, normal)))
        // Another frame's normal paving or a normal block elsewhere does not make it uncertain.
        assertEquals(damaged, AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(damaged, normal.copy(frameId = 2L))))
        assertEquals(damaged, AndroidRiskSelectionPolicy.selectReportOutput(listOf(damaged, normal.copy(frameId = 2L))))
        val elsewhere = normal.copy(bboxNorm = RectNorm(0.7f, 0.3f, 0.2f, 0.2f))
        assertEquals(damaged, AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(damaged, elsewhere)))
        assertEquals(damaged, AndroidRiskSelectionPolicy.selectReportOutput(listOf(damaged, elsewhere)))
        // A different paving shape is a different object.
        val dot = normal.copy(className = "dot_tactile_paving")
        assertEquals(damaged, AndroidRiskSelectionPolicy.selectAutomaticReportOutput(listOf(damaged, dot)))
        assertFalse(AndroidRiskSelectionPolicy.hasConflictingReportDamage(listOf(damaged, dot)))
        val independentDamage = damaged.copy(trackId = "damage-elsewhere", bboxNorm = RectNorm(0.7f, 0.3f, 0.2f, 0.2f))
        assertEquals(independentDamage, AndroidRiskSelectionPolicy.selectReportOutput(listOf(damaged, normal, independentDamage)))
    }

    @Test
    fun conflictingScooterStatesProduceOneNeutralWarningWithoutWeakeningTheDangerEvidence() {
        val stopped = depth(
            className = "abandoned_e_scooter",
            trackId = "scooter-stopped",
            messageLevel = MessageLevel.WARNING,
            message = "정지해 있는 킥보드입니다. 멈출 준비를 하세요.",
            timeToCollisionMs = 1_200L,
            riskDistanceM = 1.4f,
        ).copy(sourceValidUntilElapsedRealtimeMs = 2_000L)
        val moving = depth(
            className = "moving_e_scooter",
            trackId = "scooter-moving",
            messageLevel = MessageLevel.STOP,
            message = "이동하는 킥보드입니다. 멈추세요.",
            timeToCollisionMs = 700L,
            riskDistanceM = 0.9f,
        ).copy(sourceValidUntilElapsedRealtimeMs = 2_100L)
        val person = depth(className = "person", trackId = "person", messageLevel = MessageLevel.WARNING,
            message = "사람이 있습니다.")

        val resolved = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(stopped, moving, person))
        assertEquals(2, resolved.size)
        val scooter = resolved.single { it.className.contains("scooter") }
        assertEquals(moving.trackId, scooter.trackId)
        assertEquals(moving.sourceValidUntilElapsedRealtimeMs, scooter.sourceValidUntilElapsedRealtimeMs)
        assertEquals(moving.timeToCollisionMs, scooter.timeToCollisionMs)
        assertEquals(moving.riskDistanceM, scooter.riskDistanceM)
        assertEquals(MessageLevel.STOP, scooter.userFacing.messageLevel)
        assertTrue(requireNotNull(scooter.userFacing.message).contains("상태가 불확실"))
        assertFalse(requireNotNull(scooter.userFacing.message).contains("정지해 있는"))
        assertFalse(requireNotNull(scooter.userFacing.message).contains("이동하는"))
        assertEquals(person, resolved.single { it.className == "person" })
    }

    @Test
    fun conflictingTactileStatesGiveOneNeutralWarningButUnrelatedOverlapAndFramesStayIndependent() {
        val normal = depth(className = "normal_tactile_block", trackId = "normal")
        val damaged = depth(className = "damaged_tactile_block", trackId = "damaged")
        val conflict = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(normal, damaged))
        assertEquals(1, conflict.size)
        assertEquals(MessageLevel.NONE, conflict.single().userFacing.messageLevel)
        assertNull(conflict.single().userFacing.message)
        assertTrue(AndroidRiskSelectionPolicy.hasConflictingTactileState(listOf(normal, damaged)))

        val differentFrame = damaged.copy(frameId = 2L)
        assertEquals(listOf(normal, differentFrame),
            AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(normal, differentFrame)))
        val person = normal.copy(className = "person")
        val bicycle = damaged.copy(className = "bicycle")
        assertEquals(listOf(person, bicycle),
            AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(person, bicycle)))
        assertFalse(AndroidRiskSelectionPolicy.hasConflictingTactileState(listOf(person, bicycle)))
        val distantDamage = damaged.copy(bboxNorm = RectNorm(0.7f, 0.3f, 0.2f, 0.2f))
        assertEquals(listOf(normal, distantDamage),
            AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(normal, distantDamage)))
    }

    @Test
    fun chainOfOverlapsDoesNotMergeTwoDistinctSameStateObjectsThroughOneOppositeState() {
        // A-B and B-C have IoU .739; A-C is .538, so class-aware NMS can retain A and C.
        val left = depth(className = "normal_tactile_block", trackId = "normal-left",
            messageLevel = MessageLevel.WARNING, message = "왼쪽 점자블록").copy(
            bboxNorm = RectNorm(0.10f, 0.30f, 0.20f, 0.20f),
            sourceValidUntilElapsedRealtimeMs = 2_000L,
        )
        val middle = depth(className = "damaged_tactile_block", trackId = "damage-middle").copy(
            bboxNorm = RectNorm(0.13f, 0.30f, 0.20f, 0.20f),
            sourceValidUntilElapsedRealtimeMs = 2_050L,
        )
        val right = depth(className = "normal_tactile_block", trackId = "normal-right",
            messageLevel = MessageLevel.STOP, message = "오른쪽 점자블록").copy(
            bboxNorm = RectNorm(0.16f, 0.30f, 0.20f, 0.20f),
            sourceValidUntilElapsedRealtimeMs = 2_100L,
        )

        val resolved = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(left, middle, right))
        assertEquals(2, resolved.size)
        assertTrue(resolved.any { it.trackId == left.trackId && it.sourceValidUntilElapsedRealtimeMs == 2_000L })
        val independent = resolved.single { it.trackId == right.trackId }
        assertEquals(MessageLevel.STOP, independent.userFacing.messageLevel)
        assertEquals(right.sourceValidUntilElapsedRealtimeMs, independent.sourceValidUntilElapsedRealtimeMs)
        assertTrue(requireNotNull(independent.userFacing.message).contains("상태가 불확실"))
    }

    @Test
    fun expiredStopDoesNotHideAStillLeasedWarningForTheSameObservedScooter() {
        val expiredStop = depth(className = "moving_e_scooter", trackId = "stop",
            messageLevel = MessageLevel.STOP, message = "멈추세요.").copy(
            sourceValidUntilElapsedRealtimeMs = 999L,
        )
        val liveWarning = depth(className = "abandoned_e_scooter", trackId = "warning",
            messageLevel = MessageLevel.WARNING, message = "주의하세요.").copy(
            sourceValidUntilElapsedRealtimeMs = 2_000L,
        )

        val beforeExpiry = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(
            listOf(expiredStop, liveWarning), nowElapsedRealtimeMs = 900L,
        ).single()
        assertEquals("stop", beforeExpiry.trackId)
        assertEquals(MessageLevel.STOP, beforeExpiry.userFacing.messageLevel)
        val afterExpiry = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(
            listOf(expiredStop, liveWarning), nowElapsedRealtimeMs = 1_000L,
        ).single()
        assertEquals("warning", afterExpiry.trackId)
        assertEquals(MessageLevel.WARNING, afterExpiry.userFacing.messageLevel)
        assertEquals(liveWarning.sourceValidUntilElapsedRealtimeMs, afterExpiry.sourceValidUntilElapsedRealtimeMs)
    }

    @Test
    fun uncertainStateDoesNotPromoteNonWarningEvidenceIntoANewAlarm() {
        val abandoned = depth(className = "abandoned_e_scooter", trackId = "abandoned",
            messageLevel = MessageLevel.INFO, message = "킥보드 안내")
        val moving = depth(className = "moving_e_scooter", trackId = "moving")
        val noAlarm = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(abandoned, moving)).single()
        assertEquals(MessageLevel.INFO, noAlarm.userFacing.messageLevel)
        assertNull(noAlarm.userFacing.message)

        val tentative = abandoned.copy(userFacing = UserFacingDepth(null, MessageLevel.CAUTION, "조심하세요."))
        val caution = AndroidRiskSelectionPolicy.resolveConflictingStateFeedback(listOf(tentative, moving)).single()
        assertEquals(MessageLevel.CAUTION, caution.userFacing.messageLevel)
        assertTrue(requireNotNull(caution.userFacing.message).contains("상태가 불확실"))
    }
    private fun depth(
        className: String,
        trackId: String,
        messageLevel: MessageLevel = MessageLevel.NONE,
        detectionConfidence: Float = 0.91f,
        finalScore: Float = 0.85f,
        timeToCollisionMs: Long? = null,
        riskDistanceM: Float = 1.0f,
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
            riskDistanceM = riskDistanceM,
            validSampleCount = 40,
            validSampleRatio = 0.8f,
            depthMedianM = 1.1f,
            depthP20M = 1.0f,
            depthIqrM = 0.1f,
            trend = Trend.STABLE,
            approachScore = 0f,
            approachSpeedMps = null,
            timeToCollisionMs = timeToCollisionMs,
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
