package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import kr.co.hanium.dreamup.walksafe.ReportStaticSourceInspector

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
    fun queuedReportsStartOnlyAfterTheWalkAndNeverFromAPauseTrigger() {
        // Owner decision 3(b) / FP-032-DETAIL-02: no drain starts in ACTIVE or PAUSED.
        val transition = functionBlock("private fun transitionWalkSession(")
        assertFalse(source.contains("captureReportQueueDrainTriggerBeforeTransition"))
        assertFalse(source.contains("startReportQueueDrainAfterTransition"))
        assertFalse(source.contains("maybeStartPausedReportQueueRecoveryObservation"))
        assertFalse(source.contains("schedulePausedReportQueueRecoveryStationaryCheck"))
        assertTrue(transition.contains("transition.current.state.allowsPostWalkTransfer"))
        assertTrue(
            transition.indexOf("reports.maybeStartPostWalkQueueDrain()") >
                transition.indexOf("lifecycle().handle(event, effects::beforePublication)"),
        )
        val postWalk = functionBlock("private fun maybeStartPostWalkReportQueueDrain(")
        assertTrue(postWalk.contains("!walk.state.allowsPostWalkTransfer"))
        assertTrue(postWalk.contains("reportQueueRecoveryBaseGatesAvailable()"))
        assertTrue(postWalk.contains("countForRecoveryDrain(reporter)"))
        assertTrue(postWalk.contains("buildReportQueueDrainTrigger("))
        assertTrue(postWalk.contains("startBoundedReportQueueDrain(trigger)"))
        // Reports: no charging condition and no step window after the walk (owner confirmation).
        assertFalse(postWalk.contains("charging"))
        assertFalse(postWalk.contains("hasActivityRecognitionPermission()"))
        val window = functionBlock("private fun schedulePostWalkTransfers(")
        assertTrue(window.contains("ports.maybeStartReportQueueDrain()"))
        val builder = functionBlock("private fun buildReportQueueDrainTrigger(")
        assertTrue(builder.contains("GatewaySessionScope.GENERAL"))
        assertTrue(builder.contains("network.currentBinding()"))
        assertTrue(builder.contains("approvedOrigin(gatewaySession.gatewayBaseUrl)"))
        assertTrue(builder.contains("AndroidNetworkTransferPolicy.isAllowed(networkPreference, networkTransport)"))
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
        assertTrue(drainSource.contains("Executors.newSingleThreadExecutor"))
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
        val preparation = functionBlock("private fun prepareExplicitReportCandidateIfCurrent(")
        val submit = functionBlock("private fun submitFrozenExplicitReportAfterConfirmation(")
        val disclosure = functionBlock("private fun beginExplicitReportConfirmationDisclosure(")
        val timeout = functionBlock("private fun scheduleExplicitReportConfirmationTimeout(")

        assertTrue(request.contains("explicitReportConfirmationPolicy.consumeIfConfirmed("))
        assertTrue(request.contains("explicitReportConfirmationPolicy.isAwaitingDisclosure(context)"))
        assertTrue(request.contains("prepareExplicitReportCandidateIfCurrent("))
        assertTrue(process.contains("ExplicitReportFrozenPayload.freeze("))
        assertTrue(process.contains("explicitReportConfirmationPolicy.stage(frozen, requestedAt)"))
        assertFalse(process.contains("scheduleExplicitReportConfirmationTimeout()"))
        assertTrue(disclosure.contains("ui.renderDisclosure(armed = false)"))
        assertTrue(source.contains("requestExplicitReport(ExplicitReportRequestSource.ON_SCREEN)"))
        assertTrue(source.contains("requestExplicitReport(ExplicitReportRequestSource.VOICE)"))
        assertTrue(
            candidateSource.contains(
                "ON_SCREEN(AndroidReportCandidatePolicy.TRIGGER_ON_SCREEN)",
            ),
        )
        assertTrue(candidateSource.contains("VOICE(AndroidReportCandidatePolicy.TRIGGER_VOICE)"))
        assertTrue(request.contains("source = source"))
        assertTrue(preparation.contains("source: ExplicitReportRequestSource"))
        assertTrue(preparation.contains("trigger = source.reportTrigger"))
        assertFalse(preparation.contains("trigger = \"voice\""))
        assertTrue(disclosure.contains("ui.deliverDisclosureAfterDraw(delivered)"))
        val visualDelivery = functionBlock("private fun deliverExplicitReportDisclosureAfterDraw(")
        assertTrue(presentationSource.contains("ViewTreeObserver.OnDrawListener"))
        assertTrue(visualDelivery.contains("target.post"))
        assertTrue(disclosure.contains("explicitReportConfirmationPolicy.arm("))
        assertTrue(disclosure.contains("ui.speakDisclosure("))
        assertTrue(disclosure.contains("onCompleted = delivered"))
        assertTrue(disclosure.contains("onFailed = failed"))
        assertTrue(disclosure.contains("ui.renderDisclosure(armed = true)"))
        assertTrue(disclosure.contains("scheduleExplicitReportConfirmationTimeout()"))
        assertTrue(presentationSource.contains("방금 고정한 신고 보내기"))
        assertTrue(REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO.contains("기관으로 자동 전송하지 않습니다"))
        assertTrue(submit.contains("confirmed.useExactBytes"))
        assertFalse(submit.contains("latestExplicitReportImage"))
        assertFalse(submit.contains("freshTrustedLocationOrNull"))
        assertTrue(submit.contains("currentExplicitReportConfirmationContextOrNull() != confirmed.context"))
        assertTrue(timeout.contains("explicitReportConfirmationPolicy.invalidateExpired("))
        assertTrue(functionBlock("internal fun pauseWalkSafeRuntime(").contains("invalidatePendingExplicitReport()"))
        assertTrue(
            actorSessionChange(source)
                .contains("reports.invalidatePendingExplicit()"),
        )
        assertTrue(
            functionBlock("private fun transitionWalkSession(")
                .contains("transition.previous.epoch != transition.current.epoch"),
        )
    }

    @Test
    fun aWalkThatBecomesActiveOrPausedAgainCancelsTheDrain() {
        val stop = functionBlock("private fun stopStepTracking()")
        val transition = functionBlock("private fun updateReportQueueDrainForTransition(")

        assertTrue(stop.contains("stepTrackingEpoch = null"))
        assertTrue(stop.contains("reportQueueDrainController.hasActiveDrain()"))
        assertTrue(stop.contains("if (retainedForReportQueueDrain) return"))
        assertFalse(source.contains("onReportQueueDrainStepSample"))
        assertTrue(transition.contains("!transition.current.state.allowsPostWalkTransfer"))
        assertTrue(transition.contains("transition.current.epoch.walkSessionId != active.walkSessionId"))
        assertTrue(transition.contains("requestReportQueueCancellation()"))
        val cancel = MainActivityRefactorSource.member(drainSource, "requestReportQueueCancellation")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(cancel,
            "reportQueueDrainCoordinator.fenceActiveAdmission()",
            "reportQueueDrainCancellationExecutor.execute { call.cancel() }"))
        assertTrue(
            functionBlock("private fun ReportQueueDrainContext.allRequiredBaseGatesAllowed(")
                .contains("walkState.allowsPostWalkTransfer"),
        )
        assertTrue(
            functionBlock("private fun startBoundedReportQueueDrain(")
                .contains("reportQueueDrainWalkState?.allowsPostWalkTransfer != true"),
        )
    }

    @Test
    fun lifecycleChangesCancelAndAfterTheWalkRestartABoundedDrain() {
        assertTrue(functionBlock("override fun onPause()").contains("cancelReportQueueDrain()"))
        assertTrue(functionBlock("override fun onDestroy()").contains("cancelReportQueueDrain()"))
        assertTrue(
            actorSessionChange(source)
                .contains("reports.cancelQueueDrain()"),
        )
        val withdrawal = functionBlock("private fun applyImmediateConsentWithdrawals(")
        assertTrue(withdrawal.contains("val previousReceipt"))
        assertTrue(withdrawal.contains("reports.cancelDrain()"))
        assertTrue(
            withdrawal.contains(
                "recordAutomaticCleanup(previousReceipt, integratedConsentConfirmedActorSha256)",
            ),
        )
        val consentOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
        val cleanup = MainActivityRefactorSource.member(consentOwner, "recordAutomaticCleanup")
        assertTrue(cleanup.contains("saveAutomaticCleanup(pending + AutomaticCleanup(ownerSha256, receipt))"))
        assertTrue(cleanup.contains("integratedConsentSession.failClosed()"))
        assertTrue(cleanup.contains("retryPendingAutomaticReportCleanup()"))
        val retryCleanup = MainActivityRefactorSource.member(consentOwner, "retryPendingAutomaticReportCleanup")
        assertTrue(retryCleanup.contains("current.backendAccountGeneration == session.backendAccountGeneration"))
        assertTrue(retryCleanup.contains("reports.automaticRevoked(record.receipt, session.actorId, session.backendAccountGeneration)"))
        val reportRevocation = withdrawal.substringBefore("items.forEach")
        assertTrue(reportRevocation.contains("IntegratedConsentItem.AUTOMATIC_REPORTING in items"))
        assertFalse(
            reportRevocation.contains(
                "reportQueueDrainCoordinator.onConsentRevoked(previousReceipt)",
            ),
        )
        val deletion = functionBlock("private fun applyAccountDeletionRuntimeFence()")
        assertTrue(deletion.contains("reports.cancelDrainAndFenceQueue()"))
        val networkCallback = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/AndroidGatewayCapacityObserver.kt").readText()
        assertTrue(networkCallback.contains("override fun onAvailable"))
        assertTrue(networkCallback.contains("override fun onLost"))
        assertTrue(networkCallback.contains("override fun onCapabilitiesChanged"))
        val gatewayOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/GatewaySessionController.kt").readText()
        assertTrue(gatewayOwner.contains("networkEffects.reportDrainNetworkChanged()"))
        assertTrue(MainActivityRefactorSource.composition(source, "gatewaySessionController").contains("override fun reportDrainNetworkChanged() = onReportQueueDrainNetworkChanged()"))

        // Owner decision 3(b): app start, foreground return and network changes open the post-walk window.
        assertTrue(
            functionBlock("private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection(")
                .contains("schedulePostWalkTransfers()"),
        )
        assertTrue(
            functionBlock("private fun onReportQueueDrainNetworkChanged(")
                .contains("schedulePostWalkTransfers()"),
        )
        val bounded = functionBlock("private fun startBoundedReportQueueDrain(")
        assertTrue(bounded.contains("reportQueueDrainTrigger != null"))
        assertTrue(bounded.contains("reportQueueDrainExecutor.execute"))
        val permissionChange = functionBlock("private fun applyObservedPermissionStateChange(")
        assertTrue(permissionChange.contains("if (!platform.activityRecognitionGranted())"))
        assertTrue(permissionChange.contains("runtime.cancelReportDrain()"))
    }

    @Test
    fun drainAddsNoRawCollectionTransportAndProductionQueueRequiresApprovedBuildProfile() {
        val drain = functionBlock("private fun drainInitialExactReportQueue(")

        assertTrue(drain.contains("transportFactory(trigger)"))
        val wiring = MainActivityRefactorSource.composition(source, "reportQueueDrainController")
        assertTrue(wiring.contains("transportFactory = { trigger -> AndroidReportQueueTransport("))
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

    private fun functionBlock(signature: String): String = if (signature.contains("ReportQueueDrainContext.")) {
        MainActivityRefactorSource.composition(source, "reportQueueDrainController")
        MainActivityRefactorSource.member(drainSource, "ReportQueueDrainContext.allRequiredBaseGatesAllowed")
    } else MainActivityRefactorSource.function(source, signature)
    private val drainSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportQueueDrainController.kt").readText()
    private val candidateSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/ReportCandidateController.kt").readText()
    private val presentationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/AndroidExplicitReportPresentation.kt").readText()

    private fun actorSessionChange(mainSource: String): String {
        MainActivityRefactorSource.composition(mainSource, "actorSessionLifecycleCoordinator")
        val main = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
private fun onGatewayProcessSessionChanged(
        snapshot: GatewaySessionProcessSnapshot,
        consentAuthorityChanged: Boolean,
    ) {
        actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)
        refreshGuidanceDiagnosticAuthority()
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main gateway callback must invoke the owner before diagnostic refresh", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val call = main.indexOf("actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)")
        org.junit.Assert.assertTrue(call >= 0)
        org.junit.Assert.assertTrue(main.indexOf("refreshGuidanceDiagnosticAuthority()") > call)
        return MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/ActorSessionLifecycleCoordinator.kt",
        ).readText(), "onGatewayProcessSessionChanged")
    }


    @Test
    fun actorSessionChangeRejectsNonExecutingOrReorderedCallers() {
        val mainSource = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        actorSessionChange(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "onGatewayProcessSessionChanged")
        val call = "actorSessionLifecycleCoordinator.onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)"
        org.junit.Assert.assertTrue(caller.contains(call))
        val variants = linkedMapOf(
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )

        variants["reordered effects"] = caller.replace(call, "").replace(
            "refreshGuidanceDiagnosticAuthority()", "refreshGuidanceDiagnosticAuthority()\n" + call,
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { actorSessionChange(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }

}
