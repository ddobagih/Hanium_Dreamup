package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-22: late start/takeover grants for a stale operation are ended under the same session.
 * S03-25: a renewal answered for a replaced or signed-out gateway session cannot extend the walk.
 */
class MainActivityGatewayWalkLeaseOwnershipStaticTest {
    private val source = listOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first(File::isFile).readText()

    @Test
    fun staleOrFailedStartReleasesOnlyTheLeaseThisOperationObtained() {
        val request = functionBlock("private fun requestGatewayWalkStart(")
        val stale = request.substringAfter(
            "if (!isGatewayWalkOperationCurrent(operation, token, gatewaySession)) {",
        ).substringBefore("return@runOnUiThread")
        assertTrue(stale.contains("authority.cancel(operation)"))
        assertTrue(stale.contains("endAbandonedGatewayWalkStartLeaseBestEffort("))
        assertTrue(stale.contains("lease = abandonedGatewayWalkStartLeaseOrNull(outcome)"))
        val failure = request.substringAfter("onFailure = {").substringBefore("makeGatewayWalkStartRetryAvailable(")
        assertTrue(failure.contains("endAbandonedGatewayWalkStartLeaseBestEffort("))

        val abandoned = functionBlock("fun abandonedGatewayWalkStartLeaseOrNull(")
        assertTrue(abandoned.contains("GatewayWalkStartResult.Granted"))
        assertTrue(abandoned.contains("GatewayWalkHttpException)?.abandonedLease"))
    }

    @Test
    fun staleTakeoverGrantIsEndedInsteadOfLeftToExpire() {
        val takeover = functionBlock("private fun executeGatewayWalkTakeover(")
        val stale = takeover.substringAfter(
            "if (!isGatewayWalkOperationCurrent(operation, token, gatewaySession)) {",
        ).substringBefore("return@runOnUiThread")
        assertTrue(stale.contains("clearConflict = true"))
        assertTrue(stale.contains("endAbandonedGatewayWalkStartLeaseBestEffort("))
        assertTrue(stale.contains("lease = outcome.getOrNull()"))
    }

    @Test
    fun abandonedLeaseReleaseIsBoundToTheOperationWalkAndSession() {
        val release = functionBlock("private fun endAbandonedGatewayWalkStartLeaseBestEffort(")
        assertTrue(release.contains("if (lease == null || lease.walkId != operation.epoch.walkSessionId) return"))
        assertTrue(release.contains("scheduler.execute"))
        assertTrue(release.contains("requests.end("))
        assertTrue(release.contains("session = gatewaySession"))
        assertTrue(release.contains("lease = lease"))
        assertFalse(release.contains("completeStart("))
        assertFalse(release.contains("completeTakeover("))
    }

    @Test
    fun renewalCallbackRequiresTheSameGatewaySessionBeforeApplyingTheLease() {
        val renewal = functionBlock("private fun requestGatewayWalkRenewal()")
        val sessionCheck = renewal.indexOf("if (!identity.isCurrent(gatewaySession)) {")
        assertTrue(sessionCheck > renewal.indexOf("runOnUiThread {"))
        assertTrue(sessionCheck < renewal.indexOf("currentOperationStatus(operation)"))
        assertTrue(sessionCheck < renewal.indexOf("completeRenew("))
        val rejected = renewal.substring(sessionCheck).substringBefore("return@runOnUiThread")
        assertTrue(rejected.contains("authority.cancel(operation)"))
        assertTrue(rejected.contains("handleGatewayWalkAuthorityLost(\"walk_lease_gateway_session_changed\")"))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
