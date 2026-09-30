package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * login-F1/F4/F9 wiring (owner decision 2, Root decision R1): the running app renews a
 * renewable long-lived login through the durable restore renewal path instead of clearing it,
 * proactively before access expiry, and the walk lease waits for that renewal.
 */
class MainActivityInRunGatewayRenewalStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val buildSource = File("build.gradle.kts").readText()

    @Test
    fun expiredRenewableLoginIsRenewedBeforeAnyClear() {
        val expiry = functionBlock("private fun clearExpiredGatewaySessionIfCurrent(")
        assertInOrder(
            expiry,
            "session.isUsableFor(session.actorId)",
            ") return",
            "if (requestInRunGatewaySessionRenewal(session, GatewayInRunRenewalTrigger.ON_DEMAND)) return",
            "clearGatewaySession(",
        )
        val lookup = functionBlock("private fun gatewaySessionOrNull(")
        assertInOrder(
            lookup,
            "isGatewaySessionReadyForCurrentActor(session)",
            "clearExpiredGatewaySessionIfCurrent(session)",
            "GatewayInRunRenewalProcessState.gate.isInFlight()",
            "gateway=session_renewing",
        )
        // Release keeps the long-lived login on (Root decision R1).
        assertTrue(
            buildSource.contains(
                "buildConfigField(\"boolean\", \"WALKSAFE_LONG_LIVED_LOGIN_ENABLED\", \"true\")",
            ),
        )
    }

    @Test
    fun inRunRenewalUsesTheDurableRestoreRenewalAsASingleFlight() {
        val request = functionBlock("private fun requestInRunGatewaySessionRenewal(")
        assertInOrder(
            request,
            "BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED",
            "process.session !== session",
            "GatewayInRunRenewalPolicy.actionFor(session, trigger, System.currentTimeMillis())",
            "gate.tryBegin(session.lease, SystemClock.elapsedRealtime(), waiter)",
            "GatewayInRunRenewalGate.Admission.JOINED -> return true",
            "ActiveNetworkTransport.OFFLINE",
            "gatewaySessionStore.restoreActive(",
            "restored.version != session.versionOrNull",
            "GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(",
            "renewRestoredGatewaySession(",
            "inRun = GatewayInRunRenewalContext(",
        )
        assertFalse(request.contains("moveActiveToPendingRevocation"))
        assertFalse(request.contains("clearGatewaySession("))

        val renewal = functionBlock("private fun renewRestoredGatewaySession(")
        assertInOrder(
            renewal,
            "gatewaySessionStore.reserveRenewal(",
            "gatewaySessionClient.renew(",
            "gatewaySessionStore.commitRenewal(",
            "GatewaySessionProcessCoordinator.publishVerified(",
            "firstRunSnapshot = inRun?.publishFirstRunSnapshot ?: restored.firstRunSnapshot",
            "GatewayInRunRenewalPolicy.failureAction(error, restored.session.lease)",
            "gatewaySessionStore.rollbackRenewalForRetry(",
            "inRun.sourceSession, inRun.publishFirstRunSnapshot",
            "GatewaySessionProcessCoordinator.deferRenewalAfterRollback(",
            "gatewaySessionStore.abandonRenewal(",
            "} finally {",
            "inRun?.let { completeInRunGatewaySessionRenewal(it, expectedActivityLease) }",
            "catch (_: RejectedExecutionException)",
            "inRun?.let { completeInRunGatewaySessionRenewal(it, expectedActivityLease) }",
        )
        val complete = functionBlock("private fun completeInRunGatewaySessionRenewal(")
        assertInOrder(
            complete,
            "finishInRunGatewaySessionRenewal(",
            "GatewaySessionProcessCoordinator.snapshot().session == null",
            "handleGatewaySessionDowngrade(\"gateway_session_renewal_failed\"",
            "scheduleInRunGatewaySessionRenewal()",
        )
    }

    @Test
    fun proactiveRenewalIsArmedOnEverySessionPublishAndForegroundReturn() {
        val retry = functionBlock("private fun retryPendingGatewayRenewal()")
        assertInOrder(
            retry,
            "scheduleInRunGatewaySessionRenewal()",
            "renewalRetryAtElapsedMs",
        )
        assertTrue(source.contains("onGatewayProcessSessionChanged(snapshot, consentAuthorityChanged)\n            retryPendingGatewayRenewal()"))
        val schedule = functionBlock("private fun scheduleInRunGatewaySessionRenewal()")
        assertInOrder(
            schedule,
            "gatewayWalkRenewalHandler.removeCallbacks(inRunGatewayRenewalRunnable)",
            "GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(",
            "gatewayWalkRenewalHandler.postDelayed(inRunGatewayRenewalRunnable",
        )
        val due = functionBlock("private fun onInRunGatewaySessionRenewalDue()")
        assertInOrder(
            due,
            "isActivityForeground",
            "WalkSessionState.ACTIVE",
            "WalkSessionState.PAUSED",
            "requestInRunGatewaySessionRenewal(session, GatewayInRunRenewalTrigger.PROACTIVE)",
        )
        val destroy = functionBlock("override fun onDestroy()")
        assertTrue(destroy.contains("gatewayWalkRenewalHandler.removeCallbacksAndMessages(null)"))
    }

    @Test
    fun walkLeaseWaitsForTheRenewalInsteadOfStoppingOnAccessExpiry() {
        val renewal = functionBlock("private fun requestGatewayWalkRenewal()")
        assertInOrder(
            renewal,
            "gatewaySessionOrNull(\"walk_renew\", speak = false)",
            "awaitInRunGatewaySessionRenewal { gatewayWalkRenewalHandler.post(gatewayWalkRenewalRunnable) }",
            "handleGatewayWalkAuthorityLost(\"walk_lease_gateway_session_missing\")",
            "if (!isCurrentGatewaySession(gatewaySession)) {",
            "if (!isRenewedGatewaySessionContinuation(gatewaySession)) {",
            "handleGatewayWalkAuthorityLost(\"walk_lease_gateway_session_changed\")",
            "gatewayWalkRenewalHandler.post(gatewayWalkRenewalRunnable)",
            "GatewayWalkRenewalFailurePolicy.classify(outcome.exceptionOrNull())",
            "failure == GatewayWalkRenewalFailure.LOGIN_REQUIRED",
            "probeGatewaySessionAfterUnauthorized(gatewaySession)",
            "GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(",
        )
        val await = functionBlock("private fun awaitInRunGatewaySessionRenewal(")
        assertInOrder(
            await,
            "GatewayInRunRenewalProcessState.gate.joinIfInFlight(waiter)",
            "isGatewaySessionReadyForCurrentActor(gatewayFieldSession)",
        )
        val continuation = functionBlock("private fun isRenewedGatewaySessionContinuation(")
        assertInOrder(
            continuation,
            "GatewayInRunRenewalPolicy.isRenewedContinuation(previous, current)",
            "isCurrentGatewaySession(current)",
        )
    }

    @Test
    fun rejectedLongLivedAccessGetsOneBoundedRefreshBeforeTheClear() {
        val guard = functionBlock("private fun gatewayFailureUiGuardOrNull(")
        assertInOrder(
            guard,
            "if (expectedAuthenticationFailure) {",
            "if (probeGatewaySessionAfterUnauthorized(session)) {",
            "speakInteraction(\"로그인을 확인하고 있습니다. 잠시 후 다시 시도하세요.\")",
            "return null",
            "clearGatewaySession(",
        )
        val capacity = functionBlock("private fun requestGatewayCapacityRefresh(")
        assertInOrder(
            capacity,
            "GatewaySessionRevalidationStatus.NOT_READY",
            "probeGatewaySessionAfterUnauthorized(session)",
            "clearGatewaySession(",
        )
        val probe = functionBlock("private fun probeGatewaySessionAfterUnauthorized(")
        assertInOrder(
            probe,
            "GatewayInRunRenewalTrigger.UNAUTHORIZED",
            "gate.joinIfInFlight(",
            "GatewayInRunRenewalTrigger.ON_DEMAND",
            "unauthorizedProbe.tryAcquire(",
            "requestInRunGatewaySessionRenewal(",
        )
    }

    @Test
    fun reuseNoticeIsNotReadTwiceByTalkBack() {
        val renewal = functionBlock("private fun renewRestoredGatewaySession(")
        val notice = renewal.substringAfter("GatewayRenewalFailureNotice.of(error)?.let { notice ->")
            .substringBefore("} finally {")
        assertTrue(notice.length < renewal.length)
        assertInOrder(
            notice,
            "updateNavigationStatus(notice.statusToken)",
            "accountAccessNotice = notice.speech",
            "updateFirstRunOnboardingUi()",
            "GatewayInRunRenewalPolicy.liveRegionReadsNotice(",
            "isScreenReaderActive()",
            "accountAccessStatusText.isShown",
            "if (!readByLiveRegion) speakInteraction(notice.speech)",
        )
    }

    private fun functionBlock(marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("missing block marker: $marker", start >= 0)
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        throw AssertionError("unterminated block: $marker")
    }

    private fun assertInOrder(section: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = section.indexOf(token, previous + 1)
            assertTrue("missing or out-of-order token: $token", current > previous)
            previous = current
        }
    }
}
