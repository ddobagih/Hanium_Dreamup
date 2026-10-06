package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.*;
import kr.co.hanium.dreamup.walksafe.admin.AdminBoundaryActivity;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Activity lifecycle/executor/controller, with a latch-controlled synthetic security API.
 * Bypasses credential entry/signing for authentication races; not an operational MFA login test. */
@RunWith(AndroidJUnit4.class)
public final class AdminSecurityRaceDeviceTest {
    private static final String SESSION = "66666666-6666-4666-8666-666666666666";
    private static final String DEVICE = "synthetic-race-device";
    private static final String TOKEN = "synthetic-race-access-token-0000000000000000000";
    private static final String SUCCESS = "SYNTHETIC_AUTH_SUCCESS_MUST_NOT_APPEAR";

    @Test public void lateLoginWhileStoppedCannotPublishAuthority() throws Exception { lateAuthentication(false, false); }
    @Test public void lateLoginAfterReturnCannotPublishAuthority() throws Exception { lateAuthentication(false, true); }
    @Test public void lateRecoveryAfterReturnCannotPublishAuthority() throws Exception { lateAuthentication(true, true); }
    @Test public void logoutDropsLocalAuthorityBeforeDelayedServerSuccess() throws Exception { logout(false); }
    @Test public void logoutDropsLocalAuthorityBeforeDelayedServerFailure() throws Exception { logout(true); }

    private void lateAuthentication(boolean recovery, boolean returnFirst) throws Exception {
        Fixture fixture = new Fixture(recovery ? "startRecovery" : "login", false);
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            final ExecutorService[] executor = new ExecutorService[1];
            scenario.onActivity(activity -> {
                install(activity, fixture.controller);
                executor[0] = get(activity, "networkExecutor");
                startOperation(activity, () -> {
                    if (recovery) fixture.controller.startRecovery("admin-001", "synthetic-recovery-only", DEVICE, "fixture");
                    else fixture.login();
                });
            });
            assertTrue("authentication request actually started", fixture.entered.await(5, TimeUnit.SECONDS));
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue(fixture.controller.isLocallyLocked());
            if (returnFirst) scenario.moveToState(Lifecycle.State.RESUMED);
            fixture.release.countDown();
            executor[0].submit(() -> {}).get(5, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (!returnFirst) scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                assertFalse(fixture.controller.hasRetainedSession());
                assertFalse(fixture.controller.hasControllerOwnedCredentials());
                assertFalse(fixture.controller.snapshot().isAccessSessionActive());
                assertFalse(fixture.controller.snapshot().isRecoveryActive());
                assertNotEquals(SUCCESS, ((TextView) get(activity, "resultText")).getText().toString());
                assertEquals(View.GONE, ((View) get(activity, "sessionGroup")).getVisibility());
            });
        } finally { fixture.release.countDown(); fixture.controller.close(); }
    }

    private void logout(boolean serverFails) throws Exception {
        Fixture fixture = new Fixture("revokeSession", serverFails);
        fixture.login();
        assertTrue("positive control: session is initially active", fixture.controller.snapshot().isAccessSessionActive());
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            final ExecutorService[] executor = new ExecutorService[1];
            scenario.onActivity(activity -> {
                install(activity, fixture.controller);
                executor[0] = get(activity, "networkExecutor");
                invoke(activity, "render");
                Button logout = get(activity, "revokeCurrentButton");
                assertTrue(logout.isEnabled()); assertTrue(logout.performClick());
            });
            assertTrue("server revocation actually started", fixture.entered.await(5, TimeUnit.SECONDS));
            try {
                assertFalse("local session must be gone BEFORE server responds", fixture.controller.hasRetainedSession());
                assertFalse("controller secrets must be gone BEFORE server responds", fixture.controller.hasControllerOwnedCredentials());
                scenario.onActivity(activity -> assertEquals("session UI hidden during revocation", View.GONE,
                    ((View) get(activity, "sessionGroup")).getVisibility()));
            } finally { fixture.release.countDown(); }
            executor[0].submit(() -> {}).get(5, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse(fixture.controller.hasRetainedSession());
                String message = ((TextView) get(activity, "resultText")).getText().toString();
                if (serverFails) {
                    assertTrue(message, message.contains("서버"));
                    assertNotEquals("로그아웃했습니다.", message);
                } else assertEquals("로그아웃했습니다.", message);
            });
        } finally { fixture.release.countDown(); fixture.controller.close(); }
    }

    private static void awaitInitialized(ActivityScenario<AdminBoundaryActivity> scenario) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 10000;
        boolean[] ready = {false};
        while (!ready[0] && android.os.SystemClock.elapsedRealtime() < until) {
            scenario.onActivity(activity -> ready[0] = get(activity, "controller") != null);
            if (!ready[0]) Thread.sleep(25);
        }
        assertTrue("native key initialization must finish before injecting test API", ready[0]);
    }
    private static void install(AdminBoundaryActivity activity, AdminSecurityController replacement) {
        AdminSecurityController original = get(activity, "controller");
        original.close();
        try { Field field = AdminBoundaryActivity.class.getDeclaredField("controller"); field.setAccessible(true); field.set(activity, replacement); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private static <T> T get(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void invoke(Object target, String name) {
        try { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private interface Work { void run() throws Exception; }
    private static void startOperation(AdminBoundaryActivity activity, Work work) {
        try {
            Class<?> operation = Class.forName(AdminBoundaryActivity.class.getName() + "$SecurityOperation");
            Object callback = Proxy.newProxyInstance(operation.getClassLoader(), new Class<?>[]{operation},
                (proxy, method, args) -> { work.run(); return null; });
            Method method = AdminBoundaryActivity.class.getDeclaredMethod("runSecurityOperation", String.class,
                String.class, AdminRecoveryMessagePolicy.Phase.class, operation);
            method.setAccessible(true);
            method.invoke(activity, "합성 인증 응답 대기", SUCCESS, AdminRecoveryMessagePolicy.Phase.GENERAL, callback);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static final class Fixture {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AdminSecurityController controller;
        Fixture(String delayedMethod, boolean fail) {
            AdminSecurityApi api = (AdminSecurityApi) Proxy.newProxyInstance(AdminSecurityApi.class.getClassLoader(),
                new Class<?>[]{AdminSecurityApi.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals(delayedMethod)) {
                        entered.countDown();
                        if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("fixture release timed out");
                        if (fail) throw new IOException("synthetic server failure");
                    }
                    switch (name) {
                        case "login": return new AdminSecurityApi.LoginResult(TOKEN, AdminSecurityState.NORMAL, SESSION);
                        case "startRecovery": return new AdminSecurityApi.RecoveryStartResult(TOKEN, AdminSecurityState.RECOVERY_IN_PROGRESS);
                        case "getState": case "revokeSession": return new AdminSecurityApi.StateSnapshot(AdminSecurityState.NORMAL,
                            "v1", "2026-10-07T00:00:00Z", AdminRecoveryCustodyState.ATTESTED, "2026-10-06T00:00:00Z");
                        case "getCompleteDeviceInventory":
                            ((Runnable) args[1]).run();
                            return new AdminSecurityApi.DeviceInventory(List.of(new AdminSecurityApi.SessionInfo(SESSION,
                                DEVICE, "fixture", true, false, "2026-10-07T00:00:00Z")),
                                List.of(new AdminSecurityApi.DeviceInfo(DEVICE, true)));
                        case "clearLocalBinding": case "cancelInFlightRequests": return null;
                        default: throw new AssertionError("Unexpected fixture API call: " + name);
                    }
                });
            controller = new AdminSecurityController(api);
        }
        void login() throws IOException { controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture"); }
    }
}
