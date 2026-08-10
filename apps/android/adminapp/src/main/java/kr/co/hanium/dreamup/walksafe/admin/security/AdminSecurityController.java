package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class AdminSecurityController implements AutoCloseable {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final class Snapshot {
        private final AdminSecurityState securityState;
        private final String currentSessionId;
        private final List<AdminSecurityApi.SessionInfo> sessions;
        private final long reauthenticatedUntilEpochMs;
        private final String stateVersion;
        private final String observedAt;
        private final boolean accessSessionActive;
        private final boolean recoveryActive;

        private Snapshot(
            AdminSecurityState securityState,
            String currentSessionId,
            List<AdminSecurityApi.SessionInfo> sessions,
            long reauthenticatedUntilEpochMs,
            String stateVersion,
            String observedAt,
            boolean accessSessionActive,
            boolean recoveryActive
        ) {
            this.securityState = securityState;
            this.currentSessionId = currentSessionId;
            this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
            this.reauthenticatedUntilEpochMs = reauthenticatedUntilEpochMs;
            this.stateVersion = stateVersion;
            this.observedAt = observedAt;
            this.accessSessionActive = accessSessionActive;
            this.recoveryActive = recoveryActive;
        }

        public AdminSecurityState securityState() { return securityState; }
        public String currentSessionId() { return currentSessionId; }
        public List<AdminSecurityApi.SessionInfo> sessions() { return sessions; }
        public long reauthenticatedUntilEpochMs() { return reauthenticatedUntilEpochMs; }
        public String stateVersion() { return stateVersion; }
        public String observedAt() { return observedAt; }
        public boolean isAccessSessionActive() { return accessSessionActive; }
        public boolean isRecoveryActive() { return recoveryActive; }
    }

    private final AdminSecurityApi api;
    private final AdminOperationsApi operationsApi;
    private final AdminSecurityTelemetry.Recorder telemetry;
    private AdminSecurityState securityState = AdminSecurityState.SIGNED_OUT;
    private String accessToken;
    private String authenticatedAdminId;
    private String authenticatedDeviceId;
    private String recoveryToken;
    private String recoveryAdminId;
    private String currentSessionId;
    private List<AdminSecurityApi.SessionInfo> sessions = List.of();
    private long reauthenticatedUntilEpochMs;
    private AdminHighRiskActionGate.Binding pendingReconfirmation;
    private String stateVersion;
    private String observedAt;

    public AdminSecurityController(AdminSecurityApi api) {
        this(api, null, AdminSecurityTelemetry.androidLogRecorder());
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminSecurityTelemetry.Recorder telemetry
    ) {
        this(api, null, telemetry);
    }

    public AdminSecurityController(AdminSecurityApi api, AdminOperationsApi operationsApi) {
        this(api, operationsApi, AdminSecurityTelemetry.androidLogRecorder());
    }

    public AdminSecurityController(
        AdminSecurityApi api,
        AdminOperationsApi operationsApi,
        AdminSecurityTelemetry.Recorder telemetry
    ) {
        if (api == null) throw new IllegalArgumentException("api is required");
        if (telemetry == null) throw new IllegalArgumentException("telemetry is required");
        this.api = api;
        this.operationsApi = operationsApi;
        this.telemetry = telemetry;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            securityState,
            currentSessionId,
            sessions,
            reauthenticatedUntilEpochMs,
            stateVersion,
            observedAt,
            accessToken != null,
            recoveryToken != null
        );
    }

    public synchronized void login(
        String adminId,
        String password,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        String correlationId = AdminSecurityTelemetry.newCorrelationId();
        securityState = AdminSecurityState.AUTHENTICATING;
        recoveryToken = null;
        recoveryAdminId = null;
        boolean sessionIssued = false;
        try {
            AdminSecurityApi.LoginResult result = api.login(adminId, password, totpCode, deviceId, deviceLabel);
            requireUsableToken(result.accessToken(), "invalid_login_token");
            requireAuthenticatedState(result.securityState(), "invalid_login_state");
            requireCanonicalSessionId(result.currentSessionId(), "invalid_current_session_id");
            accessToken = result.accessToken();
            authenticatedAdminId = adminId;
            authenticatedDeviceId = deviceId;
            recoveryToken = null;
            recoveryAdminId = null;
            currentSessionId = result.currentSessionId();
            securityState = result.securityState();
            reauthenticatedUntilEpochMs = 0L;
            pendingReconfirmation = null;
            sessionIssued = true;
            loadRemoteState(result.accessToken(), result.securityState());
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
            sessions = List.of();
            reauthenticatedUntilEpochMs = 0L;
            pendingReconfirmation = null;
            stateVersion = null;
            observedAt = null;
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

    public synchronized void refresh() throws IOException {
        String token = requireAccessToken();
        try {
            loadRemoteState(token, null);
            if (securityState != AdminSecurityState.NORMAL) clearReconfirmation();
        } catch (IOException | RuntimeException error) {
            failClosed();
            throw error;
        }
    }

    public synchronized void reauthenticate(String password, String totpCode, long nowEpochMs) throws IOException {
        clearReconfirmation();
        throw new IllegalArgumentException("exact reauthentication binding is required");
    }

    public synchronized void reauthenticate(
        String password,
        String totpCode,
        String action,
        String method,
        String path,
        long nowEpochMs
    ) throws IOException {
        if (securityState != AdminSecurityState.NORMAL) throw new IllegalStateException("administrator access is not normal");
        String token = requireAccessToken();
        String nonce = newNonce();
        try {
            AdminSecurityApi.ReauthenticationResult result = api.reauthenticate(
                token,
                password,
                totpCode,
                action,
                method,
                path,
                nonce
            );
            long until = result.reauthenticatedUntilEpochMs();
            if (until <= nowEpochMs) throw new IOException("invalid reauthentication expiry");
            if (!action.equals(result.action())
                || !method.equals(result.method())
                || !path.equals(result.path())) {
                throw new IOException("reauthentication binding response does not match the request");
            }
            pendingReconfirmation = new AdminHighRiskActionGate.Binding(
                action,
                method,
                path,
                nonce,
                until
            );
            reauthenticatedUntilEpochMs = until;
        } catch (IOException | RuntimeException error) {
            clearReconfirmation();
            throw error;
        }
    }

    public synchronized void revokeSession(String sessionId) throws IOException {
        String token = requireAccessToken();
        requireCanonicalSessionId(sessionId, "invalid_session_id");
        boolean revokingCurrent = sessionId.equals(currentSessionId);
        try {
            AdminSecurityApi.StateSnapshot state = api.revokeSession(token, sessionId);
            AdminSecurityState next = state.securityState();
            requireAuthenticatedState(next, "invalid_revoke_state");
            if (revokingCurrent) {
                clearLocalSession();
                return;
            }
            securityState = next;
            stateVersion = requiredMetadata(state.stateVersion(), "invalid_revoke_state_version");
            observedAt = requiredMetadata(state.observedAt(), "invalid_revoke_observed_at");
            List<AdminSecurityApi.SessionInfo> remaining = new ArrayList<>();
            for (AdminSecurityApi.SessionInfo item : sessions) {
                if (!item.sessionId().equals(sessionId)) remaining.add(item);
            }
            sessions = List.copyOf(remaining);
            if (next != AdminSecurityState.NORMAL) clearReconfirmation();
        } catch (IOException | RuntimeException error) {
            if (revokingCurrent) clearLocalSession();
            else failClosed();
            throw error;
        }
    }

    public synchronized void startRecovery(
        String adminId,
        String recoveryCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        String correlationId = AdminSecurityTelemetry.newCorrelationId();
        clearLocalSession();
        recoveryToken = null;
        recoveryAdminId = null;
        try {
            AdminSecurityApi.RecoveryStartResult result = api.startRecovery(
                adminId,
                recoveryCode,
                deviceId,
                deviceLabel
            );
            requireUsableToken(result.recoveryToken(), "invalid_recovery_token");
            if (result.securityState() != AdminSecurityState.RECOVERY_IN_PROGRESS) {
                throw new IOException("invalid recovery state");
            }
            recoveryToken = result.recoveryToken();
            recoveryAdminId = adminId;
            securityState = AdminSecurityState.RECOVERY_IN_PROGRESS;
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

    public synchronized void completeRecovery(
        String newPassword,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        String correlationId = AdminSecurityTelemetry.newCorrelationId();
        String token = recoveryToken;
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
            AdminSecurityApi.LoginResult result = api.completeRecovery(
                token,
                newPassword,
                totpCode,
                deviceId,
                deviceLabel
            );
            requireUsableToken(result.accessToken(), "invalid_recovery_access_token");
            if (result.securityState() != AdminSecurityState.NORMAL) throw new IOException("recovery did not return normal state");
            requireCanonicalSessionId(result.currentSessionId(), "invalid_recovery_session_id");
            accessToken = result.accessToken();
            authenticatedAdminId = requireRecoveryAdminId();
            authenticatedDeviceId = deviceId;
            recoveryToken = null;
            recoveryAdminId = null;
            currentSessionId = result.currentSessionId();
            securityState = AdminSecurityState.NORMAL;
            reauthenticatedUntilEpochMs = 0L;
            pendingReconfirmation = null;
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

    public synchronized AdminHighRiskActionGate.Decision highRiskDecision(
        AdminHighRiskActionGate.Action action,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        return AdminHighRiskActionGate.evaluate(
            action,
            securityState,
            reauthenticatedUntilEpochMs,
            nowEpochMs,
            operationalWorkflowsEnabled
        );
    }

    public synchronized AdminOperationsApi.Result recordReviewDecision(
        String reportId,
        AdminReportDecision decision,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireOperationsApi().recordReviewDecision(
            requireOperationalSession(operationalWorkflowsEnabled),
            reportId,
            decision
        );
    }

    public synchronized AdminOperationsApi.Result readReviewDecisions(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireOperationsApi().readReviewDecisions(
            requireOperationalSession(operationalWorkflowsEnabled),
            reportId
        );
    }

    public synchronized AdminOperationsApi.Result recordDelivery(
        String reportId,
        AdminInstitutionDelivery delivery,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireOperationsApi().recordDelivery(
            requireOperationalSession(operationalWorkflowsEnabled),
            reportId,
            delivery
        );
    }

    public synchronized AdminOperationsApi.Result readDeliveries(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireOperationsApi().readDeliveries(
            requireOperationalSession(operationalWorkflowsEnabled),
            reportId
        );
    }

    public synchronized AdminHighRiskActionGate.Decision consumeHighRiskAuthorization(
        String action,
        String method,
        String path,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        AdminHighRiskActionGate.Binding binding = pendingReconfirmation;
        clearReconfirmation();
        AdminHighRiskActionGate.Decision decision = AdminHighRiskActionGate.evaluate(
            action,
            method,
            path,
            securityState,
            binding,
            nowEpochMs,
            operationalWorkflowsEnabled
        );
        return decision;
    }

    public synchronized void signOutLocal() {
        clearLocalSession();
        recoveryToken = null;
        recoveryAdminId = null;
    }

    @Override
    public synchronized void close() {
        clearLocalSession();
        recoveryToken = null;
        recoveryAdminId = null;
    }

    private String requireAccessToken() {
        if (accessToken == null) throw new IllegalStateException("administrator session is unavailable");
        return accessToken;
    }

    private AdminOperationsApi requireOperationsApi() {
        if (operationsApi == null) throw new IllegalStateException("administrator operations are unavailable");
        return operationsApi;
    }

    private AdminOperationsApi.SessionContext requireOperationalSession(boolean operationalWorkflowsEnabled) {
        if (!operationalWorkflowsEnabled) throw new IllegalStateException("operational workflows are locked");
        if (securityState != AdminSecurityState.NORMAL) {
            throw new IllegalStateException("administrator access is not normal");
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

    private String requireRecoveryAdminId() throws IOException {
        if (recoveryAdminId == null) throw new IOException("recovery administrator identity is unavailable");
        return recoveryAdminId;
    }

    private void failClosed() {
        securityState = AdminSecurityState.FAIL_CLOSED;
        sessions = List.of();
        clearReconfirmation();
        stateVersion = null;
        observedAt = null;
    }

    private void clearLocalSession() {
        accessToken = null;
        authenticatedAdminId = null;
        authenticatedDeviceId = null;
        currentSessionId = null;
        sessions = List.of();
        clearReconfirmation();
        stateVersion = null;
        observedAt = null;
        securityState = AdminSecurityState.SIGNED_OUT;
    }

    private void loadRemoteState(String token, AdminSecurityState expectedState) throws IOException {
        AdminSecurityApi.StateSnapshot state = api.getState(token);
        AdminSecurityState next = state.securityState();
        requireAuthenticatedState(next, "invalid_remote_security_state");
        if (expectedState != null && expectedState != next) throw new IOException("inconsistent remote security state");
        List<AdminSecurityApi.SessionInfo> updatedSessions = api.getSessions(token);
        long currentMatches = updatedSessions.stream()
            .filter(item -> item.isCurrent() && !item.isRevoked() && item.sessionId().equals(currentSessionId))
            .count();
        if (currentMatches != 1L) throw new IOException("current administrator session binding is invalid");
        securityState = next;
        stateVersion = requiredMetadata(state.stateVersion(), "invalid_state_version");
        observedAt = requiredMetadata(state.observedAt(), "invalid_state_observed_at");
        sessions = List.copyOf(updatedSessions);
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
        if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(character -> character < 0x20)) {
            throw new IOException(reason);
        }
        return value;
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
}
