package kr.co.hanium.dreamup.walksafe.report

import java.nio.file.Files
import java.nio.file.Paths
import kr.co.hanium.dreamup.walksafe.AndroidRiskSelectionPolicy
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

/**
 * S12-17 (originals/12.txt:161-167) / owner decision 5 (b): an explicit report keeps requiring
 * report-grade metric distance. A visible damaged block without it is refused with its own
 * sentence ("거리를 확인할 수 없어 … 저장하지 않았습니다" + one next action) instead of "none".
 */
class ExplicitReportRejectionNoticeTest {
    @Test
    fun payloadLimitRefusalExplainsThatNothingWasSavedAndOffersRecapture() {
        val notice = ExplicitReportRejectionNotice.messageFor(
            ExplicitReportRejectionNotice.STATUS_PAYLOAD_TOO_LARGE,
        )
        assertEquals(ExplicitReportRejectionNotice.PAYLOAD_TOO_LARGE_KO, notice)
        assertTrue(requireNotNull(notice).contains("저장하지 않았습니다"))
        assertTrue(notice.contains("다시 촬영"))
        assertFalse(notice.contains("payload"))
    }

    @Test
    fun everyCandidateRejectionHasAnExplanationWithoutReadingInternalCodes() {
        for (reason in AndroidReportCandidateRejection.entries) {
            val message = requireNotNull(ExplicitReportRejectionNotice.messageFor(
                "reportCandidate=blocked:candidate_${reason.diagnosticCode}",
            )) { "No speech for $reason" }
            assertFalse(message.contains(reason.diagnosticCode))
            assertFalse(message.contains("reportCandidate"))
            if (reason != AndroidReportCandidateRejection.CLASS_NOT_REPORTABLE) {
                assertTrue(message.contains("저장하지 않았습니다"))
                assertTrue(message.endsWith("주세요."))
            }
        }
        val location = requireNotNull(ExplicitReportRejectionNotice.messageFor("reportCandidate=blocked:candidate_location_untrusted"))
        assertTrue(location.contains("위치가 정확하지 않아"))
        val expired = requireNotNull(ExplicitReportRejectionNotice.messageFor("reportCandidate=blocked:candidate_depth_expired"))
        assertTrue(expired.contains("촬영 정보가 오래되어"))
        assertNull(ExplicitReportRejectionNotice.messageFor("reportCandidate=blocked:candidate_location_untrusted latitude=37.5"))
        assertNull(ExplicitReportRejectionNotice.messageFor("reportCandidate=queued id=123"))
    }

    @Test
    fun unverifiedCoordinatesHaveASpokenReasonAndRetryAction() {
        val notice = ExplicitReportRejectionNotice
        assertEquals(notice.STATUS_COORDINATE_UNVERIFIED,
            notice.explicitStatus(notice.STATUS_COORDINATE_UNVERIFIED, true))
        assertEquals(notice.COORDINATE_UNVERIFIED_KO, notice.messageFor(notice.STATUS_COORDINATE_UNVERIFIED))
        assertTrue(notice.COORDINATE_UNVERIFIED_KO.contains("신고를 저장하지 않았습니다"))
        assertTrue(notice.COORDINATE_UNVERIFIED_KO.contains("다시 신고해 주세요"))
    }

    @Test
    fun damageWithoutReportGradeDistanceIsDistinguishedFromNoDamage() {
        val cases = listOf(
            damage("pseudo", source = DepthSource.OBJECT_SIZE_PRIOR),
            damage("trend", source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH),
            damage("weak", finalScore = 0.54f),
            damage("limited").copy(spatialCoverageLimited = true),
        )
        cases.forEach { output ->
            val outputs = listOf(output)
            assertNull(output.trackId, AndroidRiskSelectionPolicy.selectReportOutput(outputs))
            assertTrue(output.trackId, AndroidRiskSelectionPolicy.hasReportableDamageWithoutReportDepth(outputs))
        }
        assertFalse(AndroidRiskSelectionPolicy.hasReportableDamageWithoutReportDepth(emptyList()))
        assertFalse(
            AndroidRiskSelectionPolicy.hasReportableDamageWithoutReportDepth(
                listOf(damage("car", source = DepthSource.OBJECT_SIZE_PRIOR).copy(className = "car")),
            ),
        )
        // A report-grade damage beside an unmeasured one is still selected, so no refusal.
        val metric = damage("metric", finalScore = 0.60f)
        val both = listOf(damage("pseudo", source = DepthSource.OBJECT_SIZE_PRIOR), metric)
        assertEquals(metric, AndroidRiskSelectionPolicy.selectReportOutput(both))
        assertFalse(AndroidRiskSelectionPolicy.hasReportableDamageWithoutReportDepth(both))
    }

    @Test
    fun onlyTheNoOutputRefusalIsRefinedAndEachHasOneSentence() {
        val notice = ExplicitReportRejectionNotice
        assertEquals(notice.STATUS_DAMAGE_WITHOUT_DISTANCE, notice.explicitStatus(notice.STATUS_NO_DAMAGE, true))
        assertEquals(notice.STATUS_NO_DAMAGE, notice.explicitStatus(notice.STATUS_NO_DAMAGE, false))
        listOf(
            "reportCandidate=blocked:gps_missing",
            "reportCandidate=blocked:login_required",
            "reportCandidate=awaiting:explicit_transfer_confirmation",
        ).forEach { other ->
            assertEquals(other, notice.explicitStatus(other, true))
            assertNull(other, notice.messageFor(other))
        }
        assertEquals("신고할 손상 점자블록이 없습니다.", notice.messageFor(notice.STATUS_NO_DAMAGE))
        assertEquals(
            "신고할 손상 점자블록이 없습니다.",
            notice.messageFor("reportCandidate=blocked:not_reportable_class"),
        )
        val unmeasured = requireNotNull(notice.messageFor(notice.STATUS_DAMAGE_WITHOUT_DISTANCE))
        assertTrue(unmeasured.startsWith("손상된 점자블록이 보이지만 거리를 확인할 수 없어 신고를 저장하지 않았습니다."))
        // Exactly one next action after the refusal.
        assertEquals(1, unmeasured.split(". ").size - 1)
        assertTrue(unmeasured.endsWith("다시 신고해 주세요."))
        assertFalse(unmeasured.contains("접수"))
    }

    @Test
    fun mainActivityCarriesTheMarkerAndUsesOneSentenceForAllChannels() {
        val source = String(
            Files.readAllBytes(Paths.get("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")),
            Charsets.UTF_8,
        )
        val publish = source.substringAfter("internal fun publishCurrentFrameReportState(")
            .substringBefore("private fun prepareReportCandidate(")
        assertTrue(publish.contains("latestExplicitReportUnmeasuredDamageCapturedAtMs ="))
        assertTrue(publish.contains("if (explicitReportDamageWithoutDepth) capturedAtMs else 0L"))
        assertTrue(
            source.contains(
                "AndroidRiskSelectionPolicy.hasReportableDamageWithoutReportDepth(frozenReportOutputs)",
            ),
        )
        val prepare = source.substringAfter("private fun prepareExplicitReportCandidateIfCurrent(")
            .substringBefore("private fun beginExplicitReportConfirmationDisclosure(")
        assertTrue(prepare.contains("latestExplicitReportUnmeasuredDamageCapturedAtMs == latestExplicitReportCapturedAtMs"))
        assertTrue(prepare.contains("ExplicitReportRejectionNotice.explicitStatus(status, unmeasuredDamage)"))
        val request = source.substringAfter("private fun requestExplicitReport(")
            .substringBefore("private fun ensureExplicitReportAuthorityPreconditions(")
        assertTrue(request.contains("ExplicitReportRejectionNotice.messageFor(status)"))
        assertTrue(request.contains("speakInteraction(message)"))
        assertTrue(request.contains("renderExplicitReportConfirmationIdle(message)"))
        assertFalse(request.contains("\"신고할 손상 점자블록이 없습니다.\""))
        // Resets clear the capture time, which retires the marker without another reset site.
        assertTrue(
            source.substringAfter("private fun clearExplicitReportFrameState(")
                .substringBefore("private fun DepthFrameSnapshot.runtimeMetricValidSampleCount(")
                .contains("latestExplicitReportCapturedAtMs = 0L"),
        )
        // The live-region view that carries the refusal to TalkBack.
        assertTrue(source.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE\n            visibility = View.GONE"))
    }

    private fun damage(
        trackId: String,
        source: DepthSource = DepthSource.ARCORE_RAW_DEPTH,
        finalScore: Float = 0.85f,
    ): TrackedObjectDepth = TrackedObjectDepth(
        frameId = 1L,
        timestampMs = 1_000L,
        trackId = trackId,
        className = "damaged_tactile_block",
        detectionConfidence = 0.95f,
        bboxNorm = RectNorm(0.2f, 0.3f, 0.4f, 0.2f),
        polygonNorm = emptyList(),
        maskAreaNorm = 0.08f,
        centerNorm = Point2(0.4f, 0.4f),
        bottomContactNorm = null,
        source = source,
        zDistanceM = if (source.metric) 1.1f else null,
        rayDistanceM = null,
        groundDistanceM = null,
        riskDistanceM = if (source.metric) 1.0f else null,
        validSampleCount = if (source.metric) 40 else 0,
        validSampleRatio = if (source.metric) 0.8f else 0f,
        depthMedianM = if (source.metric) 1.1f else null,
        depthP20M = if (source.metric) 1.0f else null,
        depthIqrM = if (source.metric) 0.1f else null,
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
        userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
        trackAgeFrames = 10,
        trackStableMs = 2_000L,
    )
}
