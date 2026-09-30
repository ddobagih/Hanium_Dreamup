package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-25: the runtime frame hands its acquisition failure to the metric gate once per frame. */
class MainActivityDepthAcquisitionSeverityStaticTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
    }

    @Test
    fun runtimeFrameConsumesTheFailureRightAfterAcquisitionAndPassesItToTheGate() {
        val frame = source.substringAfter("override fun onDrawFrame(gl: GL10?)")
            .substringBefore("private fun handleRuntimeMetricPreflightFrame(")
        val acquired = frame.indexOf("provider.acquireDepthSnapshot(")
        val consumed = frame.indexOf("provider.consumeRuntimeAcquisitionFailure()")
        val preflightReturn = frame.indexOf("handleRuntimeMetricPreflightFrame(")
        assertTrue(acquired in 0 until consumed)
        // Consumed before any early return, so a stale reason cannot stop a later frame.
        assertTrue(consumed < preflightReturn)
        assertEquals(1, Regex("consumeRuntimeAcquisitionFailure\\(\\)").findAll(source).count())
        val gateCall = frame.substringAfter("updateRuntimeMetricOutputGate(").substringBefore(")\n")
        assertTrue(gateCall.contains("acquisitionFailure = runtimeAcquisitionFailure"))
    }

    @Test
    fun gateStopsAtOnceForFatalFailuresInBothActivationAndSteadyState() {
        val gate = source.substringAfter("private fun updateRuntimeMetricOutputGate(")
            .substringBefore("private fun startInitialNavigationServicesIfCurrent(")
        assertTrue(gate.contains("val acquisitionFatal = acquisitionFailure?.stopsImmediately == true"))
        assertTrue(gate.contains("result.status == RuntimeMetricPreflightStatus.AVAILABLE && !acquisitionFatal"))
        assertTrue(gate.contains("lost = result.status == RuntimeMetricPreflightStatus.UNKNOWN || acquisitionFatal"))
        val steady = gate.substringAfter("val assessment = assessRuntimeDepthFrame(")
            .substringBefore("if (assessment.isNewFrame)")
        assertTrue(steady.contains("acquisitionFailure = acquisitionFailure"))
        assertTrue(gate.contains("shouldHandleLoss = assessment.shouldHandleLoss"))
        assertTrue(gate.contains("runOnUiThread { handleRuntimeMetricLoss(expectedArSessionGeneration) }"))
    }
}
