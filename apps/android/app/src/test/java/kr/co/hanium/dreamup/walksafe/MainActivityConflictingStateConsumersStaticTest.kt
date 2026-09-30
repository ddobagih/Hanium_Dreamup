package kr.co.hanium.dreamup.walksafe

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10-17: the pure selection policy must reach both live feedback and explicit report consumers. */
class MainActivityConflictingStateConsumersStaticTest {
    @Test
    fun liveFeedbackReceivesOneNeutralStateAndTactileRouteRemainsConservative() {
        val source = mainSource()
        val draw = source.substringAfter("val safeGuidance = rawCurrentFrame.guidance.let")
            .substringBefore("val matchedEvidence = tactileFrame.matchedEvidence")
        assertTrue(draw.contains("hasConflictingTactileState(guidance.outputs)"))
        assertTrue(draw.contains("mode = kr.co.hanium.dreamup.walksafe.navigation.LocalRouteMode.TMAP"))
        assertTrue(draw.contains("rawCurrentFrame.copy(guidance = safeGuidance)"))
        assertTrue(source.contains("resolveConflictingStateFeedback(\n            tactileFrame.guidance.outputs,"))
        val dispatchSource = String(
            Files.readAllBytes(Paths.get("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileFrameCoordinator.kt")),
            Charsets.UTF_8,
        )
        assertTrue(dispatchSource.contains("resolveConflictingStateFeedback(\n            frame.guidance.outputs, nowElapsedRealtimeMs = nowMs,"))
        assertTrue(source.contains("dispatchTactileFrameFeedback(\n            frame = tactileFrame,"))
    }

    @Test
    fun explicitReportRefusesSameCaptureStateConflictWithAnUnderstandableNotice() {
        val source = mainSource()
        val draw = source.substringAfter("publishCurrentFrameReportState(\n                frameGeneration = frameGeneration,")
            .substringBefore("reportImage = detectionSnapshot.reportImage")
        assertTrue(draw.contains("hasConflictingReportDamage(frozenReportOutputs)"))
        val publish = source.substringAfter("internal fun publishCurrentFrameReportState(")
            .substringBefore("private fun prepareReportCandidate(")
        assertTrue(publish.contains("latestExplicitReportConflictingStateCapturedAtMs ="))
        val prepare = source.substringAfter("private fun prepareExplicitReportCandidateIfCurrent(")
            .substringBefore("private fun beginExplicitReportConfirmationDisclosure(")
        assertTrue(prepare.contains("latestExplicitReportConflictingStateCapturedAtMs == latestExplicitReportCapturedAtMs"))
        assertTrue(prepare.contains("reportCandidate=blocked:conflicting_tactile_state"))
        val request = source.substringAfter("private fun requestExplicitReport(")
            .substringBefore("private fun ensureExplicitReportAuthorityPreconditions(")
        assertTrue(request.contains("점자블록의 손상 상태가 불확실해 신고를 저장하지 않았습니다."))
        assertTrue(request.contains("speakInteraction(message)"))
        assertTrue(request.contains("renderExplicitReportConfirmationIdle(message)"))
    }

    private fun mainSource(): String = String(
        Files.readAllBytes(Paths.get("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")),
        Charsets.UTF_8,
    )
}
