package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-21: the server end is confirmed apart from the local stop and resent with one request id. */
class MainActivityGatewayWalkEndConfirmationStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun endRetriesReuseTheOperationRequestIdForTheOwningSessionOnly() {
        val end = block("private fun endGatewayWalkBestEffort(")

        assertTrue(end.contains("previousRetries: Int = 0,"))
        assertTrue(end.contains("requestId = operation.requestId,"))
        assertFalse(end.contains("UUID.randomUUID()"))
        assertTrue(end.contains("GatewayWalkEndRetryPolicy.retryDelayMsOrNull("))
        assertTrue(end.contains("leaseMayStillBeHeld = lease.isLocallyUsable(SystemClock.elapsedRealtime())"))
        assertTrue(end.contains("sameGatewaySession = isCurrentGatewaySession(gatewaySession)"))
        assertTrue(end.contains("endGatewayWalkBestEffort(gatewaySession, operation, previousRetries + 1)"))
        assertTrue(end.contains("runOnUiThread"))
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
