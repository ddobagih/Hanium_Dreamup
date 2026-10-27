package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
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
    fun localInboxRetainsAccessibleControlsAndWorkerAuthorityRevalidation() {
        assertTrue(main.contains("onLocalQuarantine = { userReportInteractionOwner.refreshLocalQuarantine() }"))
        val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/UserReportPanel.kt").readText()
        assertTrue(panel.contains("style.accessiblePriorityUserButton("))
        assertTrue(panel.contains("onClick = actions.onLocalQuarantineDelete"))
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/interaction/UserReportInteractionOwner.kt").readText()
        val open = MainActivityRefactorSource.member(owner, "refreshLocalQuarantine")
        assertTrue(open.contains("localExecutor.execute"))
        assertTrue(open.contains("generation != localQuarantineGeneration"))
        assertTrue(open.contains("current.localIdentityEpoch != authority.localIdentityEpoch"))
        val deletion = MainActivityRefactorSource.member(owner, "confirmLocalQuarantineDelete")
        assertTrue(deletion.contains("if (!localSelectionIsCurrent(selected))"))
        assertTrue(deletion.contains("currentAdmission = { localSelectionIsCurrent(selected) }"))
        assertTrue(deletion.contains("deleteQuarantinedExact(selected.summary"))
        assertTrue(deletion.contains("서버 접수나 기관 보관본의 삭제 요청은 보내지 않습니다."))
        val close = MainActivityRefactorSource.member(owner, "invalidateLocalQuarantineSelection")
        assertTrue(close.contains("localQuarantineGeneration += 1L"))
        assertTrue(close.contains("localQuarantineSelection = null"))
    }

    @Test
    fun quarantinedEntryContinuesTheDrainAndProducesAVisibleNotice() {
        val drain = MainActivityRefactorSource.function(main, "private fun drainInitialExactReportQueue")
        val stop = drain.substringAfter("val outcome = result.outcome").substringBefore("remaining -= 1")
        assertTrue(stop.contains("outcome != ReportQueueDrainOutcome.QUARANTINED"))
        assertTrue(drain.indexOf("reportQueueDrainResultMessage(result)") < drain.indexOf("val outcome = result.outcome"))

        val message = MainActivityRefactorSource.function(main, "private fun reportQueueDrainResultMessage")
        val quarantined = message.substringAfter("ReportQueueDrainOutcome.QUARANTINED ->")
            .substringBefore("else -> null")
        assertTrue("QUARANTINED must map to a notice", quarantined.contains("기기에 보관했습니다. 다른 신고는 계속 확인합니다."))
        assertFalse(quarantined.contains("null"))
        assertFalse(quarantined.contains("접수되었습니다"))
        assertTrue(ReportQuarantineReview.QUARANTINED_NOTICE.contains("보관된 신고 확인"))
    }
}
