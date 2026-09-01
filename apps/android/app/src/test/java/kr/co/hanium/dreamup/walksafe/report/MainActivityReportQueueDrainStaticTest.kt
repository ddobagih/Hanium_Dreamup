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
    fun onlyActiveToExplicitRecheckPauseCanStartDrain() {
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
        val builder = functionBlock("private fun buildReportQueueDrainTrigger(")
        val start = functionBlock("private fun startReportQueueDrainAfterTransition(")
        assertTrue(capture.contains("before.state != WalkSessionState.ACTIVE"))
        assertTrue(capture.contains("event != WalkSessionEvent.RecheckRequested"))
        assertFalse(capture.contains("WalkSessionEvent.EndRequested"))
        assertTrue(start.contains("event != WalkSessionEvent.RecheckRequested"))
        assertFalse(start.contains("WalkSessionEvent.EndRequested"))
        assertTrue(start.contains("transition.current.state != WalkSessionState.PAUSED"))
        assertTrue(
            start.contains(
                "transition.current.recoveryStage != WalkSessionRecoveryStage.RECHECK_REQUIRED",
            ),
        )
        assertTrue(capture.contains("activityOriginalUploadAdmission.stationarySnapshot("))
        assertTrue(builder.contains("GatewaySessionScope.GENERAL"))
        assertTrue(builder.contains("currentIntegratedConsentBinding()"))
        assertTrue(builder.contains("approvedReportQueueGatewayOriginOrNull("))
    }

    @Test
    fun triggerPinsConsentAuthorityNetworkAndRecoversPreviousWalkQueueEntries() {
        val builder = functionBlock("private fun buildReportQueueDrainTrigger(")
        val start = functionBlock("private fun startBoundedReportQueueDrain(")
        val context = functionBlock("private fun reportQueueDrainContext(")

        assertTrue(
            start.contains(
                "reportQueueStore.countForRecoveryDrain(trigger.reporterActorId)",
            ),
        )
        assertFalse(start.contains("trigger.consentConfirmation.backendConsentReceiptSha256"))
        assertTrue(start.contains("drainInitialExactReportQueue(trigger, initialCount)"))
        assertTrue(context.contains("currentConfirmation == trigger.consentConfirmation"))
        assertTrue(context.contains("currentGateway.generation == trigger.gatewaySessionGeneration"))
        assertTrue(context.contains("currentGateway.session === trigger.gatewaySession"))
        assertTrue(context.contains("currentTransport == trigger.networkTransport"))
        assertTrue(context.contains("trigger.networkBinding.isSameNetworkBinding(currentBinding)"))
        assertTrue(
            builder.contains(
                "permissionSessionPolicy.snapshot().mobileNetworkPreference",
            ),
        )
        assertTrue(
            context.contains(
                "permissionSessionPolicy.snapshot().mobileNetworkPreference",
            ),
        )
        assertFalse(
            context.contains(
                "trigger.consentConfirmation.selections.mobileNetworkTransfer",
            ),
        )
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
    fun explicitConfirmationQueuesOnlyTheFirstActionFrozenSnapshot() {
        val request = functionBlock("private fun requestExplicitReport(")
        val process = functionBlock("private fun processReportCandidate(")
        val submit = functionBlock("private fun submitFrozenExplicitReportAfterConfirmation(")
        val disclosure = functionBlock("private fun beginExplicitReportConfirmationDisclosure(")
        val timeout = functionBlock("private fun scheduleExplicitReportConfirmationTimeout(")

        assertTrue(request.contains("explicitReportConfirmationPolicy.consumeIfConfirmed("))
        assertTrue(request.contains("explicitReportConfirmationPolicy.isAwaitingDisclosure(context)"))
        assertTrue(request.contains("prepareExplicitReportCandidateIfCurrent("))
        assertTrue(process.contains("ExplicitReportFrozenPayload.freeze("))
        assertTrue(process.contains("explicitReportConfirmationPolicy.stage(frozen, requestedAt)"))
        assertFalse(process.contains("scheduleExplicitReportConfirmationTimeout()"))
        assertTrue(disclosure.contains("renderExplicitReportConfirmationDisclosure(armed = false)"))
        assertTrue(source.contains("requestExplicitReport(ExplicitReportRequestSource.ON_SCREEN)"))
        assertTrue(source.contains("requestExplicitReport(ExplicitReportRequestSource.VOICE)"))
        assertTrue(disclosure.contains("deliverExplicitReportDisclosureAfterDraw(delivered)"))
        val visualDelivery = functionBlock("private fun deliverExplicitReportDisclosureAfterDraw(")
        assertTrue(visualDelivery.contains("OnDrawListener"))
        assertTrue(visualDelivery.contains("explicitReportConfirmationText.post"))
        assertTrue(disclosure.contains("explicitReportConfirmationPolicy.arm("))
        assertTrue(disclosure.contains("speakExplicitConfirmation("))
        assertTrue(disclosure.contains("onCompleted = delivered"))
        assertTrue(disclosure.contains("onFailed = failed"))
        assertTrue(disclosure.contains("renderExplicitReportConfirmationDisclosure(armed = true)"))
        assertTrue(disclosure.contains("scheduleExplicitReportConfirmationTimeout()"))
        assertTrue(source.contains("방금 고정한 신고 보내기"))
        assertTrue(source.contains("기관에는 자동 전송하지 않습니다"))
        assertTrue(submit.contains("confirmed.useExactBytes"))
        assertFalse(submit.contains("latestExplicitReportImage"))
        assertFalse(submit.contains("freshTrustedLocationOrNull"))
        assertTrue(submit.contains("currentExplicitReportConfirmationContextOrNull() != confirmed.context"))
        assertTrue(timeout.contains("explicitReportConfirmationPolicy.invalidateExpired("))
        assertTrue(functionBlock("internal fun pauseWalkSafeRuntime(").contains("invalidatePendingExplicitReport()"))
        assertTrue(
            functionBlock("private fun onGatewayProcessSessionChanged(")
                .contains("invalidatePendingExplicitReport()"),
        )
        assertTrue(
            functionBlock("private fun transitionWalkSession(")
                .contains("transition.previous.epoch != transition.current.epoch"),
        )
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
        assertTrue(transition.contains("transition.current.state != WalkSessionState.PAUSED"))
        assertTrue(transition.contains("transition.current.epoch.walkSessionId != active.walkSessionId"))
        assertTrue(
            functionBlock("private fun ReportQueueDrainContext.allRequiredBaseGatesAllowed(")
                .contains("walkState == WalkSessionState.PAUSED"),
        )
    }

    @Test
    fun lifecycleChangesCancelAndPausedResumeCanSafelyRestartABoundedDrain() {
        assertTrue(functionBlock("override fun onPause()").contains("cancelReportQueueDrain()"))
        assertTrue(functionBlock("override fun onDestroy()").contains("cancelReportQueueDrain()"))
        assertTrue(
            functionBlock("private fun onGatewayProcessSessionChanged(")
                .contains("cancelReportQueueDrain()"),
        )
        val withdrawal = functionBlock("private fun applyImmediateConsentWithdrawals(")
        assertTrue(withdrawal.contains("val previousReceipt"))
        assertTrue(withdrawal.contains("cancelReportQueueDrain()"))
        assertTrue(
            withdrawal.contains(
                "reportQueueDrainCoordinator.onAutomaticReportingRevoked(previousReceipt)",
            ),
        )
        val reportRevocation = withdrawal.substringBefore("items.forEach")
        assertTrue(reportRevocation.contains("IntegratedConsentItem.AUTOMATIC_REPORTING in items"))
        assertFalse(
            reportRevocation.contains(
                "reportQueueDrainCoordinator.onConsentRevoked(previousReceipt)",
            ),
        )
        val deletion = functionBlock("private fun applyAccountDeletionRuntimeFence()")
        assertTrue(deletion.contains("reportQueueDrainCoordinator.onAccountDeleted()"))
        val networkCallback = source.substringAfter("private val gatewayCapacityNetworkCallback =")
            .substringBefore("private var currentDestination")
        assertTrue(networkCallback.contains("override fun onAvailable"))
        assertTrue(networkCallback.contains("override fun onLost"))
        assertTrue(networkCallback.contains("override fun onCapabilitiesChanged"))
        assertTrue(networkCallback.contains("onReportQueueDrainNetworkChanged()"))

        assertTrue(
            functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection(")
                .contains("maybeStartPausedReportQueueRecoveryObservation()"),
        )
        val observe = functionBlock("private fun maybeStartPausedReportQueueRecoveryObservation(")
        assertTrue(observe.contains("walk.state != WalkSessionState.PAUSED"))
        assertTrue(observe.contains("!walk.isForeground"))
        assertTrue(observe.contains("hasActivityRecognitionPermission()"))
        assertTrue(observe.contains("reportQueueRecoveryBaseGatesAvailable()"))
        assertTrue(observe.contains("countForRecoveryDrain(reporter)"))
        val stationary = functionBlock("private fun startPausedReportQueueRecoveryIfCurrent(")
        assertTrue(stationary.contains("buildReportQueueDrainTrigger("))
        assertTrue(stationary.contains("startBoundedReportQueueDrain(trigger)"))
        val bounded = functionBlock("private fun startBoundedReportQueueDrain(")
        assertTrue(bounded.contains("reportQueueDrainTrigger != null"))
        assertTrue(bounded.contains("reportQueueDrainExecutor.execute"))
        val permissionChange = functionBlock("private fun applyObservedPermissionStateChange(")
        assertTrue(permissionChange.contains("if (!hasActivityRecognitionPermission())"))
        assertTrue(permissionChange.contains("cancelReportQueueDrain()"))
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
        assertTrue(
            contract.contains(
                "enabled = BuildConfig.DEBUG && BuildConfig.WALKSAFE_REPORT_QUEUE_ENABLED",
            ),
        )
        assertTrue(contract.contains("BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN"))
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("WALKSAFE_REPORT_QUEUE_TEST_ORIGIN"))
        assertTrue(build.contains("?: \"http://127.0.0.1:8081\""))
        val uploader = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt",
        ).readText()
        assertTrue(
            uploader.contains(
                "approvedReportQueueGatewayOriginOrNull(session.gatewayBaseUrl)",
            ),
        )
        assertTrue(uploader.contains("approvedGatewayOrigin + \"/api/reports/v2\""))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
