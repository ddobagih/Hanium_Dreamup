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
        val capture = owner("session/readiness/WalkReadinessCoordinator.kt", "capture")
        val retry = capture.indexOf("resources.retryFailedMonitoring()")
        val read = capture.indexOf("val deviceResources = resources.snapshot()")
        assertTrue(retry >= 0 && retry < read)
        val binding = MainActivityRefactorSource.property(source, "walkReadinessCoordinator")
        assertTrue(binding.contains("WalkReadinessCoordinator("))
        assertTrue(binding.contains("override fun retryFailedMonitoring() { retryFailedWalkSessionResourceMonitoring() }"))
        assertTrue(binding.contains("override fun snapshot() = walkSessionResourceProbe.snapshot()"))
        assertTrue(MainActivityRefactorSource.member(source, "captureWalkSessionReadiness").contains("walkReadinessCoordinator.capture(decision, action, recordResourceDiagnostics, checkEntryPermissions)"))
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
            .contains("readiness.capture("))
    }

    @Test
    fun theWalkRenewalTickPollsResourcesBeforeRenewing() {
        val gateway = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/GatewayWalkSessionController.kt").readText()
        val tick = MainActivityRefactorSource.property(gateway, "gatewayWalkRenewalRunnable")
        val binding = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        assertTrue(binding.contains("override fun observeResourceSafety() { observeWalkRuntimeResourceSafety() }"))
        val poll = tick.indexOf("runtime.observeResourceSafety()")
        val renew = tick.indexOf("requestGatewayWalkRenewal()")
        assertTrue(poll >= 0 && poll < renew)
        val observe = functionBlock("private fun observeWalkRuntimeResourceSafety(")
        assertTrue(observe.contains("resources: WalkSessionDeviceResourceSnapshot? = null"))
        val epochGuard = observe.indexOf("facts.currentRuntimeEpoch()")
        val fallback = observe.indexOf("val currentResources = resources ?: facts.resourceSnapshot()")
        assertTrue(epochGuard >= 0 && fallback > epochGuard)
        val resourceWire = MainActivityRefactorSource.composition(source, "startupResourceSafetyAdmission")
        assertTrue(resourceWire.contains("override fun resourceSnapshot() = walkSessionResourceProbe.snapshot()"))
        assertTrue(resourceWire.contains("override fun currentRuntimeEpoch() = walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertTrue(observe.contains("currentResources.toRuntimeSafetyObservation("))
        val schedule = functionBlock("private fun scheduleGatewayWalkRenewal()")
        assertTrue(schedule.contains("if (snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) return"))
        assertTrue(schedule.contains("renewIntervalMs,"))
        assertTrue(binding.contains("renewIntervalMs = GATEWAY_WALK_RENEW_INTERVAL_MS"))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)

    private fun owner(path: String, name: String): String = MainActivityRefactorSource.member(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), name,
    )
}
