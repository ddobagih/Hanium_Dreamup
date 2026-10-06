package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public final class AdminSecurityController implements AutoCloseable {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final class Snapshot {
        private final AdminSecurityState securityState;
        private final String authenticatedAdminId;
        private final String currentSessionId;
        private final List<AdminSecurityApi.SessionInfo> sessions;
        private final List<AdminSecurityApi.DeviceInfo> devices;
        private final long reauthenticatedUntilEpochMs;
        private final String stateVersion;
        private final String observedAt;
        private final AdminRecoveryCustodyState recoveryCustodyState;
        private final String recoveryCustodyAttestedAt;
        private final boolean accessSessionActive;
        private final boolean recoveryActive;

        private Snapshot(
            AdminSecurityState securityState,
            String authenticatedAdminId,
            String currentSessionId,
            List<AdminSecurityApi.SessionInfo> sessions,
            List<AdminSecurityApi.DeviceInfo> devices,
            long reauthenticatedUntilEpochMs,
            String stateVersion,
            String observedAt,
            AdminRecoveryCustodyState recoveryCustodyState,
            String recoveryCustodyAttestedAt,
            boolean accessSessionActive,
            boolean recoveryActive
        ) {
            this.securityState = securityState;
            this.authenticatedAdminId = authenticatedAdminId;
            this.currentSessionId = currentSessionId;
            this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
            this.devices = Collections.unmodifiableList(new ArrayList<>(devices));
            this.reauthenticatedUntilEpochMs = reauthenticatedUntilEpochMs;
            this.stateVersion = stateVersion;
            this.observedAt = observedAt;
            this.recoveryCustodyState = recoveryCustodyState;
            this.recoveryCustodyAttestedAt = recoveryCustodyAttestedAt;
            this.accessSessionActive = accessSessionActive;
            this.recoveryActive = recoveryActive;
        }

        public AdminSecurityState securityState() { return securityState; }
        public String authenticatedAdminId() { return authenticatedAdminId; }
        public String currentSessionId() { return currentSessionId; }
        public List<AdminSecurityApi.SessionInfo> sessions() { return sessions; }
        public List<AdminSecurityApi.DeviceInfo> devices() { return devices; }
        public long reauthenticatedUntilEpochMs() { return reauthenticatedUntilEpochMs; }
        public String stateVersion() { return stateVersion; }
        public String observedAt() { return observedAt; }
        public AdminRecoveryCustodyState recoveryCustodyState() { return recoveryCustodyState; }
        public String recoveryCustodyAttestedAt() { return recoveryCustodyAttestedAt; }
        public boolean isAccessSessionActive() { return accessSessionActive; }
        public boolean isRecoveryActive() { return recoveryActive; }
    }

    // Network work remains serialized, but never owns the UI/lifecycle monitor.
    private final ReentrantLock workLock = new ReentrantLock();
    private final Object lifecycleLock = new Object();
    private final ThreadLocal<LocalOperation> currentOperation = new ThreadLocal<>();
    private Snapshot publishedSnapshot;
    private long localGeneration;
    private boolean locallyLocked;
    private boolean closed;
    private boolean localClearPending;
    private final AdminRecoveryCustodyAttempt custodyAttempt;

    private final AdminSecurityApi api;
    private final AdminOperationsApi operationsApi;
    private final AdminReportRepository reportRepository;
    private final AdminIncidentRepository incidentRepository;
    private final AdminRawCollectionRepository rawCollectionRepository;
    private final AdminSecurityTelemetry.Recorder telemetry;
    private AdminSecurityState securityState = AdminSecurityState.SIGNED_OUT;
    private String accessToken;
    private String authenticatedAdminId;
    private String authenticatedDeviceId;
    private String recoveryToken;
    private String recoveryAdminId;
    private String currentSessionId;
    private List<AdminSecurityApi.SessionInfo> sessions = AdminJava8Collections.list();
    private List<AdminSecurityApi.DeviceInfo> devices = AdminJava8Collections.list();
    private volatile int deviceTotal;
    private volatile boolean devicesComplete = true;
    private long reauthenticatedUntilEpochMs;
    private AdminHighRiskActionGate.Binding pendingReconfirmation;
    private String stateVersion;
    private String observedAt;
    private AdminRecoveryCustodyState recoveryCustodyState;
    private String recoveryCustodyAttestedAt;

    public AdminSecurityController(AdminSecurityApi api) {
        this(api, null, null, null, AdminSecurityTelemetry.androidLogRecorder());
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminRecoveryCustodyAttempt.Store custodyAttemptStore
    ) {
        this(api, null, null, null, null,
            AdminSecurityTelemetry.androidLogRecorder(), custodyAttemptStore);
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminSecurityTelemetry.Recorder telemetry
    ) {
        this(api, null, null, null, telemetry);
    }

    public AdminSecurityController(AdminSecurityApi api, AdminOperationsApi operationsApi) {
        this(api, operationsApi, null, null, AdminSecurityTelemetry.androidLogRecorder());
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository
    ) {
        this(
            api,
            operationsApi,
            reportRepository,
            null,
            AdminSecurityTelemetry.androidLogRecorder()
        );
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository,
        AdminIncidentRepository incidentRepository
    ) {
        this(
            api,
            operationsApi,
            reportRepository,
            incidentRepository,
            AdminSecurityTelemetry.androidLogRecorder()
        );
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository,
        AdminIncidentRepository incidentRepository,
        AdminRawCollectionRepository rawCollectionRepository
    ) {
        this(
            api,
            operationsApi,
            reportRepository,
            incidentRepository,
            rawCollectionRepository,
            AdminSecurityTelemetry.androidLogRecorder()
        );
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminSecurityTelemetry.Recorder telemetry
    ) {
        this(api, operationsApi, null, null, telemetry);
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository,
        AdminSecurityTelemetry.Recorder telemetry
    ) {
        this(api, operationsApi, reportRepository, null, telemetry);
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository,
        AdminIncidentRepository incidentRepository,
        AdminSecurityTelemetry.Recorder telemetry
    ) {
        this(api, operationsApi, reportRepository, incidentRepository, null, telemetry);
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository,
        AdminIncidentRepository incidentRepository,
        AdminRawCollectionRepository rawCollectionRepository,
        AdminSecurityTelemetry.Recorder telemetry
    ) {
        this(api, operationsApi, reportRepository, incidentRepository,
            rawCollectionRepository, telemetry, new AdminRecoveryCustodyAttempt.MemoryStore());
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository,
        AdminIncidentRepository incidentRepository,
        AdminRawCollectionRepository rawCollectionRepository,
        AdminRecoveryCustodyAttempt.Store custodyAttemptStore
    ) {
        this(api, operationsApi, reportRepository, incidentRepository,
            rawCollectionRepository, AdminSecurityTelemetry.androidLogRecorder(),
            custodyAttemptStore);
    }

    private AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminReportRepository reportRepository,
        AdminIncidentRepository incidentRepository,
        AdminRawCollectionRepository rawCollectionRepository,
        AdminSecurityTelemetry.Recorder telemetry,
        AdminRecoveryCustodyAttempt.Store custodyAttemptStore
    ) {
        if (api == null) throw new IllegalArgumentException("api is required");
        if (telemetry == null) throw new IllegalArgumentException("telemetry is required");
        this.api = api;
        this.operationsApi = operationsApi;
        this.reportRepository = reportRepository;
        this.incidentRepository = incidentRepository;
        this.rawCollectionRepository = rawCollectionRepository;
        this.telemetry = telemetry;
        this.custodyAttempt = new AdminRecoveryCustodyAttempt(custodyAttemptStore);
        publishedSnapshot = currentSnapshot();
    }

    public Snapshot snapshot() {
        synchronized (lifecycleLock) {
            return locallyLocked ? emptySnapshot(AdminSecurityState.FAIL_CLOSED) : publishedSnapshot;
        }
    }

    public boolean isLocallyLocked() {
        synchronized (lifecycleLock) { return locallyLocked; }
    }

    public long localGeneration() {
        synchronized (lifecycleLock) { return localGeneration; }
    }

    public boolean isLocalGenerationCurrent(long expected) {
        synchronized (lifecycleLock) { return !closed && expected == localGeneration; }
    }

    public boolean hasRetainedSession() {
        synchronized (lifecycleLock) {
            return publishedSnapshot.isAccessSessionActive() || publishedSnapshot.isRecoveryActive();
        }
    }

    boolean hasControllerOwnedCredentials() {
        synchronized (lifecycleLock) {
            return accessToken != null || recoveryToken != null
                || authenticatedAdminId != null || authenticatedDeviceId != null
                || currentSessionId != null || recoveryAdminId != null
                || pendingReconfirmation != null;
        }
    }

    /** SAF is already bound to a session/report and revalidated before reading or writing. */
    public void suspendForBackground(boolean intentionalExternalActivity) {
        if (intentionalExternalActivity) return;
        synchronized (lifecycleLock) {
            localGeneration += 1L;
            locallyLocked = true;
        }
    }

    /** Reopens only the signed-out form, never an existing administrator session. */
    public void allowFreshAuthentication() {
        synchronized (lifecycleLock) {
            if (closed || hasRetainedSession()) {
                throw new IllegalStateException("retained session requires local user confirmation");
            }
            localGeneration += 1L;
            locallyLocked = false;
            publishedSnapshot = emptySnapshot(AdminSecurityState.SIGNED_OUT);
        }
    }

    /** Called only after successful, generation-bound device credential confirmation. */
    public void resumeLocalSession(long confirmedGeneration) throws IOException {
        try (LocalOperation ignored = beginResume(confirmedGeneration)) {
            clearReconfirmation();
            String token;
            boolean recoveryActive;
            synchronized (lifecycleLock) {
                requireCurrentOperation();
                token = accessToken;
                recoveryActive = recoveryToken != null;
            }
            if (token != null) {
                loadRemoteState(token, null);
            } else if (!recoveryActive) {
                throw new IllegalStateException("administrator session is unavailable");
            }
            synchronized (lifecycleLock) {
                requireCurrentOperation();
                ignored.resumeValidated = true;
            }
        }
    }

    private LocalOperation beginOperation(boolean authentication) {
        LocalOperation parent = currentOperation.get();
        if (parent != null) {
            requireCurrentOperation();
            return new LocalOperation(parent.generation, false, false, parent.resuming);
        }
        long generation;
        synchronized (lifecycleLock) {
            if (closed || locallyLocked) throw new IllegalStateException("administrator access is locally locked");
            generation = localGeneration;
        }
        return acquireOperation(generation, authentication, false);
    }

    private LocalOperation beginResume(long generation) {
        synchronized (lifecycleLock) {
            if (closed || !locallyLocked || generation != localGeneration) {
                throw new IllegalStateException("local confirmation is stale");
            }
        }
        return acquireOperation(generation, false, true);
    }

    private LocalOperation acquireOperation(long generation, boolean authentication, boolean resuming) {
        workLock.lock();
        LocalOperation operation = new LocalOperation(generation, true, authentication, resuming);
        currentOperation.set(operation);
        try {
            clearPendingLocalSessionIfNeeded();
            requireCurrentOperation();
            return operation;
        } catch (RuntimeException error) {
            currentOperation.remove();
            workLock.unlock();
            throw error;
        }
    }

    private void requireCurrentOperation() {
        LocalOperation operation = currentOperation.get();
        synchronized (lifecycleLock) {
            if (operation == null || closed || operation.generation != localGeneration
                || (locallyLocked && !operation.resuming)) {
                throw new IllegalStateException("administrator operation is no longer current");
            }
        }
    }

    private void discardLocalSession(boolean permanent) {
        synchronized (lifecycleLock) {
            localGeneration += 1L;
            locallyLocked = false;
            closed |= permanent;
            localClearPending = true;
            clearControllerSessionState();
            recoveryToken = null;
            recoveryAdminId = null;
            publishedSnapshot = emptySnapshot(AdminSecurityState.SIGNED_OUT);
        }
        // An active worker drains this on exit. Otherwise cleanup may be slow,
        // so never run it on the caller that just revoked local access.
        if (!workLock.isLocked()) {
            Thread cleanup = new Thread(() -> {
                workLock.lock();
                try { clearPendingLocalSessionIfNeeded(); }
                finally { workLock.unlock(); }
            }, "admin-local-session-cleanup");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    // Caller owns workLock. Never hold lifecycleLock across the client's cleanup.
    private void clearPendingLocalSessionIfNeeded() {
        while (true) {
            synchronized (lifecycleLock) {
                if (!localClearPending) return;
            }
            clearDiscardedSession();
        }
    }

    private void clearDiscardedSession() {
        long clearingGeneration;
        synchronized (lifecycleLock) { clearingGeneration = localGeneration; }
        api.clearLocalBinding();
        synchronized (lifecycleLock) {
            clearControllerSessionState();
            recoveryToken = null;
            recoveryAdminId = null;
            if (clearingGeneration == localGeneration) localClearPending = false;
        }
    }

    private static Snapshot emptySnapshot(AdminSecurityState state) {
        return new Snapshot(state, null, null, AdminJava8Collections.list(),
            AdminJava8Collections.list(), 0L, null, null, null, null, false, false);
    }

    private final class LocalOperation implements AutoCloseable {
        private final long generation;
        private final boolean owner;
        private final boolean authentication;
        private final boolean resuming;
        private boolean resumeValidated;

        private LocalOperation(long generation, boolean owner, boolean authentication, boolean resuming) {
            this.generation = generation;
            this.owner = owner;
            this.authentication = authentication;
            this.resuming = resuming;
        }

        @Override public void close() {
            if (!owner) return;
            boolean released = false;
            boolean authenticationCleared = false;
            try {
                while (true) {
                    synchronized (lifecycleLock) {
                        boolean stale = closed || generation != localGeneration;
                        if (stale) clearReconfirmation();
                        boolean clear = localClearPending
                            || (stale && authentication && !authenticationCleared);
                        if (!clear) {
                            if (stale && authentication) {
                                publishedSnapshot = emptySnapshot(AdminSecurityState.SIGNED_OUT);
                            } else if (!closed && generation == localGeneration) {
                                publishedSnapshot = currentSnapshot();
                                if (resuming && resumeValidated) locallyLocked = false;
                            }
                            // Publication and worker release are one lifecycle transition:
                            // a later discard can acquire workLock or be drained on entry.
                            currentOperation.remove();
                            workLock.unlock();
                            released = true;
                            return;
                        }
                    }
                    clearDiscardedSession();
                    authenticationCleared = true;
                    // Cleanup can overlap another stop/discard. Recheck before publishing.
                }
            } finally {
                if (!released) {
                    currentOperation.remove();
                    workLock.unlock();
                }
            }
        }
    }

    private Snapshot currentSnapshot() {
        return new Snapshot(
            securityState,
            authenticatedAdminId,
            currentSessionId,
            sessions,
            devices,
            reauthenticatedUntilEpochMs,
            stateVersion,
            observedAt,
            recoveryCustodyState,
            recoveryCustodyAttestedAt,
            accessToken != null,
            recoveryToken != null
        );
    }

    /** One API call of a login or recovery step (S18-20: String or mutable credentials). */
    @FunctionalInterface
    private interface CredentialCall<T> {
        T run() throws IOException;
    }

    public void login(
        String adminId,
        String password,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        loginWith(adminId, deviceId,
            () -> api.login(adminId, password, totpCode, deviceId, deviceLabel));
    }

    /**
     * S18-20: login from caller-owned mutable buffers. They go to the API as char[] and are
     * never turned into a String here; the caller zeroes them after this returns or throws.
     */
    public void login(
        String adminId,
        char[] password,
        char[] totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        loginWith(adminId, deviceId,
            () -> api.login(adminId, password, totpCode, deviceId, deviceLabel));
    }

    private void loginWith(
        String adminId,
        String deviceId,
        CredentialCall<AdminSecurityApi.LoginResult> loginCall
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(true)) {
            String correlationId = AdminSecurityTelemetry.newCorrelationId();
            securityState = AdminSecurityState.AUTHENTICATING;
            recoveryCustodyState = null;
            recoveryCustodyAttestedAt = null;
            recoveryToken = null;
            recoveryAdminId = null;
            boolean sessionIssued = false;
            try {
                AdminSecurityApi.LoginResult result = loginCall.run();
                requireCurrentOperation();
            requireUsableToken(result.accessToken(), "invalid_login_token");
                requireAuthenticatedState(result.securityState(), "invalid_login_state");
                requireCanonicalSessionId(result.currentSessionId(), "invalid_current_session_id");
                synchronized (lifecycleLock) {
                    requireCurrentOperation();
                    accessToken = result.accessToken();
                    authenticatedAdminId = adminId;
                    authenticatedDeviceId = deviceId;
                    recoveryToken = null;
                    recoveryAdminId = null;
                    currentSessionId = result.currentSessionId();
                    securityState = result.securityState();
                    reauthenticatedUntilEpochMs = 0L;
                    pendingReconfirmation = null;
                }
                sessionIssued = true;
                loadRemoteState(result.accessToken(), result.securityState());
                // Only a verified identity switch ends the previous local attempt. A failed
                // login may follow a server-committed attestation whose response was lost.
                custodyAttempt.discardIfDifferentBinding(adminId, deviceId);
                recordTelemetry(
                    AdminSecurityTelemetry.AUTH_SUCCEEDED,
                    AdminSecurityTelemetry.Severity.INFO,
                    correlationId,
                    "succeeded",
                    null
                );
            } catch (IOException | RuntimeException error) {
                if (!sessionIssued) {
                    accessToken = null;
                    authenticatedAdminId = null;
                    authenticatedDeviceId = null;
                    currentSessionId = null;
                }
                sessions = AdminJava8Collections.list();
                devices = AdminJava8Collections.list();
                clearDeviceTotals();
                reauthenticatedUntilEpochMs = 0L;
                pendingReconfirmation = null;
                stateVersion = null;
                observedAt = null;
                recoveryCustodyState = null;
                recoveryCustodyAttestedAt = null;
                securityState = AdminSecurityState.FAIL_CLOSED;
                recordTelemetry(
                    AdminSecurityTelemetry.AUTH_FAILED,
                    AdminSecurityTelemetry.Severity.WARN,
                    correlationId,
                    "failed",
                    error
                );
                throw error;
            }
        }
    }

    public void refresh() throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            String token = requireAccessToken();
            try {
                loadRemoteState(token, null);
                if (securityState != AdminSecurityState.NORMAL
                    || recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
                    clearReconfirmation();
                }
            } catch (IOException | RuntimeException error) {
                failClosed();
                throw error;
            }
        }
    }

    public boolean refreshAndValidateSafSaveSession(String expectedSessionId)
        throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                requireCanonicalSessionId(expectedSessionId, "invalid_expected_session_id");
                refresh();
                boolean valid = securityState == AdminSecurityState.NORMAL
                    && recoveryCustodyState == AdminRecoveryCustodyState.ATTESTED
                    && expectedSessionId.equals(currentSessionId)
                    && sessions.stream().filter(item ->
                        item.isCurrent()
                            && !item.isRevoked()
                            && expectedSessionId.equals(item.sessionId())
                    ).count() == 1L;
                if (!valid) failClosed();
                return valid;
            } catch (IOException | RuntimeException error) {
                failClosed();
                throw error;
            }
        }
    }

    public void reauthenticate(String password, String totpCode, long nowEpochMs) throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            clearReconfirmation();
            throw new IllegalArgumentException("exact reauthentication binding is required");
        }
    }

    public void reauthenticate(
        String password,
        String totpCode,
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            if (securityState != AdminSecurityState.NORMAL) throw new IllegalStateException("administrator access is not normal");
            if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
                throw new AdminRecoveryCustodyRequiredException();
            }
            if (action == null) throw new IllegalArgumentException("high-risk action is required");
            String token = requireAccessToken();
            String nonce = newNonce();
            try {
                AdminSecurityApi.ReauthenticationResult result = api.reauthenticate(
                    token,
                    password,
                    totpCode,
                    action.reauthenticationAction(),
                    action.method(),
                    action.path(),
                    nonce,
                    action.intentSha256()
                );
                long until = result.reauthenticatedUntilEpochMs();
                if (until <= nowEpochMs) throw new IOException("invalid reauthentication expiry");
                if (!action.reauthenticationAction().equals(result.action())
                    || !action.method().equals(result.method())
                    || !action.path().equals(result.path())) {
                    throw new IOException("reauthentication binding response does not match the request");
                }
                synchronized (lifecycleLock) {
                    requireCurrentOperation();
                    pendingReconfirmation = new AdminHighRiskActionGate.Binding(
                        action,
                        nonce,
                        until
                    );
                    reauthenticatedUntilEpochMs = until;
                }
            } catch (IOException | RuntimeException error) {
                clearReconfirmation();
                throw error;
            }
        }
    }

    private void reauthenticate(
        char[] password,
        char[] totpCode,
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            if (securityState != AdminSecurityState.NORMAL) throw new IllegalStateException("administrator access is not normal");
            if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
                throw new AdminRecoveryCustodyRequiredException();
            }
            if (action == null) throw new IllegalArgumentException("high-risk action is required");
            String token = requireAccessToken();
            String nonce = newNonce();
            try {
                AdminSecurityApi.ReauthenticationResult result = api.reauthenticate(
                    token,
                    password,
                    totpCode,
                    action.reauthenticationAction(),
                    action.method(),
                    action.path(),
                    nonce,
                    action.intentSha256()
                );
                long until = result.reauthenticatedUntilEpochMs();
                if (until <= nowEpochMs) throw new IOException("invalid reauthentication expiry");
                if (!action.reauthenticationAction().equals(result.action())
                    || !action.method().equals(result.method())
                    || !action.path().equals(result.path())) {
                    throw new IOException("reauthentication binding response does not match the request");
                }
                synchronized (lifecycleLock) {
                    requireCurrentOperation();
                    pendingReconfirmation = new AdminHighRiskActionGate.Binding(action, nonce, until);
                    reauthenticatedUntilEpochMs = until;
                }
            } catch (IOException | RuntimeException error) {
                clearReconfirmation();
                throw error;
            }
        }
    }

    public void revokeSession(String sessionId) throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            String token = requireAccessToken();
            requireCanonicalSessionId(sessionId, "invalid_session_id");
            boolean revokingCurrent = sessionId.equals(currentSessionId);
            if (revokingCurrent) {
                synchronized (lifecycleLock) {
                    requireCurrentOperation();
                    clearControllerSessionState();
                    publishedSnapshot = currentSnapshot();
                }
                // Keep only the captured token and HTTP binding until this revoke finishes.
                // Clearing the client's binding here would prevent the server revoke itself.
            }
            try {
                AdminSecurityApi.StateSnapshot state = api.revokeSession(token, sessionId);
                AdminSecurityState next = state.securityState();
                requireAuthenticatedState(next, "invalid_revoke_state");
                if (revokingCurrent) {
                    clearLocalSession();
                    return;
                }
                applyRemoteState(state, null);
                List<AdminSecurityApi.SessionInfo> remaining = new ArrayList<>();
                for (AdminSecurityApi.SessionInfo item : sessions) {
                    if (!item.sessionId().equals(sessionId)) remaining.add(item);
                }
                sessions = AdminJava8Collections.copyList(remaining);
                if (next != AdminSecurityState.NORMAL
                    || recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
                    clearReconfirmation();
                }
            } catch (IOException | RuntimeException error) {
                if (revokingCurrent) clearLocalSession();
                else failClosed();
                throw error;
            }
        }
    }

    public void attestRecoveryCustody(
        AdminSecurityApi.RecoveryMaterialKind materialKind
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            if (securityState != AdminSecurityState.NORMAL) {
                throw new IllegalStateException("administrator access is not normal");
            }
            String token = requireAccessToken();
            String adminId;
            String deviceId;
            String expectedVersion;
            synchronized (lifecycleLock) {
                requireCurrentOperation();
                adminId = authenticatedAdminId;
                deviceId = authenticatedDeviceId;
                expectedVersion = stateVersion;
            }
            AdminRecoveryCustodyAttempt.Snapshot pending = custodyAttempt.prepare(
                adminId, deviceId, materialKind, expectedVersion, newCustodyReference()
            );
            requireCurrentOperation();
            AdminSecurityApi.RecoveryCustodyAttestation attestation =
                new AdminSecurityApi.RecoveryCustodyAttestation(
                    pending.custodyReference(), pending.materialKind(),
                    AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE, true,
                    pending.expectedStateVersion()
                );
            try {
                AdminSecurityApi.StateSnapshot state = api.attestRecoveryCustody(token, attestation);
                applyRemoteState(state, AdminSecurityState.NORMAL);
                if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
                    throw new IOException("recovery custody attestation was not confirmed");
                }
                custodyAttempt.confirm(pending);
                clearReconfirmation();
            } catch (AdminSecurityApiException error) {
                if (error.code()
                    == AdminSecurityApiException.Code.CUSTODY_STATE_VERSION_CONFLICT) {
                    try {
                        custodyAttempt.abandon(pending);
                    } finally {
                        failClosed();
                    }
                } else {
                    failClosed();
                }
                throw error;
            } catch (IOException | RuntimeException error) {
                failClosed();
                throw error;
            }
        }
    }

    public AdminSecurityApi.RecoveryMaterialKind pendingRecoveryCustodyMaterialKind() {
        synchronized (lifecycleLock) {
            return authenticatedAdminId != null && authenticatedDeviceId != null
                ? custodyAttempt.pendingMaterialKindFor(
                    authenticatedAdminId, authenticatedDeviceId
                ) : null;
        }
    }

    /**
     * Reports another device lost after a password + fresh TOTP reconfirmation bound to that
     * device's exact report-lost path (S18-29). Credentials are wiped in every outcome. When
     * reconfirmation is impossible (e.g. recovery custody not attested), the emergency path is
     * recovery-code recovery, which revokes every existing session and device key.
     */
    public void reportLostDevice(
        String deviceId,
        char[] password,
        char[] totpCode,
        long nowEpochMs
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                reportLostDeviceWithReconfirmation(deviceId, password, totpCode, nowEpochMs);
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    private void reportLostDeviceWithReconfirmation(
        String deviceId,
        char[] password,
        char[] totpCode,
        long nowEpochMs
    ) throws IOException {
        {
            String token = requireAccessToken();
            if (deviceId != null && deviceId.equals(authenticatedDeviceId)) {
                throw new IllegalArgumentException("current device cannot be reported lost");
            }
            boolean activeRemoteDevice = false;
            for (AdminSecurityApi.DeviceInfo device : devices) {
                if (!device.deviceId().equals(deviceId)) continue;
                if (device.isCurrent()) {
                    throw new IllegalArgumentException("current device cannot be reported lost");
                }
                activeRemoteDevice = true;
            }
            if (!activeRemoteDevice) throw new IllegalArgumentException("active remote device key is required");
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.deviceReportLost(deviceId);
            reauthenticate(password, totpCode, operation, nowEpochMs);
            // Lost-device revocation is a security function, not a report workflow, so the
            // release-gated operational workflow flag does not apply.
            AdminHighRiskActionGate.Decision decision =
                consumeHighRiskAuthorization(operation, nowEpochMs, true);
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            try {
                AdminSecurityApi.StateSnapshot state =
                    api.reportLostDevice(token, deviceId, decision.requestHeaders());
                applyRemoteState(state, null);
                List<AdminSecurityApi.SessionInfo> remaining = new ArrayList<>();
                for (AdminSecurityApi.SessionInfo session : sessions) {
                    if (!session.deviceId().equals(deviceId)) remaining.add(session);
                }
                sessions = AdminJava8Collections.copyList(remaining);
                List<AdminSecurityApi.DeviceInfo> remainingDevices = new ArrayList<>();
                for (AdminSecurityApi.DeviceInfo device : devices) {
                    if (!device.deviceId().equals(deviceId)) remainingDevices.add(device);
                }
                // W20A-R7: the revoked device leaves the server total too; an unknown
                // total (legacy sessions-only inventory, -1) stays unknown and partial.
                int removedDevices = devices.size() - remainingDevices.size();
                devices = AdminJava8Collections.copyList(remainingDevices);
                if (deviceTotal >= 0) {
                    deviceTotal = Math.max(devices.size(), deviceTotal - removedDevices);
                    devicesComplete = deviceTotal == devices.size();
                }
                clearReconfirmation();
            } catch (IOException | RuntimeException error) {
                failClosed();
                throw error;
            }
        }
    }

    public void startRecovery(
        String adminId,
        String recoveryCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        startRecoveryWith(adminId,
            () -> api.startRecovery(adminId, recoveryCode, deviceId, deviceLabel));
    }

    /** S18-20: recovery start from a caller-owned mutable recovery code (see char[] login). */
    public void startRecovery(
        String adminId,
        char[] recoveryCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        startRecoveryWith(adminId,
            () -> api.startRecovery(adminId, recoveryCode, deviceId, deviceLabel));
    }

    private void startRecoveryWith(
        String adminId,
        CredentialCall<AdminSecurityApi.RecoveryStartResult> startCall
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(true)) {
            String correlationId = AdminSecurityTelemetry.newCorrelationId();
            clearLocalSession();
            recoveryToken = null;
            recoveryAdminId = null;
            try {
                AdminSecurityApi.RecoveryStartResult result = startCall.run();
                requireCurrentOperation();
            requireUsableToken(result.recoveryToken(), "invalid_recovery_token");
                if (result.securityState() != AdminSecurityState.RECOVERY_IN_PROGRESS) {
                    throw new IOException("invalid recovery state");
                }
                synchronized (lifecycleLock) {
                    requireCurrentOperation();
                    recoveryToken = result.recoveryToken();
                    recoveryAdminId = adminId;
                    securityState = AdminSecurityState.RECOVERY_IN_PROGRESS;
                }
                recordTelemetry(
                    AdminSecurityTelemetry.RECOVERY_START_SUCCEEDED,
                    AdminSecurityTelemetry.Severity.INFO,
                    correlationId,
                    "succeeded",
                    null
                );
            } catch (IOException | RuntimeException error) {
                recoveryToken = null;
                recoveryAdminId = null;
                securityState = AdminSecurityState.FAIL_CLOSED;
                recordTelemetry(
                    AdminSecurityTelemetry.RECOVERY_START_FAILED,
                    AdminSecurityTelemetry.Severity.WARN,
                    correlationId,
                    "failed",
                    error
                );
                throw error;
            }
        }
    }

    public void completeRecovery(
        String newPassword,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        completeRecoveryWith(deviceId,
            token -> api.completeRecovery(token, newPassword, totpCode, deviceId, deviceLabel));
    }

    /** S18-20: recovery completion from caller-owned mutable secrets (see char[] login). */
    public void completeRecovery(
        char[] newPassword,
        char[] totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        completeRecoveryWith(deviceId,
            token -> api.completeRecovery(token, newPassword, totpCode, deviceId, deviceLabel));
    }

    @FunctionalInterface
    private interface RecoveryCompletionCall {
        AdminSecurityApi.LoginResult run(String recoveryToken) throws IOException;
    }

    private void completeRecoveryWith(
        String deviceId,
        RecoveryCompletionCall completionCall
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(true)) {
            String correlationId = AdminSecurityTelemetry.newCorrelationId();
            String token;
            synchronized (lifecycleLock) {
                requireCurrentOperation();
                token = recoveryToken;
            }
            if (token == null) {
                IllegalStateException error = new IllegalStateException("recovery is not active");
                recordTelemetry(
                    AdminSecurityTelemetry.RECOVERY_COMPLETE_FAILED,
                    AdminSecurityTelemetry.Severity.WARN,
                    correlationId,
                    "failed",
                    error
                );
                throw error;
            }
            try {
                AdminSecurityApi.LoginResult result = completionCall.run(token);
                requireCurrentOperation();
            requireUsableToken(result.accessToken(), "invalid_recovery_access_token");
                if (result.securityState() != AdminSecurityState.NORMAL) throw new IOException("recovery did not return normal state");
                requireCanonicalSessionId(result.currentSessionId(), "invalid_recovery_session_id");
                synchronized (lifecycleLock) {
                    requireCurrentOperation();
                    accessToken = result.accessToken();
                    authenticatedAdminId = requireRecoveryAdminId();
                    authenticatedDeviceId = deviceId;
                    recoveryToken = null;
                    recoveryAdminId = null;
                    currentSessionId = result.currentSessionId();
                    securityState = AdminSecurityState.NORMAL;
                    reauthenticatedUntilEpochMs = 0L;
                    pendingReconfirmation = null;
                }
                loadRemoteState(result.accessToken(), AdminSecurityState.NORMAL);
                recordTelemetry(
                    AdminSecurityTelemetry.RECOVERY_COMPLETE_SUCCEEDED,
                    AdminSecurityTelemetry.Severity.INFO,
                    correlationId,
                    "succeeded",
                    null
                );
            } catch (IOException | RuntimeException error) {
                if (error instanceof AdminSecurityApiException apiError && apiError.requiresRecoveryRestart()) {
                    recoveryToken = null;
                    recoveryAdminId = null;
                }
                securityState = AdminSecurityState.FAIL_CLOSED;
                recordTelemetry(
                    AdminSecurityTelemetry.RECOVERY_COMPLETE_FAILED,
                    AdminSecurityTelemetry.Severity.WARN,
                    correlationId,
                    "failed",
                    error
                );
                throw error;
            }
        }
    }

    public AdminHighRiskActionGate.Decision highRiskDecision(
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        Snapshot state = snapshot();
        return AdminHighRiskActionGate.evaluate(
            action,
            state.securityState(),
            state.recoveryCustodyState(),
            state.reauthenticatedUntilEpochMs(),
            nowEpochMs,
            operationalWorkflowsEnabled
        );
    }

    public AdminOperationsApi.Result recordReviewDecision(
        String reportId,
        AdminReportDecision decision,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireOperationsApi().recordReviewDecision(
                requireOperationalSession(operationalWorkflowsEnabled),
                reportId,
                decision
            );
        }
    }

    public AdminOriginalEvidence loadAdminOriginalEvidence(
        String reportId,
        int expectedContentRevision,
        String reason,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.originalEvidence(reportId);
            try {
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireOperationsApi().loadOriginalEvidence(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    reportId,
                    expectedContentRevision,
                    reason,
                    decision.requestHeaders()
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    /**
     * S18-36: resolves a restored review attempt by its decision_id before any new decision.
     * {@link AdminOperationsApi.Result#mutationId()} is the decision id when it was recorded
     * and null when the server has no such decision.
     */
    public AdminOperationsApi.Result lookupReviewDecision(
        String reportId,
        String decisionId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireOperationsApi().lookupReviewDecision(
                requireOperationalSession(operationalWorkflowsEnabled),
                reportId,
                decisionId
            );
        }
    }

    /** S18-3: confirms this device's PENDING key in the current (ACTIVE key) session. */
    public AdminSecurityApi.DeviceKeyRotationResult confirmDeviceKeyRotation(
        AdminSecurityApi.DeviceKeyRotationRequest request
    ) throws IOException {
        try (LocalOperation ignored = beginOperation(false)) {
            if (securityState != AdminSecurityState.NORMAL) {
                throw new IllegalStateException("administrator access is not normal");
            }
            return api.confirmDeviceKeyRotation(requireAccessToken(), request);
        }
    }

    public AdminOperationsApi.Result readReviewDecisions(
        String reportId,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireOperationsApi().readReviewDecisions(
                requireOperationalSession(operationalWorkflowsEnabled),
                reportId,
                cursor
            );
        }
    }

    public AdminOperationsApi.Result readReviewDecisions(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return readReviewDecisions(reportId, null, operationalWorkflowsEnabled);
        }
    }

    public AdminOperationsApi.Result readRecentReviewDecisions(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireOperationsApi().readRecentReviewDecisions(
                requireOperationalSession(operationalWorkflowsEnabled), reportId
            );
        }
    }

    public AdminOperationsApi.Result recordDelivery(
        String reportId,
        AdminInstitutionDelivery delivery,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireOperationsApi().recordDelivery(
                requireOperationalSession(operationalWorkflowsEnabled),
                reportId,
                delivery
            );
        }
    }

    /**
     * S18-40: HIGH-risk correction of the latest effective delivery event. Reauthentication binds
     * the exact action/method/path, the one-time reconfirmation is consumed here, and the
     * credentials are cleared whether or not the correction succeeds (no automatic retry).
     */
    public AdminOperationsApi.Result correctDelivery(
        String reportId,
        AdminDeliveryCorrection correction,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean resendOfUncertainAttempt,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                if (correction == null) {
                    throw new IllegalArgumentException("delivery correction is required");
                }
                AdminHighRiskActionGate.Operation operation =
                    AdminHighRiskActionGate.deliveryCorrection(reportId);
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireOperationsApi().recordDeliveryCorrection(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    reportId,
                    correction,
                    decision.requestHeaders(),
                    resendOfUncertainAttempt
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminOperationsApi.Result readDeliveries(
        String reportId,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireOperationsApi().readDeliveries(
                requireOperationalSession(operationalWorkflowsEnabled),
                reportId,
                cursor
            );
        }
    }

    public AdminOperationsApi.Result readDeliveries(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return readDeliveries(reportId, null, operationalWorkflowsEnabled);
        }
    }

    public AdminOperationsApi.Result readRecentDeliveries(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireOperationsApi().readRecentDeliveries(
                requireOperationalSession(operationalWorkflowsEnabled), reportId
            );
        }
    }

    public AdminReportModels.Page listAdminReports(
        AdminReportModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().list(
                requireOperationalSession(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminReportModels.Detail getAdminReportDetail(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().detail(
                requireOperationalSession(operationalWorkflowsEnabled),
                reportId
            );
        }
    }

    public AdminReportModels.StatusSnapshot updateAdminReportStatus(
        String reportId,
        String nextStatus,
        int expectedVersion,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                return updateAdminReportStatus(
                    reportId,
                    nextStatus,
                    expectedVersion,
                    password,
                    totpCode,
                    nowEpochMs,
                    operationalWorkflowsEnabled,
                    () -> true
                );
            } catch (AdminReportWorkflowController.StatusDispatchCancelledException impossible) {
                throw new IllegalStateException("unconditional report status dispatch was cancelled", impossible);
            }
        }
    }

    public AdminReportModels.StatusSnapshot updateAdminReportStatus(
        String reportId,
        String nextStatus,
        int expectedVersion,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled,
        AdminReportWorkflowController.StatusDispatch dispatch
    ) throws IOException, GeneralSecurityException,
        AdminReportWorkflowController.StatusDispatchCancelledException {
        try (LocalOperation ignored = beginOperation(false)) {
            if (dispatch == null) throw new IllegalArgumentException("status dispatch is required");
            try {
                AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.bindBody(AdminHighRiskActionGate.reportStatus(reportId),
                        AdminReportHttpClient.statusBody(nextStatus, expectedVersion));
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireReportRepository().updateStatus(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    reportId,
                    nextStatus,
                    expectedVersion,
                    decision.requestHeaders(),
                    dispatch
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminDeliveryPackage createAdminDeliveryPackage(
        AdminDeliveryPackage.Eligibility eligibility,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                if (eligibility == null) {
                    throw new IllegalArgumentException("package eligibility is required");
                }
                String reportId = eligibility.reportId();
                AdminHighRiskActionGate.Operation operation =
                    AdminHighRiskActionGate.deliveryPackage(reportId);
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireReportRepository().createDeliveryPackage(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    eligibility,
                    decision.requestHeaders()
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminDeliveryPackage.Proof getAdminDeliveryPackageProof(
        String reportId,
        int packageRevision,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().deliveryPackageProof(
                requireOperationalSession(operationalWorkflowsEnabled),
                reportId,
                packageRevision
            );
        }
    }

    public AdminAuditModels.Page listAdminAudits(
        AdminAuditModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().audits(
                requireOperationalSession(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminIncidentModels.Page listAdminIncidents(
        AdminIncidentModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireIncidentRepository().list(
                requireOperationalSession(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminIncidentModels.Detail getAdminIncidentDetail(
        String incidentId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireIncidentRepository().detail(
                requireOperationalSession(operationalWorkflowsEnabled),
                incidentId
            );
        }
    }

    public AdminIncidentModels.HistoryPage getAdminIncidentHistory(
        String incidentId,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireIncidentRepository().history(
                requireOperationalSession(operationalWorkflowsEnabled),
                incidentId,
                cursor
            );
        }
    }

    public AdminIncidentModels.StatusSnapshot updateAdminIncidentStatus(
        String incidentId,
        AdminIncidentModels.StatusRequest request,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                AdminHighRiskActionGate.Operation operation =
                    AdminHighRiskActionGate.bindBody(AdminHighRiskActionGate.incidentStatus(incidentId),
                        AdminIncidentHttpClient.statusBody(request));
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireIncidentRepository().updateStatus(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    incidentId,
                    request,
                    decision.requestHeaders()
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminRawCollectionModels.Page listAdminRawQuarantine(
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.bindBody(AdminHighRiskActionGate.rawCollectionList(), new byte[0]);
            try {
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireRawCollectionRepository().listQuarantine(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    decision.requestHeaders()
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminRawCollectionModels.PurposeDecisionReceipt
        decideAdminRawCollectionPurpose(
            AdminRawCollectionModels.Summary source,
            AdminRawCollectionModels.PurposeDecisionRequest request,
            char[] password,
            char[] totpCode,
            long nowEpochMs,
            boolean operationalWorkflowsEnabled
        ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                if (source == null) throw new IllegalArgumentException("decision source is required");
                AdminHighRiskActionGate.Operation operation =
                    AdminHighRiskActionGate.bindBody(AdminHighRiskActionGate.rawCollectionPurposeDecision(source.collectionId()),
                        AdminRawCollectionHttpClient.purposeBody(request));
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireRawCollectionRepository().decidePurpose(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    source,
                    request,
                    decision.requestHeaders()
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminRawCollectionModels.LegalHoldReceipt
        recordAdminRawCollectionLegalHold(
            String collectionId,
            AdminRawCollectionModels.LegalHoldRequest request,
            char[] password,
            char[] totpCode,
            long nowEpochMs,
            boolean operationalWorkflowsEnabled
        ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                AdminHighRiskActionGate.Operation operation =
                    AdminHighRiskActionGate.bindBody(AdminHighRiskActionGate.rawCollectionLegalHold(collectionId),
                        AdminRawCollectionHttpClient.legalHoldBody(request));
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireRawCollectionRepository().recordLegalHold(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    collectionId,
                    request,
                    decision.requestHeaders()
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminReportRequestModels.Page listAdminReportRequests(
        AdminReportRequestModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().listRequests(
                requireOperationalSession(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminReportRequestModels.Detail getAdminReportRequestDetail(
        String requestId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().requestDetail(
                requireOperationalSession(operationalWorkflowsEnabled),
                requestId
            );
        }
    }

    public AdminReportRequestModels.StatusSnapshot updateAdminReportRequestStatus(
        String requestId,
        String requestType,
        String nextStatus,
        int expectedVersion,
        String publicResponse,
        String internalNote,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            try {
                AdminHighRiskActionGate.Operation operation =
                    AdminHighRiskActionGate.bindBody(AdminHighRiskActionGate.reportRequestStatus(requestId),
                        AdminReportHttpClient.requestStatusBody(requestType, nextStatus, expectedVersion, publicResponse, internalNote));
                reauthenticate(password, totpCode, operation, nowEpochMs);
                AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
                    operation,
                    nowEpochMs,
                    operationalWorkflowsEnabled
                );
                if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
                return requireReportRepository().updateRequestStatus(
                    requireOperationalSession(operationalWorkflowsEnabled),
                    requestId,
                    requestType,
                    nextStatus,
                    expectedVersion,
                    publicResponse,
                    internalNote,
                    decision.requestHeaders()
                );
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                if (totpCode != null) Arrays.fill(totpCode, '\0');
            }
        }
    }

    public AdminExternalCopyDeletionModels.Page listAdminExternalCopyDeletions(
        AdminExternalCopyDeletionModels.Filter filter,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().listExternalCopyDeletions(
                requireOperationalSession(operationalWorkflowsEnabled),
                filter,
                cursor
            );
        }
    }

    public AdminExternalCopyDeletionModels.Item recordAdminExternalCopyDeletion(
        AdminExternalCopyDeletionModels.EventCommand command,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (LocalOperation ignored = beginOperation(false)) {
            return requireReportRepository().recordExternalCopyDeletion(
                requireOperationalSession(operationalWorkflowsEnabled),
                command
            );
        }
    }

    public AdminHighRiskActionGate.Decision consumeHighRiskAuthorization(
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        try (LocalOperation ignored = beginOperation(false)) {
            AdminHighRiskActionGate.Binding binding = pendingReconfirmation;
            clearReconfirmation();
            AdminHighRiskActionGate.Decision decision = AdminHighRiskActionGate.evaluate(
                action,
                securityState,
                recoveryCustodyState,
                binding,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            return decision;
        }
    }

    public void signOutLocal() {
        discardLocalSession(false);
    }

    /**
     * S18-18: the local lock and the controller's secret references are dropped first and
     * immediately; only then are the clients' in-flight requests cancelled. Neither step waits
     * for the network or the worker, and no server session revoke is sent from here (a logout
     * is the separate, explicit revoke operation).
     */
    @Override
    public void close() {
        discardLocalSession(true);
        bestEffortCancel(api::cancelInFlightRequests);
        if (reportRepository != null) bestEffortCancel(reportRepository::cancelInFlightRequests);
        if (incidentRepository != null) bestEffortCancel(incidentRepository::cancelInFlightRequests);
        if (operationsApi != null) bestEffortCancel(operationsApi::cancelInFlightRequests);
        if (rawCollectionRepository != null) {
            bestEffortCancel(rawCollectionRepository::cancelInFlightRequests);
        }
    }

    private static void bestEffortCancel(Runnable cancellation) {
        try {
            cancellation.run();
        } catch (RuntimeException ignored) {
            // Best-effort cancellation must never undo or delay the local lock above.
        }
    }

    private String requireAccessToken() {
        synchronized (lifecycleLock) {
            requireCurrentOperation();
            if (accessToken == null) throw new IllegalStateException("administrator session is unavailable");
            return accessToken;
        }
    }

    private AdminOperationsApi requireOperationsApi() {
        if (operationsApi == null) throw new IllegalStateException("administrator operations are unavailable");
        return operationsApi;
    }

    private AdminReportRepository requireReportRepository() {
        if (reportRepository == null) {
            throw new IllegalStateException("administrator report reads are unavailable");
        }
        return reportRepository;
    }

    private AdminIncidentRepository requireIncidentRepository() {
        if (incidentRepository == null) {
            throw new IllegalStateException("administrator incident operations are unavailable");
        }
        return incidentRepository;
    }

    private AdminRawCollectionRepository requireRawCollectionRepository() {
        if (rawCollectionRepository == null) {
            throw new IllegalStateException("administrator raw collection review is unavailable");
        }
        return rawCollectionRepository;
    }

    private AdminOperationsApi.SessionContext requireOperationalSession(boolean operationalWorkflowsEnabled) {
        synchronized (lifecycleLock) {
            requireCurrentOperation();
            if (!operationalWorkflowsEnabled) throw new IllegalStateException("operational workflows are locked");
            if (securityState != AdminSecurityState.NORMAL) {
                throw new IllegalStateException("administrator access is not normal");
            }
            if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
                throw new AdminRecoveryCustodyRequiredException();
            }
            if (accessToken == null || authenticatedAdminId == null
                || currentSessionId == null || authenticatedDeviceId == null) {
                throw new IllegalStateException("administrator session binding is unavailable");
            }
            return new AdminOperationsApi.SessionContext(
                accessToken,
                authenticatedAdminId,
                currentSessionId,
                authenticatedDeviceId
            );
        }
    }

    private String requireRecoveryAdminId() throws IOException {
        if (recoveryAdminId == null) throw new IOException("recovery administrator identity is unavailable");
        return recoveryAdminId;
    }

    private void failClosed() {
        securityState = AdminSecurityState.FAIL_CLOSED;
        sessions = AdminJava8Collections.list();
        devices = AdminJava8Collections.list();
        clearDeviceTotals();
        clearReconfirmation();
        stateVersion = null;
        observedAt = null;
        recoveryCustodyState = null;
        recoveryCustodyAttestedAt = null;
    }

    // The totals describe the device list, so they are cleared with it (W20A-R7).
    private void clearDeviceTotals() {
        deviceTotal = 0;
        devicesComplete = true;
    }

    private void clearLocalSession() {
        api.clearLocalBinding();
        synchronized (lifecycleLock) { clearControllerSessionState(); }
    }

    // Only controller-owned references are cleared here. Client binding cleanup
    // stays behind workLock so it cannot invalidate an in-flight API call.
    private void clearControllerSessionState() {
        accessToken = null;
        authenticatedAdminId = null;
        authenticatedDeviceId = null;
        currentSessionId = null;
        sessions = AdminJava8Collections.list();
        devices = AdminJava8Collections.list();
        clearDeviceTotals();
        clearReconfirmation();
        stateVersion = null;
        observedAt = null;
        recoveryCustodyState = null;
        recoveryCustodyAttestedAt = null;
        securityState = AdminSecurityState.SIGNED_OUT;
    }

    private void loadRemoteState(String token, AdminSecurityState expectedState) throws IOException {
        requireCurrentOperation();
        AdminSecurityApi.StateSnapshot state = api.getState(token);
        AdminSecurityState next = state.securityState();
        requireAuthenticatedState(next, "invalid_remote_security_state");
        if (expectedState != null && expectedState != next) throw new IOException("inconsistent remote security state");
        requireCurrentOperation();
        AdminSecurityApi.DeviceInventory inventory = api.getCompleteDeviceInventory(
            token, this::requireCurrentOperation
        );
        String expectedSessionId;
        String expectedDeviceId;
        synchronized (lifecycleLock) {
            requireCurrentOperation();
            expectedSessionId = currentSessionId;
            expectedDeviceId = authenticatedDeviceId;
        }
        List<AdminSecurityApi.SessionInfo> updatedSessions = inventory.sessions();
        List<AdminSecurityApi.DeviceInfo> updatedDevices = inventory.devices();
        for (AdminSecurityApi.SessionInfo item : updatedSessions) {
            requiredAwareRfc3339(item.lastSeenAt(), "invalid_session_last_seen_at");
        }
        long currentMatches = updatedSessions.stream()
            .filter(item -> item.isCurrent() && !item.isRevoked() && item.sessionId().equals(expectedSessionId))
            .count();
        if (currentMatches != 1L) throw new IOException("current administrator session binding is invalid");
        Set<String> deviceIds = new HashSet<>();
        int currentDeviceCount = 0;
        for (AdminSecurityApi.DeviceInfo item : updatedDevices) {
            String deviceId = item.deviceId();
            if (deviceId == null
                || !deviceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
                || !deviceIds.add(deviceId)) {
                throw new IOException("invalid administrator device inventory");
            }
            if (item.isCurrent()) {
                currentDeviceCount += 1;
                if (!deviceId.equals(expectedDeviceId)) {
                    throw new IOException("current administrator device binding is invalid");
                }
            }
        }
        if (currentDeviceCount != 1) throw new IOException("current administrator device binding is invalid");
        applyRemoteState(state, expectedState);
        sessions = AdminJava8Collections.copyList(updatedSessions);
        devices = AdminJava8Collections.copyList(updatedDevices);
        deviceTotal = inventory.deviceTotal();
        devicesComplete = inventory.isDevicesComplete();
    }

    /**
     * Active trusted devices on the server; may exceed the listed devices (S18-25).
     * -1 means the server did not report a total (legacy sessions-only inventory).
     */
    public int deviceTotal() { return deviceTotal; }

    /** False when the server's capped device list omits some active devices. */
    public boolean devicesComplete() { return devicesComplete; }

    /**
     * Notice for a partial device list, or null when every active device is listed.
     * An unknown total (-1) must not be rendered as "N대 중 N대" (W20A-R7).
     */
    public static String partialDeviceListNotice(
        int deviceTotal,
        int listedDevices,
        boolean devicesComplete
    ) {
        if (devicesComplete) return null;
        if (deviceTotal < 0) {
            return "기기 목록이 일부만 표시되었을 수 있습니다: 전체 개수 확인 불가, 표시된 기기 "
                + listedDevices + "대. 분실 기기가 보이지 않으면 서버 운영 절차로 확인하세요.";
        }
        return "기기 목록이 일부만 표시되었습니다: 활성 장치 키 " + deviceTotal
            + "대 중 " + listedDevices + "대. 분실 기기가 보이지 않으면 서버 운영 절차로 확인하세요.";
    }

    private void applyRemoteState(
        AdminSecurityApi.StateSnapshot state,
        AdminSecurityState expectedState
    ) throws IOException {
        if (state == null) throw new IOException("missing remote security state");
        AdminSecurityState next = state.securityState();
        requireAuthenticatedState(next, "invalid_remote_security_state");
        if (expectedState != null && expectedState != next) {
            throw new IOException("inconsistent remote security state");
        }
        AdminRecoveryCustodyState custodyState = state.recoveryCustodyState();
        String custodyAttestedAt = state.recoveryCustodyAttestedAt();
        if (custodyState == null
            || (custodyState == AdminRecoveryCustodyState.ATTESTED) != (custodyAttestedAt != null)) {
            throw new IOException("invalid recovery custody state");
        }
        securityState = next;
        stateVersion = requiredMetadata(state.stateVersion(), "invalid_state_version");
        observedAt = requiredAwareRfc3339(state.observedAt(), "invalid_state_observed_at");
        recoveryCustodyState = custodyState;
        recoveryCustodyAttestedAt = custodyAttestedAt == null
            ? null
            : requiredAwareRfc3339(
                custodyAttestedAt,
                "invalid_recovery_custody_attested_at"
            );
    }

    private static void requireAuthenticatedState(AdminSecurityState state, String reason) throws IOException {
        if (state != AdminSecurityState.NORMAL && state != AdminSecurityState.RECOVERY_REQUIRED) {
            throw new IOException(reason);
        }
    }

    private static void requireUsableToken(String token, String reason) throws IOException {
        if (token == null || token.length() < 32 || token.length() > 512 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException(reason);
        }
    }

    private static void requireCanonicalSessionId(String value, String reason) throws IOException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IOException(reason);
        } catch (NullPointerException | IllegalArgumentException error) {
            throw new IOException(reason, error);
        }
    }

    private static String requiredMetadata(String value, String reason) throws IOException {
        if (value == null || value.trim().isEmpty() || value.length() > 128 || value.chars().anyMatch(character -> character < 0x20)) {
            throw new IOException(reason);
        }
        return value;
    }

    private static String requiredAwareRfc3339(String value, String reason) throws IOException {
        String checked = requiredMetadata(value, reason);
        if (!checked.matches(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
                + "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})"
        )) {
            throw new IOException(reason);
        }
        try {
            OffsetDateTime.parse(checked, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return checked;
        } catch (DateTimeParseException error) {
            throw new IOException(reason, error);
        }
    }

    private void clearReconfirmation() {
        reauthenticatedUntilEpochMs = 0L;
        pendingReconfirmation = null;
    }

    private void recordTelemetry(
        String eventName,
        AdminSecurityTelemetry.Severity severity,
        String correlationId,
        String outcome,
        Throwable error
    ) {
        try {
            telemetry.record(new AdminSecurityTelemetry.Event(
                eventName,
                severity,
                correlationId,
                outcome,
                error == null ? null : AdminSecurityTelemetry.failureCode(error)
            ));
        } catch (RuntimeException ignored) {
            // Telemetry must never change authentication or recovery behavior.
        }
    }

    private static String newNonce() {
        byte[] value = new byte[16];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String newCustodyReference() {
        byte[] value = new byte[32];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
