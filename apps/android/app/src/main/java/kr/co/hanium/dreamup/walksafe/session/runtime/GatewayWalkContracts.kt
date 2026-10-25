package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

internal interface GatewayWalkRuntime {
    fun snapshot(): WalkSessionSnapshot
    fun currentStartReadinessOrNull(token: WalkSessionConfirmationToken): WalkSessionReadinessSnapshot?
    fun blocksAutomaticStart(): Boolean
    fun startRequested(token: WalkSessionConfirmationToken, readiness: WalkSessionReadinessSnapshot)
    fun isActive(): Boolean
    fun activate(): Boolean
    fun isForeground(): Boolean
    fun safetyStop(reason: String)
    fun refreshReadinessUi()
    fun observeResourceSafety()
}

internal interface GatewayWalkIdentity {
    fun sessionOrNull(reason: String, speak: Boolean = true): GatewayFieldSession?
    fun currentSession(): GatewayFieldSession?
    fun isCurrent(session: GatewayFieldSession): Boolean
    fun isRenewedContinuation(session: GatewayFieldSession): Boolean
    fun awaitRenewal(onRenewed: () -> Unit): Boolean
    fun probeAfterUnauthorized(session: GatewayFieldSession, onRenewed: () -> Unit): Boolean
}

internal interface GatewayWalkOutput {
    fun updateStatus(title: String, detail: String)
    fun updateNavigationStatus(detail: String)
    fun makeStartRetryAvailable(detail: String, spoken: String?)
    fun startTakeoverRecognition(operationId: String): Boolean
    fun speakTakeover(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean
    fun debug(tag: String, message: String)
}

internal interface GatewayWalkScheduler {
    fun execute(action: () -> Unit)
    fun runOnUiThread(action: () -> Unit)
    fun runOnUiThread(action: Runnable)
    fun nowMs(): Long
    fun removeCallbacks(runnable: Runnable)
    fun post(runnable: Runnable)
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun postDelayed(action: () -> Unit, delayMs: Long)
}

/** Native HTTP client remains the one existing implementation. The port only exposes its operations. */
internal interface GatewayWalkRequests {
    fun startWithOwnedWalkRecovery(session: GatewayFieldSession, walkId: String, requestId: String,
        nowElapsedMs: () -> Long, isCurrent: () -> Boolean): GatewayWalkStartResult
    fun takeover(session: GatewayFieldSession, walkId: String, requestId: String,
        conflict: GatewayWalkConflict, localNowElapsedMs: Long): GatewayWalkLease
    fun renew(session: GatewayFieldSession, lease: GatewayWalkLease, requestId: String,
        localNowElapsedMs: Long): GatewayWalkLease
    fun end(session: GatewayFieldSession, lease: GatewayWalkLease, requestId: String)
}

internal class GatewayWalkClientRequests(private val client: GatewayWalkSessionClient) : GatewayWalkRequests {
    override fun startWithOwnedWalkRecovery(session: GatewayFieldSession, walkId: String, requestId: String,
        nowElapsedMs: () -> Long, isCurrent: () -> Boolean) =
        client.startWithOwnedWalkRecovery(session, walkId, requestId, nowElapsedMs, isCurrent)
    override fun takeover(session: GatewayFieldSession, walkId: String, requestId: String,
        conflict: GatewayWalkConflict, localNowElapsedMs: Long) =
        client.takeover(session, walkId, requestId, conflict, localNowElapsedMs)
    override fun renew(session: GatewayFieldSession, lease: GatewayWalkLease, requestId: String,
        localNowElapsedMs: Long) = client.renew(session, lease, requestId, localNowElapsedMs)
    override fun end(session: GatewayFieldSession, lease: GatewayWalkLease, requestId: String) {
        client.end(session, lease, requestId)
    }
}
