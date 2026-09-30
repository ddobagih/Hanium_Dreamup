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
            .substringBefore("gatewayWalkAuthorityController.completeRenew(")

        assertTrue(failed.contains("GatewayWalkRenewalFailurePolicy.classify(outcome.exceptionOrNull())"))
        assertTrue(failed.contains("safeRemainingMsOrNull(operation.epoch)"))
        assertTrue(failed.contains("gatewayWalkRenewalHandler.postDelayed(gatewayWalkRenewalRunnable, retryDelayMs)"))
        assertTrue(failed.contains("handleGatewayWalkAuthorityLost(failure.reasonCode, GatewayWalkRenewalFailurePolicy.detailKo(failure))"))
        assertFalse(failed.contains("handleGatewayWalkAuthorityLost(\"walk_lease_renew_failed\")"))
        assertTrue(renewal.contains("gatewayWalkRenewalRetries = null"))
        assertTrue(
            renewal.indexOf("if (!isCurrentGatewaySession(gatewaySession)) {") <
                renewal.indexOf("GatewayWalkRenewalFailurePolicy.classify("),
        )
        val lost = block("private fun handleGatewayWalkAuthorityLost(")
        assertTrue(lost.contains("detailKo: String = \"서버의 단일 활성 보행 권한을 확인하지 못했습니다.\""))
        assertTrue(lost.contains("detailKo,"))
    }

    private fun block(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> { depth += 1; opened = true }
                '}' -> { depth -= 1; if (opened && depth == 0) return source.substring(start, index + 1) }
            }
        }
        error("unterminated: $signature")
    }
}
