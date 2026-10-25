package kr.co.hanium.dreamup.walksafe.admin;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Future;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminMutableCredentials;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOriginalEvidence;

/** Single owner of one-time credentials, in-flight work and displayed original evidence. */
final class OriginalEvidenceCoordinator<I> {
    interface ImageDisposer<I> { void destroy(I image); }
    interface ExpiryScheduler {
        /** Returns the cancellation action for an elapsed-time delay. */
        Runnable schedule(long delayMs, Runnable expiry);
    }
    static final class Binding {
        final String reportId;
        final int contentRevision;
        final String sessionId;
        final String deviceId;
        final long sessionGeneration;
        Binding(String reportId, int contentRevision, String sessionId, String deviceId,
            long sessionGeneration) {
            this.reportId = reportId;
            this.contentRevision = contentRevision;
            this.sessionId = sessionId;
            this.deviceId = deviceId;
            this.sessionGeneration = sessionGeneration;
        }
        boolean matches(Binding current) {
            return current != null && contentRevision == current.contentRevision
                && sessionGeneration == current.sessionGeneration
                && Objects.equals(reportId, current.reportId)
                && Objects.equals(sessionId, current.sessionId)
                && Objects.equals(deviceId, current.deviceId);
        }
    }
    static final class Request {
        final long generation;
        final Binding binding;
        private final AdminMutableCredentials credentials;
        private char[][] dispatched;
        Request(long generation, Binding binding, char[] password, char[] totp) {
            this.generation = generation;
            this.binding = binding;
            credentials = new AdminMutableCredentials(password, totp);
        }
        synchronized char[][] takeCredentials() {
            dispatched = credentials.takeForDispatch();
            return dispatched;
        }
        synchronized void clearCredentials() {
            credentials.close();
            if (dispatched != null) {
                Arrays.fill(dispatched[0], '\0');
                Arrays.fill(dispatched[1], '\0');
                dispatched = null;
            }
        }
    }

    private final ImageDisposer<I> imageDisposer;
    private long generation;
    private Request request;
    private Future<?> task;
    private AdminOriginalEvidence evidence;
    private I image;
    private Runnable cancelExpiry;

    OriginalEvidenceCoordinator(ImageDisposer<I> imageDisposer) {
        this.imageDisposer = imageDisposer;
    }
    synchronized Request begin(Binding binding, char[] password, char[] totp) {
        clear();
        request = new Request(generation, binding, password, totp);
        return request;
    }
    synchronized void attachTask(Request expected, Future<?> task) {
        if (request == expected) this.task = task;
        else task.cancel(true);
    }
    synchronized void finishTask(Request expected) {
        if (request == expected) task = null;
    }
    synchronized boolean isCurrent(Request expected) {
        return expected != null && request == expected && expected.generation == generation;
    }
    synchronized boolean adopt(Request expected, Binding current, boolean accessAllowed,
        AdminOriginalEvidence loaded, I decoded, long now) {
        if (!accessAllowed || !isCurrent(expected) || !expected.binding.matches(current)
            || loaded == null || !loaded.matches(current.reportId, current.contentRevision,
                current.sessionId, current.deviceId, now)) {
            if (loaded != null) loaded.close();
            if (decoded != null) imageDisposer.destroy(decoded);
            return false;
        }
        evidence = loaded;
        image = decoded;
        expected.clearCredentials();
        // Completion is terminal: the same request cannot adopt another result.
        request = null;
        task = null;
        return true;
    }
    synchronized AdminOriginalEvidence evidence() { return evidence; }
    synchronized boolean isDisplaying(AdminOriginalEvidence expected) { return evidence == expected; }
    synchronized void scheduleExpiry(AdminOriginalEvidence expected, long nowEpochMs,
        ExpiryScheduler scheduler, Runnable clearDisplay) {
        if (cancelExpiry != null) cancelExpiry.run();
        // The delay is fixed when displayed. A later wall-clock rollback must not prolong it.
        // The display adapter detaches its view before clear() destroys the owned image.
        long delay = Math.max(1L, expected.expiresAtEpochMs() - nowEpochMs);
        cancelExpiry = scheduler.schedule(delay, () -> {
            if (isDisplaying(expected)) clearDisplay.run();
        });
    }
    synchronized boolean clear() {
        generation++;
        boolean wasLoading = task != null;
        if (task != null) task.cancel(true);
        task = null;
        if (request != null) request.clearCredentials();
        request = null;
        if (cancelExpiry != null) cancelExpiry.run();
        cancelExpiry = null;
        if (image != null) imageDisposer.destroy(image);
        image = null;
        if (evidence != null) evidence.close();
        evidence = null;
        return wasLoading;
    }
}
