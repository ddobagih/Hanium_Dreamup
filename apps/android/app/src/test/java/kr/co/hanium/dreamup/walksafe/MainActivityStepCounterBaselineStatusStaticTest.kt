package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-20 (originals/09.txt:57 "시작 준비 중 Counter 기준값 확보 여부 명시", w25 review pos-F10): the existing
 * field heartbeat status record carries the step tracker's Counter baseline state as an enum name.
 */
class MainActivityStepCounterBaselineStatusStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun theFieldHeartbeatCarriesTheCounterBaselineStateAsAnEnumName() {
        assertHeartbeatStartBinding()
        val heartbeat = MainActivityRefactorSource.property(source, "fieldDiagnosticRecording")
        val sensors = heartbeat.substringAfter("FieldHeartbeatSensors(").substringBefore("heartbeat.tick(").ifEmpty {
            heartbeat.substringAfter("FieldHeartbeatSensors(")
        }
        assertTrue(sensors.contains("stepCounterBaseline = if (::stepTracker.isInitialized)"))
        assertTrue(sensors.contains("stepTracker.counterBaselineState.name"))
    }

    private fun assertHeartbeatStartBinding(mainSource: String = source) {
        MainActivityRefactorSource.composition(mainSource, "fieldDiagnosticRecording")
        val caller = MainActivityRefactorSource.factory(mainSource, "startFieldSessionHeartbeat")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedCaller = """
private fun startFieldSessionHeartbeat() {
        privacyStartupResources.acquireOnWorker(
            { fieldDiagnosticRecording }, { it.closeHeartbeat() },
        ).startFieldSessionHeartbeat()
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main heartbeat must register its original cleanup before starting", expectedCaller.filterNot(Char::isWhitespace),
            caller.filterNot(Char::isWhitespace))
    }

    @Test
    fun heartbeatBindingRejectsMissingOrNonExecutingStartAndRelease() {
        assertHeartbeatStartBinding()
        val caller = MainActivityRefactorSource.factory(source, "startFieldSessionHeartbeat")
        val start = ").startFieldSessionHeartbeat()"
        org.junit.Assert.assertTrue(caller.contains(start))
        val variants = mapOf(
            "conditional start" to caller.replace(start, ").let { if (false) it.startFieldSessionHeartbeat() }"),
            "uninvoked start" to caller.replace(start, ").let { { it.startFieldSessionHeartbeat() } }"),
            "commented start" to caller.replace(start, ") /* .startFieldSessionHeartbeat() */"),
            "omitted start" to caller.replace(start, ")"),
            "commented cleanup" to caller.replace("{ it.closeHeartbeat() }", "{ /* it.closeHeartbeat() */ }"),
            "wrong instance cleanup" to caller.replace("{ it.closeHeartbeat() }", "{ fieldDiagnosticRecording.closeHeartbeat() }"),
        )
        val accepted = variants.filter { (_, changed) ->
            runCatching { assertHeartbeatStartBinding(source.replace(caller, changed)) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent heartbeat bindings accepted: $accepted", accepted.isEmpty())
    }

}
