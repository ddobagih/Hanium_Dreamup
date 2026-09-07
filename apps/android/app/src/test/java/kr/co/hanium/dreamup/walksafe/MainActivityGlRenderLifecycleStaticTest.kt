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
        val content = functionBlock("private fun buildContentView()")
        val renderMode = functionBlock("private fun syncGlSurfaceRenderMode(")

        assertTrue(content.contains("renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY"))
        assertFalse(content.contains("renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY"))
        assertTrue(renderMode.contains("isActivityForeground"))
        assertTrue(renderMode.contains("session != null"))
        assertTrue(renderMode.contains("arSessionPurpose != ArSessionPurpose.NONE"))
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
        val preflight = functionBlock("private fun startRuntimeMetricPreflightSession(")
        val runtime = functionBlock("private fun continueDepthSessionStart(")
        val stop = functionBlock("private fun stopDepthSession(")

        assertTrue(pause.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
        assertTrue(
            pause.indexOf("syncGlSurfaceRenderMode(forceFrame = true)") <
                pause.indexOf("surfaceView.onPause()"),
        )
        assertTrue(resume.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
        assertTrue(resume.contains("surfaceView.onResume()"))
        assertTrue(preflight.contains("arSessionPurpose = ArSessionPurpose.PREFLIGHT"))
        assertTrue(preflight.contains("resumeRendererAfterSessionClose(resumeRenderer)"))
        assertTrue(runtime.contains("arSessionPurpose = ArSessionPurpose.RUNTIME"))
        assertTrue(runtime.contains("resumeRendererAfterSessionClose(resumeRenderer)"))
        assertTrue(stop.contains("arSessionPurpose = ArSessionPurpose.NONE"))
        assertTrue(stop.contains("pauseRendererForSessionClose()"))
    }

    @Test
    fun cameraFallbackAndActivityPauseDoNotWakeTheGlLoop() {
        val afterCameraRelease = functionBlock(
            "private fun startWalkSessionRuntimeAfterCameraRelease(",
        )
        val fallback = functionBlock("private fun startCameraFallbackSession(")
        val activityResume = functionBlock("override fun onResume()")
        val pause = functionBlock("internal fun pauseWalkSafeRuntime()")
        val cancelOutputs = functionBlock("private fun cancelWalkSessionOutputs(")

        assertTrue(afterCameraRelease.contains("val arSessionReady ="))
        assertTrue(afterCameraRelease.contains("if (arSessionReady) surfaceView.onResume()"))
        assertTrue(afterCameraRelease.contains("if (!arSessionReady) surfaceView.onPause()"))
        assertFalse(fallback.contains("surfaceView.onResume()"))
        assertFalse(activityResume.contains("surfaceView.onResume()"))
        assertTrue(pause.contains("syncGlSurfaceRenderMode(forceFrame = true)"))
        assertTrue(
            pause.indexOf("syncGlSurfaceRenderMode(forceFrame = true)") <
                pause.indexOf("surfaceView.onPause()"),
        )
        assertTrue(cancelOutputs.contains("allowContinuousRendering = false"))
        assertTrue(
            cancelOutputs.indexOf("syncGlSurfaceRenderMode(") <
                cancelOutputs.indexOf("surfaceView.onPause()"),
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
