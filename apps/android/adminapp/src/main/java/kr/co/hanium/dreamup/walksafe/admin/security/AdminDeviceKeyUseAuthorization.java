package kr.co.hanium.dreamup.walksafe.admin.security;

import java.security.GeneralSecurityException;

/**
 * S18-1: decides what happens when the user-authentication-bound device key refuses to sign.
 *
 * <p>A key that needs a recent device unlock asks for the device credential once. An operation
 * without one-time secrets is then retried once; an operation that already consumed the
 * password/TOTP (login, recovery completion, reauthenticated high-risk work) is never replayed
 * and the operator re-enters them (S18-1-F2). A key invalidated because the secure lock screen
 * was removed or reset is never retried: the app stays locked and the operator prepares a
 * replacement key (S18-1-F1).
 */
public final class AdminDeviceKeyUseAuthorization {
    /** The device key needs a recent device credential or strong biometric authentication. */
    public static final class AuthenticationRequiredException extends GeneralSecurityException {
        public AuthenticationRequiredException(Throwable cause) {
            super("administrator device key needs recent user authentication", cause);
        }
    }

    /** The device key was permanently invalidated (the secure lock screen was removed or reset). */
    public static final class KeyInvalidatedException extends GeneralSecurityException {
        public KeyInvalidatedException(Throwable cause) {
            super("administrator device key was permanently invalidated", cause);
        }
    }

    /** A user-authentication-bound key cannot be created without a secure lock screen. */
    public static final class LockScreenRequiredException extends GeneralSecurityException {
        public LockScreenRequiredException(Throwable cause) {
            super("a secure lock screen is required for the administrator device key", cause);
        }
    }

    public enum Decision {
        /** Not a device-key authentication failure; handle it as before. */
        NOT_KEY_AUTHENTICATION,
        /** Show the device credential screen, then report its result. */
        REQUEST_DEVICE_CREDENTIAL,
        /** Credential confirmed: run the same operation exactly once more. */
        RETRY_ONCE,
        /**
         * Credential confirmed, but the failed operation consumed one-time secrets: never
         * replay it; the operator re-enters the password and a new code.
         */
        REENTER_CREDENTIALS,
        /** Authentication was declined or the single retry also failed. */
        STOP,
        /** The key can no longer be used; keep the app locked and replace the device key. */
        STOP_KEY_INVALIDATED
    }

    private boolean awaitingCredential;
    private boolean awaitingRepeatable;
    private boolean retried;

    public static boolean requiresAuthentication(Throwable error) {
        return hasCause(error, AuthenticationRequiredException.class);
    }

    public static boolean isInvalidated(Throwable error) {
        return hasCause(error, KeyInvalidatedException.class);
    }

    public static boolean requiresLockScreen(Throwable error) {
        return hasCause(error, LockScreenRequiredException.class);
    }

    /** Same as {@link #onFailure(Throwable, boolean)} for an operation that may be repeated. */
    public synchronized Decision onFailure(Throwable error) {
        return onFailure(error, true);
    }

    /**
     * @param repeatable false when the operation already consumed one-time credentials, so it
     *     must never be replayed after the device credential screen
     */
    public synchronized Decision onFailure(Throwable error, boolean repeatable) {
        if (isInvalidated(error)) {
            reset();
            return Decision.STOP_KEY_INVALIDATED;
        }
        if (!requiresAuthentication(error)) {
            reset();
            return Decision.NOT_KEY_AUTHENTICATION;
        }
        if (retried || awaitingCredential) {
            reset();
            return Decision.STOP;
        }
        awaitingCredential = true;
        awaitingRepeatable = repeatable;
        return Decision.REQUEST_DEVICE_CREDENTIAL;
    }

    public synchronized Decision onCredentialResult(boolean confirmed) {
        if (!awaitingCredential) return Decision.STOP;
        boolean repeatable = awaitingRepeatable;
        awaitingCredential = false;
        awaitingRepeatable = false;
        if (!confirmed) {
            retried = false;
            return Decision.STOP;
        }
        if (!repeatable) {
            // The next attempt is a new, manually started operation with fresh secrets.
            retried = false;
            return Decision.REENTER_CREDENTIALS;
        }
        retried = true;
        return Decision.RETRY_ONCE;
    }

    /** The operation finished without a device-key authentication failure. */
    public synchronized void onSuccess() {
        reset();
    }

    public synchronized void reset() {
        awaitingCredential = false;
        awaitingRepeatable = false;
        retried = false;
    }

    public synchronized boolean isAwaitingCredential() {
        return awaitingCredential;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (type.isInstance(cursor)) return true;
            if (cursor.getCause() == cursor) break;
        }
        return false;
    }
}
