package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-23: the renewal callback classifies the failure before choosing stop or bounded retry. */
class MainActivityGatewayWalkRenewalRetryStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun renewalFailureIsClassifiedAndRetriedOnlyInsideTheHeldLease() {
        val renewal = block("private fun requestGatewayWalkRenewal()")
        val failed = renewal.substringAfter("if (renewed == null) {")
            .substringBefore("authority.completeRenew(")

        assertTrue(failed.contains("GatewayWalkRenewalFailurePolicy.classify(outcome.exceptionOrNull())"))
        assertTrue(failed.contains("safeRemainingMsOrNull(operation.epoch)"))
        assertTrue(failed.contains("scheduler.postDelayed(gatewayWalkRenewalRunnable, retryDelayMs)"))
        assertTrue(failed.contains("handleGatewayWalkAuthorityLost(failure.reasonCode, GatewayWalkRenewalFailurePolicy.detailKo(failure))"))
        assertFalse(failed.contains("handleGatewayWalkAuthorityLost(\"walk_lease_renew_failed\")"))
        assertTrue(renewal.contains("gatewayWalkRenewalRetries = null"))
        assertTrue(
            renewal.indexOf("if (!identity.isCurrent(gatewaySession)) {") <
                renewal.indexOf("GatewayWalkRenewalFailurePolicy.classify("),
        )
        val lost = block("private fun handleGatewayWalkAuthorityLost(")
        assertTrue(lost.contains("detailKo: String = \"서버의 단일 활성 보행 권한을 확인하지 못했습니다.\""))
        assertTrue(lost.contains("output.updateStatus("))
        assertTrue(lost.contains("\"\$detailKo\\n\\n\$restartNoticeKo\""))
        val wiring = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        assertTrue(wiring.contains("restartNoticeKo = WALK_RESTART_NOTICE_KO"))
        assertTrue(wiring.contains("override fun updateStatus(title: String, detail: String) = this@MainActivity.updateStatus(title, detail)"))
    }

    private fun block(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
