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
    fun localInboxUsesLargeButtonsSpeechAndWorkerWithAuthorityRevalidation() {
        assertTrue(main.contains("onClick = { openReportPanel(openStored = true) }"))
        val open = ReportStaticSourceInspector.functionBlock(main, "private fun openQuarantinedReports")
        assertTrue(open.contains("!walkSessionLifecycle.snapshot().state.allowsPostWalkTransfer"))
        assertTrue(open.contains("reportCleanupExecutor.execute"))
        assertTrue(open.contains("quarantineAuthorityCurrent(authority, generation)"))
        assertTrue(open.contains("ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT"))
        val render = ReportStaticSourceInspector.functionBlock(main, "private fun renderQuarantinedReports")
        assertTrue(render.contains("voiceHelpButton(label)"))
        assertTrue(render.contains("보관본 삭제 확인"))
        assertTrue(render.contains("유지하기"))
        val handle = ReportStaticSourceInspector.functionBlock(main, "private fun handleQuarantineAction")
        assertTrue(handle.indexOf("review.deletionTarget()") < handle.indexOf("deleteQuarantinedReport("))
        assertTrue(handle.contains("authorityCurrent = { quarantineAuthorityCurrent(authority, generation) }"))
        assertTrue(handle.contains("review.finishDelete(target, deleted)"))
        val speech = ReportStaticSourceInspector.functionBlock(main, "private fun quarantineSpeech")
        assertTrue(speech.contains("서버 자료를 삭제하는 기능은 아닙니다"))
        assertFalse(speech.contains("imageJpeg"))
        assertFalse(speech.contains("payloadSha256"))
        val close = ReportStaticSourceInspector.functionBlock(main, "private fun closeReportPanel")
        assertTrue(close.contains("quarantineGeneration++"))
        assertTrue(close.contains("quarantineReview = null"))
    }

    @Test
    fun quarantinedEntryContinuesTheDrainAndProducesAVisibleNotice() {
        val drain = ReportStaticSourceInspector.functionBlock(main, "private fun drainInitialExactReportQueue")
        val stop = drain.substringAfter("val outcome = result.outcome").substringBefore("remaining -= 1")
        assertTrue(stop.contains("outcome != ReportQueueDrainOutcome.QUARANTINED"))
        assertTrue(drain.indexOf("reportQueueDrainResultMessage(result)") < drain.indexOf("val outcome = result.outcome"))

        val message = ReportStaticSourceInspector.functionBlock(main, "private fun reportQueueDrainResultMessage")
        val quarantined = message.substringAfter("ReportQueueDrainOutcome.QUARANTINED ->")
            .substringBefore("else -> null")
        assertTrue("QUARANTINED must map to a notice", quarantined.contains("ReportQuarantineReview.QUARANTINED_NOTICE"))
        assertFalse(quarantined.contains("null"))
        assertFalse(quarantined.contains("접수되었습니다"))
        assertTrue(ReportQuarantineReview.QUARANTINED_NOTICE.contains("보관된 신고 확인"))
    }
}
