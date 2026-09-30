package kr.co.hanium.dreamup.walksafe.network

/**
 * User notice after a long-lived renewal failure that ends in re-login (FP-011 failure_behavior[0]).
 *
 * An unknown refresh outcome is not recovered with the previous refresh value: the server may already
 * have rotated it, and replaying it is refused as reuse (FP-011 design_rules[2], FP-047
 * prohibited_behaviors[1]). The user is told why a new login is needed instead of a silent logout.
 * Confirmed-unapplied busy retries and duplicate attempts are handled before this notice and stay silent.
 *
 * Owner decision 1 (2026-09-29, FP-011-DETAIL-02): a reused refresh token logs out every device of the
 * account. The device that sent it (`refresh_token_reuse_detected`) and every other device on its next
 * refresh (`field_session_revoked_reuse_detected`) show which devices were disconnected and why.
 */
internal enum class GatewayRenewalFailureNotice(val statusToken: String, val speech: String) {
    REUSE_DETECTED(
        statusToken = "gateway=refresh_reuse_detected all_devices_revoked reauthentication_required",
        speech = "이전 로그인 정보가 다시 사용되어 보안을 위해 모든 기기에서 로그아웃했습니다. 다시 로그인해 주세요.",
    ),
    OUTCOME_UNKNOWN(
        statusToken = "gateway=refresh_outcome_unknown reauthentication_required",
        speech = "연결이 끊겨 로그인 확인 결과를 알 수 없습니다. 안전을 위해 다시 로그인해 주세요.",
    ),
    ;

    companion object {
        fun of(error: Throwable): GatewayRenewalFailureNotice? {
            val http = error as? GatewaySessionHttpException ?: return null
            return when {
                http.serverCode == "refresh_token_reuse_detected" ||
                    http.serverCode == "field_session_revoked_reuse_detected" -> REUSE_DETECTED
                http.nextAction == GatewaySessionNextAction.OUTCOME_UNKNOWN ||
                    http.reason == "gateway_refresh_outcome_unknown" ||
                    http.serverCode == "field_session_storage_outcome_unknown" -> OUTCOME_UNKNOWN
                else -> null
            }
        }
    }
}
