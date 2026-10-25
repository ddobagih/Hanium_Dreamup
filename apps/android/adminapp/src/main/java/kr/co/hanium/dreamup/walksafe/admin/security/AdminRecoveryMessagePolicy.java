package kr.co.hanium.dreamup.walksafe.admin.security;

public final class AdminRecoveryMessagePolicy {
    public enum Phase {
        GENERAL,
        RECOVERY_START,
        RECOVERY_COMPLETE,
        CUSTODY_ATTESTATION,
        LOST_DEVICE_REPORT,
        /** S18-18: revoke of this device's own session (the logout button). */
        CURRENT_SESSION_LOGOUT
    }

    private static final String GENERIC_FAILURE =
        "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.";
    private static final String CUSTODY_FAILURE =
        "복구자료 보관 상태를 확인하지 못했습니다. 고위험 작업과 관리자 업무 잠금을 유지합니다.";
    private static final String LOST_DEVICE_FAILURE =
        "분실 기기의 세션·키 폐기를 확인하지 못했습니다. 서버 상태를 새로고침한 뒤 다시 확인하세요.";
    private static final String LOST_DEVICE_CUSTODY_REQUIRED =
        "복구자료 보관이 확인되지 않아 분실 처리를 할 수 없습니다. 휴대전화 밖에 보관한 복구코드로 분실 복구를 시작하세요.";
    private static final String TOTP_REPLAYED =
        "이 추가 인증 코드는 이미 사용되었습니다. 새 코드가 표시된 뒤 입력하세요.";
    private static final String REAUTHENTICATION_FAILED =
        "비밀번호와 추가 인증 코드를 확인해 주세요. 로그인이나 직전 재인증에 사용한 코드는 이미 사용된 코드이므로 새 코드가 표시된 뒤 입력하세요.";

    // S18-19: a timeout is not an authentication failure. The server may already have
    // processed the request, so the text says the outcome is unknown and nothing was re-sent.
    static final String OUTCOME_UNKNOWN_TIMEOUT =
        "서버 응답을 제한 시간 안에 받지 못했습니다. 서버가 요청을 처리했는지 알 수 없어 자동으로 다시 보내지 않았고, "
            + "안전을 위해 관리자 업무를 잠갔습니다. 서버 상태를 다시 확인한 뒤 필요하면 새 요청으로 시도하세요.";

    // S18-18: a failed logout already locked this device and dropped its session values
    // (the controller clears the local session on every current-session revoke failure);
    // only the server-side revoke is unconfirmed. Say both, separately.
    // adm-F6: only when the controller really no longer retains a session; a failure before
    // the revoke (locked, closed, token or session id checks) leaves the session in place.
    static final String LOGOUT_SERVER_REVOKE_UNCONFIRMED =
        "이 기기에서는 로그아웃해 관리자 업무를 잠그고 보관하던 세션 값을 지웠습니다. "
            + "서버 세션 폐기는 확인하지 못했습니다(서버가 처리했는지 알 수 없음). "
            + "다시 로그인한 뒤 세션 목록에 이 기기의 이전 세션이 남아 있으면 폐기하세요.";

    public static final String RECOVERY_PREPARATION_KO =
        "복구 완료 전 신뢰할 수 있는 로컬 운영자가 서버의 TOTP 비밀키를 새 값으로 교체해야 합니다. "
            + "교체가 끝난 뒤 이전과 다른 새 비밀번호와 새 TOTP의 6자리 코드를 입력하세요. 확인 전까지 관리자 업무는 잠겨 있습니다.";

    private AdminRecoveryMessagePolicy() {}

    /**
     * Same as {@link #failureMessage(Phase, Throwable, boolean)} without knowing whether a local
     * session remains, so the logout text never claims a deletion that may not have happened.
     */
    public static String failureMessage(Phase phase, Throwable error) {
        return failureMessage(phase, error, true);
    }

    /**
     * @param localSessionRetained whether the controller still retains a session after the
     *     failure ({@code AdminSecurityController#hasRetainedSession()}); it only matters for
     *     {@link Phase#CURRENT_SESSION_LOGOUT}
     */
    public static String failureMessage(Phase phase, Throwable error, boolean localSessionRetained) {
        if (phase == Phase.CURRENT_SESSION_LOGOUT) {
            // adm-F6: with the session still retained nothing was deleted locally (only a local
            // pre-check failed), so this is the ordinary generic failure without login hints.
            return localSessionRetained ? GENERIC_FAILURE : LOGOUT_SERVER_REVOKE_UNCONFIRMED;
        }
        if (error instanceof java.net.SocketTimeoutException
            && (phase == Phase.GENERAL || phase == Phase.RECOVERY_START
                || phase == Phase.RECOVERY_COMPLETE)) {
            return OUTCOME_UNKNOWN_TIMEOUT;
        }
        if (phase == Phase.GENERAL && error instanceof java.io.IOException
            && !(error instanceof AdminSecurityApiException)) {
            if (error instanceof AdminDeviceProof.ChallengeTimeException) {
                return "기기의 자동 날짜·시간 설정을 확인하고 새 도전값으로 다시 시도하세요.";
            }
            String reason = error.getMessage();
            if ("invalid_admin_id".equals(reason)) {
                return "관리자 ID를 입력하고 형식을 확인한 뒤 다시 로그인하세요.";
            }
            if ("invalid_password".equals(reason)) {
                return "비밀번호를 12자 이상 256자 이하로 입력한 뒤 다시 로그인하세요.";
            }
            if ("invalid_totp_code".equals(reason)) {
                return "6자리 숫자 추가 인증 코드를 입력한 뒤 다시 로그인하세요.";
            }
        }
        // W20A-R3: a lost-phone emergency must say why nothing was revoked and what to do
        // next; the local custody gate and the server's custody check both mean switching
        // to off-phone recovery-code recovery.
        if (phase == Phase.LOST_DEVICE_REPORT
            && error instanceof AdminRecoveryCustodyRequiredException) {
            return LOST_DEVICE_CUSTODY_REQUIRED;
        }
        if (!(error instanceof AdminSecurityApiException apiError)) return genericMessage(phase);

        if (apiError.code() == AdminSecurityApiException.Code.RATE_LIMITED) {
            Long seconds = apiError.retryAfterSeconds();
            return seconds == null
                ? "요청이 너무 잦습니다. 잠시 기다린 뒤 다시 시도하세요."
                : "요청이 너무 잦습니다. " + seconds + "초 후 다시 시도하세요.";
        }

        if (phase == Phase.GENERAL || phase == Phase.LOST_DEVICE_REPORT) {
            if (apiError.code() == AdminSecurityApiException.Code.TOTP_REPLAYED) {
                return TOTP_REPLAYED;
            }
            if (apiError.code() == AdminSecurityApiException.Code.REAUTHENTICATION_FAILED) {
                return REAUTHENTICATION_FAILED;
            }
        }
        if (phase == Phase.LOST_DEVICE_REPORT
            && apiError.code() == AdminSecurityApiException.Code.RECOVERY_CUSTODY_REQUIRED) {
            return LOST_DEVICE_CUSTODY_REQUIRED;
        }
        if (phase == Phase.CUSTODY_ATTESTATION
            && apiError.code()
                == AdminSecurityApiException.Code.CUSTODY_STATE_VERSION_CONFLICT) {
            return "다른 기기에서 보관 확인 상태가 변경되어 이전 시도는 폐기했습니다. "
                + "다시 로그인해 최신 상태를 확인하고, 새 보관 확인이 필요하면 다시 직접 확인해 주세요.";
        }

        if (phase == Phase.RECOVERY_START) return recoveryStartMessage(apiError.code());
        if (phase == Phase.RECOVERY_COMPLETE) return recoveryCompleteMessage(apiError.code());
        return genericMessage(phase);
    }

    private static String genericMessage(Phase phase) {
        if (phase == Phase.CUSTODY_ATTESTATION) return CUSTODY_FAILURE;
        if (phase == Phase.LOST_DEVICE_REPORT) return LOST_DEVICE_FAILURE;
        return GENERIC_FAILURE;
    }

    private static String recoveryStartMessage(AdminSecurityApiException.Code code) {
        return switch (code) {
            case RECOVERY_EXPIRED ->
                "기존 복구 정보가 만료되었습니다. 같은 복구코드와 이 기기로 복구 시작을 다시 시도하세요.";
            case RECOVERY_INVALID ->
                "복구코드와 기기를 확인할 수 없습니다. 휴대전화 밖에 보관한 복구자료와 이 기기를 확인한 뒤 다시 시도하세요.";
            case RECOVERY_IN_PROGRESS ->
                "복구가 이미 진행 중입니다. 처음 사용한 기기에서 같은 복구코드로 복구 시작을 다시 시도하세요.";
            case RECOVERY_DEVICE_KEY_REQUIRED ->
                "이 기기의 관리자 기기 키가 서버에 등록되지 않았습니다. 화면의 공개키 등록 정보를 신뢰할 수 있는 로컬 운영자에게 전달해 등록한 뒤 복구 시작을 다시 시도하세요.";
            default -> GENERIC_FAILURE;
        };
    }

    private static String recoveryCompleteMessage(AdminSecurityApiException.Code code) {
        return switch (code) {
            case RECOVERY_EXPIRED ->
                "복구 유효시간이 끝났습니다. 같은 복구코드와 이 기기로 복구 시작부터 다시 진행하세요.";
            case RECOVERY_INVALID ->
                "현재 복구 정보를 사용할 수 없습니다. 휴대전화 밖에 보관한 복구코드와 이 기기를 확인한 뒤 복구 시작부터 다시 진행하세요.";
            case RECOVERY_IN_PROGRESS ->
                "복구가 아직 완료되지 않았습니다. 처음 사용한 기기에서 새 인증정보를 다시 확인하세요.";
            case TOTP_SECRET_NOT_REPLACED ->
                "별도 관리 경로에서 TOTP 비밀키를 새 값으로 교체한 뒤, 새 비밀키에서 나온 6자리 코드를 입력하세요.";
            case RECOVERY_VERIFICATION_FAILED ->
                "새 비밀번호와 교체된 TOTP의 6자리 코드를 다시 확인해 입력하세요.";
            case DEVICE_KEY_NOT_ACTIVE ->
                "이 기기의 관리자 기기 키가 활성 상태가 아닙니다. 화면의 공개키 등록 정보를 신뢰할 수 있는 로컬 운영자에게 전달해 활성 키로 등록한 뒤 복구 완료를 다시 시도하세요.";
            default -> GENERIC_FAILURE;
        };
    }
}
