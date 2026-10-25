package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class AdminLocalSessionLifecycleTest {
    private static final String SESSION = "11111111-1111-4111-8111-111111111111";
    private static final String DEVICE = "synthetic-device";
    private static final String TOKEN = "synthetic-access-token-not-a-real-secret";

    @Test
    public void snapshotAndBackgroundLockDoNotWaitForLoginAndLateLoginCannotRestoreAccess()
        throws Exception {
        FakeApi api = new FakeApi();
        api.block = "login";
        AdminSecurityController controller = controller(api);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> login = executor.submit(() -> { login(controller); return null; });
        try {
            assertTrue(api.entered.await(2, TimeUnit.SECONDS));
            assertFalse(executor.submit(controller::snapshot).get(500, TimeUnit.MILLISECONDS)
                .isAccessSessionActive());
            executor.submit(() -> controller.suspendForBackground(false))
                .get(500, TimeUnit.MILLISECONDS);
            assertTrue(controller.isLocallyLocked());
            controller.allowFreshAuthentication();
            api.release.countDown();
            assertThrows(ExecutionException.class, () -> login.get(2, TimeUnit.SECONDS));
            assertFalse(controller.snapshot().isAccessSessionActive());
            assertFalse(controller.snapshot().isRecoveryActive());
            assertEquals(0, api.inventoryCalls);
            api.block = null;
            login(controller);
            assertTrue(controller.snapshot().isAccessSessionActive());
        } finally {
            api.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void snapshotRemainsResponsiveWhileAnAuthenticatedRefreshIsBlocked() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        api.block = "state";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> refresh = executor.submit(() -> { controller.refresh(); return null; });
        try {
            assertTrue(api.entered.await(2, TimeUnit.SECONDS));
            assertEquals(SESSION, executor.submit(controller::snapshot)
                .get(500, TimeUnit.MILLISECONDS).currentSessionId());
            executor.submit(() -> controller.suspendForBackground(false))
                .get(500, TimeUnit.MILLISECONDS);
            assertFalse(controller.snapshot().isAccessSessionActive());
            api.release.countDown();
            assertThrows(ExecutionException.class, () -> refresh.get(2, TimeUnit.SECONDS));
            assertTrue(controller.isLocallyLocked());
        } finally {
            api.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void confirmedResumePreservesServerSessionButDropsPreviousActionAuthorization()
        throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        controller.reauthenticate("synthetic-password", "123456",
            AdminHighRiskActionGate.Action.DATA_DELETE, 1L);

        controller.suspendForBackground(false);
        assertTrue(controller.hasRetainedSession());
        assertFalse(controller.snapshot().isAccessSessionActive());
        assertThrows(IllegalStateException.class, controller::allowFreshAuthentication);
        assertThrows(IllegalStateException.class, controller::refresh);
        assertFalse(controller.highRiskDecision(
            AdminHighRiskActionGate.Action.DATA_DELETE, 2L, true).isAllowed());
        controller.resumeLocalSession(controller.localGeneration());

        assertFalse(controller.isLocallyLocked());
        assertEquals(SESSION, controller.snapshot().currentSessionId());
        assertEquals(1, api.loginCalls);
        assertEquals(0, api.revokeCalls);
        assertEquals(2, api.inventoryCalls);
        assertFalse(controller.consumeHighRiskAuthorization(
            AdminHighRiskActionGate.Action.DATA_DELETE, 2L, true).isAllowed());
    }

    @Test
    public void aStaleDeviceConfirmationCannotResumeAnotherForegroundGeneration() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        controller.suspendForBackground(false);
        long oldGeneration = controller.localGeneration();
        controller.suspendForBackground(false);
        assertThrows(IllegalStateException.class, () -> controller.resumeLocalSession(oldGeneration));
        assertTrue(controller.isLocallyLocked());
        assertEquals(1, api.inventoryCalls);
    }

    @Test
    public void remoteRevocationKeepsAConfirmedLocalResumeLocked() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        controller.suspendForBackground(false);
        api.omitCurrentSession = true;
        assertThrows(IOException.class, () -> controller.resumeLocalSession(controller.localGeneration()));
        assertTrue(controller.isLocallyLocked());
        assertFalse(controller.snapshot().isAccessSessionActive());
        assertEquals(0, api.revokeCalls);
    }

    @Test
    public void leavingDuringResumeCannotUnlockWhenTheRemoteStateFinallyArrives() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        controller.suspendForBackground(false);
        long generation = controller.localGeneration();
        api.block = "state";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> resume = executor.submit(() -> { controller.resumeLocalSession(generation); return null; });
        try {
            assertTrue(api.entered.await(2, TimeUnit.SECONDS));
            controller.suspendForBackground(false);
            api.release.countDown();
            assertThrows(ExecutionException.class, () -> resume.get(2, TimeUnit.SECONDS));
            assertTrue(controller.isLocallyLocked());
            assertFalse(controller.snapshot().isAccessSessionActive());
        } finally {
            api.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void lateReauthenticationCannotAuthorizeANewForegroundTask() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        api.block = "reauthenticate";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> reauthentication = executor.submit(() -> {
            controller.reauthenticate("synthetic-password", "123456",
                AdminHighRiskActionGate.Action.DATA_DELETE, 1L);
            return null;
        });
        try {
            assertTrue(api.entered.await(2, TimeUnit.SECONDS));
            controller.suspendForBackground(false);
            api.release.countDown();
            // The server answered after the screen left: the controller rejects the stale
            // result (same fail-closed outcome as a late login or recovery start) and stores
            // no grant, rather than returning normally with a grant it must later ignore.
            ExecutionException late = assertThrows(ExecutionException.class,
                () -> reauthentication.get(2, TimeUnit.SECONDS));
            assertTrue(late.getCause() instanceof IllegalStateException);
            assertEquals("administrator operation is no longer current", late.getCause().getMessage());
            java.lang.reflect.Field grant =
                AdminSecurityController.class.getDeclaredField("pendingReconfirmation");
            grant.setAccessible(true);
            assertNull("a stale reauthentication must not leave a grant behind", grant.get(controller));
            assertThrows(IllegalStateException.class, () -> controller.consumeHighRiskAuthorization(
                AdminHighRiskActionGate.Action.DATA_DELETE, 2L, true));
            controller.resumeLocalSession(controller.localGeneration());
            assertEquals(0L, controller.snapshot().reauthenticatedUntilEpochMs());
            assertFalse(controller.consumeHighRiskAuthorization(
                AdminHighRiskActionGate.Action.DATA_DELETE, 2L, true).isAllowed());
            // Only a new reauthentication in the new foreground generation authorizes, once.
            api.block = null;
            controller.reauthenticate("synthetic-password", "123456",
                AdminHighRiskActionGate.Action.DATA_DELETE, 3L);
            assertTrue(controller.consumeHighRiskAuthorization(
                AdminHighRiskActionGate.Action.DATA_DELETE, 4L, true).isAllowed());
            assertFalse(controller.consumeHighRiskAuthorization(
                AdminHighRiskActionGate.Action.DATA_DELETE, 5L, true).isAllowed());
        } finally {
            api.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void closeFencesAccessWithoutWaitingForTheNetworkAndWorkerClearsItsBinding()
        throws Exception {
        FakeApi api = new FakeApi();
        api.block = "login";
        AdminSecurityController controller = controller(api);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> login = executor.submit(() -> { login(controller); return null; });
        try {
            assertTrue(api.entered.await(2, TimeUnit.SECONDS));
            executor.submit(controller::close).get(500, TimeUnit.MILLISECONDS);
            assertFalse(controller.snapshot().isAccessSessionActive());
            api.release.countDown();
            assertThrows(ExecutionException.class, () -> login.get(2, TimeUnit.SECONDS));
            assertTrue(api.clearBindingCalls > 0);
            assertThrows(IllegalStateException.class, () -> login(controller));
        } finally {
            api.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void stoppedRecoveryStartCannotPublishARecoveryToken() throws Exception {
        FakeApi api = new FakeApi();
        api.block = "recovery";
        AdminSecurityController controller = controller(api);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> recovery = executor.submit(() -> {
            controller.startRecovery("synthetic-admin", "synthetic-recovery-code-not-real",
                DEVICE, "test");
            return null;
        });
        try {
            assertTrue(api.entered.await(2, TimeUnit.SECONDS));
            controller.suspendForBackground(false);
            api.release.countDown();
            assertThrows(ExecutionException.class, () -> recovery.get(2, TimeUnit.SECONDS));
            assertFalse(controller.hasRetainedSession());
            assertFalse(controller.snapshot().isRecoveryActive());
        } finally {
            api.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void intentionalSafRoundTripPreservesBindingAndStillRejectsRemoteRevocation()
        throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        long generation = controller.localGeneration();
        controller.suspendForBackground(true);
        assertFalse(controller.isLocallyLocked());
        assertEquals(generation, controller.localGeneration());
        assertTrue(controller.refreshAndValidateSafSaveSession(SESSION));
        api.omitCurrentSession = true;
        assertThrows(IOException.class, () -> controller.refreshAndValidateSafSaveSession(SESSION));
        assertEquals(AdminSecurityState.FAIL_CLOSED, controller.snapshot().securityState());
        assertEquals(1, api.loginCalls);
        assertEquals(0, api.revokeCalls);
    }

    @Test
    public void uncertainCustodyResponseRetainsItsReferenceUntilConfirmation() throws Exception {
        FakeApi api = new FakeApi();
        AdminSecurityController controller = controller(api);
        login(controller);
        api.failCustody = true;
        assertThrows(IOException.class, () -> controller.attestRecoveryCustody(
            AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE));
        controller.refresh();
        controller.attestRecoveryCustody(AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE);
        assertEquals(api.custodyReferences.get(0), api.custodyReferences.get(1));
        controller.attestRecoveryCustody(AdminSecurityApi.RecoveryMaterialKind.RECOVERY_CODE);
        assertNotEquals(api.custodyReferences.get(1), api.custodyReferences.get(2));
    }

    @Test
    public void typedErrorsRejectTrailingJsonRatherThanInterpretingItAsRecoveryGuidance()
        throws Exception {
        Method parser = AdminSecurityHttpClient.class.getDeclaredMethod(
            "parseTypedError", AdminSecurityHttpClient.Response.class);
        parser.setAccessible(true);
        String valid = "{\"detail\":{\"code\":\"admin_totp_secret_not_replaced\","
            + "\"message\":\"synthetic\"}}";
        assertNotNull(parser.invoke(null, new AdminSecurityHttpClient.Response(409, valid)));
        assertNull(parser.invoke(null, new AdminSecurityHttpClient.Response(409, valid + " []")));
    }

    @Test
    public void activityWiringKeepsSafExceptionsAndDoesNotEraseConfirmedRecoveryOnLocalLock()
        throws Exception {
        String activity = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java")),
            java.nio.charset.StandardCharsets.UTF_8);
        String stop = activity.substring(activity.indexOf("protected void onStop()"),
            activity.indexOf("protected void onDestroy()"));
        assertTrue(stop.contains("packageWorkflow.awaitingSave() || packageWorkflow.awaitingReconnect() || awaitingLocalUnlock"));
        assertTrue(stop.contains("reportWorkflowController.suspendForLifecycle()"));
        assertFalse(stop.contains("revokeSession("));
        assertFalse(stop.contains("resetSessionBoundReportState("));
        String render = activity.substring(activity.indexOf("private void render()"),
            activity.indexOf("private void renderDevices("));
        assertTrue(render.indexOf("if (locallyLocked)") < render.indexOf("reconcileOperationsAccessBinding("));
        assertTrue(render.indexOf("return;") < render.indexOf("reconcileOperationsAccessBinding("));
        assertTrue(activity.contains("controller.resumeLocalSession(generation)"));
        assertTrue(activity.contains("resultCode == RESULT_OK"));
        assertTrue(activity.contains("generation != securityOperationGeneration"));
        assertFalse(activity.contains("networkExecutor.execute(controller::close)"));
    }

    private static AdminSecurityController controller(FakeApi api) {
        return new AdminSecurityController(api, event -> {});
    }

    private static void login(AdminSecurityController controller) throws IOException {
        controller.login("synthetic-admin", "synthetic-password", "123456", DEVICE, "test");
    }

    private static final class FakeApi implements AdminSecurityApi {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> custodyReferences = new ArrayList<>();
        volatile String block;
        boolean omitCurrentSession;
        boolean failCustody;
        int loginCalls;
        int inventoryCalls;
        int revokeCalls;
        int clearBindingCalls;

        private void before(String operation) throws IOException {
            if (!operation.equals(block)) return;
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("synthetic wait expired");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("synthetic wait interrupted", error);
            }
        }

        private StateSnapshot state() {
            return new StateSnapshot(AdminSecurityState.NORMAL, "1",
                "2026-09-17T00:00:00Z", AdminRecoveryCustodyState.ATTESTED,
                "2026-09-17T00:00:00Z");
        }

        @Override public LoginResult login(String admin, String password, String totp,
            String device, String label) throws IOException {
            loginCalls += 1;
            before("login");
            return new LoginResult(TOKEN, AdminSecurityState.NORMAL, SESSION);
        }

        @Override public StateSnapshot getState(String token) throws IOException {
            before("state");
            return state();
        }

        @Override public DeviceInventory getDeviceInventory(String token) {
            inventoryCalls += 1;
            return new DeviceInventory(omitCurrentSession ? List.of() : List.of(
                new SessionInfo(SESSION, DEVICE, "test", true, false, "2026-09-17T00:00:00Z")),
                List.of(new DeviceInfo(DEVICE, true)));
        }

        @Override public StateSnapshot revokeSession(String token, String session) {
            revokeCalls += 1;
            return state();
        }

        @Override public StateSnapshot attestRecoveryCustody(String token,
            RecoveryCustodyAttestation attestation) throws IOException {
            custodyReferences.add(attestation.custodyReference());
            if (failCustody) {
                failCustody = false;
                throw new IOException("synthetic uncertain custody response");
            }
            return state();
        }

        @Override public StateSnapshot reportLostDevice(
            String token, String device, java.util.Map<String, String> headers
        ) {
            return state();
        }

        @Override public void clearLocalBinding() { clearBindingCalls += 1; }

        @Override public ReauthenticationResult reauthenticate(String token, String password,
            String totp, String action, String method, String path, String nonce) throws IOException {
            before("reauthenticate");
            return new ReauthenticationResult(20_000L, action, method, path);
        }

        @Override public RecoveryStartResult startRecovery(String admin, String code,
            String device, String label) throws IOException {
            before("recovery");
            return new RecoveryStartResult("synthetic-recovery-token-not-a-real-secret",
                AdminSecurityState.RECOVERY_IN_PROGRESS);
        }

        @Override public LoginResult completeRecovery(String token, String password, String totp,
            String device, String label) {
            return new LoginResult(TOKEN, AdminSecurityState.NORMAL, SESSION);
        }
    }
}
