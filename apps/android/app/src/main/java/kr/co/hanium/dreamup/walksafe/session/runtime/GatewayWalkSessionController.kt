package kr.co.hanium.dreamup.walksafe.session.runtime

import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*

/** Owns pending walk operations, takeover delivery, renewal cadence and bounded end retries.
 * The supplied authority is the exact process-retained authority; construction performs no queries.
 */
internal class GatewayWalkSessionController(
    private val authority: GatewayWalkAuthorityController,
    private val requests: GatewayWalkRequests,
    private val runtime: GatewayWalkRuntime,
    private val identity: GatewayWalkIdentity,
    private val output: GatewayWalkOutput,
    private val scheduler: GatewayWalkScheduler,
    private val renewIntervalMs: Long,
    private val restartNoticeKo: String,
    private val debug: Boolean,
) {
    private var gatewayWalkTakeoverPromptPending = false
    private var gatewayWalkTakeoverPromptOperationId: String? = null
    private var gatewayWalkStartConfirmationToken: WalkSessionConfirmationToken? = null
    private var gatewayWalkStartOperationId: String? = null
    private var gatewayWalkResumeRevalidationPending = false
    private var gatewayWalkRenewalRetries: Pair<String, Int>? = null
    private val gatewayWalkRenewalRunnable = Runnable {
        // Preserve resource observation before renewal on every scheduled tick.
        runtime.observeResourceSafety()
        requestGatewayWalkRenewal()
    }

    val takeoverPromptPending get() = gatewayWalkTakeoverPromptPending
    val takeoverPromptOperationId get() = gatewayWalkTakeoverPromptOperationId
    val startConfirmationToken get() = gatewayWalkStartConfirmationToken
    val resumeRevalidationPending get() = gatewayWalkResumeRevalidationPending

    fun clearResumeRevalidation() { gatewayWalkResumeRevalidationPending = false }
    fun cancelScheduledRenewal() { scheduler.removeCallbacks(gatewayWalkRenewalRunnable) }
    fun clearDetachedRequestState() {
        clearStartOwnership()
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        gatewayWalkResumeRevalidationPending = false
    }

    fun requestGatewayWalkStart(token: WalkSessionConfirmationToken) {
        val snapshot = runtime.snapshot()
        if (
            snapshot.state != WalkSessionState.READY ||
            snapshot.epoch != token.epoch ||
            snapshot.confirmationToken != token
        ) return
        if (runtime.currentStartReadinessOrNull(token) == null) {
            makeGatewayWalkStartRetryAvailable(
                detail =
                    "환경·장착·기기 상태가 바뀌었거나 측정값이 만료되었습니다. " +
                        "실제 상태를 다시 점검하세요.",
            )
            return
        }
        val gatewaySession = identity.sessionOrNull("walk_start") ?: run {
            makeGatewayWalkStartRetryAvailable(
                detail = "로그인 연결을 확인한 뒤 보행 시작을 다시 시도하세요.",
            )
            return
        }
        val operation = authority.beginStart(snapshot.epoch) ?: run {
            makeGatewayWalkStartRetryAvailable(
                detail = "보행 시작 요청을 준비하지 못했습니다. 다시 시도하세요.",
            )
            return
        }
        gatewayWalkStartConfirmationToken = token
        gatewayWalkStartOperationId = operation.operationId
        output.updateStatus("보행 권한 확인", "계정의 활성 보행 권한을 확인하고 있습니다.")
        try {
            scheduler.execute {
                val outcome = runCatching {
                    requests.startWithOwnedWalkRecovery(
                        session = gatewaySession,
                        walkId = operation.epoch.walkSessionId,
                        requestId = operation.requestId,
                        nowElapsedMs = { scheduler.nowMs() },
                        isCurrent = {
                            val check = java.util.concurrent.FutureTask {
                                isGatewayWalkOperationCurrent(operation, token, gatewaySession)
                            }
                            scheduler.runOnUiThread(check)
                            runCatching {
                                check.get(2, java.util.concurrent.TimeUnit.SECONDS)
                            }.getOrDefault(false)
                        },
                    )
                }
                scheduler.runOnUiThread {
                    if (!isGatewayWalkOperationCurrent(operation, token, gatewaySession)) {
                        authority.cancel(operation)
                        endAbandonedGatewayWalkStartLeaseBestEffort(
                            gatewaySession = gatewaySession,
                            operation = operation,
                            lease = abandonedGatewayWalkStartLeaseOrNull(outcome),
                        )
                        retireStartIfOwned(operation, token)
                        return@runOnUiThread
                    }
                    outcome.fold(
                        onSuccess = { result ->
                            handleGatewayWalkStartResult(
                                operation = operation,
                                token = token,
                                gatewaySession = gatewaySession,
                                result = result,
                            )
                        },
                        onFailure = {
                            authority.cancel(operation)
                            endAbandonedGatewayWalkStartLeaseBestEffort(
                                gatewaySession = gatewaySession,
                                operation = operation,
                                lease = abandonedGatewayWalkStartLeaseOrNull(outcome),
                            )
                            makeGatewayWalkStartRetryAvailable(
                                detail =
                                    "서버 보행 권한을 확인하지 못해 시작하지 않았습니다. " +
                                        "연결을 확인한 뒤 다시 시도하세요.",
                                spoken =
                                    "서버 보행 권한을 확인하지 못해 보행 안내를 시작하지 않았습니다.",
                            )
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            authority.cancel(operation)
            makeGatewayWalkStartRetryAvailable(
                detail = "보행 권한 확인 작업을 시작하지 못했습니다. 다시 시도하세요.",
            )
        }
    }

    private fun handleGatewayWalkStartResult(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
        gatewaySession: GatewayFieldSession,
        result: GatewayWalkStartResult,
    ) {
        when (authority.completeStart(operation, result)) {
            GatewayWalkAuthorityCompletion.LEASE_ACTIVE ->
                completeGatewayWalkActivation(token)
            GatewayWalkAuthorityCompletion.CONFLICT -> {
                val conflict =
                    authority.currentConflictOrNull(operation.epoch)
                        ?: run {
                            makeGatewayWalkStartRetryAvailable(
                                detail = "다른 기기의 보행 상태를 확인하지 못했습니다. 다시 시도하세요.",
                            )
                            return
                        }
                requestGatewayWalkTakeoverConfirmation(
                    operation = operation,
                    token = token,
                    gatewaySession = gatewaySession,
                    activeDeviceId = conflict.activeDeviceId,
                )
            }
            GatewayWalkAuthorityCompletion.STALE,
            GatewayWalkAuthorityCompletion.INVALID,
            -> {
                makeGatewayWalkStartRetryAvailable(
                    detail = "보행 권한 응답이 현재 시작 요청과 일치하지 않습니다. 다시 시도하세요.",
                )
            }
        }
    }

    private fun makeGatewayWalkStartRetryAvailable(
        detail: String,
        spoken: String? = null,
    ) {
        clearStartOwnership()
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        output.makeStartRetryAvailable(detail, spoken)
    }

    private fun requestGatewayWalkTakeoverConfirmation(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
        gatewaySession: GatewayFieldSession,
        activeDeviceId: String,
    ) {
        if (
            !identity.isCurrent(gatewaySession) ||
            runtime.snapshot().epoch != operation.epoch ||
            runtime.snapshot().confirmationToken != token
        ) {
            authority.clearConflict(operation.epoch)
            makeGatewayWalkStartRetryAvailable(
                detail = "로그인 또는 보행 상태가 바뀌었습니다. 다시 시도하세요.",
            )
            return
        }
        gatewayWalkTakeoverPromptPending = true
        gatewayWalkTakeoverPromptOperationId = operation.operationId
        val sameDevice = activeDeviceId == gatewaySession.deviceId
        val conflictTitle = if (sameDevice) "이전 로그인에서 보행 중" else "다른 기기에서 보행 중"
        val prompt = "${conflictTitle}입니다. 이전 보행을 종료하고 이 기기에서 시작할까요? 예 또는 아니요라고 말해 주세요."
        val delivered = {
            scheduler.runOnUiThread {
                if (!gatewayWalkTakeoverPromptPending ||
                    gatewayWalkTakeoverPromptOperationId != operation.operationId
                ) return@runOnUiThread
                if (authority.currentConflictOrNull(operation.epoch) == null) {
                    cancelGatewayWalkTakeoverConfirmation(
                        operation.epoch,
                        "takeover_conflict_stale",
                        detail = "보행 상태가 바뀌어 다시 확인이 필요합니다. 안내 시작을 다시 시도해 주세요.",
                    )
                    return@runOnUiThread
                }
                if (!output.startTakeoverRecognition(operation.operationId)) {
                    cancelGatewayWalkTakeoverConfirmation(
                        operation.epoch,
                        "takeover_voice_not_started",
                    )
                }
            }
        }
        val failed = {
            scheduler.runOnUiThread {
                if (!gatewayWalkTakeoverPromptPending ||
                    gatewayWalkTakeoverPromptOperationId != operation.operationId
                ) return@runOnUiThread
                cancelGatewayWalkTakeoverConfirmation(
                    operation.epoch,
                    "takeover_prompt_not_delivered",
                )
            }
        }
        output.updateStatus(
            conflictTitle,
            "이전 보행 종료 여부를 음성으로 확인합니다.",
        )
        val accepted = output.speakTakeover(prompt, delivered, failed)
        if (!accepted) failed()
    }

    fun handleGatewayWalkTakeoverRecognition(phrases: List<String>) {
        val token = gatewayWalkStartConfirmationToken ?: return
        val epoch = token.epoch
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        when (
            GatewayWalkTakeoverConfirmation.fromRecognizedText(
                phrases.firstOrNull { it.isNotBlank() },
            )
        ) {
            GatewayWalkTakeoverConfirmation.YES -> {
                val operation =
                    authority.beginTakeover(epoch)
                        ?: run {
                            cancelGatewayWalkTakeoverConfirmation(
                                epoch,
                                "takeover_conflict_stale",
                            )
                            return
                        }
                executeGatewayWalkTakeover(operation, token)
            }
            GatewayWalkTakeoverConfirmation.NO -> {
                authority.clearConflict(epoch)
                makeGatewayWalkStartRetryAvailable(
                    detail = "이전 기기의 보행을 유지합니다. 원할 때 다시 시도할 수 있습니다.",
                    spoken =
                        "이전 기기의 보행을 유지합니다. 이 기기에서는 시작하지 않았습니다.",
                )
            }
            GatewayWalkTakeoverConfirmation.NO_RESPONSE,
            GatewayWalkTakeoverConfirmation.UNRECOGNIZED,
            -> cancelGatewayWalkTakeoverConfirmation(
                epoch,
                "takeover_voice_unconfirmed",
            )
        }
    }

    private fun executeGatewayWalkTakeover(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
    ) {
        val gatewaySession = identity.sessionOrNull("walk_takeover") ?: run {
            authority.cancel(operation, clearConflict = true)
            makeGatewayWalkStartRetryAvailable(
                detail = "로그인 연결을 확인한 뒤 보행 시작을 다시 시도하세요.",
            )
            return
        }
        val conflict = operation.sourceConflict ?: run {
            authority.cancel(operation, clearConflict = true)
            makeGatewayWalkStartRetryAvailable(
                detail = "이전 기기의 보행 상태가 만료되었습니다. 다시 시도하세요.",
            )
            return
        }
        gatewayWalkStartOperationId = operation.operationId
        output.updateStatus("보행 권한 이전", "이전 보행 종료와 새 권한 발급을 요청하고 있습니다.")
        try {
            scheduler.execute {
                val outcome = runCatching {
                    requests.takeover(
                        session = gatewaySession,
                        walkId = operation.epoch.walkSessionId,
                        requestId = operation.requestId,
                        conflict = conflict,
                        localNowElapsedMs = scheduler.nowMs(),
                    )
                }
                scheduler.runOnUiThread {
                    if (!isGatewayWalkOperationCurrent(operation, token, gatewaySession)) {
                        authority.cancel(
                            operation,
                            clearConflict = true,
                        )
                        endAbandonedGatewayWalkStartLeaseBestEffort(
                            gatewaySession = gatewaySession,
                            operation = operation,
                            lease = outcome.getOrNull(),
                        )
                        retireStartIfOwned(operation, token)
                        return@runOnUiThread
                    }
                    outcome.fold(
                        onSuccess = { lease ->
                            if (
                                authority.completeTakeover(
                                    operation,
                                    lease,
                                ) == GatewayWalkAuthorityCompletion.LEASE_ACTIVE
                            ) {
                                completeGatewayWalkActivation(token)
                            } else {
                                makeGatewayWalkStartRetryAvailable(
                                    detail =
                                        "권한 이전 응답이 현재 요청과 일치하지 않습니다. " +
                                            "다시 시도하세요.",
                                )
                            }
                        },
                        onFailure = {
                            authority.cancel(
                                operation,
                                clearConflict = true,
                            )
                            makeGatewayWalkStartRetryAvailable(
                                detail =
                                    "이전 보행 종료와 권한 이전을 확인하지 못했습니다. " +
                                        "다시 시도하세요.",
                                spoken =
                                    "보행 권한을 이전하지 못해 이 기기에서는 시작하지 않았습니다.",
                            )
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            authority.cancel(operation, clearConflict = true)
            makeGatewayWalkStartRetryAvailable(
                detail = "보행 권한 이전 작업을 시작하지 못했습니다. 다시 시도하세요.",
            )
        }
    }

    private fun completeGatewayWalkActivation(token: WalkSessionConfirmationToken) {
        val snapshot = runtime.snapshot()
        val currentReadiness = if (
            snapshot.state == WalkSessionState.READY &&
            snapshot.epoch == token.epoch &&
            snapshot.confirmationToken == token
        ) {
            runtime.currentStartReadinessOrNull(token)
        } else {
            null
        }
        if (
            runtime.blocksAutomaticStart() ||
            currentReadiness == null ||
            authority.activeLeaseOrNull(snapshot.epoch) == null
        ) {
            val endOperation = authority.beginEnd(token.epoch)
            val currentGatewaySession = identity.currentSession()
            if (endOperation != null && currentGatewaySession != null) {
                endGatewayWalkBestEffort(currentGatewaySession, endOperation)
            } else {
                authority.invalidate(token.epoch)
            }
            makeGatewayWalkStartRetryAvailable(
                detail =
                    "서버 응답을 기다리는 동안 실제 상태가 바뀌어 " +
                        "보행 안내를 시작하지 않았습니다. 다시 시도하세요.",
            )
            return
        }
        clearStartOwnership()
        if (debug) {
            val nowMs = scheduler.nowMs()
            output.debug(
                "WalkSafeNavigation",
                "event=GATEWAY_WALK_ACTIVATION " +
                    "confirmation_age_ms=${nowMs - token.readiness.assessedAtElapsedRealtimeMs} " +
                    "previous_stale=${token.readiness.staleRequirementsAt(nowMs).joinToString("+") { it.name }.ifEmpty { "NONE" }} " +
                    "live_ready=${currentReadiness.isReadyAt(nowMs)}",
            )
        }
        runtime.startRequested(token, currentReadiness)
        if (runtime.snapshot().state != WalkSessionState.ACTIVE) {
            // The lifecycle rejected the confirmation (e.g. its readiness expired while the
            // server responded). Release the lease this start acquired instead of holding it.
            val endOperation = authority.beginEnd(token.epoch)
            val currentGatewaySession = identity.currentSession()
            if (endOperation != null && currentGatewaySession != null) {
                endGatewayWalkBestEffort(currentGatewaySession, endOperation)
            } else {
                authority.invalidate(token.epoch)
            }
            makeGatewayWalkStartRetryAvailable(
                detail = "준비 확인이 오래되어 보행 안내를 시작하지 않았습니다. 다시 점검한 뒤 시도하세요.",
            )
            return
        }
        if (runtime.isActive()) {
            runtime.activate()
        }
    }

    private fun isGatewayWalkOperationCurrent(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
        gatewaySession: GatewayFieldSession,
    ): Boolean {
        val snapshot = runtime.snapshot()
        return authority.isCurrent(operation) &&
            snapshot.epoch == operation.epoch &&
            snapshot.state == WalkSessionState.READY &&
            snapshot.confirmationToken == token &&
            gatewayWalkStartConfirmationToken == token &&
            gatewayWalkStartOperationId == operation.operationId &&
            identity.isCurrent(gatewaySession)
    }

    private fun clearStartOwnership() {
        gatewayWalkStartConfirmationToken = null
        gatewayWalkStartOperationId = null
    }

    private fun retireStartIfOwned(operation: GatewayWalkAuthorityOperation, token: WalkSessionConfirmationToken) {
        if (gatewayWalkStartOperationId != operation.operationId || gatewayWalkStartConfirmationToken != token) return
        clearStartOwnership()
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        runtime.refreshReadinessUi()
    }

    fun scheduleGatewayWalkRenewal() {
        scheduler.removeCallbacks(gatewayWalkRenewalRunnable)
        val snapshot = runtime.snapshot()
        if (snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) return
        val renewDelayMs = authority.nextRenewDelayMs(
            snapshot.epoch,
            renewIntervalMs,
        ) ?: run {
            handleGatewayWalkAuthorityLost("walk_lease_local_expiry")
            return
        }
        scheduler.postDelayed(
            gatewayWalkRenewalRunnable,
            renewDelayMs,
        )
    }

    fun requestGatewayWalkRenewal() {
        scheduler.removeCallbacks(gatewayWalkRenewalRunnable)
        val snapshot = runtime.snapshot()
        if (snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) return
        val operation = authority.beginRenew(snapshot.epoch)
            ?: run {
                if (
                    authority.activeLeaseOrNull(snapshot.epoch) == null
                ) {
                    handleGatewayWalkAuthorityLost("walk_lease_local_expiry")
                }
                return
            }
        val lease = operation.sourceLease ?: run {
            handleGatewayWalkAuthorityLost("walk_lease_missing")
            return
        }
        val gatewaySession = identity.sessionOrNull("walk_renew", speak = false) ?: run {
            authority.cancel(operation)
            // login-F1: an expired access credential of a renewable login is being renewed in
            // the running app; renew the lease with the renewed login instead of stopping.
            if (identity.awaitRenewal { scheduler.post(gatewayWalkRenewalRunnable) }) {
                return
            }
            handleGatewayWalkAuthorityLost("walk_lease_gateway_session_missing")
            return
        }
        try {
            scheduler.execute {
                val outcome = runCatching {
                    requests.renew(
                        session = gatewaySession,
                        lease = lease,
                        requestId = operation.requestId,
                        localNowElapsedMs = scheduler.nowMs(),
                    )
                }
                scheduler.runOnUiThread {
                    val callbackSnapshot = runtime.snapshot()
                    if (
                        callbackSnapshot.epoch != operation.epoch ||
                        callbackSnapshot.state !in
                        setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
                    ) return@runOnUiThread
                    // A renewal answered for a replaced or signed-out gateway session must not
                    // extend the current walk, even when the lease itself still matches.
                    if (!identity.isCurrent(gatewaySession)) {
                        authority.cancel(operation)
                        if (!identity.isRenewedContinuation(gatewaySession)) {
                            handleGatewayWalkAuthorityLost("walk_lease_gateway_session_changed")
                        } else {
                            // login-F1: the same login was renewed in-run meanwhile; this answer is
                            // not applied, the lease is renewed again with the renewed credential.
                            scheduler.post(gatewayWalkRenewalRunnable)
                        }
                        return@runOnUiThread
                    }
                    when (authority.currentOperationStatus(operation)) {
                        GatewayWalkAuthorityCompletion.INVALID -> {
                            handleGatewayWalkAuthorityLost(
                                "walk_lease_expired_during_renew",
                            )
                            return@runOnUiThread
                        }
                        GatewayWalkAuthorityCompletion.LEASE_ACTIVE -> Unit
                        else -> return@runOnUiThread
                    }
                    val renewed = outcome.getOrNull()
                    if (renewed == null) {
                        authority.cancel(operation)
                        // S03-23: rejection and login loss stop at once; a transient failure is
                        // retried a bounded number of times, only inside the lease already held.
                        val failure = GatewayWalkRenewalFailurePolicy.classify(outcome.exceptionOrNull())
                        // login-F1/F4: a login rejection of a renewable long-lived login first gets one
                        // durable refresh; renewed -> the lease renewal is retried, revoked -> the
                        // reason is shown and the next attempt stops the walk as before.
                        if (
                            failure == GatewayWalkRenewalFailure.LOGIN_REQUIRED &&
                            identity.probeAfterUnauthorized(gatewaySession) {
                                scheduler.post(gatewayWalkRenewalRunnable)
                            }
                        ) {
                            output.updateNavigationStatus("walk_lease=renew_waiting_login_refresh")
                            return@runOnUiThread
                        }
                        val previousRetries = gatewayWalkRenewalRetries
                            ?.takeIf { it.first == operation.epoch.walkSessionId }?.second ?: 0
                        val retryDelayMs = GatewayWalkRenewalFailurePolicy.retryDelayMsOrNull(
                            failure = failure,
                            previousRetries = previousRetries,
                            safeRemainingMs = authority
                                .safeRemainingMsOrNull(operation.epoch),
                        )
                        if (retryDelayMs != null) {
                            gatewayWalkRenewalRetries =
                                operation.epoch.walkSessionId to previousRetries + 1
                            output.updateNavigationStatus(
                                "walk_lease=renew_retry attempt=${previousRetries + 1} " +
                                    "reason=${failure.reasonCode}",
                            )
                            scheduler.postDelayed(gatewayWalkRenewalRunnable, retryDelayMs)
                        } else {
                            handleGatewayWalkAuthorityLost(failure.reasonCode, GatewayWalkRenewalFailurePolicy.detailKo(failure))
                        }
                        return@runOnUiThread
                    }
                    if (
                        authority.completeRenew(
                            operation,
                            renewed,
                        ) != GatewayWalkAuthorityCompletion.LEASE_ACTIVE
                    ) {
                        authority.cancel(operation)
                        handleGatewayWalkAuthorityLost("walk_lease_renew_failed")
                        return@runOnUiThread
                    }
                    gatewayWalkRenewalRetries = null
                    gatewayWalkResumeRevalidationPending = false
                    scheduleGatewayWalkRenewal()
                    if (
                        runtime.snapshot().state == WalkSessionState.PAUSED &&
                        runtime.isForeground()
                    ) {
                        runtime.refreshReadinessUi()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            authority.cancel(operation)
            handleGatewayWalkAuthorityLost("walk_lease_renew_rejected")
        }
    }

    fun revalidateGatewayWalkAfterForegroundReturn() {
        val snapshot = runtime.snapshot()
        if (snapshot.state != WalkSessionState.PAUSED) return
        if (authority.activeLeaseOrNull(snapshot.epoch) == null) {
            handleGatewayWalkAuthorityLost("walk_lease_expired_while_backgrounded")
            return
        }
        gatewayWalkResumeRevalidationPending = true
        requestGatewayWalkRenewal()
    }

    fun handleGatewayWalkAuthorityLost(
        reason: String,
        detailKo: String = "서버의 단일 활성 보행 권한을 확인하지 못했습니다.",
    ) {
        scheduler.removeCallbacks(gatewayWalkRenewalRunnable)
        gatewayWalkResumeRevalidationPending = false
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        clearStartOwnership()
        val snapshot = runtime.snapshot()
        authority.invalidate(snapshot.epoch)
        if (snapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) {
            runtime.safetyStop(reason)
        }
        output.updateStatus(
            "보행 안전 중지",
            "$detailKo\n\n$restartNoticeKo",
        )
    }

    fun cancelGatewayWalkTakeoverConfirmation(
        epoch: WalkRuntimeEpoch,
        reason: String,
        detail: String = "이전 보행 종료를 확인하지 않아 시작하지 않았습니다. 다시 시도할 수 있습니다.",
    ) {
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        authority.clearConflict(epoch)
        makeGatewayWalkStartRetryAvailable(
            detail = detail,
        )
        output.updateNavigationStatus("walk=takeover_not_confirmed reason=$reason")
    }

    fun cancelPendingGatewayWalkStart() {
        val token = gatewayWalkStartConfirmationToken
        clearStartOwnership()
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        if (token != null) authority.invalidate(token.epoch)
    }

    fun endGatewayWalkBestEffort(
        gatewaySession: GatewayFieldSession,
        operation: GatewayWalkAuthorityOperation,
        previousRetries: Int = 0,
    ) {
        val lease = operation.sourceLease ?: return
        try {
            scheduler.execute {
                val outcome = runCatching {
                    requests.end(
                        session = gatewaySession,
                        lease = lease,
                        requestId = operation.requestId,
                    )
                }
                // S03-21: the local stop is already done. A lost or transient server answer is
                // resent with the same request id, by the owning session, while the lease may hold.
                scheduler.runOnUiThread {
                    val retryDelayMs = GatewayWalkEndRetryPolicy.retryDelayMsOrNull(
                        error = outcome.exceptionOrNull(),
                        previousRetries = previousRetries,
                        leaseMayStillBeHeld = lease.isLocallyUsable(scheduler.nowMs()),
                        sameGatewaySession = identity.isCurrent(gatewaySession),
                    ) ?: return@runOnUiThread
                    scheduler.postDelayed(
                        { endGatewayWalkBestEffort(gatewaySession, operation, previousRetries + 1) },
                        retryDelayMs,
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            // The server lease expires after its bounded lifetime.
        }
    }

    private fun abandonedGatewayWalkStartLeaseOrNull(
        outcome: Result<GatewayWalkStartResult>,
    ): GatewayWalkLease? =
        (outcome.getOrNull() as? GatewayWalkStartResult.Granted)?.lease
            ?: (outcome.exceptionOrNull() as? GatewayWalkHttpException)?.abandonedLease

    /**
     * A start or takeover the server granted after this operation became stale was never held by
     * [authority]. End exactly that lease with the session that obtained it.
     * A later start of the same walk may already be queued ahead of this task and receive the same
     * lease (ALREADY_ACTIVE), so the end is skipped while the controller still tracks the walk.
     */
    private fun endAbandonedGatewayWalkStartLeaseBestEffort(
        gatewaySession: GatewayFieldSession,
        operation: GatewayWalkAuthorityOperation,
        lease: GatewayWalkLease?,
    ) {
        if (lease == null || lease.walkId != operation.epoch.walkSessionId) return
        try {
            scheduler.execute {
                if (authority.tracksWalk(lease.walkId)) return@execute
                runCatching {
                    requests.end(
                        session = gatewaySession,
                        lease = lease,
                        requestId = UUID.randomUUID().toString(),
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            // The server lease expires after its bounded lifetime.
        }
    }
}
