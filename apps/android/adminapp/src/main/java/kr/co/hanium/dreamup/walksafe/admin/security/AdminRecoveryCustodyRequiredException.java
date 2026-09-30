package kr.co.hanium.dreamup.walksafe.admin.security;

/**
 * Local gate: high-risk work needs off-phone recovery custody to be attested first.
 *
 * A typed subclass lets the lost-device flow tell the administrator to switch to
 * recovery-code recovery instead of showing a generic refresh message (W20A-R3).
 */
public final class AdminRecoveryCustodyRequiredException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public AdminRecoveryCustodyRequiredException() {
        super("recovery custody is not attested");
    }
}
