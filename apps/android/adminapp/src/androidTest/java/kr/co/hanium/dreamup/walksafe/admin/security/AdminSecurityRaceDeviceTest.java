package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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

    /** Real buttons and executor; only the server's custody statement is synthetic. No backup is made. */
    @Test public void custodyConfirmationCannotClaimBackupVerificationOrSubmitWithoutUserConfirmation() throws Exception {
        Fixture fixture = new Fixture("unused", false);
        fixture.custody = AdminRecoveryCustodyState.UNATTESTED;
        fixture.login();
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                install(activity, fixture.controller); invoke(activity, "render");
                assertEquals(View.VISIBLE, ((View) get(activity, "custodyGroup")).getVisibility());
                assertTrue(button(activity.getWindow().getDecorView(), "복구자료 외부 보관 확인 기록").performClick());
                assertEquals(0, fixture.custodyRequests.size());
                assertTrue(((TextView) get(activity, "resultText")).getText().toString().contains("확인란"));
                ((CheckBox) get(activity, "custodyConfirmationInput")).setChecked(true);
                assertTrue(button(activity.getWindow().getDecorView(), "복구자료 외부 보관 확인 기록").performClick());
                assertFalse(((CheckBox) get(activity, "custodyConfirmationInput")).isChecked());
            });
            awaitSecurityWork(scenario);
            scenario.onActivity(activity -> {
                assertEquals(1, fixture.custodyRequests.size());
                assertEquals(AdminRecoveryCustodyState.ATTESTED, fixture.controller.snapshot().recoveryCustodyState());
                String result = ((TextView) get(activity, "resultText")).getText().toString();
                assertTrue(result, result.contains("실제 백업 복원 여부는 별도 확인"));
                assertTrue(button(activity.getWindow().getDecorView(), "서버·기기 보안 정보 펼치기").performClick());
                TextView label = get(activity, "custodyText");
                assertTrue(label.isShown());
                assertTrue(label.getText().toString(), label.getText().toString().contains("백업·복원 미검증"));
                assertEquals(View.GONE, ((View) get(activity, "custodyGroup")).getVisibility());
            });
        } finally { fixture.controller.close(); }
    }

    @Test public void lostCustodyResponseRequiresExplicitRetryWithTheSameAttempt() throws Exception {
        Fixture fixture = new Fixture("unused", false);
        fixture.custody = AdminRecoveryCustodyState.UNATTESTED;
        fixture.failCustodyOnce = true;
        fixture.login();
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                install(activity, fixture.controller); invoke(activity, "render");
                ((CheckBox) get(activity, "custodyConfirmationInput")).setChecked(true);
                assertTrue(button(activity.getWindow().getDecorView(), "복구자료 외부 보관 확인 기록").performClick());
            });
            awaitSecurityWork(scenario);
            assertEquals(1, fixture.custodyRequests.size());
            // Transport failure retains the token for explicit state refresh, but locks work.
            assertEquals(AdminSecurityState.FAIL_CLOSED, fixture.controller.snapshot().securityState());
            assertNotNull(fixture.controller.pendingRecoveryCustodyMaterialKind());
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, ((View) get(activity, "operationsGroup")).getVisibility());
                assertEquals(View.GONE, ((View) get(activity, "custodyGroup")).getVisibility());
                assertTrue(button(activity.getWindow().getDecorView(), "서버 보안상태와 기기 세션 새로고침").performClick());
            });
            awaitSecurityWork(scenario);
            assertEquals(AdminSecurityState.NORMAL, fixture.controller.snapshot().securityState());
            scenario.onActivity(activity -> {
                invoke(activity, "render");
                assertEquals(1, fixture.custodyRequests.size());
                assertTrue(((TextView) get(activity, "custodyText")).getText().toString().contains("확정되지 않았습니다"));
                assertFalse(((View) get(activity, "custodyMaterialKindInput")).isEnabled());
                ((CheckBox) get(activity, "custodyConfirmationInput")).setChecked(true);
                assertTrue(button(activity.getWindow().getDecorView(), "복구자료 외부 보관 확인 기록").performClick());
            });
            awaitSecurityWork(scenario);
            assertEquals(2, fixture.custodyRequests.size());
            assertEquals(fixture.custodyRequests.get(0).custodyReference(), fixture.custodyRequests.get(1).custodyReference());
            assertEquals(fixture.custodyRequests.get(0).materialKind(), fixture.custodyRequests.get(1).materialKind());
            assertNull(fixture.controller.pendingRecoveryCustodyMaterialKind());
            assertEquals(AdminRecoveryCustodyState.ATTESTED, fixture.controller.snapshot().recoveryCustodyState());
        } finally { fixture.controller.close(); }
    }

    @Test public void refreshedUnattestedCustodyReopensConfirmationAndRemovesAttestedLabel() throws Exception {
        Fixture fixture = new Fixture("unused", false); fixture.login();
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                install(activity, fixture.controller); invoke(activity, "render");
                assertEquals(View.GONE, ((View) get(activity, "custodyGroup")).getVisibility());
                fixture.custody = AdminRecoveryCustodyState.UNATTESTED; fixture.stateRevision++;
                assertTrue(button(activity.getWindow().getDecorView(), "서버 보안상태와 기기 세션 새로고침").performClick());
            });
            awaitSecurityWork(scenario);
            scenario.onActivity(activity -> {
                assertEquals(AdminRecoveryCustodyState.UNATTESTED, fixture.controller.snapshot().recoveryCustodyState());
                assertEquals(View.VISIBLE, ((View) get(activity, "custodyGroup")).getVisibility());
                String label = ((TextView) get(activity, "custodyText")).getText().toString();
                assertTrue(label, label.contains("미확인") && label.contains("고위험 작업 잠금"));
                assertEquals(View.GONE, ((View) get(activity, "operationsGroup")).getVisibility());
            });
        } finally { fixture.controller.close(); }
    }

    private static void awaitSecurityWork(ActivityScenario<AdminBoundaryActivity> scenario) throws Exception {
        ExecutorService[] executor = new ExecutorService[1];
        scenario.onActivity(activity -> executor[0] = get(activity, "networkExecutor"));
        executor[0].submit(() -> {}).get(5, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test public void recoveryStartDisplaysServerTotpPrerequisiteAndKeepsOperationsLocked() throws Exception {
        recoveryStartForm(false);
    }

    @Test public void failedRecoveryStartDoesNotExposeCompletionForm() throws Exception {
        recoveryStartForm(true);
    }

    /** Native form handlers with synthetic history; does not enable the operational build gate. */
    @Test public void deliveryPrefillRequiresCompleteMatchingHistoryAndPreservesUserInput() throws Exception {
        Fixture fixture = new Fixture("unused", false); fixture.login();
        AdminReportModels.Detail detail = deliveryDetail();
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                install(activity, fixture.controller); invoke(activity, "render");
                ((AdminReportController) get(activity, "reportController")).replaceDetail(detail);
                invokeWith(activity, "connectReportToOperations", new Class<?>[]{AdminReportModels.Detail.class}, detail);
                EditText input = get(activity, "institutionInput");
                for (String condition : List.of("valid", "typed", "partial", "count", "stale", "reload", "terminal")) {
                    List<AdminOperationsApi.DeliveryHistoryItem> history = get(activity, "deliveryHistoryItems");
                    history.clear(); history.add(deliveryItem(condition.equals("terminal")));
                    set(activity, "deliveryHistorySnapshotRevision", condition.equals("stale") ? 2L : 1L);
                    set(activity, "deliveryHistoryTotalCount", condition.equals("count") ? 2L : 1L);
                    set(activity, "deliveryHistoryNextCursor", condition.equals("partial") ? "next" : null);
                    set(activity, "deliveryHistoryNeedsFirstPage", condition.equals("reload"));
                    input.setText(condition.equals("typed") ? "사용자 입력 기관" : "");
                    invoke(activity, "prefillDeliveryInstitution");
                    assertEquals(condition, condition.equals("valid") ? "기존 시험 기관" :
                        condition.equals("typed") ? "사용자 입력 기관" : "", input.getText().toString());
                }
            });
        } finally { fixture.controller.close(); }
    }

    @Test public void institutionConflictUsesFreshHistoryAndRequestsManualResubmission() throws Exception {
        deliveryConflictForm(true, true);
    }

    @Test public void institutionConflictWithStaleHistoryDoesNotOverwriteTheInstitution() throws Exception {
        deliveryConflictForm(true, false);
    }

    @Test public void ordinaryConflictKeepsTheInstitutionAndDoesNotClaimMismatch() throws Exception {
        deliveryConflictForm(false, true);
    }

    private void deliveryConflictForm(boolean mismatch, boolean fresh) throws Exception {
        Fixture fixture = new Fixture("unused", false); fixture.login();
        AdminReportModels.Detail detail = deliveryDetail();
        var history = AdminOperationsApi.Result.deliveryHistory("synthetic-correlation", 200,
            detail.summary().id(), fresh ? 1L : 2L, 1L, null, List.of(deliveryItem(false)));
        Class<?> refreshType = Class.forName(AdminBoundaryActivity.class.getName() + "$OperationalConflictRefresh");
        var ctor = refreshType.getDeclaredConstructor(AdminReportModels.Detail.class, AdminOperationsApi.Result.class);
        ctor.setAccessible(true); Object refresh = ctor.newInstance(detail, history);
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                install(activity, fixture.controller); invoke(activity, "render");
                ((AdminReportController) get(activity, "reportController")).replaceDetail(detail);
                invokeWith(activity, "connectReportToOperations", new Class<?>[]{AdminReportModels.Detail.class}, detail);
                EditText input = get(activity, "institutionInput"); input.setText("사용자 입력 기관");
                set(activity, "operationInFlight", true); set(activity, "recordingDelivery", true);
                invokeWith(activity, "applyOperationalConflict",
                    new Class<?>[]{String.class, boolean.class, refreshType, boolean.class},
                    detail.summary().id(), true, refresh, mismatch);
                assertEquals(mismatch && fresh ? "기존 시험 기관" : "사용자 입력 기관", input.getText().toString());
                String result = ((TextView) get(activity, "resultText")).getText().toString();
                assertTrue(result, result.contains(mismatch ? "기관명이 다릅니다" : "다른 관리자의 변경"));
                assertTrue(result, result.contains(mismatch ? "자동으로 다시 제출하지 않았습니다" : "자동 재제출하지 않고"));
                if (mismatch) assertTrue(result, result.contains(fresh ? "직접 다시 제출" : "다시 조회"));
                assertFalse((Boolean) get(activity, "operationInFlight"));
                assertFalse((Boolean) get(activity, "recordingDelivery"));
            });
        } finally { fixture.controller.close(); }
    }

    private static AdminReportModels.Detail deliveryDetail() throws Exception {
        try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
            .open("admin-report-detail-v2.json")) {
            var json = new org.json.JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            json.put("latest_delivery_revision", 1);
            json.getJSONObject("current_delivery").put("revision", 1).put("status", "SUBMITTED");
            return AdminReportModels.parseDetail(json.toString(), AdminDeviceFixtures.REPORT_ID);
        }
    }

    private static AdminOperationsApi.DeliveryHistoryItem deliveryItem(boolean terminal) {
        return new AdminOperationsApi.DeliveryHistoryItem("99999999-9999-4999-8999-999999999999", 1L,
            "88888888-8888-4888-8888-888888888888", 1L,
            terminal ? AdminInstitutionDelivery.Status.RESOLVED : AdminInstitutionDelivery.Status.SUBMITTED,
            terminal ? "synthetic-receipt" : null, "기존 시험 기관",
            "2026-10-25T00:00:00Z", "2026-10-25T00:00:01Z", null);
    }

    private static void set(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invokeWith(Object target, String name, Class<?>[] types, Object... args) {
        try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void recoveryStartForm(boolean fail) throws Exception {
        Fixture fixture = new Fixture("startRecovery", fail);
        fixture.release.countDown();
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitInitialized(scenario);
            scenario.onActivity(activity -> {
                install(activity, fixture.controller); invoke(activity, "render");
                ((EditText) get(activity, "adminIdInput")).setText("admin-001");
                ((EditText) get(activity, "recoveryCodeInput")).setText("synthetic-recovery-code-only");
                assertTrue(button(activity.getWindow().getDecorView(), "분실 복구 시작 및 기존 세션 폐기").performClick());
                assertEquals(0, ((EditText) get(activity, "recoveryCodeInput")).length());
            });
            assertTrue("recovery request reached the synthetic API", fixture.entered.await(5, TimeUnit.SECONDS));
            awaitSecurityWork(scenario);
            scenario.onActivity(activity -> {
                assertFalse(fixture.controller.snapshot().isAccessSessionActive());
                assertEquals(View.GONE, ((View) get(activity, "operationsGroup")).getVisibility());
                ViewGroup form = get(activity, "recoveryCompleteGroup");
                String result = ((TextView) get(activity, "resultText")).getText().toString();
                if (fail) {
                    assertEquals(AdminSecurityState.FAIL_CLOSED, fixture.controller.snapshot().securityState());
                    assertFalse(fixture.controller.snapshot().isRecoveryActive());
                    assertEquals(View.GONE, form.getVisibility());
                    assertFalse(result, result.contains("복구가 시작되었습니다"));
                } else {
                    assertTrue(fixture.controller.snapshot().isRecoveryActive());
                    assertEquals(View.VISIBLE, form.getVisibility());
                    assertTrue(result, result.contains(AdminRecoveryMessagePolicy.RECOVERY_PREPARATION_KO));
                    assertTrue(((TextView) form.getChildAt(0)).isShown());
                    assertEquals(AdminRecoveryMessagePolicy.RECOVERY_PREPARATION_KO,
                        ((TextView) form.getChildAt(0)).getText().toString());
                    assertEquals("새 비밀번호", ((EditText) get(activity, "newPasswordInput")).getHint().toString());
                    assertTrue(((EditText) get(activity, "newTotpInput")).getHint().toString().contains("새 TOTP"));
                }
            });
        } finally { fixture.controller.close(); }
    }

    private static Button button(View root, String text) {
        if (root instanceof Button && text.contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

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
            scenario.onActivity(activity -> ready[0] = get(activity, "controller") != null);
            if (!ready[0]) Thread.sleep(25);
        }
        assertTrue("native key initialization must finish before injecting test API", ready[0]);
        until = android.os.SystemClock.elapsedRealtime() + 5000;
        boolean[] focused = {false};
        while (!focused[0] && android.os.SystemClock.elapsedRealtime() < until) {
            scenario.onActivity(activity -> focused[0] = activity.hasWindowFocus() && (Boolean) get(activity, "activityStarted"));
            if (!focused[0]) Thread.sleep(25);
        }
        assertTrue("the real administrator window must be visible and started", focused[0]);
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
        volatile AdminRecoveryCustodyState custody = AdminRecoveryCustodyState.ATTESTED;
        volatile int stateRevision = 1;
        volatile boolean failCustodyOnce;
        final java.util.List<AdminSecurityApi.RecoveryCustodyAttestation> custodyRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
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
                        case "getState": case "revokeSession": return state();
                        case "attestRecoveryCustody":
                            custodyRequests.add((AdminSecurityApi.RecoveryCustodyAttestation) args[1]);
                            if (failCustodyOnce) { failCustodyOnce = false; throw new IOException("synthetic response loss"); }
                            custody = AdminRecoveryCustodyState.ATTESTED; stateRevision++; return state();
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
        AdminSecurityApi.StateSnapshot state() { return new AdminSecurityApi.StateSnapshot(AdminSecurityState.NORMAL,
            Integer.toString(stateRevision), "2026-10-07T00:00:00Z", custody,
            custody == AdminRecoveryCustodyState.ATTESTED ? "2026-10-06T00:00:00Z" : null); }
        void login() throws IOException { controller.login("admin-001", "synthetic-password", "123456", DEVICE, "fixture"); }
    }
}
