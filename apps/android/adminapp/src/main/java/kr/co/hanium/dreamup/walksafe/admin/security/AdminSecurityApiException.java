package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;

public final class AdminSecurityApiException extends IOException {
    public enum Code {
        RECOVERY_EXPIRED("admin_recovery_expired"),
        RECOVERY_INVALID("admin_recovery_invalid"),
        RATE_LIMITED("admin_auth_rate_limited"),
        RECOVERY_IN_PROGRESS("admin_recovery_in_progress"),
        TOTP_SECRET_NOT_REPLACED("admin_totp_secret_not_replaced"),
        RECOVERY_VERIFICATION_FAILED("admin_recovery_verification_failed");

        private final String wireValue;

        Code(String wireValue) {
            this.wireValue = wireValue;
        }

        static Code fromWireValue(String value) {
            for (Code code : values()) {
                if (code.wireValue.equals(value)) return code;
            }
            return null;
        }
    }

    private final Code code;
    private final Long retryAfterSeconds;

    public AdminSecurityApiException(Code code, Long retryAfterSeconds) {
        super("administrator API rejected request: " + requireCode(code).name());
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds != null
            && retryAfterSeconds >= 1L
            && retryAfterSeconds <= 3_600L
            ? retryAfterSeconds
            : null;
    }

    public Code code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean requiresRecoveryRestart() {
        return code == Code.RECOVERY_EXPIRED || code == Code.RECOVERY_INVALID;
    }

    private static Code requireCode(Code code) {
        if (code == null) throw new IllegalArgumentException("error code is required");
        return code;
    }
}
