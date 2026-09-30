package kr.co.hanium.dreamup.walksafe.admin.security;

/**
 * S18-2: classifies the Android KeyInfo security level of the administrator device key.
 *
 * <p>The policy keeps the key inside the device's protected area, so only a Trusted Execution
 * Environment or StrongBox key is accepted. StrongBox is not required. Software-only and
 * unknown levels are recorded as such and are never offered for registration.
 */
public final class AdminDeviceKeySecurityLevel {
    public static final String STRONGBOX = "STRONGBOX";
    public static final String TRUSTED_ENVIRONMENT = "TRUSTED_ENVIRONMENT";
    public static final String SOFTWARE = "SOFTWARE";
    public static final String UNKNOWN = "UNKNOWN";

    // android.security.keystore.KeyProperties.SECURITY_LEVEL_* (API 31); same names and values.
    static final int LEVEL_UNKNOWN = -2;
    static final int LEVEL_UNKNOWN_SECURE = -1;
    static final int LEVEL_SOFTWARE = 0;
    static final int LEVEL_TRUSTED_ENVIRONMENT = 1;
    static final int LEVEL_STRONGBOX = 2;

    private AdminDeviceKeySecurityLevel() {}

    /**
     * @param securityLevel KeyInfo.getSecurityLevel() on API 31+, or null below API 31
     * @param insideSecureHardware KeyInfo.isInsideSecureHardware(), used only below API 31
     */
    public static String classify(Integer securityLevel, boolean insideSecureHardware) {
        if (securityLevel == null) return insideSecureHardware ? TRUSTED_ENVIRONMENT : SOFTWARE;
        switch (securityLevel) {
            case LEVEL_STRONGBOX:
                return STRONGBOX;
            case LEVEL_TRUSTED_ENVIRONMENT:
                return TRUSTED_ENVIRONMENT;
            case LEVEL_SOFTWARE:
                return SOFTWARE;
            default:
                // UNKNOWN_SECURE is hardware of an unreported kind; it is not a level the
                // policy can confirm, so it stays unregistered like UNKNOWN.
                return UNKNOWN;
        }
    }

    public static boolean isAccepted(String level) {
        return STRONGBOX.equals(level) || TRUSTED_ENVIRONMENT.equals(level);
    }
}
