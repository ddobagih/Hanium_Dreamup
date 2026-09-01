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

        assertTrue(advance.contains("requestGatewayWalkStart(token)"))
        assertFalse(advance.contains("WalkSessionEvent.StartRequested(token)"))
        assertTrue(result.contains("gatewayWalkAuthorityController.completeStart("))
        assertTrue(activation.contains("gatewayWalkAuthorityController.activeLeaseOrNull("))
        assertTrue(
            activation.indexOf("gatewayWalkAuthorityController.activeLeaseOrNull(") <
                activation.indexOf("WalkSessionEvent.StartRequested(token)"),
        )
    }

    @Test
    fun startReadinessIsRecapturedBeforeTheRequestAndAfterTheLeaseResponse() {
        val request = functionBlock("private fun requestGatewayWalkStart(")
        val activation = functionBlock("private fun completeGatewayWalkActivation(")
        val recapture = functionBlock("private fun currentGatewayWalkStartReadinessOrNull(")

        assertTrue(
            request.indexOf("currentGatewayWalkStartReadinessOrNull(token)") <
                request.indexOf("gatewayWalkSessionClient.start("),
        )
        assertTrue(
            activation.indexOf("currentGatewayWalkStartReadinessOrNull(token)") <
                activation.indexOf("WalkSessionEvent.StartRequested(token)"),
        )
        assertTrue(activation.contains("gatewayWalkAuthorityController.beginEnd(token.epoch)"))
        assertTrue(activation.contains("endGatewayWalkBestEffort("))
        assertTrue(recapture.contains("captureWalkSessionReadiness("))
        assertTrue(recapture.contains("it.isReady"))
        assertTrue(recapture.contains("it.plan.mode == token.readiness.plan.mode"))
    }

    @Test
    fun centralSafetyStopEndsTheServerLeaseBestEffort() {
        val safetyStop =
            functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")

        assertTrue(safetyStop.contains("gatewayWalkAuthorityController.beginEnd(it.epoch)"))
        assertTrue(safetyStop.contains("endGatewayWalkBestEffort("))
        assertTrue(
            safetyStop.indexOf("gatewayWalkAuthorityController.beginEnd(it.epoch)") <
                safetyStop.indexOf("WalkSessionEvent.SafetyStopRequested"),
        )
    }

    @Test
    fun conflictRequiresASeparateExactVoiceConfirmation() {
        val prompt = functionBlock("private fun requestGatewayWalkTakeoverConfirmation(")
        val recognition = functionBlock("private fun handleGatewayWalkTakeoverRecognition(")
        val takeover = functionBlock("private fun executeGatewayWalkTakeover(")

        assertTrue(prompt.contains("VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER"))
        assertTrue(prompt.contains("예 또는 아니요"))
        assertTrue(recognition.contains("GatewayWalkTakeoverConfirmation.YES"))
        assertTrue(recognition.contains("GatewayWalkTakeoverConfirmation.NO"))
        assertTrue(takeover.contains("gatewayWalkSessionClient.takeover("))
        assertTrue(gatewayWalkSource.contains(".put(\"confirmation\", \"voice_confirmed\")"))
        assertFalse(prompt.contains("postDelayed("))
    }

    @Test
    fun failedStartOrTakeoverRestoresAnExplicitRetryButton() {
        val request = functionBlock("private fun requestGatewayWalkStart(")
        val result = functionBlock("private fun handleGatewayWalkStartResult(")
        val takeover = functionBlock("private fun executeGatewayWalkTakeover(")
        val cancelled = functionBlock("private fun cancelGatewayWalkTakeoverConfirmation(")
        val retry = functionBlock("private fun makeGatewayWalkStartRetryAvailable(")
        val refresh = functionBlock("private fun refreshStartupCapabilityUi()")

        assertTrue(request.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(result.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(takeover.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(cancelled.contains("makeGatewayWalkStartRetryAvailable("))
        assertTrue(retry.contains("confirmedStartupCapabilityDecision = null"))
        assertTrue(retry.contains("startupCapabilityRetryRequiresUserAction = true"))
        assertTrue(retry.contains("refreshStartupCapabilityUi()"))
        assertTrue(
            refresh.contains(
                "startupCapabilityRetryRequiresUserAction -> \"보행 시작 다시 시도\"",
            ),
        )
    }

    @Test
    fun renewalAndExpiryFailClosedWithoutReusingLoginGeneration() {
        val scheduling = functionBlock("private fun scheduleGatewayWalkRenewal()")
        val renewal = functionBlock("private fun requestGatewayWalkRenewal()")
        val lost = functionBlock("private fun handleGatewayWalkAuthorityLost(")
        val runtime = functionBlock("private fun isWalkSessionRuntimeActive()")

        assertTrue(source.contains("GATEWAY_WALK_RENEW_INTERVAL_MS = 30_000L"))
        assertTrue(scheduling.contains("nextRenewDelayMs("))
        assertTrue(renewal.contains("gatewayWalkSessionClient.renew("))
        assertTrue(renewal.contains("currentOperationStatus(operation)"))
        assertTrue(renewal.contains("walk_lease_expired_during_renew"))
        assertTrue(lost.contains("enterWalkSessionSafetyStopAndCancelOutputs(reason)"))
        assertTrue(runtime.contains("gatewayWalkAuthorityController.activeLeaseOrNull("))
        assertFalse(renewal.contains("gatewaySessionGeneration"))
        assertFalse(renewal.contains("recoveryGeneration"))
    }

    @Test
    fun endIsLocalFirstAndRemoteBestEffortWithoutLeaseRestore() {
        val transition = functionBlock("private fun transitionWalkSession(")
        val end = functionBlock("private fun endGatewayWalkBestEffort(")
        val create = functionBlock("override fun onCreate(savedInstanceState: Bundle?)")
        val destroy = functionBlock("override fun onDestroy()")

        assertTrue(
            transition.indexOf("walkSessionLifecycle.handle(event)") <
                transition.indexOf("endGatewayWalkBestEffort("),
        )
        assertTrue(end.contains("runCatching"))
        assertTrue(create.contains("gatewayWalkAuthorityController.reset()"))
        assertTrue(destroy.contains("gatewayWalkAuthorityController.reset()"))
        assertFalse(source.contains("restoreGatewayWalkLease"))
        assertFalse(readiness.contains("WALK_AUTHORITY"))
        assertFalse(readiness.contains("WALK_LEASE"))
    }

    private fun functionBlock(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("missing source marker: $marker", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("missing function body: $marker", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        throw AssertionError("unterminated function body: $marker")
    }
}
