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
        val capture = owner("navigation/capture/TactileFrameCaptureController.kt")
        assertTrue(MainActivityRefactorSource.composition(source, "tactileCapture").contains("TactileFrameCaptureController"))
        assertTrue(capture.contains("hasConflictingTactileState(guidance.outputs)"))
        assertTrue(capture.contains("mode = kr.co.hanium.dreamup.walksafe.navigation.LocalRouteMode.TMAP"))
        assertTrue(capture.contains("safeFrame to receipt"))
        val pipeline = owner("runtime/vision/frame/FramePipelineController.kt")
        assertTrue(pipeline.contains("resolveConflictingStateFeedback(result.guidance.outputs, clock.elapsedRealtimeMs())"))
        val dispatchSource = String(
            Files.readAllBytes(Paths.get("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileFrameCoordinator.kt")),
            Charsets.UTF_8,
        )
        assertTrue(dispatchSource.contains("resolveConflictingStateFeedback(\n            frame.guidance.outputs, nowElapsedRealtimeMs = nowMs,"))
        assertTrue(owner("runtime/vision/frame/FramePublicationController.kt").contains("tactile.dispatchFeedback(input.receipt, input.frame,"))
    }

    @Test
    fun explicitReportRefusesSameCaptureStateConflictWithAnUnderstandableNotice() {
        val source = mainSource()
        val draw = owner("runtime/vision/frame/FramePublicationController.kt")
        assertTrue(draw.contains("hasConflictingReportDamage(input.frozenOutputs)"))
        val publish = MainActivityRefactorSource.function(source, "internal fun publishCurrentFrameReportState(")
        assertTrue(publish.contains("latestExplicitReportConflictingStateCapturedAtMs ="))
        val prepare = MainActivityRefactorSource.function(source, "private fun prepareExplicitReportCandidateIfCurrent(")
        assertTrue(prepare.contains("latestExplicitReportConflictingStateCapturedAtMs == latestExplicitReportCapturedAtMs"))
        assertTrue(prepare.contains("reportCandidate=blocked:conflicting_tactile_state"))
        val request = MainActivityRefactorSource.function(source, "private fun requestExplicitReport(")
        assertTrue(request.contains("점자블록의 손상 상태가 불확실해 신고를 저장하지 않았습니다."))
        assertTrue(request.contains("speakInteraction(message)"))
        assertTrue(request.contains("ui.renderIdle(message)"))
    }

    private fun owner(path: String) = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()

    private fun mainSource(): String = String(
        Files.readAllBytes(Paths.get("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")),
        Charsets.UTF_8,
    )
}
