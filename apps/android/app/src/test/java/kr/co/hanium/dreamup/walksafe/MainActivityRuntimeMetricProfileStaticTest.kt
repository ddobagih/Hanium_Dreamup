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
        val controller = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricSessionController.kt").readText()
        val coordinator = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/metric/RuntimeMetricPreflightCoordinator.kt").readText()
        assertEquals(2, Regex("RuntimeMetricPreflightSession\\(").findAll(controller).count())
        assertEquals(1, Regex("RuntimeMetricPreflightSession\\(generation").findAll(coordinator).count())
        listOf("finishRuntimeMetricPreflightWithoutSession", "beginRuntimeMetricActivation").forEach { name ->
            val body = MainActivityRefactorSource.member(controller, name)
            assertTrue(body, body.contains("profile = scope.profile"))
            assertFalse(body, body.contains("testCandidateProfile"))
        }
        val start = MainActivityRefactorSource.member(coordinator, "startRuntimeMetricPreflightSession")
        assertTrue(start.contains("RuntimeMetricDepthSupport.SUPPORTED, admission.profile()"))
        assertFalse(start.contains("testCandidateProfile"))
        assertTrue(MainActivityRefactorSource.property(source, "runtimeMetricSession")
            .contains("override val profile get() = activeEnvironmentProfiles?.runtimeMetric"))
        assertTrue(MainActivityRefactorSource.property(source, "runtimeMetricPreflight")
            .contains("override fun profile() = activeEnvironmentProfiles?.runtimeMetric"))
        assertTrue(source.contains("WalkSafeEnvironmentProfiles.active(allowTestCandidate = BuildConfig.DEBUG)"))
    }
}
