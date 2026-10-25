package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityDeviceCheckRuntimeRegressionStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun metricPreflightLatchIsSetOnlyAfterAllStartGuardsPass() {
        val continuation = functionBlock("private fun maybeContinuePostLoginDeviceCheck(")
        val pendingBranch = continuation.substringAfter(
            "if (observation.metricDepth == PostLoginMetricDepthState.PENDING",
        ).substringBefore("evaluate(binding)")
        assertTrue(pendingBranch.contains("probes.startMetricPreflight(binding)"))
        assertFalse(pendingBranch.contains("probes.metricPreflightStarted = true"))

        assertTrue(MainActivityRefactorSource.property(source, "postLoginDeviceProbes").contains("get() = postLoginDeviceProbesHolder.value"))
        val probeBinding = MainActivityRefactorSource.property(source, "postLoginDeviceProbesHolder")
        assertTrue(probeBinding.contains("override fun startMetricPreflight(binding: PostLoginDeviceCheckBinding)"))
        assertTrue(probeBinding.contains("beginRuntimeMetricPreflight()"))
        val metricBinding = MainActivityRefactorSource.property(source, "runtimeMetricSession")
        assertTrue(metricBinding.contains("bindingCurrent(binding: PostLoginDeviceCheckBinding) = isPostLoginDeviceCheckBindingCurrent(binding)"))
        assertTrue(metricBinding.contains("postLoginDeviceProbes.setMetricPreflightStarted(started)"))
        val begin = functionBlock("private fun beginRuntimeMetricPreflight()")
        assertInOrder(
            begin,
            "if (!canBeginRuntimeMetricPreflight()) return",
            "device.bindingCurrent(binding)",
            "device.setPreflightStarted(true)",
        )
    }

    @Test
    fun onboardingDepthPreflightResumesTheRendererThatConsumesFrames() {
        val preflight = functionBlock("private fun startRuntimeMetricPreflightSession(")
        val resume = functionBlock("private fun resumeRendererAfterSessionClose(")

        assertTrue(preflight.contains("native().publish(preflightSession, provider, preflightPurpose)"))
        val binding = MainActivityRefactorSource.composition(source, "runtimeMetricPreflight")
        assertTrue(binding.contains("{ arSessionOwner }"))
        assertTrue(binding.contains("ArSessionPurpose.PREFLIGHT, ArSessionPurpose.RUNTIME, SystemClock::elapsedRealtime"))
        assertTrue(preflight.contains("renderer.resumeAfterClose(resumeRenderer)"))
        assertTrue(binding.contains("override fun resumeAfterClose(resume: Boolean) = resumeRendererAfterSessionClose(resume)"))
        assertTrue(resume.contains("session != null"))
        assertTrue(resume.contains("arSessionOwner.observe().purpose != ArSessionPurpose.NONE"))
        assertTrue(resume.contains("surfaceView.onResume()"))
        assertTrue(resume.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)

    private fun owner(path: String, name: String): String = MainActivityRefactorSource.member(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), name,
    )

    private fun assertInOrder(text: String, vararg snippets: String) {
        var cursor = -1
        snippets.forEach { snippet ->
            val next = text.indexOf(snippet, cursor + 1)
            assertTrue("missing or out of order: $snippet", next > cursor)
            cursor = next
        }
    }
}
