package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S14-43-F1: a failed resource subscription is retried inside the same Activity at readiness
 * capture, which every start, resume and user retry passes before activation.
 * S14-43-F2: the ACTIVE/PAUSED renewal tick re-reads the resource values.
 */
class MainActivityResourceMonitoringRecoveryStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val probe = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidWalkSessionResourceProbe.kt",
    ).readText()

    @Test
    fun readinessCaptureRetriesAFailedSubscriptionBeforeReadingResources() {
        val capture = functionBlock("private fun captureWalkSessionReadiness(")
        val retry = capture.indexOf("retryFailedWalkSessionResourceMonitoring()")
        val read = capture.indexOf("val deviceResources = walkSessionResourceProbe.snapshot()")
        assertTrue(retry >= 0 && retry < read)
        val helper = functionBlock("private fun retryFailedWalkSessionResourceMonitoring()")
        assertTrue(helper.contains("WalkSessionResourceMonitoringState.SUBSCRIPTION_FAILED"))
        assertTrue(helper.contains("ensureWalkSessionResourceMonitoring()"))
        // A failed start leaves the started flag false, so ensure calls start() again.
        val ensure = functionBlock("private fun ensureWalkSessionResourceMonitoring()")
        assertTrue(ensure.contains("walkSessionResourceProbeStarted =\n            runCatching {"))
        assertTrue(ensure.contains("}.getOrDefault(false)"))
        // start() is re-entrant after a failure: no receiver is kept and the check only rejects a live one.
        val start = probe.substringAfter("fun start(onResourceChanged: () -> Unit): Boolean {")
            .substringBefore("fun snapshot(): WalkSessionDeviceResourceSnapshot {")
        assertTrue(start.contains("check(receiver == null)"))
        assertTrue(start.contains("if (!receiverRegistered) return false"))
        assertTrue(start.contains("receiver = null\n                monitoring.subscriptionFailed("))
        // Start and resume both capture readiness before activation.
        assertTrue(functionBlock("private fun recheckWalkSessionResumeBeforeConfirmation(")
            .contains("captureWalkSessionReadiness("))
    }

    @Test
    fun theWalkRenewalTickPollsResourcesBeforeRenewing() {
        val tick = source.substringAfter("private val gatewayWalkRenewalRunnable = Runnable {")
            .substringBefore("\n    }\n")
        val poll = tick.indexOf("observeWalkRuntimeResourceSafety()")
        val renew = tick.indexOf("requestGatewayWalkRenewal()")
        assertTrue(poll >= 0 && poll < renew)
        val observe = functionBlock("private fun observeWalkRuntimeResourceSafety(")
        assertTrue(observe.contains("val resources = walkSessionResourceProbe.snapshot()"))
        assertTrue(observe.contains("thermalCritical = resources.thermalBelowCritical == false,"))
        val schedule = functionBlock("private fun scheduleGatewayWalkRenewal()")
        assertTrue(schedule.contains("if (snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) return"))
        assertTrue(schedule.contains("GATEWAY_WALK_RENEW_INTERVAL_MS,"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = Regex("""\n    (private |internal |override |@VisibleForTesting|/\*\*|fun )""")
            .find(source, start + signature.length)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
