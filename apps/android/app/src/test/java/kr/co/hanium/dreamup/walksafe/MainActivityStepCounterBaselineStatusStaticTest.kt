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
        val heartbeat = source.substringAfter("private fun startFieldSessionHeartbeat() {")
            .substringBefore("override fun onDrawFrame(")
        val sensors = heartbeat.substringAfter("FieldHeartbeatSensors(").substringBefore("heartbeat.tick(").ifEmpty {
            heartbeat.substringAfter("FieldHeartbeatSensors(")
        }
        assertTrue(sensors.contains("stepCounterBaseline = if (::stepTracker.isInitialized) {"))
        assertTrue(sensors.contains("stepTracker.counterBaselineState.name"))
    }
}
