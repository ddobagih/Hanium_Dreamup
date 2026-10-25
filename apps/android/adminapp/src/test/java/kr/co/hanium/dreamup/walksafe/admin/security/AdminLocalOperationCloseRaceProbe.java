package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Standalone target for tests/close-race/CloseRaceDebugger.java; no production hook. */
public final class AdminLocalOperationCloseRaceProbe {
    static final String SESSION = "11111111-1111-4111-8111-111111111111";
    static final String DEVICE = "synthetic-device";

    static AdminSecurityApi fakeApi() {
        return (AdminSecurityApi) Proxy.newProxyInstance(
            AdminSecurityApi.class.getClassLoader(), new Class<?>[] {AdminSecurityApi.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "login":
                        return new AdminSecurityApi.LoginResult(
                            "synthetic-access-token-not-a-real-secret", AdminSecurityState.NORMAL, SESSION);
                    case "getState":
                        return new AdminSecurityApi.StateSnapshot(AdminSecurityState.NORMAL, "synthetic-v1",
                            "2026-09-17T00:00:00Z", AdminRecoveryCustodyState.ATTESTED,
                            "2026-09-17T00:00:00Z");
                    case "getCompleteDeviceInventory":
                        if (args[1] == null) throw new IllegalArgumentException("current operation check is required");
                        ((Runnable) args[1]).run();
                        // Fall through to the same synthetic inventory as the interface default.
                    case "getDeviceInventory":
                        return new AdminSecurityApi.DeviceInventory(List.of(
                            new AdminSecurityApi.SessionInfo(SESSION, DEVICE, "test", true, false,
                                "2026-09-17T00:00:00Z")),
                            List.of(new AdminSecurityApi.DeviceInfo(DEVICE, true)));
                    case "bindRequestAdmission":
                    case "clearLocalBinding":
                        return null;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            }
        );
    }

    static void login(AdminSecurityController controller) throws Exception {
        controller.login("synthetic-admin", "synthetic-password", "123456", DEVICE, "test");
    }

    public static void main(String[] args) throws Exception {
        AdminSecurityController controller = new AdminSecurityController(fakeApi(), event -> {});
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BufferedReader commands = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("READY");
        Future<?> login = executor.submit(() -> { login(controller); return null; });
        try {
            if (!"BACKGROUND".equals(commands.readLine())) throw new AssertionError("missing background command");
            controller.suspendForBackground(false);
            boolean retained = controller.hasRetainedSession();
            if (!retained) controller.allowFreshAuthentication();
            System.out.println("BACKGROUND retained=" + retained);
            if (!"ASSERT".equals(commands.readLine())) throw new AssertionError("missing assertion command");
            login.get(5, TimeUnit.SECONDS);
            if (!retained) {
                boolean revived = false;
                try {
                    controller.refresh();
                    revived = controller.snapshot().isAccessSessionActive();
                } catch (IllegalStateException expected) {
                    // A discarded login may not supply credentials to the new generation.
                }
                if (revived) throw new AssertionError("stale login credentials revived access after close race");
            } else {
                if (!controller.isLocallyLocked() || controller.snapshot().isAccessSessionActive()) {
                    throw new AssertionError("background access was not concealed");
                }
                controller.resumeLocalSession(controller.localGeneration());
                if (!SESSION.equals(controller.snapshot().currentSessionId())) {
                    throw new AssertionError("confirmed resume lost the retained session");
                }
            }
            System.out.println("RESULT PASS");
        } finally {
            controller.close();
            executor.shutdownNow();
        }
    }
}
