package kr.co.hanium.dreamup.walksafe.admin.security;

public enum AdminRecoveryCustodyState {
    UNATTESTED,
    ATTESTED;

    public static AdminRecoveryCustodyState fromWireValue(String value) {
        if (value == null) return null;
        return switch (value) {
            case "UNATTESTED" -> UNATTESTED;
            case "ATTESTED" -> ATTESTED;
            default -> null;
        };
    }
}
