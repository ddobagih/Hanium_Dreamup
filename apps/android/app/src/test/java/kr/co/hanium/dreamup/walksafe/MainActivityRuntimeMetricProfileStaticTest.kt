package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S14-22: production preflight sessions use the build-bound bundle, never an implicit test profile. */
class MainActivityRuntimeMetricProfileStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun everyPreflightSessionReceivesTheActiveEnvironmentProfile() {
        val calls = Regex("""(?<![A-Za-z])RuntimeMetricPreflightSession\((.*?)\n\s*\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(3, calls.size)
        calls.forEach { call ->
            assertTrue(call, call.contains("profile = activeEnvironmentProfiles?.runtimeMetric"))
            assertFalse(call, call.contains("testCandidateProfile"))
        }
        assertTrue(
            source.contains(
                "WalkSafeEnvironmentProfiles.active(allowTestCandidate = BuildConfig.DEBUG)",
            ),
        )
    }
}
