package kr.co.hanium.dreamup.walksafe.admin.security;

public enum AdminSecurityState {
    SIGNED_OUT,
    AUTHENTICATING,
    NORMAL,
    RECOVERY_REQUIRED,
    RECOVERY_IN_PROGRESS,
    FAIL_CLOSED;

    public static AdminSecurityState fromWireValue(String value) {
        if (value == null) return FAIL_CLOSED;
        return switch (value.trim()) {
            case "NORMAL" -> NORMAL;
            case "RECOVERY_REQUIRED" -> RECOVERY_REQUIRED;
            case "RECOVERY_IN_PROGRESS" -> RECOVERY_IN_PROGRESS;
            default -> FAIL_CLOSED;
        };
    }
}
