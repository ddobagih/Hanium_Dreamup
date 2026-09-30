package kr.co.hanium.dreamup.walksafe.admin.security;

/**
 * S18-1-F2: holds the single operation that may resume after the device credential screen.
 *
 * <p>The Activity passes the operation to repeat, or {@code null} when the failed attempt already
 * consumed one-time secrets (password, TOTP, recovery code). A {@code null} operation is never
 * replayed: after the credential screen the decision is
 * {@link AdminDeviceKeyUseAuthorization.Decision#REENTER_CREDENTIALS} and nothing runs.
 */
public final class AdminDeviceKeyUseRetry {
    private final AdminDeviceKeyUseAuthorization authorization;
    private Runnable pending;
    private Runnable ready;

    public AdminDeviceKeyUseRetry(AdminDeviceKeyUseAuthorization authorization) {
        if (authorization == null) throw new IllegalArgumentException("authorization is required");
        this.authorization = authorization;
    }

    /** @param retry the same operation to run once more, or null for credential-bound work */
    public synchronized AdminDeviceKeyUseAuthorization.Decision onFailure(
        Throwable error,
        Runnable retry
    ) {
        AdminDeviceKeyUseAuthorization.Decision decision =
            authorization.onFailure(error, retry != null);
        pending = decision == AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL
            ? retry
            : null;
        ready = null;
        return decision;
    }

    public synchronized AdminDeviceKeyUseAuthorization.Decision onCredentialResult(boolean confirmed) {
        Runnable retry = pending;
        pending = null;
        AdminDeviceKeyUseAuthorization.Decision decision = authorization.onCredentialResult(confirmed);
        ready = decision == AdminDeviceKeyUseAuthorization.Decision.RETRY_ONCE ? retry : null;
        return decision;
    }

    /** The device credential screen could not be shown; nothing will be retried. */
    public synchronized void cancelPending() {
        pending = null;
        ready = null;
        authorization.onCredentialResult(false);
    }

    /** Returns the operation to run now (at most once), or null. */
    public synchronized Runnable takeReady() {
        Runnable retry = ready;
        ready = null;
        return retry;
    }
}
