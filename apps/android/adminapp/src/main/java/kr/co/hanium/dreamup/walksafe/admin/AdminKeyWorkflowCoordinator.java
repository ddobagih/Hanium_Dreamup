package kr.co.hanium.dreamup.walksafe.admin;

import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyStore;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyUseAuthorization;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyUseRetry;

/** Owns local key UI workflow; durable key material/rotation remain in their existing adapters. */
final class AdminKeyWorkflowCoordinator {
    // Public workflow choices only, retained across the intentional Activity recreation.
    private static volatile boolean replacementLoginRequested;
    private static volatile boolean rotationCompletedNotice;
    private final AdminDeviceKeyUseAuthorization authorization = new AdminDeviceKeyUseAuthorization();
    private final AdminDeviceKeyUseRetry retry = new AdminDeviceKeyUseRetry(authorization);
    private AdminDeviceKeyStore.Descriptor pending;
    private AdminDeviceKeyStore.Descriptor replacementLogin;
    private boolean activeUnavailable;
    private boolean awaitingCredential;
    private boolean reconciling;
    private boolean closed;
    private long generation;

    synchronized long generation() { return generation; }
    synchronized boolean isCurrent(long expected) { return !closed && expected == generation; }
    synchronized boolean applyPending(long expected, AdminDeviceKeyStore.Descriptor value) {
        if (!isCurrent(expected)) return false;
        pending = value;
        return true;
    }
    synchronized AdminDeviceKeyStore.Descriptor pending() { return pending; }
    synchronized void bindReplacement(AdminDeviceKeyStore.Descriptor descriptor) {
        if (!closed) replacementLogin = descriptor;
    }
    synchronized AdminDeviceKeyStore.Descriptor replacementLogin() { return replacementLogin; }
    synchronized void markActiveUnavailable() { activeUnavailable = true; }
    synchronized boolean activeUnavailable() { return activeUnavailable; }
    static boolean replacementLoginRequested() { return replacementLoginRequested; }
    static void requestReplacementLogin(boolean replacement) { replacementLoginRequested = replacement; }
    static void rotationCompleted() { rotationCompletedNotice = true; }
    static synchronized boolean consumeRotationNotice() {
        boolean notice = rotationCompletedNotice;
        rotationCompletedNotice = false;
        return notice;
    }
    synchronized void replacementPromoted() {
        if (closed) return;
        replacementLoginRequested = false;
        replacementLogin = null;
        activeUnavailable = false;
        pending = null;
    }
    synchronized boolean beginReconcile() {
        if (closed || reconciling) return false;
        reconciling = true;
        return true;
    }
    synchronized boolean finishReconcile(long expected) {
        if (!isCurrent(expected)) return false;
        reconciling = false;
        return true;
    }
    synchronized boolean isReconciling() { return reconciling; }
    synchronized AdminDeviceKeyUseAuthorization.Decision onFailure(Throwable error, Runnable operation) {
        if (closed) return AdminDeviceKeyUseAuthorization.Decision.STOP;
        return retry.onFailure(error, operation);
    }
    synchronized void credentialLaunched() { if (!closed) awaitingCredential = true; }
    synchronized boolean awaitingCredential() { return awaitingCredential; }
    synchronized AdminDeviceKeyUseAuthorization.Decision credentialResult(boolean confirmed) {
        boolean expected = awaitingCredential && !closed;
        awaitingCredential = false;
        return retry.onCredentialResult(expected && confirmed);
    }
    synchronized void cancelCredential() {
        awaitingCredential = false;
        retry.cancelPending();
    }
    synchronized Runnable takeReady() { return closed ? null : retry.takeReady(); }
    synchronized void onSuccess() { authorization.onSuccess(); }
    synchronized void close() {
        closed = true;
        generation++;
        reconciling = false;
        cancelCredential();
    }
}
