package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class AdminImmediateLogoutTest {
    private static final String SESSION = "66666666-6666-4666-8666-666666666666";
    private static final String TOKEN = "synthetic-logout-capability-token";
    private static final String DEVICE = "synthetic-logout-device";

    @Test public void localRevokeCancelsOldAdmissionWithoutWaitingForBlockedRefresh() throws Exception {
        Fixture fixture = new Fixture();
        AdminSecurityController controller = fixture.controller;
        controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture");
        long originalGeneration = controller.localGeneration();
        fixture.blockRefresh = true;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> refresh = workers.submit(() -> { controller.refresh(); return null; });
        try {
            assertTrue(fixture.entered.await(2, TimeUnit.SECONDS));
            AdminSecurityController.CurrentSessionRevocation revoke = workers.submit(
                () -> controller.beginCurrentSessionRevocation(SESSION)).get(500, TimeUnit.MILLISECONDS);
            assertFalse(controller.snapshot().isAccessSessionActive());
            assertFalse(controller.hasRetainedSession());
            assertFalse(controller.isLocalGenerationCurrent(originalGeneration));
            assertEquals(1, fixture.cancelled.get());
            assertThrows(IllegalStateException.class, controller::refresh);
            revoke.revoke();
            assertEquals(1, fixture.revokes.get());
            assertThrows(IllegalStateException.class, revoke::revoke);
            fixture.release.countDown();
            assertThrows(ExecutionException.class, () -> refresh.get(2, TimeUnit.SECONDS));
            assertFalse(controller.snapshot().isAccessSessionActive());
        } finally {
            fixture.release.countDown();
            workers.shutdownNow();
            controller.close();
        }
    }

    @Test public void closedOrBackgroundOwnerCannotDispatchCapturedServerRevoke() throws Exception {
        for (boolean closed : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            fixture.controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture");
            try (AdminSecurityController.CurrentSessionRevocation revoke =
                fixture.controller.beginCurrentSessionRevocation(SESSION)) {
                if (closed) fixture.controller.close(); else fixture.controller.suspendForBackground(false);
                assertThrows(IllegalStateException.class, revoke::revoke);
                assertEquals(0, fixture.revokes.get());
                assertFalse(fixture.controller.hasRetainedSession());
            } finally { fixture.controller.close(); }
        }
    }

    @Test public void capturedRevokeCannotRunAfterAFreshLoginHasAReplacementSession() throws Exception {
        Fixture fixture = new Fixture();
        fixture.controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture");
        try (AdminSecurityController.CurrentSessionRevocation revoke = fixture.controller.beginCurrentSessionRevocation(SESSION)) {
            fixture.currentSession = "88888888-8888-4888-8888-888888888888";
            fixture.controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture");
            assertThrows(IllegalStateException.class, revoke::revoke);
            assertEquals(0, fixture.revokes.get());
            assertEquals(fixture.currentSession, fixture.controller.snapshot().currentSessionId());
            assertTrue(fixture.controller.snapshot().isAccessSessionActive());
        } finally { fixture.controller.close(); }
    }

    @Test public void closingCapturedRevokeCancelsItsOnlyRequestAndLateResponseRestoresNothing() throws Exception {
        Fixture fixture = new Fixture();
        fixture.controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture");
        AdminSecurityController.CurrentSessionRevocation revoke = fixture.controller.beginCurrentSessionRevocation(SESSION);
        fixture.blockRevoke = true;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> result = worker.submit(() -> { revoke.revoke(); return null; });
        try {
            assertTrue(fixture.revokeEntered.await(2, TimeUnit.SECONDS));
            revoke.close();
            assertEquals(1, fixture.cancelled.get());
            fixture.revokeRelease.countDown();
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertEquals(1, fixture.revokes.get());
            assertFalse(fixture.controller.hasRetainedSession());
            assertFalse(fixture.controller.snapshot().isAccessSessionActive());
        } finally {
            fixture.revokeRelease.countDown(); revoke.close(); worker.shutdownNow(); fixture.controller.close();
        }
    }

    private static final class Fixture {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger cancelled = new AtomicInteger(), revokes = new AtomicInteger();
        volatile boolean blockRefresh, blockRevoke;
        volatile String currentSession = SESSION;
        final CountDownLatch revokeEntered = new CountDownLatch(1), revokeRelease = new CountDownLatch(1);
        volatile AdminRequestAdmission admission;
        final AdminSecurityController controller;
        Fixture() {
            AdminSecurityApi api = (AdminSecurityApi) Proxy.newProxyInstance(
                AdminSecurityApi.class.getClassLoader(), new Class<?>[]{AdminSecurityApi.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "bindRequestAdmission": admission = (AdminRequestAdmission) args[0]; return null;
                        case "login": return new AdminSecurityApi.LoginResult(TOKEN, AdminSecurityState.NORMAL, currentSession);
                        case "getState":
                            if (blockRefresh) {
                                try (AdminRequestAdmission.Registration ignored = admission.dispatch(cancelled::incrementAndGet, entered::countDown)) {
                                    if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                                }
                            }
                            return state();
                        case "getCompleteDeviceInventory":
                            ((Runnable) args[1]).run();
                            return new AdminSecurityApi.DeviceInventory(
                                AdminJava8Collections.list(new AdminSecurityApi.SessionInfo(currentSession, DEVICE,
                                    "fixture", true, false, "2026-10-07T00:00:00Z")),
                                AdminJava8Collections.list(new AdminSecurityApi.DeviceInfo(DEVICE, true)));
                        case "revokeSession":
                            assertEquals(TOKEN, args[0]); assertEquals(SESSION, args[1]);
                            assertEquals(DEVICE, args[2]);
                            try (AdminRequestAdmission.Registration ignored = ((AdminRequestAdmission) args[3])
                                .dispatch(cancelled::incrementAndGet, revokes::incrementAndGet)) {
                                if (blockRevoke) {
                                    revokeEntered.countDown();
                                    if (!revokeRelease.await(3, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                                }
                                return state();
                            }
                        case "clearLocalBinding": case "cancelInFlightRequests": return null;
                        default: throw new AssertionError(method.getName());
                    }
                });
            controller = new AdminSecurityController(api);
        }
        private static AdminSecurityApi.StateSnapshot state() {
            return new AdminSecurityApi.StateSnapshot(AdminSecurityState.NORMAL, "v1", "2026-10-07T00:00:00Z",
                AdminRecoveryCustodyState.ATTESTED, "2026-10-06T00:00:00Z");
        }
    }
}
