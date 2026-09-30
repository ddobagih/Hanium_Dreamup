package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import kr.co.hanium.dreamup.walksafe.ReportStaticSourceInspector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S15-4: a quarantined head is skipped by the drain loop and the user is told about it. */
class MainActivityReportQueueQuarantineNoticeStaticTest {
    private val main = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun quarantinedEntryContinuesTheDrainAndProducesAVisibleNotice() {
        val drain = ReportStaticSourceInspector.functionBlock(main, "private fun drainInitialExactReportQueue")
        val stop = drain.substringAfter("val outcome = result.outcome").substringBefore("remaining -= 1")
        assertTrue(stop.contains("outcome != ReportQueueDrainOutcome.QUARANTINED"))
        assertTrue(drain.indexOf("reportQueueDrainResultMessage(result)") < drain.indexOf("val outcome = result.outcome"))

        val message = ReportStaticSourceInspector.functionBlock(main, "private fun reportQueueDrainResultMessage")
        val quarantined = message.substringAfter("ReportQueueDrainOutcome.QUARANTINED ->")
            .substringBefore("else -> null")
        assertTrue("QUARANTINED must map to a notice", quarantined.contains("기기에 보관했습니다"))
        assertFalse(quarantined.contains("null"))
        assertFalse(quarantined.contains("접수되었습니다"))
    }
}
