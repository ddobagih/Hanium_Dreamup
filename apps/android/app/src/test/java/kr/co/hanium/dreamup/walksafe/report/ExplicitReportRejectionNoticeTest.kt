package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import kr.co.hanium.dreamup.walksafe.ReportStaticSourceInspector

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
        val publish = MainActivityRefactorSource.function(source, "internal fun publishCurrentFrameReportState(")
        assertTrue(publish.contains("latestExplicitReportUnmeasuredDamageCapturedAtMs ="))
        assertTrue(publish.contains("if (explicitReportDamageWithoutDepth) capturedAtMs else 0L"))
        assertTrue(
            java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePublicationController.kt").readText().contains(
                "AndroidRiskSelectionPolicy.hasReportableDamageWithoutReportDepth(input.frozenOutputs)",
            ),
        )
        val prepare = MainActivityRefactorSource.function(source, "private fun prepareExplicitReportCandidateIfCurrent(")
        assertTrue(prepare.contains("latestExplicitReportUnmeasuredDamageCapturedAtMs == latestExplicitReportCapturedAtMs"))
        assertTrue(prepare.contains("ExplicitReportRejectionNotice.explicitStatus(status, unmeasuredDamage)"))
        val request = MainActivityRefactorSource.function(source, "private fun requestExplicitReport(")
        assertTrue(request.contains("ExplicitReportRejectionNotice.messageFor(status)"))
        assertTrue(request.contains("speakInteraction(message)"))
        assertTrue(request.contains("ui.renderIdle(message)"))
        assertFalse(request.contains("\"신고할 손상 점자블록이 없습니다.\""))
        // Resets clear the capture time, which retires the marker without another reset site.
        val candidate = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportCandidateController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(candidate, "clearFrameCapture").contains("latestExplicitReportCapturedAtMs = 0L"))
        val composer = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRuntimeViewComposer.kt").readText()
        val confirmation = MainActivityRefactorSource.member(composer, "createReportConfirmation")
        assertTrue(confirmation.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertTrue(confirmation.contains("visibility = View.GONE"))
        assertTrue(source.contains("explicitReportConfirmationText = runtimeComposer.createReportConfirmation()"))

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
