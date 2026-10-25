package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityGlRenderLifecycleStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun idleSurfaceStartsWhenDirtyAndContinuousRenderingRequiresForegroundArSession() {
        val cameraComposer = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeCameraViewComposer.kt").readText()
        val content = MainActivityRefactorSource.member(cameraComposer, "createSurface")
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("surfaceView = cameraComposer.createSurface(this@MainActivity)"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView").contains("NativeCameraViewComposer(this, viewStyle)"))
        val renderMode = functionBlock("private fun syncGlSurfaceRenderMode(")

        assertTrue(content.contains("renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY"))
        assertFalse(content.contains("renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY"))
        assertTrue(renderMode.contains("isActivityForeground"))
        assertTrue(renderMode.contains("arSessionOwner.observe().session != null"))
        assertTrue(renderMode.contains("arSessionOwner.observe().purpose != ArSessionPurpose.NONE"))
        assertTrue(renderMode.contains("GLSurfaceView.RENDERMODE_CONTINUOUSLY"))
        assertTrue(renderMode.contains("GLSurfaceView.RENDERMODE_WHEN_DIRTY"))
        assertTrue(renderMode.contains("surfaceView.requestRender()"))
        assertTrue(
            source.indexOf("GLSurfaceView.RENDERMODE_CONTINUOUSLY") ==
                source.lastIndexOf("GLSurfaceView.RENDERMODE_CONTINUOUSLY"),
        )
        assertTrue(
            source.contains("setContentView(insetNativeContent(buildContentView()))\n                surfaceView.onPause()"),
        )
    }

    @Test
    fun arSessionPublicationAndRemovalOwnTheRenderModeBoundary() {
        val pause = functionBlock("private fun pauseRendererForSessionClose()")
        val resume = functionBlock("private fun resumeRendererAfterSessionClose(")
        val preflightSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricPreflightCoordinator.kt").readText()
        val preflight = MainActivityRefactorSource.member(preflightSource, "startRuntimeMetricPreflightSession")
        val preflightBinding = MainActivityRefactorSource.property(source, "runtimeMetricPreflight")
        assertTrue(MainActivityRefactorSource.member(source, "startRuntimeMetricPreflightSession").contains("runtimeMetricPreflight.startRuntimeMetricPreflightSession(preflightSession, provider, generation, lifecycleGeneration)"))
        assertTrue(preflightBinding.contains("RuntimeMetricPreflightCoordinator("))
        assertTrue(preflightBinding.contains("runtimeMetricStateLock, { runtimeMetricSession }, { arSessionOwner }, runtimeCameraPlatform"))
        assertTrue(preflightBinding.contains("ArSessionPurpose.PREFLIGHT, ArSessionPurpose.RUNTIME"))
        assertTrue(preflightBinding.contains("override fun pauseForClose() = pauseRendererForSessionClose()"))
        assertTrue(preflightBinding.contains("override fun resumeAfterClose(resume: Boolean) = resumeRendererAfterSessionClose(resume)"))
        val runtimeContinuation = MainActivityRefactorSource.function(source, "private fun continueDepthSessionStart(")
        val transactions = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
        val runtime = MainActivityRefactorSource.member(transactions, "start")
        val stop = MainActivityRefactorSource.member(transactions, "stopDepthSession")
        val arOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/ar/RuntimeArSessionOwner.kt").readText()
        val detach = MainActivityRefactorSource.member(arOwner, "detachForStop")
        val retire = MainActivityRefactorSource.member(arOwner, "clearActive")

        assertTrue(pause.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
        assertTrue(
            pause.indexOf("syncGlSurfaceRenderMode(forceFrame = true)") <
                pause.indexOf("surfaceView.onPause()"),
        )
        assertTrue(resume.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
        assertTrue(resume.contains("surfaceView.onResume()"))
        assertTrue(preflight.contains("native().publish(preflightSession, provider, preflightPurpose)"))
        assertTrue(preflight.contains("renderer.resumeAfterClose(resumeRenderer)"))
        assertTrue(runtime.contains("arOwner.publish(newSession, provider, runtimePurpose)"))
        assertTrue(runtime.contains("renderer.resumeAfterClose(resumeRenderer)"))
        assertTrue(detach.contains("clearActive()") && retire.contains("purpose = nonePurpose"))
        assertTrue(stop.contains("renderer.pauseForClose()"))
        val binding = MainActivityRefactorSource.composition(source, "runtimeCameraLifecycle")
        assertTrue(binding.contains("ArSessionPurpose.NONE, ArSessionPurpose.RUNTIME"))
        assertTrue(runtimeContinuation.contains("transactions.start("))
        assertTrue(binding.contains("override fun pauseForClose() = pauseRendererForSessionClose()"))
        assertTrue(binding.contains("override fun resumeAfterClose(shouldResume: Boolean) = resumeRendererAfterSessionClose(shouldResume)"))
    }

    @Test
    fun cameraFallbackAndActivityPauseDoNotWakeTheGlLoop() {
        val afterCameraRelease = MainActivityRefactorSource.function(source, "private fun startWalkSessionRuntimeAfterCameraRelease(")
        val binding = MainActivityRefactorSource.composition(source, "walkRuntimeCameraAdmissionController")
        val fallback = MainActivityRefactorSource.function(source, "private fun startCameraFallbackSession(")
        val activityResume = functionBlock("override fun onResume()")
        val pause = functionBlock("internal fun pauseWalkSafeRuntime()")
        val cancelOutputs = MainActivityRefactorSource.function(source, "private fun cancelWalkSessionOutputs(")
        val cleanupBinding = MainActivityRefactorSource.composition(source, "walkSessionCleanupController")

        assertTrue(afterCameraRelease.contains("effects.resumeOrPauseSurfaceForCurrentArSession()"))
        assertTrue(binding.contains("val arSessionReady ="))
        assertTrue(binding.contains("if (arSessionReady) surfaceView.onResume()"))
        assertTrue(binding.contains("if (!arSessionReady) surfaceView.onPause()"))
        assertFalse(fallback.contains("surfaceView.onResume()"))
        assertFalse(activityResume.contains("surfaceView.onResume()"))
        assertTrue(pause.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
        assertTrue(
            pause.indexOf("syncGlSurfaceRenderMode(forceFrame = true)") <
                pause.indexOf("surfaceView.onPause()"),
        )
        assertTrue(cleanupBinding.contains("allowContinuousRendering = false"))
        assertTrue(
            cleanupBinding.indexOf("syncGlSurfaceRenderMode(") <
                cleanupBinding.indexOf("surfaceView.onPause()"),
        )
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        val opening = source.indexOf('{', start)
        check(opening >= 0) { "missing body: $signature" }
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }
}
