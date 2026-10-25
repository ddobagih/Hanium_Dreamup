package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring only; resource failures and their original timestamps are tested in device tests. */
class MainActivityResourceDiagnosticsStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun body(name: String): String = MainActivityRefactorSource.function(source, "private fun $name(")

    @Test
    fun resourceEventsRecordAndObserveTheSameSnapshotWithoutAnExtraQuery() {
        val callback = body("ensureWalkSessionResourceMonitoring")
        assertTrue(callback.contains("val resources = walkSessionResourceProbe.snapshot()"))
        assertTrue(callback.contains("logWalkSessionResourceDiagnostics(\"resource_event\", resources)"))
        assertTrue(callback.contains("observeWalkRuntimeResourceSafety(resources = resources)"))
        assertTrue(callback.split("walkSessionResourceProbe.snapshot()").size == 2)
    }

    @Test
    fun explicitChecksRecordDiagnosticsAndAutomaticFrameChecksKeepRecordingDisabled() {
        val capture = body("captureWalkSessionReadiness")
        assertTrue(capture.contains("recordResourceDiagnostics: Boolean = false"))
        assertTrue(capture.contains("if (recordResourceDiagnostics)"))
        assertTrue(capture.contains("resources.recordDiagnostic(\"readiness_recheck\", deviceResources)"))
        for (name in listOf("currentGatewayWalkStartReadinessOrNull", "recheckWalkSessionResumeBeforeConfirmation")) {
            assertTrue("Explicit check has no resource diagnostics: $name", body(name).contains("recordResourceDiagnostics = true"))
        }
        assertTrue(body("handleStartupCapabilityConfirmAction").contains(
            "effects.logResources(\"explicit_recheck\", facts.resourceSnapshot())",
        ))
        val logger = body("logWalkSessionResourceDiagnostics")
        assertTrue(logger.contains("if (BuildConfig.DEBUG)"))
        assertTrue(logger.contains("resources.resourceDiagnosticsLine()"))
        assertFalse(logger.contains("walkSessionResourceProbe.snapshot()"))
        for (name in listOf("startAdmittedNavigationBeforeCamera", "completePendingWalkSessionResumeActivation", "applyRuntimeReadinessIfActive")) {
            val automatic = body(name)
            assertFalse("Automatic path records diagnostics: $name", automatic.contains("recordResourceDiagnostics = true"))
        }
    }
}
