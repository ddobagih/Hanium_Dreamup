package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityDepthAcquisitionSeverityStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    @Test fun runtimeFrameConsumesTheFailureRightAfterAcquisitionAndPassesItToTheGate() {
        val render = source("runtime/vision/frame/ArFrameRenderController")
        val capture = source("runtime/vision/frame/ArFrameRecognitionCapture")
        assertTrue(main.contains("override fun onDrawFrame(gl: GL10?) = framePipelineController.draw()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(render,
            "recognition.acquireFrameRecognitionCapture(", "requireNotNull(captured).depthSnapshot",
            "consumeRuntimeAcquisitionFailure()", "observer.processPreflight(scope)", "processRuntimeCapture(scope)"))
        assertEquals(1, Regex("consumeRuntimeAcquisitionFailure\\(\\)").findAll(render).count())
        assertTrue(ReportStaticSourceInspector.appearsInOrder(capture,
            "packet?.image?.use(copyInput)", "provider.acquireDepthSnapshot("))
        assertTrue(capture.contains("cameraImageTimestampEvidence = packet?.timestampEvidence?.takeIf { input != null }"))
        assertTrue(source("runtime/vision/frame/FramePipelineController").contains("runtime.updateMetricOutput(source, epoch)"))
        assertTrue(main.contains("capture.resources.generation, epoch, capture.acquisitionFailure, capture.recognition?.input?.cameraTimestampNs)"))
        assertTrue(source("runtime/metric/RuntimeMetricPreflightCoordinator").contains("acquisitionFailure"))
    }

    @Test fun gateStopsAtOnceForFatalFailuresInBothActivationAndSteadyState() {
        val gate = MainActivityRefactorSource.member(source("runtime/metric/RuntimeMetricSessionController"), "updateRuntimeMetricFrameEvidence")
        assertTrue(gate.contains("val acquisitionFatal = acquisitionFailure?.stopsImmediately == true"))
        assertTrue(gate.contains("result.status == RuntimeMetricPreflightStatus.AVAILABLE && !acquisitionFatal"))
        assertTrue(gate.contains("lost = result.status == RuntimeMetricPreflightStatus.UNKNOWN || acquisitionFatal"))
        val steady = gate.substringAfter("val assessment = assessRuntimeDepthFrame(").substringBefore("if (assessment.isNewFrame)")
        assertTrue(steady.contains("acquisitionFailure = acquisitionFailure"))
        assertTrue(steady.contains("ordinaryDepthGap = assessment.fatalAcquisitionFailure == null"))
        assertTrue(gate.contains("shouldHandleLoss = assessment.shouldHandleLoss"))
        assertTrue(gate.contains("effects.postToUi { effects.runtimeMetricLost(expectedArSessionGeneration) }"))
    }
}
