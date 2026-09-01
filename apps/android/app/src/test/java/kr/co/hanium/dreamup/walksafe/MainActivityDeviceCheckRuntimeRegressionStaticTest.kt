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
            "PostLoginMetricDepthState.PENDING ->",
        ).substringBefore("PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED")
        assertTrue(pendingBranch.contains("beginRuntimeMetricPreflight()"))
        assertFalse(pendingBranch.contains("postLoginMetricPreflightStarted = true"))

        val begin = functionBlock("private fun beginRuntimeMetricPreflight()")
        assertInOrder(
            begin,
            "if (!canBeginRuntimeMetricPreflight()) return",
            "isPostLoginDeviceCheckBindingCurrent(binding)",
            "postLoginMetricPreflightStarted = true",
        )
    }

    @Test
    fun onboardingDepthPreflightResumesTheRendererThatConsumesFrames() {
        val resume = functionBlock("private fun resumeRendererAfterSessionClose(")
        assertTrue(resume.contains("arSessionPurpose == ArSessionPurpose.PREFLIGHT"))
        assertTrue(resume.contains("surfaceView.onResume()"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    depth += 1
                    opened = true
                }
                '}' -> {
                    depth -= 1
                    if (opened && depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated function: $signature")
    }

    private fun assertInOrder(text: String, vararg snippets: String) {
        var cursor = -1
        snippets.forEach { snippet ->
            val next = text.indexOf(snippet, cursor + 1)
            assertTrue("missing or out of order: $snippet", next > cursor)
            cursor = next
        }
    }
}
