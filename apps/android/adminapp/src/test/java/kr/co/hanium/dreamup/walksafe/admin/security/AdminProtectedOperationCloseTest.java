package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Real authority -> domain operations -> HTTP adapter -> proof executor, with a delayed transport. */
public final class AdminProtectedOperationCloseTest {
    private static final String INCIDENT = "11111111-1111-4111-8111-111111111111";

    @Test public void incidentChallengeResumingAfterCloseCannotDispatchPatch() throws Exception {
        assertLateChallengeCannotDispatch(false, false);
    }
    @Test public void reportChallengeResumingAfterCloseCannotDispatchPatch() throws Exception {
        assertLateChallengeCannotDispatch(true, false);
    }
    @Test public void incidentChallengeResumingAfterBackgroundCannotDispatchPatch() throws Exception {
        assertLateChallengeCannotDispatch(false, true);
    }
    @Test public void reportChallengeResumingAfterBackgroundCannotDispatchPatch() throws Exception {
        assertLateChallengeCannotDispatch(true, true);
    }

    @Test public void incidentSigningResumingAfterCloseCannotDispatchPatch() throws Exception {
        assertLateChallengeCannotDispatch(false, false, true);
    }
    @Test public void reportSigningResumingAfterCloseCannotDispatchPatch() throws Exception {
        assertLateChallengeCannotDispatch(true, false, true);
    }

    private void assertLateChallengeCannotDispatch(boolean report, boolean background) throws Exception {
        assertLateChallengeCannotDispatch(report, background, false);
    }
    private void assertLateChallengeCannotDispatch(boolean report, boolean background, boolean pauseSigner) throws Exception {
        CountDownLatch challengeEntered = new CountDownLatch(1);
        CountDownLatch releaseChallenge = new CountDownLatch(1);
        AtomicInteger patches = new AtomicInteger();
        AtomicInteger dispatchMarks = new AtomicInteger();
        java.util.function.Function<byte[], String> challenge = body -> {
            if (!pauseSigner) awaitRelease(challengeEntered, releaseChallenge);
            try {
                Map<String, Object> value = AdminStrictJson.parseObject(new String(body, StandardCharsets.UTF_8));
                AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
                    (String) value.get("action"), (String) value.get("admin_id"),
                    (String) value.get("body_sha256"), (String) value.get("correlation_id"),
                    (String) value.get("device_id"), (String) value.get("device_key_marker"),
                    ((Long) value.get("device_key_version")).intValue(), (String) value.get("method"),
                    (String) value.get("path"), AdminDeviceProof.Purpose.ACTION,
                    (String) value.get("query_sha256"), (String) value.get("read_purpose"),
                    (String) value.get("session_id"));
                return AdminDeviceProofTest.challengeResponse(intent, AdminDeviceProofTest.ISSUED_AT, null);
            } catch (IOException error) { throw new AssertionError(error); }
        };
        AdminDeviceProof.Signer signer = body -> {
            if (pauseSigner) awaitRelease(challengeEntered, releaseChallenge);
            return new byte[] {0x30, 0x06, 0x02, 0x01, 1, 0x02, 0x01, 1};
        };
        AdminIncidentHttpClient incidents = new AdminIncidentHttpClient("http://127.0.0.1:8000", true,
            AdminDeviceProofTest.MARKER, 1, signer, () -> AdminDeviceProofTest.ISSUED_AT + 1L,
            (method, url, headers, body) -> {
                if (method.equals("PATCH")) { patches.incrementAndGet(); throw new IOException("unexpected PATCH"); }
                return new AdminIncidentHttpClient.Response(200, challenge.apply(body));
            });
        AdminReportHttpClient reports = new AdminReportHttpClient("http://127.0.0.1:8000", true,
            AdminDeviceProofTest.MARKER, 1, signer, () -> AdminDeviceProofTest.ISSUED_AT + 1L,
            (method, url, headers, body) -> {
                if (method.equals("PATCH")) { patches.incrementAndGet(); throw new IOException("unexpected PATCH"); }
                return new AdminReportHttpClient.Response(200, challenge.apply(body));
            });
        AdminSecurityApi delegate = AdminLocalOperationCloseRaceProbe.fakeApi();
        AdminSecurityApi api = (AdminSecurityApi) Proxy.newProxyInstance(AdminSecurityApi.class.getClassLoader(),
            new Class<?>[] {AdminSecurityApi.class}, (proxy, method, args) -> {
                if (method.getName().equals("reauthenticate")) {
                    java.util.Arrays.fill((char[]) args[1], '\0');
                    java.util.Arrays.fill((char[]) args[2], '\0');
                    return new AdminSecurityApi.ReauthenticationResult(20_000L,
                        (String) args[3], (String) args[4], (String) args[5]);
                }
                if (method.getName().equals("cancelInFlightRequests")) return null;
                try { return method.invoke(delegate, args); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
        AdminSecurityController controller = new AdminSecurityController(api, null, reports, incidents, null, event -> {});
        AdminLocalOperationCloseRaceProbe.login(controller);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        char[] password = "synthetic-password".toCharArray();
        char[] totp = "123456".toCharArray();
        try {
            Future<?> operation = worker.submit(() -> report
                ? controller.reports().updateAdminReportStatus(INCIDENT, "reviewed", 1,
                    password, totp, 10_000L, true, () -> { dispatchMarks.incrementAndGet(); return true; })
                : controller.incidents().updateAdminIncidentStatus(INCIDENT,
                    new AdminIncidentModels.StatusRequest("ACKNOWLEDGED", 1,
                        "33333333-3333-4333-8333-333333333333", "사고를 확인한 사유입니다.",
                        "서비스 중단을 관찰했습니다.", "b".repeat(64)), password, totp, 10_000L, true));
            assertTrue("real protected client must reach the challenge", challengeEntered.await(3, TimeUnit.SECONDS));
            if (background) controller.suspendForBackground(false); else controller.close();
            assertFalse(controller.snapshot().isAccessSessionActive());
            releaseChallenge.countDown();
            assertThrows(ExecutionException.class, () -> operation.get(3, TimeUnit.SECONDS));
            assertEquals("stale authority must send no final PATCH", 0, patches.get());
            assertEquals("stale authority must not mark a report mutation dispatched", 0, dispatchMarks.get());
            assertArrayEquals(new char[password.length], password);
            assertArrayEquals(new char[totp.length], totp);
        } finally {
            releaseChallenge.countDown();
            controller.close();
            worker.shutdownNow();
        }
    }
    private static void awaitRelease(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }
}
