package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityReportQueueDrainStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun onlyActiveToRecheckOrEndTransitionCanStartDrain() {
        val transition = functionBlock("private fun transitionWalkSession(")

        assertTrue(transition.contains("captureReportQueueDrainTriggerBeforeTransition(before, event)"))
        assertTrue(transition.contains("startReportQueueDrainAfterTransition("))
        assertTrue(
            transition.indexOf("captureReportQueueDrainTriggerBeforeTransition") <
                transition.indexOf("walkSessionLifecycle.handle(event)"),
        )
        assertTrue(
            transition.indexOf("startReportQueueDrainAfterTransition") >
                transition.indexOf("walkSessionLifecycle.handle(event)"),
        )
        assertEquals(2, source.split("startReportQueueDrainAfterTransition(").size - 1)

        val capture = functionBlock("private fun captureReportQueueDrainTriggerBeforeTransition(")
        assertTrue(capture.contains("before.state != WalkSessionState.ACTIVE"))
        assertTrue(capture.contains("event != WalkSessionEvent.RecheckRequested"))
        assertTrue(capture.contains("event != WalkSessionEvent.EndRequested"))
        assertTrue(capture.contains("activityOriginalUploadAdmission.stationarySnapshot("))
        assertTrue(capture.contains("GatewaySessionScope.GENERAL"))
        assertTrue(capture.contains("currentIntegratedConsentBinding()"))
    }

    @Test
    fun triggerPinsExactConsentAuthorityNetworkAndInitialCount() {
        val start = functionBlock("private fun startReportQueueDrainAfterTransition(")
        val context = functionBlock("private fun reportQueueDrainContext(")

        assertTrue(start.contains("reportQueueStore.countForDrain("))
        assertTrue(start.contains("trigger.walkSessionId"))
        assertTrue(start.contains("trigger.consentConfirmation.backendConsentReceiptSha256"))
        assertTrue(start.contains("drainInitialExactReportQueue(trigger, initialCount)"))
        assertTrue(context.contains("currentConfirmation == trigger.consentConfirmation"))
        assertTrue(context.contains("currentGateway.generation == trigger.gatewaySessionGeneration"))
        assertTrue(context.contains("currentGateway.session === trigger.gatewaySession"))
        assertTrue(context.contains("currentTransport == trigger.networkTransport"))
        assertTrue(context.contains("trigger.networkBinding.isSameNetworkBinding(currentBinding)"))
        assertTrue(context.contains("automaticReportingAllowed ="))
        assertTrue(context.contains("currentConfirmation?.selections?.automaticReporting == true"))
    }

    @Test
    fun drainIsSingleThreadedBoundedAndStopsOnEveryNonDeleteOutcome() {
        assertTrue(source.contains("Executors.newSingleThreadExecutor"))
        val drain = functionBlock("private fun drainInitialExactReportQueue(")

        assertTrue(drain.contains("var remaining = initialCount"))
        assertTrue(drain.contains("while (remaining > 0)"))
        assertTrue(drain.contains("remaining -= 1"))
        assertTrue(drain.contains("ReportQueueDrainOutcome.DELETED_AFTER_STATUS"))
        assertTrue(drain.contains("ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD"))
        assertTrue(drain.contains("break"))
        assertTrue(drain.contains("finishReportQueueDrain(trigger)"))
    }

    @Test
    fun trackerRetainsOnlySensorAndAnyBaselineChangeCancels() {
        val stop = functionBlock("private fun stopStepTracking()")
        val sample = functionBlock("private fun onReportQueueDrainStepSample(")
        val transition = functionBlock("private fun updateReportQueueDrainForTransition(")

        assertTrue(stop.contains("stepTrackingEpoch = null"))
        assertTrue(stop.contains("reportQueueDrainTrigger != null"))
        assertTrue(stop.contains("if (retainedForReportQueueDrain) return"))
        assertTrue(source.contains("onReportQueueDrainStepSample(steps, observedAtMs)"))
        assertTrue(sample.contains("stepCount == trigger.stationarySnapshot.stepCount"))
        assertTrue(sample.contains("observedAtMs >= trigger.stationarySnapshot.observedAtMs"))
        assertTrue(sample.contains("reportQueueDrainCoordinator.cancelActive()"))
        assertTrue(sample.contains("stopStepTrackingNow()"))
        assertTrue(transition.contains("transition.current.state == WalkSessionState.ACTIVE"))
        assertTrue(transition.contains("transition.current.epoch.walkSessionId != active.walkSessionId"))
    }

    @Test
    fun lifecycleConsentAuthorityAndNetworkChangesOnlyCancel() {
        assertTrue(functionBlock("override fun onPause()").contains("cancelReportQueueDrain()"))
        assertTrue(functionBlock("override fun onDestroy()").contains("cancelReportQueueDrain()"))
        assertTrue(
            functionBlock("private fun onGatewayProcessSessionChanged(")
                .contains("cancelReportQueueDrain()"),
        )
        val withdrawal = functionBlock("private fun applyImmediateConsentWithdrawals(")
        assertTrue(withdrawal.contains("val previousReceipt"))
        assertTrue(withdrawal.contains("cancelReportQueueDrain()"))
        assertTrue(withdrawal.contains("reportQueueDrainCoordinator.onConsentRevoked(previousReceipt)"))
        val deletion = functionBlock("private fun applyAccountDeletionRuntimeFence()")
        assertTrue(deletion.contains("reportQueueDrainCoordinator.onAccountDeleted()"))
        val networkCallback = source.substringAfter("private val gatewayCapacityNetworkCallback =")
            .substringBefore("private var currentDestination")
        assertTrue(networkCallback.contains("override fun onAvailable"))
        assertTrue(networkCallback.contains("override fun onLost"))
        assertTrue(networkCallback.contains("override fun onCapabilitiesChanged"))
        assertTrue(networkCallback.contains("onReportQueueDrainNetworkChanged()"))

        assertFalse(functionBlock("override fun onResume()").contains("startReportQueueDrain"))
        assertFalse(
            functionBlock("private fun onGatewayProcessSessionChanged(")
                .contains("startReportQueueDrain"),
        )
        assertFalse(networkCallback.contains("startReportQueueDrain"))
        assertFalse(
            functionBlock("private fun applyIntegratedConsentConfirmation(")
                .contains("startReportQueueDrain"),
        )
    }

    @Test
    fun drainAddsNoRawCollectionTransportAndProductionQueueRequiresApprovedBuildProfile() {
        val drain = functionBlock("private fun drainInitialExactReportQueue(")

        assertTrue(drain.contains("AndroidReportQueueTransport("))
        assertFalse(drain.contains("metadataLogUploader"))
        assertFalse(drain.contains("frameCaptureUploader"))
        assertFalse(drain.contains("rawcollection"))
        assertTrue(source.contains("const val PERSISTENT_REPORT_QUEUE_ENABLED = false"))
        val contract = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportQueueContract.kt",
        ).readText()
        assertTrue(contract.contains("PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE"))
        assertTrue(contract.contains("approvedReportQueueCapacityProfile("))
        assertTrue(contract.contains("BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED"))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
