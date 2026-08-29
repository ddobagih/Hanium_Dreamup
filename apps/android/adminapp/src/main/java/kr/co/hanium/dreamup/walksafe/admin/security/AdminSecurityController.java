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

public final class AdminSecurityController implements AutoCloseable {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final class Snapshot {
        private final AdminSecurityState securityState;
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

    private final AdminSecurityApi api;
    private final AdminOperationsApi operationsApi;
    private final AdminReportRepository reportRepository;
    private final AdminIncidentRepository incidentRepository;
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
        if (api == null) throw new IllegalArgumentException("api is required");
        if (telemetry == null) throw new IllegalArgumentException("telemetry is required");
        this.api = api;
        this.operationsApi = operationsApi;
        this.reportRepository = reportRepository;
        this.incidentRepository = incidentRepository;
        this.telemetry = telemetry;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
            securityState,
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

    public synchronized void login(
        String adminId,
        String password,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        String correlationId = AdminSecurityTelemetry.newCorrelationId();
        securityState = AdminSecurityState.AUTHENTICATING;
        recoveryCustodyState = null;
        recoveryCustodyAttestedAt = null;
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
            sessions = AdminJava8Collections.list();
            devices = AdminJava8Collections.list();
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

    public synchronized void refresh() throws IOException {
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

    public synchronized void reauthenticate(String password, String totpCode, long nowEpochMs) throws IOException {
        clearReconfirmation();
        throw new IllegalArgumentException("exact reauthentication binding is required");
    }

    public synchronized void reauthenticate(
        String password,
        String totpCode,
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs
    ) throws IOException {
        if (securityState != AdminSecurityState.NORMAL) throw new IllegalStateException("administrator access is not normal");
        if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
            throw new IllegalStateException("recovery custody is not attested");
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
                nonce
            );
            long until = result.reauthenticatedUntilEpochMs();
            if (until <= nowEpochMs) throw new IOException("invalid reauthentication expiry");
            if (!action.reauthenticationAction().equals(result.action())
                || !action.method().equals(result.method())
                || !action.path().equals(result.path())) {
                throw new IOException("reauthentication binding response does not match the request");
            }
            pendingReconfirmation = new AdminHighRiskActionGate.Binding(
                action,
                nonce,
                until
            );
            reauthenticatedUntilEpochMs = until;
        } catch (IOException | RuntimeException error) {
            clearReconfirmation();
            throw error;
        }
    }

    private synchronized void reauthenticate(
        char[] password,
        char[] totpCode,
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs
    ) throws IOException {
        if (securityState != AdminSecurityState.NORMAL) throw new IllegalStateException("administrator access is not normal");
        if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
            throw new IllegalStateException("recovery custody is not attested");
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
                nonce
            );
            long until = result.reauthenticatedUntilEpochMs();
            if (until <= nowEpochMs) throw new IOException("invalid reauthentication expiry");
            if (!action.reauthenticationAction().equals(result.action())
                || !action.method().equals(result.method())
                || !action.path().equals(result.path())) {
                throw new IOException("reauthentication binding response does not match the request");
            }
            pendingReconfirmation = new AdminHighRiskActionGate.Binding(action, nonce, until);
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

    public synchronized void attestRecoveryCustody(
        AdminSecurityApi.RecoveryMaterialKind materialKind
    ) throws IOException {
        if (securityState != AdminSecurityState.NORMAL) {
            throw new IllegalStateException("administrator access is not normal");
        }
        String token = requireAccessToken();
        AdminSecurityApi.RecoveryCustodyAttestation attestation =
            new AdminSecurityApi.RecoveryCustodyAttestation(
                newCustodyReference(),
                materialKind,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE,
                true
            );
        try {
            AdminSecurityApi.StateSnapshot state = api.attestRecoveryCustody(token, attestation);
            applyRemoteState(state, AdminSecurityState.NORMAL);
            if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
                throw new IOException("recovery custody attestation was not confirmed");
            }
            clearReconfirmation();
        } catch (IOException | RuntimeException error) {
            failClosed();
            throw error;
        }
    }

    public synchronized void reportLostDevice(String deviceId) throws IOException {
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
        try {
            AdminSecurityApi.StateSnapshot state = api.reportLostDevice(token, deviceId);
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
            devices = AdminJava8Collections.copyList(remainingDevices);
            clearReconfirmation();
        } catch (IOException | RuntimeException error) {
            failClosed();
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
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        return AdminHighRiskActionGate.evaluate(
            action,
            securityState,
            recoveryCustodyState,
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

    public synchronized AdminReportModels.Page listAdminReports(
        AdminReportModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireReportRepository().list(
            requireOperationalSession(operationalWorkflowsEnabled),
            filters,
            cursor
        );
    }

    public synchronized AdminReportModels.Detail getAdminReportDetail(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireReportRepository().detail(
            requireOperationalSession(operationalWorkflowsEnabled),
            reportId
        );
    }

    public synchronized AdminReportModels.StatusSnapshot updateAdminReportStatus(
        String reportId,
        String nextStatus,
        int expectedVersion,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.reportStatus(reportId);
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
            decision.requestHeaders()
        );
    }

    public synchronized AdminDeliveryPackage createAdminDeliveryPackage(
        String reportId,
        String password,
        String totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.deliveryPackage(reportId);
        reauthenticate(password, totpCode, operation, nowEpochMs);
        AdminHighRiskActionGate.Decision decision = consumeHighRiskAuthorization(
            operation,
            nowEpochMs,
            operationalWorkflowsEnabled
        );
        if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
        return requireReportRepository().createDeliveryPackage(
            requireOperationalSession(operationalWorkflowsEnabled),
            reportId,
            decision.requestHeaders()
        );
    }

    public synchronized AdminAuditModels.Page listAdminAudits(
        AdminAuditModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireReportRepository().audits(
            requireOperationalSession(operationalWorkflowsEnabled),
            filters,
            cursor
        );
    }

    public synchronized AdminIncidentModels.Page listAdminIncidents(
        AdminIncidentModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireIncidentRepository().list(
            requireOperationalSession(operationalWorkflowsEnabled),
            filters,
            cursor
        );
    }

    public synchronized AdminIncidentModels.Detail getAdminIncidentDetail(
        String incidentId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireIncidentRepository().detail(
            requireOperationalSession(operationalWorkflowsEnabled),
            incidentId
        );
    }

    public synchronized AdminIncidentModels.StatusSnapshot updateAdminIncidentStatus(
        String incidentId,
        AdminIncidentModels.StatusRequest request,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        AdminHighRiskActionGate.Operation operation =
            AdminHighRiskActionGate.incidentStatus(incidentId);
        try {
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

    public synchronized AdminReportRequestModels.Page listAdminReportRequests(
        AdminReportRequestModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireReportRepository().listRequests(
            requireOperationalSession(operationalWorkflowsEnabled),
            filters,
            cursor
        );
    }

    public synchronized AdminReportRequestModels.Detail getAdminReportRequestDetail(
        String requestId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        return requireReportRepository().requestDetail(
            requireOperationalSession(operationalWorkflowsEnabled),
            requestId
        );
    }

    public synchronized AdminReportRequestModels.StatusSnapshot updateAdminReportRequestStatus(
        String requestId,
        String nextStatus,
        int expectedVersion,
        String publicResponse,
        String internalNote,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        AdminHighRiskActionGate.Operation operation =
            AdminHighRiskActionGate.reportRequestStatus(requestId);
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
            nextStatus,
            expectedVersion,
            publicResponse,
            internalNote,
            decision.requestHeaders()
        );
    }

    public synchronized AdminHighRiskActionGate.Decision consumeHighRiskAuthorization(
        AdminHighRiskActionGate.Operation action,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
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

    private AdminOperationsApi.SessionContext requireOperationalSession(boolean operationalWorkflowsEnabled) {
        if (!operationalWorkflowsEnabled) throw new IllegalStateException("operational workflows are locked");
        if (securityState != AdminSecurityState.NORMAL) {
            throw new IllegalStateException("administrator access is not normal");
        }
        if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
            throw new IllegalStateException("recovery custody is not attested");
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
        sessions = AdminJava8Collections.list();
        devices = AdminJava8Collections.list();
        clearReconfirmation();
        stateVersion = null;
        observedAt = null;
        recoveryCustodyState = null;
        recoveryCustodyAttestedAt = null;
    }

    private void clearLocalSession() {
        api.clearLocalBinding();
        accessToken = null;
        authenticatedAdminId = null;
        authenticatedDeviceId = null;
        currentSessionId = null;
        sessions = AdminJava8Collections.list();
        devices = AdminJava8Collections.list();
        clearReconfirmation();
        stateVersion = null;
        observedAt = null;
        recoveryCustodyState = null;
        recoveryCustodyAttestedAt = null;
        securityState = AdminSecurityState.SIGNED_OUT;
    }

    private void loadRemoteState(String token, AdminSecurityState expectedState) throws IOException {
        AdminSecurityApi.StateSnapshot state = api.getState(token);
        AdminSecurityState next = state.securityState();
        requireAuthenticatedState(next, "invalid_remote_security_state");
        if (expectedState != null && expectedState != next) throw new IOException("inconsistent remote security state");
        AdminSecurityApi.DeviceInventory inventory = api.getDeviceInventory(token);
        List<AdminSecurityApi.SessionInfo> updatedSessions = inventory.sessions();
        List<AdminSecurityApi.DeviceInfo> updatedDevices = inventory.devices();
        for (AdminSecurityApi.SessionInfo item : updatedSessions) {
            requiredAwareRfc3339(item.lastSeenAt(), "invalid_session_last_seen_at");
        }
        long currentMatches = updatedSessions.stream()
            .filter(item -> item.isCurrent() && !item.isRevoked() && item.sessionId().equals(currentSessionId))
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
                if (!deviceId.equals(authenticatedDeviceId)) {
                    throw new IOException("current administrator device binding is invalid");
                }
            }
        }
        if (currentDeviceCount != 1) throw new IOException("current administrator device binding is invalid");
        applyRemoteState(state, expectedState);
        sessions = AdminJava8Collections.copyList(updatedSessions);
        devices = AdminJava8Collections.copyList(updatedDevices);
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
