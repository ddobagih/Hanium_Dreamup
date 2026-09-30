package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 16-17 #36: the debug stop shows the in-app approved summary; nothing leaves the app in plaintext. */
class MainActivityFieldStatsSummaryStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun debugStopShowsTheApprovedSummaryOnly() {
        val toggle = source.substringAfter("private fun toggleFieldSessionLog() {")
            .substringBefore("} else {")
        assertTrue(toggle.contains("if (!BuildConfig.DEBUG) return") || source.substringAfter("private fun toggleFieldSessionLog() {").trimStart().startsWith("if (!BuildConfig.DEBUG) return"))
        val stop = toggle.indexOf("fieldSessionLog.stop()")
        val show = toggle.indexOf("fieldSessionLog.lastSessionStatsSummary()?.let { updateStatus(\"현장 로그 요약\", it.displayText()) }")
        assertTrue(stop >= 0 && show > stop)
        val log = File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt").readText()
        val noop = log.substringAfter("class NoopFieldSessionLog : FieldSessionLog {").substringBefore("\n}")
        // Release keeps the Noop log: no summary override, so the interface default (null/NONE) applies.
        assertFalse(noop.contains("lastSessionStatsSummary"))
        assertTrue(log.contains("fun lastSessionStatsSummary(): FieldSessionStatsSummary? = null"))
    }
}
