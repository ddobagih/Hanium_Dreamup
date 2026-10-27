package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityFp012StaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val gatewayWalkSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayWalkSession.kt")
            .readText()
    private val readiness =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt")
            .readText()

    @Test
    fun startCannotEnterActiveBeforeTheServerLeaseIsAccepted() {
        val advance = functionBlock("private fun maybeAdvanceWalkSessionAfterCapabilityCheck()")
        val result = functionBlock("private fun handleGatewayWalkStartResult(")
        val activation = functionBlock("private fun completeGatewayWalkActivation(")

        assertTrue(advance.contains("gateway.requestGatewayWalkStart(token)"))
        assertFalse(advance.contains("runtime.startRequested(token, currentReadiness)"))
        assertTrue(result.contains("authority.completeStart("))
        assertTrue(activation.contains("authority.activeLeaseOrNull("))
        assertTrue(
            activation.indexOf("authority.activeLeaseOrNull(") <
                activation.indexOf("runtime.startRequested(token, currentReadiness)"),
        )
        val mainPorts = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        assertTrue(mainPorts.contains("authority = gatewayWalkAuthorityController"))
        assertTrue(mainPorts.contains("requests = GatewayWalkClientRequests(gatewayWalkSessionClient)"))
        assertTrue(mainPorts.contains("override fun currentStartReadinessOrNull(token: WalkSessionConfirmationToken) = currentGatewayWalkStartReadinessOrNull(token)"))
        assertTrue(mainPorts.contains("transitionWalkSession(WalkSessionEvent.StartRequested(token, readiness))"))

    }

    @Test
    fun startReadinessIsRecapturedBeforeTheRequestAndAfterTheLeaseResponse() {
        val request = functionBlock("private fun requestGatewayWalkStart(")
        val activation = functionBlock("private fun completeGatewayWalkActivation(")
        val recapture = functionBlock("private fun currentGatewayWalkStartReadinessOrNull(")

        assertTrue(
            request.indexOf("runtime.currentStartReadinessOrNull(token)") <
                request.indexOf("requests.startWithOwnedWalkRecovery("),
        )
        assertTrue(
            activation.indexOf("runtime.currentStartReadinessOrNull(token)") <
                activation.indexOf("runtime.startRequested(token, currentReadiness)"),
        )
        assertTrue(activation.contains("authority.beginEnd(token.epoch)"))
        assertTrue(activation.contains("endGatewayWalkBestEffort("))
        assertTrue(recapture.contains("captureWalkSessionReadiness("))
        assertTrue(recapture.contains("it.isReady"))
        assertTrue(recapture.contains("it.plan.mode == token.readiness.plan.mode"))
        val mainPorts = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        assertTrue(mainPorts.contains("authority = gatewayWalkAuthorityController"))
        assertTrue(mainPorts.contains("requests = GatewayWalkClientRequests(gatewayWalkSessionClient)"))
        assertTrue(mainPorts.contains("override fun currentStartReadinessOrNull(token: WalkSessionConfirmationToken) = currentGatewayWalkStartReadinessOrNull(token)"))
        assertTrue(mainPorts.contains("transitionWalkSession(WalkSessionEvent.StartRequested(token, readiness))"))

    }

    @Test
    fun centralSafetyStopEndsTheServerLeaseBestEffort() {
        assertTrue(functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
            .contains("performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, null, null)"))
        val safetyStop =
            functionBlock("private fun performWalkSessionSafetyStopCleanup(")

        assertTrue(safetyStop.contains("authority.beginEnd(it.epoch)"))
        assertTrue(safetyStop.contains("endGatewayWalkBestEffort("))
        assertTrue(
            safetyStop.indexOf("authority.beginEnd(it.epoch)") <
                safetyStop.indexOf("WalkSessionEvent.SafetyStopRequested"),
        )
        assertTrue(MainActivityRefactorSource.member(source, "enterWalkSessionSafetyStopAndCancelOutputs").contains("performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, null, null)"))
        MainActivityRefactorSource.composition(source, "walkSessionCleanupController")

    }

    @Test
    fun conflictRequiresASeparateExactVoiceConfirmation() {
        val prompt = functionBlock("private fun requestGatewayWalkTakeoverConfirmation(")
        val recognition = functionBlock("private fun handleGatewayWalkTakeoverRecognition(")
        val takeover = functionBlock("private fun executeGatewayWalkTakeover(")

        assertTrue(prompt.contains("output.startTakeoverRecognition(operation.operationId)"))
        assertTrue(prompt.contains("예 또는 아니요"))
        assertTrue(recognition.contains("GatewayWalkTakeoverConfirmation.YES"))
        assertTrue(recognition.contains("GatewayWalkTakeoverConfirmation.NO"))
        assertTrue(takeover.contains("requests.takeover("))
        assertTrue(gatewayWalkSource.contains(".put(\"confirmation\", \"voice_confirmed\")"))
        assertFalse(prompt.contains("postDelayed("))
        assertTrue(MainActivityRefactorSource.composition(source, "gatewayWalkSessionController").contains("purpose = VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER"))

    }

    @Test
    fun failedStartOrTakeoverRestoresAnExplicitRetryButton() {
        val request = functionBlock("private fun requestGatewayWalkStart(")
        val result = functionBlock("private fun handleGatewayWalkStartResult(")
        val takeover = functionBlock("private fun executeGatewayWalkTakeover(")
        val cancelled = functionBlock("private fun cancelGatewayWalkTakeoverConfirmation(")
        val retryOwner = functionBlock("private fun makeGatewayWalkStartRetryAvailable(")
        assertTrue(retryOwner.contains("output.makeStartRetryAvailable(detail, spoken)"))
        val retry = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        val refresh = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/presentation/StartupCapabilityFactsRenderer.kt").readText(), "renderStartupCapabilityFacts")

        assertTrue(request.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(result.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(takeover.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(cancelled.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(retry.contains("confirmedStartupCapabilityDecision = null"))
        assertTrue(retry.contains("startupCapabilityRetryRequiresUserAction = true"))
        assertTrue(retry.contains("refreshStartupCapabilityUi()"))
        assertTrue(refresh.contains("retryRequiresUserAction = facts.retryRequiresUserAction()"))
        val presenter = ReportStaticSourceInspector.functionBlock(File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText(), "fun startupPresentation")
        assertTrue(presenter.contains("confirmation.retryRequiresUserAction -> \"보행 시작 다시 시도\""))
    }

    @Test
    fun renewalAndExpiryFailClosedWithoutReusingLoginGeneration() {
        val scheduling = functionBlock("private fun scheduleGatewayWalkRenewal()")
        val renewal = functionBlock("private fun requestGatewayWalkRenewal()")
        val lost = functionBlock("private fun handleGatewayWalkAuthorityLost(")
        val runtime = functionBlock("private fun isWalkSessionRuntimeActive()")

        assertTrue(source.contains("GATEWAY_WALK_RENEW_INTERVAL_MS = 30_000L"))
        assertTrue(scheduling.contains("nextRenewDelayMs("))
        assertTrue(renewal.contains("requests.renew("))
        assertTrue(renewal.contains("currentOperationStatus(operation)"))
        assertTrue(renewal.contains("walk_lease_expired_during_renew"))
        assertTrue(lost.contains("runtime.safetyStop(reason)"))
        assertTrue(runtime.contains("gatewayWalkAuthorityController.activeLeaseOrNull("))
        assertFalse(renewal.contains("gatewaySessionGeneration"))
        assertFalse(renewal.contains("recoveryGeneration"))
        val binding = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        assertTrue(binding.contains("override fun safetyStop(reason: String) = enterWalkSessionSafetyStopAndCancelOutputs(reason)"))
        assertTrue(binding.contains("renewIntervalMs = GATEWAY_WALK_RENEW_INTERVAL_MS"))

    }

    @Test
    fun endIsLocalFirstAndRemoteBestEffortWithoutLeaseRestore() {
        val transition = functionBlock("private fun transitionWalkSession(")
        val end = functionBlock("private fun endGatewayWalkBestEffort(")
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val destroy = functionBlock("override fun onDestroy()")

        assertTrue(
            transition.indexOf("lifecycle().handle(event, effects::beforePublication)") <
                transition.indexOf("endGatewayWalkBestEffort("),
        )
        assertTrue(end.contains("runCatching"))
        assertTrue(create.contains("gatewayWalkAuthorityController.reset()"))
        assertTrue(destroy.contains("gatewayWalkAuthorityController.reset()"))
        assertFalse(source.contains("restoreGatewayWalkLease"))
        assertFalse(readiness.contains("WALK_AUTHORITY"))
        assertFalse(readiness.contains("WALK_LEASE"))
        MainActivityRefactorSource.composition(source, "walkSessionTransitionController")
        assertTrue(MainActivityRefactorSource.composition(source, "gatewayWalkSessionController").contains("authority = gatewayWalkAuthorityController"))

    }

    private fun functionBlock(marker: String): String = MainActivityRefactorSource.function(source, marker)

}
