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
        assertEquals(AdminRecoveryCustodyState.ATTESTED, snapshot.recoveryCustodyState());
        assertEquals("2026-07-21T23:00:00Z", snapshot.recoveryCustodyAttestedAt());
        assertEquals(CURRENT_SESSION_ID, snapshot.currentSessionId());
        assertEquals(2, snapshot.sessions().size());
        assertEquals(3, snapshot.devices().size());
        assertTrue(snapshot.isAccessSessionActive());
        assertFalse(snapshot.isRecoveryActive());
    }

    @Test
    public void unconfirmedCustodyLocksHighRiskAndOperationsUntilExplicitAttestation() throws Exception {
        FakeApi api = new FakeApi();
        api.custodyState = AdminRecoveryCustodyState.UNATTESTED;
        api.custodyAttestedAt = null;
        FakeOperations operations = new FakeOperations();
        AdminSecurityController controller = new AdminSecurityController(api, operations);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        var denied = controller.highRiskDecision(
            AdminHighRiskActionGate.Action.DATA_DELETE,
            1L,
            true
        );
        assertFalse(denied.isAllowed());
        assertEquals("recovery_custody_not_attested", denied.reason());
        assertThrows(IllegalStateException.class, () -> controller.reauthenticate(
            PASSWORD, "123456", AdminHighRiskActionGate.Action.DATA_DELETE, 1L
        ));
        assertEquals(0, api.reauthenticateCount);
        assertThrows(IllegalStateException.class, () -> controller.recordReviewDecision(
            "11111111-1111-4111-8111-111111111111",
            new AdminReportDecision(
                AdminReportDecision.Decision.APPROVED, "reviewed", null, true, true, true
            ),
            true
        ));

        controller.attestRecoveryCustody(AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE);

        assertEquals(AdminRecoveryCustodyState.ATTESTED, controller.snapshot().recoveryCustodyState());
        assertEquals("2026-07-22T00:02:00Z", controller.snapshot().recoveryCustodyAttestedAt());
        assertEquals(AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE, api.attestedMaterialKind);
        assertEquals(AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE, api.attestedStorageLocation);
        assertTrue(api.separateEncryptedBackupConfirmed);
        assertTrue(api.custodyReference.matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    public void anotherAuthenticatedDeviceCanReportLostRemoteDeviceButNeverItself() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertThrows(IllegalArgumentException.class, () -> controller.reportLostDevice(DEVICE_ID));
        assertEquals(0, api.reportLostDeviceCount);

        controller.reportLostDevice(OTHER_DEVICE_ID);

        assertEquals(1, api.reportLostDeviceCount);
        assertEquals(OTHER_DEVICE_ID, api.reportedLostDeviceId);
        assertEquals(1, controller.snapshot().sessions().size());
        assertEquals(DEVICE_ID, controller.snapshot().sessions().get(0).deviceId());
        assertTrue(controller.snapshot().sessions().get(0).isCurrent());
        assertEquals("state-v3", controller.snapshot().stateVersion());
    }

    @Test
    public void activeKeyOnlyRemoteDeviceCanBeReportedLostWithoutAnActiveSession() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertFalse(controller.snapshot().sessions().stream().anyMatch(session ->
            KEY_ONLY_DEVICE_ID.equals(session.deviceId())
        ));
        assertTrue(controller.snapshot().devices().stream().anyMatch(device ->
            KEY_ONLY_DEVICE_ID.equals(device.deviceId()) && !device.isCurrent()
        ));

        controller.reportLostDevice(KEY_ONLY_DEVICE_ID);

        assertEquals(KEY_ONLY_DEVICE_ID, api.reportedLostDeviceId);
        assertFalse(controller.snapshot().devices().stream().anyMatch(device ->
            KEY_ONLY_DEVICE_ID.equals(device.deviceId())
        ));
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
    public void malformedRemoteSecurityTimestampsFailClosedAtTheControllerBoundary() {
        FakeApi observedAt = new FakeApi();
        observedAt.observedAt = "2026-07-22T00:00:00";
        AdminSecurityController observedController = new AdminSecurityController(observedAt);
        assertThrows(IOException.class, () -> observedController.login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));
        assertEquals(AdminSecurityState.FAIL_CLOSED, observedController.snapshot().securityState());

        FakeApi attestedAt = new FakeApi();
        attestedAt.custodyAttestedAt = "not-a-timestamp";
        AdminSecurityController attestedController = new AdminSecurityController(attestedAt);
        assertThrows(IOException.class, () -> attestedController.login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));
        assertEquals(AdminSecurityState.FAIL_CLOSED, attestedController.snapshot().securityState());

        FakeApi lastSeenAt = new FakeApi();
        lastSeenAt.sessionLastSeenAt = "2026-07-22T00:00:00";
        AdminSecurityController sessionController = new AdminSecurityController(lastSeenAt);
        assertThrows(IOException.class, () -> sessionController.login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));
        assertEquals(AdminSecurityState.FAIL_CLOSED, sessionController.snapshot().securityState());
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
            AdminHighRiskActionGate.Action.DATA_DELETE,
            10_000L
        );

        assertTrue(api.reconfirmationNonce.matches("[A-Za-z0-9_-]{22}"));
        assertEquals("data.delete", api.reconfirmationAction);
        assertEquals("POST", api.reconfirmationMethod);
        assertEquals("/admin/operations/data-deletions", api.reconfirmationPath);
        assertFalse(controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.PRIVILEGE_CHANGE, 10_001L, true
        ).isAllowed());
        assertFalse(controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.DATA_DELETE, 10_001L, true
        ).isAllowed());

        controller.reauthenticate(
            PASSWORD,
            "123456",
            AdminHighRiskActionGate.Action.DATA_DELETE,
            10_000L
        );
        var allowed = controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.DATA_DELETE, 10_001L, true
        );
        assertTrue(allowed.isAllowed());
        assertEquals(
            api.reconfirmationNonce,
            allowed.requestHeaders().get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
        assertFalse(controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.DATA_DELETE, 10_002L, true
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
            AdminHighRiskActionGate.Action.DATA_DELETE,
            10_000L
        );
        assertFalse(controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.DATA_DELETE, 20_000L, true
        ).isAllowed());
        assertFalse(controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.DATA_DELETE, 10_001L, true
        ).isAllowed());

        api.echoPath = "/reports/report-2/status";
        assertThrows(IOException.class, () -> controller.reauthenticate(
            PASSWORD,
            "123456",
            AdminHighRiskActionGate.Action.DATA_DELETE,
            10_000L
        ));
        assertEquals(0L, controller.snapshot().reauthenticatedUntilEpochMs());
        assertFalse(controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.DATA_DELETE, 10_001L, true
        ).isAllowed());
    }

    @Test
    public void operationsStayFlagLockedAndUseOnlyTheNormalBoundAdminSession() throws Exception {
        FakeApi api = new FakeApi();
        FakeOperations operations = new FakeOperations();
        AdminSecurityController controller = new AdminSecurityController(api, operations);
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
        assertEquals(1, api.clearLocalBindingCount);
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
        String reconfirmationAction;
        String reconfirmationMethod;
        String reconfirmationPath;
        String echoPath;
        String stateFailureMessage = "state unavailable";
        AdminSecurityApiException completeRecoveryError;
        AdminRecoveryCustodyState custodyState = AdminRecoveryCustodyState.ATTESTED;
        String custodyAttestedAt = "2026-07-21T23:00:00Z";
        String observedAt = "2026-07-22T00:00:00Z";
        String sessionLastSeenAt = "2026-07-22T00:00:00Z";
        int reauthenticateCount;
        int reportLostDeviceCount;
        String reportedLostDeviceId;
        String custodyReference;
        RecoveryMaterialKind attestedMaterialKind;
        RecoveryStorageLocation attestedStorageLocation;
        boolean separateEncryptedBackupConfirmed;
        int clearLocalBindingCount;

        @Override
        public void clearLocalBinding() {
            clearLocalBindingCount += 1;
        }

        @Override
        public LoginResult login(String adminId, String password, String totpCode, String deviceId, String deviceLabel) {
            return new LoginResult(ACCESS_TOKEN, AdminSecurityState.NORMAL, CURRENT_SESSION_ID);
        }

        @Override
        public StateSnapshot getState(String accessToken) throws IOException {
            if (failState) throw new IOException(stateFailureMessage);
            return state("state-v1", observedAt);
        }

        @Override
        public DeviceInventory getDeviceInventory(String accessToken) {
            return new DeviceInventory(
                List.of(
                    session(CURRENT_SESSION_ID, DEVICE_ID, true, sessionLastSeenAt),
                    session(OTHER_SESSION_ID, OTHER_DEVICE_ID, false, sessionLastSeenAt)
                ),
                List.of(
                    new DeviceInfo(DEVICE_ID, true),
                    new DeviceInfo(OTHER_DEVICE_ID, false),
                    new DeviceInfo(KEY_ONLY_DEVICE_ID, false)
                )
            );
        }

        @Override
        public StateSnapshot revokeSession(String accessToken, String sessionId) throws IOException {
            if (failRevoke) throw new IOException("revoke unavailable");
            return state("state-v2", "2026-07-22T00:01:00Z");
        }

        @Override
        public StateSnapshot attestRecoveryCustody(
            String accessToken,
            RecoveryCustodyAttestation attestation
        ) {
            custodyReference = attestation.custodyReference();
            attestedMaterialKind = attestation.materialKind();
            attestedStorageLocation = attestation.storageLocation();
            separateEncryptedBackupConfirmed = attestation.isSeparateEncryptedBackupConfirmed();
            custodyState = AdminRecoveryCustodyState.ATTESTED;
            custodyAttestedAt = "2026-07-22T00:02:00Z";
            return state("state-v2", custodyAttestedAt);
        }

        @Override
        public StateSnapshot reportLostDevice(String accessToken, String deviceId) {
            reportLostDeviceCount += 1;
            reportedLostDeviceId = deviceId;
            return state("state-v3", "2026-07-22T00:03:00Z");
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
            reauthenticateCount += 1;
            reconfirmationNonce = nonce;
            reconfirmationAction = action;
            reconfirmationMethod = method;
            reconfirmationPath = path;
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

        private StateSnapshot state(String version, String observedAt) {
            return new StateSnapshot(
                AdminSecurityState.NORMAL,
                version,
                observedAt,
                custodyState,
                custodyAttestedAt
            );
        }

        private static SessionInfo session(
            String id,
            String deviceId,
            boolean current,
            String lastSeenAt
        ) {
            return new SessionInfo(id, deviceId, "test phone", current, false, lastSeenAt);
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
    private static final String OTHER_DEVICE_ID = "admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String KEY_ONLY_DEVICE_ID = "admin-device-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "new correct horse battery staple";
    private static final String RECOVERY_CODE = "recovery-code-for-tests-123456";
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String NEW_ACCESS_TOKEN = "opaque-access-token-after-recovery-123456";
    private static final String RECOVERY_TOKEN = "opaque-recovery-token-for-tests-123456";
    private static final String LOST_RESPONSE_TOKEN = "opaque-lost-response-recovery-token-123456";
    private static final String RESUMED_RECOVERY_TOKEN = "opaque-resumed-recovery-token-123456";
}
