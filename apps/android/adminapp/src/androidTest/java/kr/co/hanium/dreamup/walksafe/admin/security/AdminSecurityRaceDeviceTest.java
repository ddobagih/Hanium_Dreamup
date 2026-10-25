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

    /** Actual Activity and executor; harmless callbacks only, no synthetic login or remote mutation. */
    @Test public void queuedSecurityWorkCannotRunAfterScreenExitAndReturn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            ExecutorService[] executor = new ExecutorService[1];
            scenario.onActivity(activity -> {
                assertTrue(activity.hasWindowFocus());
                executor[0] = get(activity, "networkExecutor");
                executor[0].execute(() -> {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                });
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                startOperation(activity, () -> calls.incrementAndGet());
                assertTrue((Boolean) get(activity, "operationInFlight"));
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            release.countDown();
            executor[0].submit(() -> {}).get(5, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals("old queued work must not execute", 0, calls.get());
                assertFalse((Boolean) get(activity, "operationInFlight"));
                assertNotEquals(SUCCESS, ((TextView) get(activity, "resultText")).getText().toString());
                // Positive control: the new visible screen can submit its own harmless callback.
                startOperation(activity, () -> calls.incrementAndGet());
            });
            executor[0].submit(() -> {}).get(5, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(1, calls.get());
                assertFalse((Boolean) get(activity, "operationInFlight"));
                assertEquals(SUCCESS, ((TextView) get(activity, "resultText")).getText().toString());
            });
        } finally { release.countDown(); }
    }

    @Test public void rejectedExecutorReturnsVisibleFailureWithoutRunningWork() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            ExecutorService[] executor = new ExecutorService[1];
            scenario.onActivity(activity -> executor[0] = get(activity, "networkExecutor"));
            executor[0].submit(() -> {}).get(5, TimeUnit.SECONDS);
            executor[0].shutdown();
            assertTrue(executor[0].awaitTermination(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                assertTrue(activity.hasWindowFocus());
                startOperation(activity, () -> calls.incrementAndGet());
                assertEquals(0, calls.get());
                assertFalse("rejected dispatch must release busy UI", (Boolean) get(activity, "operationInFlight"));
                String text = ((TextView) get(activity, "resultText")).getText().toString();
                assertFalse(text.isBlank());
                assertNotEquals("합성 인증 응답 대기", text);
                assertNotEquals(SUCCESS, text);
                assertTrue(((View) get(activity, "contentRoot")).isEnabled());
            });
        }
    }

    @Test public void logoutRemainsClickableAndRevokesLocallyWhileAnOlderTaskOwnsTheQueue() throws Exception {
        Fixture fixture = new Fixture("never-delayed", false);
        fixture.login();
        CountDownLatch queueEntered = new CountDownLatch(1), releaseQueue = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger oldCalls = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService[] executor = new ExecutorService[1];
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                install(activity, fixture.controller);
                invoke(activity, "render");
                executor[0] = get(activity, "networkExecutor");
                executor[0].execute(() -> {
                    queueEntered.countDown();
                    try { assertTrue(releaseQueue.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                });
            });
            assertTrue(queueEntered.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                startOperation(activity, oldCalls::incrementAndGet);
                assertTrue((Boolean) get(activity, "operationInFlight"));
                Button logout = get(activity, "revokeCurrentButton");
                assertTrue("local logout remains available while other work waits", logout.isEnabled());
                assertTrue(logout.performClick());
                assertFalse("local authority is gone before queue release", fixture.controller.hasRetainedSession());
                assertEquals(0, fixture.revokes.get());
            });
            releaseQueue.countDown();
            executor[0].submit(() -> {}).get(5, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0, oldCalls.get());
            assertEquals(1, fixture.revokes.get());
            assertFalse(fixture.controller.hasRetainedSession());
        } finally { releaseQueue.countDown(); fixture.controller.close(); }
    }

    @Test public void destroyedActivityDoesNotExecuteItsQueuedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService[] executor = new ExecutorService[1];
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                executor[0] = get(activity, "networkExecutor");
                executor[0].execute(() -> {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                });
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> startOperation(activity, () -> calls.incrementAndGet()));
            scenario.moveToState(Lifecycle.State.DESTROYED);
            assertTrue(executor[0].isShutdown());
            release.countDown();
            assertTrue(executor[0].awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        } finally { release.countDown(); }
    }

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
            scenario.onActivity(activity -> {
                String failure = get(activity, "deviceKeyFailure");
                ready[0] = get(activity, "controller") != null || (failure != null
                    && (failure.startsWith("기기 키 초기화 실패(") || failure.startsWith("기기 키 사용 불가(")));
            });
            if (!ready[0]) Thread.sleep(25);
        }
        assertTrue("native initialization must finish or report its failure before injecting the synthetic API", ready[0]);
        // Executor/lifecycle tests still exercise the real runner when native key preparation
        // is unavailable; a synthetic signed-out authority supplies only that test boundary.
        scenario.onActivity(activity -> {
            if (get(activity, "controller") == null) install(activity, new Fixture("never-delayed", false).controller);
        });
    }
    private static void install(AdminBoundaryActivity activity, AdminSecurityController replacement) {
        AdminSecurityController original = get(activity, "controller");
        if (original != null) original.close();
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
        final java.util.concurrent.atomic.AtomicInteger revokes = new java.util.concurrent.atomic.AtomicInteger();
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
                        case "revokeSession": revokes.incrementAndGet();
                        case "getState": return new AdminSecurityApi.StateSnapshot(AdminSecurityState.NORMAL,
                            "v1", "2026-10-07T00:00:00Z", AdminRecoveryCustodyState.ATTESTED, "2026-10-06T00:00:00Z");
                        case "getCompleteDeviceInventory":
                            ((Runnable) args[1]).run();
                            return new AdminSecurityApi.DeviceInventory(List.of(new AdminSecurityApi.SessionInfo(SESSION,
                                DEVICE, "fixture", true, false, "2026-10-07T00:00:00Z")),
                                List.of(new AdminSecurityApi.DeviceInfo(DEVICE, true)));
                        case "clearLocalBinding": case "cancelInFlightRequests": case "bindRequestAdmission": return null;
                        default: throw new AssertionError("Unexpected fixture API call: " + name);
                    }
                });
            controller = new AdminSecurityController(api);
        }
        void login() throws IOException { controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture"); }
    }
}
