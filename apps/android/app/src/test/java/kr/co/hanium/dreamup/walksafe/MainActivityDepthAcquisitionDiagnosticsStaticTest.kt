package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-18: the runtime status keeps the provider's bounded depth-acquisition failure reasons. */
class MainActivityDepthAcquisitionDiagnosticsStaticTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
    }

    @Test
    fun bothRuntimeStatusBranchesShowOperationalAcquisitionFailureReasons() {
        val renderSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRenderController.kt").readText()
        val presentationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePresentationController.kt").readText()
        val publicationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePublicationController.kt").readText()
        val render = MainActivityRefactorSource.member(renderSource, "draw")
        val presentation = MainActivityRefactorSource.member(presentationSource, "showStatus")
        val publish = MainActivityRefactorSource.member(publicationSource, "publish")
        val suffix = MainActivityRefactorSource.member(presentationSource, "suffix")
        assertTrue(MainActivityRefactorSource.property(source, "frameRenderController").contains("captured.provider"))
        assertTrue(render.contains("lease.provider"))
        assertTrue(publish.contains("input.provider.runtimeAcquisitionDiagnostics()"))
        assertTrue(publish.contains("val status = \"depthAcquisitionFailures=\${input.provider.runtimeAcquisitionDiagnostics()}\""))
        assertTrue(presentation.indexOf("if (!shouldUpdate) return") < presentation.indexOf("val depthAcquisitionStatus = prepareAcquisitionStatus()"))
        assertEquals(2, Regex("suffix\\(").findAll(presentation).count())
        assertTrue(suffix.contains("\${parts.visualTracking}\\n\$acquisition\\n"))
        val binding = MainActivityRefactorSource.composition(source, "framePresentation")
        assertTrue(binding.contains("latestVisualTrackingStatus"))
        assertTrue(MainActivityRefactorSource.property(source, "framePipelineController").contains("FramePublicationController("))
    }
    @Test
    fun cameraConsumersAreLabeledAndTrackingLossStillShowsAndLogsTheCounts() {
        // S11-18/S11-16: diagnostics count the one real acquisition, not each shared copy consumer.
        assertEquals(0, Regex("provider\\.acquireCameraImageOrNull\\(frame").findAll(source).count())
        val recognition = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText()
        val capture = ReportStaticSourceInspector.functionBlock(recognition, "internal fun <T> acquireFrameRecognitionCapture")
        MainActivityRefactorSource.composition(source, "frameRecognition")
        assertEquals(1, Regex("provider\\.acquireRecognitionCameraImageOrNull\\(frame\\)").findAll(capture).count())
        assertEquals(1, Regex("provider\\.acquireUntrackedRecognitionCameraImageOrNull\\(frame\\)").findAll(capture).count())
        assertTrue(capture.indexOf("if (!tracking)") < capture.indexOf("val packet ="))
        assertTrue(capture.substringAfter("if (!tracking)").substringBefore("val packet =").contains("return FrameRecognitionCapture(input,"))
        assertTrue(capture.contains("packet?.image?.use(copyInput)"))
        assertTrue(capture.contains("packet?.timestampEvidence?.takeIf { input != null }"))
        val namedSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText()
        val named = MainActivityRefactorSource.member(namedSource, "run")
        val namedBinding = MainActivityRefactorSource.member(source, "createPrimaryArDetectorWorker")
        val unknownSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/unknown/UnknownCaptureCoordinator.kt").readText()
        val unknown = MainActivityRefactorSource.member(unknownSource, "submitUnknownObjectFrame")
        MainActivityRefactorSource.composition(source, "unknownCaptureCoordinator")
        assertTrue(named.contains("consumeOwnedInput(work.input)"))
        assertTrue(namedBinding.contains("consumeRuntimeRecognitionInput(input) { it }"))
        assertTrue(unknown.contains("input.cameraFrame.toArgb()"))
        assertTrue(unknown.contains("service.submitArgb(rgb.width, rgb.height, rgb.pixels, token,"))
        val pipelineSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePipelineController.kt").readText()
        val publicationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePublicationController.kt").readText()
        val frame = MainActivityRefactorSource.member(pipelineSource, "qualify")
        val lost = frame.substringAfter("if (!runtime.visualOutputAllowsWork(")
        assertTrue(lost.indexOf("recording.acquisitionFailures(source.resources.provider, source.capturedElapsedMs)") in 0 until
            lost.indexOf("presentation().showUnavailable("))
        assertTrue(lost.contains("\\ndepthAcquisitionFailures=\${source.resources.provider.runtimeAcquisitionDiagnostics()}"))
        val status = MainActivityRefactorSource.member(publicationSource, "publish")
        assertTrue(status.contains("recording.acquisitionFailures(input.provider, scope.elapsedMs)"))
        assertTrue(MainActivityRefactorSource.property(source, "framePipelineController").contains("recordDepthAcquisitionFailuresIfDue(provider, capturedElapsedMs)"))
        val helper = MainActivityRefactorSource.member(source, "recordDepthAcquisitionFailuresIfDue")
        assertTrue(helper.contains("provider.runtimeAcquisitionFieldLogSummaryIfDue(nowElapsedRealtimeMs) ?: return"))
        assertTrue(helper.contains("fieldSessionLog.recordEvent(\"depth_acquisition_failures\", mapOf(\"state\" to summary))"))
    }
}
