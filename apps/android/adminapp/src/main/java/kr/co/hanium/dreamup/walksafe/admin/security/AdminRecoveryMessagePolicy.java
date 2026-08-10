package kr.co.hanium.dreamup.walksafe.admin.security;

public final class AdminRecoveryMessagePolicy {
    public enum Phase {
        GENERAL,
        RECOVERY_START,
        RECOVERY_COMPLETE
    }

    private static final String GENERIC_FAILURE =
        "요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.";

    private AdminRecoveryMessagePolicy() {}

    public static String failureMessage(Phase phase, Throwable error) {
        if (!(error instanceof AdminSecurityApiException apiError)) return GENERIC_FAILURE;

        if (apiError.code() == AdminSecurityApiException.Code.RATE_LIMITED) {
            Long seconds = apiError.retryAfterSeconds();
            return seconds == null
                ? "요청이 너무 잦습니다. 잠시 기다린 뒤 다시 시도하세요."
                : "요청이 너무 잦습니다. " + seconds + "초 후 다시 시도하세요.";
        }

        if (phase == Phase.RECOVERY_START) return recoveryStartMessage(apiError.code());
        if (phase == Phase.RECOVERY_COMPLETE) return recoveryCompleteMessage(apiError.code());
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
            default -> GENERIC_FAILURE;
        };
    }
}
