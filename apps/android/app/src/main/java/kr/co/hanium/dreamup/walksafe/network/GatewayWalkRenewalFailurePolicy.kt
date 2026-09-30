package kr.co.hanium.dreamup.walksafe.network

import java.io.IOException

/**
 * Why a walk lease renewal failed (S03-23). Explicit server rejection and login loss end the walk
 * at once; only a transient connection failure may be retried, and only inside the lease the
 * server already granted (FP-044-DETAIL-03, FP-043 design_rules[3]).
 */
enum class GatewayWalkRenewalFailure(val reasonCode: String, val retryable: Boolean) {
    AUTHORITY_REJECTED("walk_lease_rejected", retryable = false),
    LOGIN_REQUIRED("walk_lease_login_required", retryable = false),
    TRANSIENT("walk_lease_renew_unreachable", retryable = true),
    /** S19-17-F1: the Gateway keeps refusing this device's commands as outside its time window. */
    DEVICE_CLOCK_CHECK_REQUIRED(
        GatewayWalkSessionClient.DEVICE_CLOCK_CHECK_REQUIRED_REASON,
        retryable = false,
    ),
}

object GatewayWalkRenewalFailurePolicy {
    const val MAX_TRANSIENT_RETRIES = 2

    /** Worst case of one renewal request: transport connect 8 s plus read 12 s. */
    const val RETRY_REQUEST_BUDGET_MS = 20_000L
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L)
    private val LOGIN_CODES = setOf("gateway_auth_required", "long_lived_session_required")
    private val LOGIN_REASONS = setOf("long_lived_session_required", "gateway_session_unavailable")
    private val TRANSIENT_CODES = setOf("walk_ledger_busy", "walk_ledger_unavailable")

    fun classify(error: Throwable?): GatewayWalkRenewalFailure = when (error) {
        is GatewayWalkHttpException -> when {
            error.reason == GatewayWalkSessionClient.DEVICE_CLOCK_CHECK_REQUIRED_REASON ->
                GatewayWalkRenewalFailure.DEVICE_CLOCK_CHECK_REQUIRED
            error.statusCode == 401 || error.statusCode == 403 ||
                error.serverCode in LOGIN_CODES ||
                (error.statusCode == 0 && error.reason in LOGIN_REASONS) ->
                GatewayWalkRenewalFailure.LOGIN_REQUIRED
            error.serverCode in TRANSIENT_CODES ||
                error.statusCode == 408 || error.statusCode == 429 ||
                error.statusCode in 500..599 -> GatewayWalkRenewalFailure.TRANSIENT
            else -> GatewayWalkRenewalFailure.AUTHORITY_REJECTED
        }
        is IOException -> GatewayWalkRenewalFailure.TRANSIENT
        // Malformed or mismatched responses and unknown errors are never trusted.
        else -> GatewayWalkRenewalFailure.AUTHORITY_REJECTED
    }

    /**
     * Delay before the next renewal attempt, or null when the walk must stop now. A retry is
     * allowed only while the held lease still leaves room for the delay plus one whole request.
     */
    fun retryDelayMsOrNull(
        failure: GatewayWalkRenewalFailure,
        previousRetries: Int,
        safeRemainingMs: Long?,
    ): Long? {
        if (!failure.retryable || safeRemainingMs == null) return null
        if (previousRetries !in 0 until MAX_TRANSIENT_RETRIES) return null
        val delayMs = RETRY_DELAYS_MS[previousRetries]
        return delayMs.takeIf { safeRemainingMs > it + RETRY_REQUEST_BUDGET_MS }
    }

    fun detailKo(failure: GatewayWalkRenewalFailure): String = when (failure) {
        GatewayWalkRenewalFailure.AUTHORITY_REJECTED ->
            "서버가 이 보행의 권한을 더 이상 인정하지 않습니다. 다른 기기에서 보행이 시작되었을 수 있습니다. " +
                "새 보행을 시작하려면 보행 시작을 다시 진행해 주세요."
        GatewayWalkRenewalFailure.LOGIN_REQUIRED ->
            "로그인 확인이 필요해 보행을 안전 중지했습니다. 다시 로그인한 뒤 새 보행을 시작해 주세요."
        GatewayWalkRenewalFailure.TRANSIENT ->
            "서버 연결을 확인하지 못한 채 보행 권한 유효시간이 끝나 안전 중지했습니다. " +
                "연결을 확인한 뒤 새 보행을 시작해 주세요."
        GatewayWalkRenewalFailure.DEVICE_CLOCK_CHECK_REQUIRED ->
            "기기 시간이 서버 시간과 크게 달라 보행 권한을 확인하지 못해 안전 중지했습니다. " +
                "기기의 날짜와 시간(자동 설정)을 확인한 뒤 새 보행을 시작해 주세요."
    }
}
