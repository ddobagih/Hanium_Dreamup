package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AdminSecurityControllerTest {
    @Test
    public void loginLoadsServerAuthorityAndKeepsOnlyAnOpaqueSessionIndicatorInSnapshot() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);

        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        var snapshot = controller.snapshot();
        assertEquals(AdminSecurityState.NORMAL, snapshot.securityState());
        assertEquals("state-v1", snapshot.stateVersion());
        assertEquals("2026-07-22T00:00:00Z", snapshot.observedAt());
        assertEquals(CURRENT_SESSION_ID, snapshot.currentSessionId());
        assertEquals(2, snapshot.sessions().size());
        assertTrue(snapshot.isAccessSessionActive());
        assertFalse(snapshot.isRecoveryActive());
    }

    @Test
    public void remoteStateFailureFailsClosedAndFreezesHighRiskActions() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        api.failState = true;

        assertThrows(IOException.class, controller::refresh);

        assertEquals(AdminSecurityState.FAIL_CLOSED, controller.snapshot().securityState());
        assertFalse(controller.highRiskDecision(
            AdminHighRiskActionGate.Action.RELEASE_APPROVAL,
            1L,
            true
        ).isAllowed());
    }

    @Test
    public void issuedLoginSessionRemainsRevocableWhenTheImmediateStateReadFails() {
        FakeApi api = new FakeApi();
        api.failState = true;
        AdminSecurityController controller = new AdminSecurityController(api);

        assertThrows(IOException.class, () -> controller.login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));

        var snapshot = controller.snapshot();
        assertEquals(AdminSecurityState.FAIL_CLOSED, snapshot.securityState());
        assertTrue(snapshot.isAccessSessionActive());
        assertEquals(CURRENT_SESSION_ID, snapshot.currentSessionId());
    }

    @Test
    public void recoveryUsesASeparateMemoryTokenAndStaysFrozenUntilCompletion() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);

        controller.startRecovery("admin-01", RECOVERY_CODE, DEVICE_ID, "replacement phone");

        var active = controller.snapshot();
        assertEquals(AdminSecurityState.RECOVERY_IN_PROGRESS, active.securityState());
        assertTrue(active.isRecoveryActive());
        assertFalse(active.isAccessSessionActive());
        assertFalse(controller.highRiskDecision(
            AdminHighRiskActionGate.Action.PRIVILEGE_CHANGE,
            1L,
            true
        ).isAllowed());

        controller.completeRecovery(NEW_PASSWORD, "654321", DEVICE_ID, "replacement phone");
        var completed = controller.snapshot();
        assertEquals(AdminSecurityState.NORMAL, completed.securityState());
        assertTrue(completed.isAccessSessionActive());
        assertFalse(completed.isRecoveryActive());
        assertEquals(1, api.completeRecoveryCount);
    }

    @Test
    public void expiredOrInvalidCompletionForgetsTheStaleRecoveryTokenAndRequiresRestart() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.startRecovery("admin-01", RECOVERY_CODE, DEVICE_ID, "replacement phone");
        api.completeRecoveryError = new AdminSecurityApiException(
            AdminSecurityApiException.Code.RECOVERY_EXPIRED,
            null
        );

        assertThrows(AdminSecurityApiException.class, () ->
            controller.completeRecovery(NEW_PASSWORD, "654321", DEVICE_ID, "replacement phone")
        );

        assertEquals(AdminSecurityState.FAIL_CLOSED, controller.snapshot().securityState());
        assertFalse(controller.snapshot().isRecoveryActive());
        assertFalse(controller.snapshot().isAccessSessionActive());
    }

    @Test
    public void lostStartResponseCanResumeAfterProcessShutdownWithSameCodeAndDevice() throws Exception {
        FakeApi api = new FakeApi();
        api.loseFirstRecoveryStartResponse = true;
        AdminSecurityController firstProcess = new AdminSecurityController(api);

        assertThrows(IOException.class, () -> firstProcess.startRecovery(
            "admin-01", RECOVERY_CODE, DEVICE_ID, "replacement phone"
        ));
        assertEquals(1, api.startRecoveryCount);
        assertFalse(firstProcess.snapshot().isRecoveryActive());
        firstProcess.close();

        AdminSecurityController restartedProcess = new AdminSecurityController(api);
        restartedProcess.startRecovery(
            "admin-01", RECOVERY_CODE, DEVICE_ID, "replacement phone"
        );

        assertEquals(2, api.startRecoveryCount);
        assertTrue(api.sameRecoveryRequestRetried);
        assertTrue(restartedProcess.snapshot().isRecoveryActive());

        AdminSecurityApiException staleTokenError = assertThrows(
            AdminSecurityApiException.class,
            () -> api.completeRecovery(
                LOST_RESPONSE_TOKEN,
                NEW_PASSWORD,
                "654321",
                DEVICE_ID,
                "replacement phone"
            )
        );
        assertEquals(AdminSecurityApiException.Code.RECOVERY_INVALID, staleTokenError.code());
        assertTrue(api.oldRecoveryTokenRejected);

        restartedProcess.completeRecovery(NEW_PASSWORD, "654321", DEVICE_ID, "replacement phone");

        assertEquals(RESUMED_RECOVERY_TOKEN, api.completedRecoveryToken);
        assertEquals(AdminSecurityState.NORMAL, restartedProcess.snapshot().securityState());
        assertTrue(restartedProcess.snapshot().isAccessSessionActive());
        assertFalse(restartedProcess.snapshot().isRecoveryActive());
    }

    @Test
    public void currentSessionRevocationClearsLocalAccessEvenWhenNetworkResultIsUncertain() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        api.failRevoke = true;

        assertThrows(IOException.class, () -> controller.revokeSession(CURRENT_SESSION_ID));

        assertEquals(AdminSecurityState.SIGNED_OUT, controller.snapshot().securityState());
        assertFalse(controller.snapshot().isAccessSessionActive());
    }

    @Test
    public void anotherDeviceCanBeRevokedWithoutUnlockingOperationalWorkflows() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        controller.revokeSession(OTHER_SESSION_ID);

        assertEquals(1, controller.snapshot().sessions().size());
        assertEquals("state-v2", controller.snapshot().stateVersion());
        assertFalse(controller.highRiskDecision(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            1L,
            false
        ).isAllowed());
    }

    @Test
    public void exactPendingBindingIsActionBoundAndConsumedOnceWithTheNonceHeader() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        controller.reauthenticate(
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            10_000L
        );

        assertTrue(api.reconfirmationNonce.matches("[A-Za-z0-9_-]{22}"));
        assertFalse(controller.consumeHighRiskAuthorization(
            "report.export", "PATCH", "/reports/report-1/status", 10_001L, true
        ).isAllowed());
        assertFalse(controller.consumeHighRiskAuthorization(
            "report.status.update", "PATCH", "/reports/report-1/status", 10_001L, true
        ).isAllowed());

        controller.reauthenticate(
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            10_000L
        );
        var allowed = controller.consumeHighRiskAuthorization(
            "report.status.update", "PATCH", "/reports/report-1/status", 10_001L, true
        );
        assertTrue(allowed.isAllowed());
        assertEquals(
            api.reconfirmationNonce,
            allowed.requestHeaders().get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
        assertFalse(controller.consumeHighRiskAuthorization(
            "report.status.update", "PATCH", "/reports/report-1/status", 10_002L, true
        ).isAllowed());
    }

    @Test
    public void expiredOrMismatchedResponseBindingFailsClosed() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        controller.reauthenticate(
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            10_000L
        );
        assertFalse(controller.consumeHighRiskAuthorization(
            "report.status.update", "PATCH", "/reports/report-1/status", 20_000L, true
        ).isAllowed());
        assertFalse(controller.consumeHighRiskAuthorization(
            "report.status.update", "PATCH", "/reports/report-1/status", 10_001L, true
        ).isAllowed());

        api.echoPath = "/reports/report-2/status";
        assertThrows(IOException.class, () -> controller.reauthenticate(
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            10_000L
        ));
        assertEquals(0L, controller.snapshot().reauthenticatedUntilEpochMs());
        assertFalse(controller.consumeHighRiskAuthorization(
            "report.status.update", "PATCH", "/reports/report-1/status", 10_001L, true
        ).isAllowed());
    }

    @Test
    public void operationsStayFlagLockedAndUseOnlyTheNormalBoundAdminSession() throws Exception {
        FakeOperations operations = new FakeOperations();
        AdminSecurityController controller = new AdminSecurityController(new FakeApi(), operations);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        AdminReportDecision review = new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED, "reviewed", null, true, true, true
        );

        assertThrows(IllegalStateException.class, () -> controller.recordReviewDecision(
            "11111111-1111-4111-8111-111111111111", review, false
        ));
        assertEquals(0, operations.reviewCalls);

        controller.recordReviewDecision(
            "11111111-1111-4111-8111-111111111111", review, true
        );

        assertEquals(1, operations.reviewCalls);
        assertEquals("admin-01", operations.session.adminId());
        assertEquals(CURRENT_SESSION_ID, operations.session.sessionId());
        assertEquals(DEVICE_ID, operations.session.deviceId());
        assertEquals(ACCESS_TOKEN, operations.session.accessToken());
        controller.signOutLocal();
        assertThrows(IllegalStateException.class, () -> controller.recordReviewDecision(
            "11111111-1111-4111-8111-111111111111", review, true
        ));
    }

    @Test
    public void authAndRecoveryTelemetryUsesOnlyVersionedSafeFields() throws Exception {
        List<AdminSecurityTelemetry.Event> events = new ArrayList<>();

        FakeApi successfulApi = new FakeApi();
        AdminSecurityController successful = new AdminSecurityController(successfulApi, events::add);
        successful.login("sensitive-admin-id", PASSWORD, "123456", DEVICE_ID, "sensitive label");
        successful.startRecovery("sensitive-admin-id", RECOVERY_CODE, DEVICE_ID, "sensitive label");
        successful.completeRecovery(NEW_PASSWORD, "654321", DEVICE_ID, "sensitive label");

        FakeApi failedAuthApi = new FakeApi();
        failedAuthApi.failState = true;
        failedAuthApi.stateFailureMessage = "secret-body:" + PASSWORD;
        AdminSecurityController failedAuth = new AdminSecurityController(failedAuthApi, events::add);
        assertThrows(IOException.class, () -> failedAuth.login(
            "sensitive-admin-id", PASSWORD, "123456", DEVICE_ID, "sensitive label"
        ));

        FakeApi failedStartApi = new FakeApi();
        failedStartApi.loseFirstRecoveryStartResponse = true;
        AdminSecurityController failedStart = new AdminSecurityController(failedStartApi, events::add);
        assertThrows(IOException.class, () -> failedStart.startRecovery(
            "sensitive-admin-id", RECOVERY_CODE, DEVICE_ID, "sensitive label"
        ));

        FakeApi failedCompletionApi = new FakeApi();
        AdminSecurityController failedCompletion = new AdminSecurityController(
            failedCompletionApi,
            events::add
        );
        failedCompletion.startRecovery(
            "sensitive-admin-id", RECOVERY_CODE, DEVICE_ID, "sensitive label"
        );
        failedCompletionApi.completeRecoveryError = new AdminSecurityApiException(
            AdminSecurityApiException.Code.RECOVERY_EXPIRED,
            null
        );
        assertThrows(AdminSecurityApiException.class, () -> failedCompletion.completeRecovery(
            NEW_PASSWORD, "654321", DEVICE_ID, "sensitive label"
        ));

        List<String> names = events.stream().map(AdminSecurityTelemetry.Event::eventName).toList();
        assertTrue(names.contains(AdminSecurityTelemetry.AUTH_SUCCEEDED));
        assertTrue(names.contains(AdminSecurityTelemetry.AUTH_FAILED));
        assertTrue(names.contains(AdminSecurityTelemetry.RECOVERY_START_SUCCEEDED));
        assertTrue(names.contains(AdminSecurityTelemetry.RECOVERY_START_FAILED));
        assertTrue(names.contains(AdminSecurityTelemetry.RECOVERY_COMPLETE_SUCCEEDED));
        assertTrue(names.contains(AdminSecurityTelemetry.RECOVERY_COMPLETE_FAILED));

        String serialized = events.stream()
            .map(AdminSecurityTelemetry.Event::toJson)
            .reduce("", (left, right) -> left + right);
        for (String secret : List.of(
            "sensitive-admin-id",
            "sensitive label",
            PASSWORD,
            NEW_PASSWORD,
            RECOVERY_CODE,
            ACCESS_TOKEN,
            NEW_ACCESS_TOKEN,
            RECOVERY_TOKEN,
            "123456",
            "654321",
            "secret-body"
        )) {
            assertFalse(serialized.contains(secret));
        }
        for (AdminSecurityTelemetry.Event event : events) {
            assertEquals(AdminSecurityTelemetry.SCHEMA_VERSION, event.schemaVersion());
            assertTrue(event.correlationId().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
            ));
            assertTrue(event.toJson().matches(
                "\\{\"schema_version\":\"walksafe\\.admin-security-telemetry\\.v1\","
                    + "\"event_name\":\"[a-z.]+\",\"severity\":\"(INFO|WARN)\","
                    + "\"correlation_id\":\"[0-9a-f-]{36}\",\"outcome\":\"(succeeded|failed)\","
                    + "\"failure_code\":(null|\"[a-z_]+\")\\}"
            ));
        }
        assertTrue(events.stream().anyMatch(event ->
            "api_recovery_expired".equals(event.failureCode())
        ));
    }

    private static final class FakeApi implements AdminSecurityApi {
        boolean failState;
        boolean failRevoke;
        boolean loseFirstRecoveryStartResponse;
        boolean sameRecoveryRequestRetried;
        boolean oldRecoveryTokenRejected;
        int completeRecoveryCount;
        int startRecoveryCount;
        String firstRecoveryCode;
        String firstRecoveryDeviceId;
        String activeRecoveryToken;
        String completedRecoveryToken;
        String reconfirmationNonce;
        String echoPath;
        String stateFailureMessage = "state unavailable";
        AdminSecurityApiException completeRecoveryError;

        @Override
        public LoginResult login(String adminId, String password, String totpCode, String deviceId, String deviceLabel) {
            return new LoginResult(ACCESS_TOKEN, AdminSecurityState.NORMAL, CURRENT_SESSION_ID);
        }

        @Override
        public StateSnapshot getState(String accessToken) throws IOException {
            if (failState) throw new IOException(stateFailureMessage);
            return new StateSnapshot(AdminSecurityState.NORMAL, "state-v1", "2026-07-22T00:00:00Z");
        }

        @Override
        public List<SessionInfo> getSessions(String accessToken) {
            return List.of(
                session(CURRENT_SESSION_ID, true),
                session(OTHER_SESSION_ID, false)
            );
        }

        @Override
        public StateSnapshot revokeSession(String accessToken, String sessionId) throws IOException {
            if (failRevoke) throw new IOException("revoke unavailable");
            return new StateSnapshot(AdminSecurityState.NORMAL, "state-v2", "2026-07-22T00:01:00Z");
        }

        @Override
        public ReauthenticationResult reauthenticate(
            String accessToken,
            String password,
            String totpCode,
            String action,
            String method,
            String path,
            String nonce
        ) {
            reconfirmationNonce = nonce;
            return new ReauthenticationResult(20_000L, action, method, echoPath == null ? path : echoPath);
        }

        @Override
        public RecoveryStartResult startRecovery(
            String adminId,
            String recoveryCode,
            String deviceId,
            String deviceLabel
        ) throws IOException {
            startRecoveryCount += 1;
            if (loseFirstRecoveryStartResponse && startRecoveryCount == 1) {
                firstRecoveryCode = recoveryCode;
                firstRecoveryDeviceId = deviceId;
                activeRecoveryToken = LOST_RESPONSE_TOKEN;
                throw new IOException("server committed recovery but response was lost");
            }
            if (loseFirstRecoveryStartResponse) {
                sameRecoveryRequestRetried = recoveryCode.equals(firstRecoveryCode)
                    && deviceId.equals(firstRecoveryDeviceId);
                if (!sameRecoveryRequestRetried) throw new IOException("recovery retry binding changed");
                activeRecoveryToken = RESUMED_RECOVERY_TOKEN;
            } else {
                activeRecoveryToken = RECOVERY_TOKEN;
            }
            return new RecoveryStartResult(activeRecoveryToken, AdminSecurityState.RECOVERY_IN_PROGRESS);
        }

        @Override
        public LoginResult completeRecovery(
            String recoveryToken,
            String newPassword,
            String totpCode,
            String deviceId,
            String deviceLabel
        ) throws IOException {
            if (completeRecoveryError != null) throw completeRecoveryError;
            if (activeRecoveryToken != null && !activeRecoveryToken.equals(recoveryToken)) {
                oldRecoveryTokenRejected = true;
                throw new AdminSecurityApiException(AdminSecurityApiException.Code.RECOVERY_INVALID, null);
            }
            completeRecoveryCount += 1;
            completedRecoveryToken = recoveryToken;
            return new LoginResult(NEW_ACCESS_TOKEN, AdminSecurityState.NORMAL, CURRENT_SESSION_ID);
        }

        private static SessionInfo session(String id, boolean current) {
            return new SessionInfo(id, DEVICE_ID, "test phone", current, false, "2026-07-22T00:00:00Z");
        }
    }

    private static final class FakeOperations implements AdminOperationsApi {
        int reviewCalls;
        SessionContext session;

        @Override
        public Result recordReviewDecision(SessionContext session, String reportId, AdminReportDecision decision) {
            reviewCalls += 1;
            this.session = session;
            return new Result("22222222-2222-4222-8222-222222222222", 201);
        }

        @Override
        public Result readReviewDecisions(SessionContext session, String reportId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result recordDelivery(SessionContext session, String reportId, AdminInstitutionDelivery delivery) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result readDeliveries(SessionContext session, String reportId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final String DEVICE_ID = "admin-device-12345678-1234-1234-1234-123456789abc";
    private static final String CURRENT_SESSION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_SESSION_ID = "22222222-2222-4222-8222-222222222222";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "new correct horse battery staple";
    private static final String RECOVERY_CODE = "recovery-code-for-tests-123456";
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String NEW_ACCESS_TOKEN = "opaque-access-token-after-recovery-123456";
    private static final String RECOVERY_TOKEN = "opaque-recovery-token-for-tests-123456";
    private static final String LOST_RESPONSE_TOKEN = "opaque-lost-response-recovery-token-123456";
    private static final String RESUMED_RECOVERY_TOKEN = "opaque-resumed-recovery-token-123456";
}
