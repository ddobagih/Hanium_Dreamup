package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.Test;

/** Pauses the actual HTTP adapter after the executor's last check, before real Call registration. */
public final class AdminBackgroundDispatchRaceTest {
    private static final String INCIDENT = "11111111-1111-4111-8111-111111111111";

    @Test public void incidentBackgroundBeforeRegistrationRejectsPatchAndAllowsNewLease() throws Exception {
        assertBackgroundBeforeRegistration(false);
    }

    @Test public void reportBackgroundBeforeRegistrationRejectsPatchAndAllowsNewLease() throws Exception {
        assertBackgroundBeforeRegistration(true);
    }

    private void assertBackgroundBeforeRegistration(boolean report) throws Exception {
        CountDownLatch beforeRegistration = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        AtomicInteger patches = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(dispatcher(patches, null));
            server.start();
            String origin = server.url("/").toString();
            Object actualTransport = productionTransport(origin, report);
            Class<?> contract = report ? AdminReportHttpClient.Transport.class : AdminIncidentHttpClient.Transport.class;
            Object paused = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract},
                (proxy, method, args) -> {
                    // Supports the original four arguments and the admission-bound overload.
                    int methodArgument = args != null && args.length == 5 ? 1 : 0;
                    if (method.getName().equals("execute") && "PATCH".equals(args[methodArgument])
                        && pauseOnce.getAndSet(false)) {
                        beforeRegistration.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    try { return method.invoke(actualTransport, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
            AdminSecurityController controller = controller(origin, report, paused);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> first = worker.submit(() -> update(controller, report));
                assertTrue(beforeRegistration.await(3, TimeUnit.SECONDS));
                controller.suspendForBackground(false);
                assertTrue(controller.isLocallyLocked());
                release.countDown();
                Throwable failure = null;
                try { first.get(3, TimeUnit.SECONDS); }
                catch (ExecutionException error) { failure = error.getCause(); }
                assertEquals("background completed before Call registration: no PATCH", 0, patches.get());
                assertNotNull("expired dispatch must fail", failure);
                controller.resumeLocalSession(controller.localGeneration());
                worker.submit(() -> update(controller, report)).get(3, TimeUnit.SECONDS);
                assertEquals("a fresh resumed lease must remain usable", 1, patches.get());
            } finally {
                release.countDown();
                controller.close();
                worker.shutdownNow();
            }
        }
    }

    @Test public void incidentBackgroundCancelsAdmittedCallWithoutClosingTheClient() throws Exception {
        assertBackgroundAfterRegistration(false);
    }

    @Test public void reportBackgroundCancelsAdmittedCallWithoutClosingTheClient() throws Exception {
        assertBackgroundAfterRegistration(true);
    }

    private void assertBackgroundAfterRegistration(boolean report) throws Exception {
        CountDownLatch admittedPatch = new CountDownLatch(1);
        AtomicInteger patches = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(dispatcher(patches, admittedPatch));
            server.start();
            String origin = server.url("/").toString();
            AdminSecurityController controller = controller(origin, report, productionTransport(origin, report));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> first = worker.submit(() -> update(controller, report));
                assertTrue(admittedPatch.await(3, TimeUnit.SECONDS));
                controller.suspendForBackground(false);
                ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> first.get(2, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IOException);
                controller.resumeLocalSession(controller.localGeneration());
                worker.submit(() -> update(controller, report)).get(3, TimeUnit.SECONDS);
                assertEquals(2, patches.get());
            } finally {
                controller.close();
                worker.shutdownNow();
            }
        }
    }

    private static Object update(AdminSecurityController controller, boolean report) throws Exception {
        if (report) return controller.reports().updateAdminReportStatus(INCIDENT, "reviewed", 1,
            "synthetic-password".toCharArray(), "123456".toCharArray(), 10_000L, true);
        return controller.incidents().updateAdminIncidentStatus(INCIDENT,
            new AdminIncidentModels.StatusRequest("ACKNOWLEDGED", 1,
                "33333333-3333-4333-8333-333333333333", "사고를 확인한 사유입니다.",
                "서비스 중단을 관찰했습니다.", "b".repeat(64)),
            "synthetic-password".toCharArray(), "123456".toCharArray(), 10_000L, true);
    }

    private static AdminDeviceProof.Signer signer() {
        return body -> new byte[] {0x30, 0x06, 0x02, 0x01, 1, 0x02, 0x01, 1};
    }

    private static Object productionTransport(String origin, boolean report) throws Exception {
        AdminDeviceKeyStore.Descriptor descriptor = new AdminDeviceKeyStore.Descriptor(
            AdminDeviceProofTest.MARKER, 1, "unused");
        Object production = report ? new AdminReportHttpClient(origin, true, descriptor, signer())
            : new AdminIncidentHttpClient(origin, true, descriptor, signer());
        Field field = production.getClass().getDeclaredField("transport");
        field.setAccessible(true);
        return field.get(production);
    }

    private static AdminSecurityController controller(String origin, boolean report, Object transport) throws Exception {
        AdminReportRepository reports = report ? new AdminReportHttpClient(origin, true,
            AdminDeviceProofTest.MARKER, 1, signer(), () -> AdminDeviceProofTest.ISSUED_AT + 1,
            (AdminReportHttpClient.Transport) transport) : null;
        AdminIncidentRepository incidents = report ? null : new AdminIncidentHttpClient(origin, true,
            AdminDeviceProofTest.MARKER, 1, signer(), () -> AdminDeviceProofTest.ISSUED_AT + 1,
            (AdminIncidentHttpClient.Transport) transport);
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
        return controller;
    }

    private static Dispatcher dispatcher(AtomicInteger patches, CountDownLatch admittedPatch) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    if ("PATCH".equals(request.getMethod())) {
                        int count = patches.incrementAndGet();
                        if (admittedPatch != null) admittedPatch.countDown();
                        MockResponse.Builder response = new MockResponse.Builder().code(200)
                            .addHeader("Content-Type", "application/json")
                            .body("{\"schema_version\":\"walksafe.admin-incident-status.v1\","
                                + "\"incident_id\":\"" + INCIDENT + "\",\"status\":\"ACKNOWLEDGED\","
                                + "\"status_version\":2,\"allowed_next_states\":[\"RESOLVED\"],"
                                + "\"updated_at\":\"2026-08-29T00:02:00Z\"}");
                        if (request.getTarget().startsWith("/admin/reports/")) {
                            response.body("{\"schema_version\":\"walksafe.admin-report-status.v1\","
                                + "\"id\":\"" + INCIDENT + "\",\"status\":\"reviewed\","
                                + "\"status_version\":2,\"allowed_next_statuses\":[\"resolved\"],"
                                + "\"updated_at\":\"2026-08-29T00:02:00Z\"}");
                        }
                        if (admittedPatch != null && count == 1) response.bodyDelay(5, TimeUnit.SECONDS);
                        return response.build();
                    }
                    Map<String, Object> value = AdminStrictJson.parseObject(request.getBody().utf8());
                    AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
                        (String) value.get("action"), (String) value.get("admin_id"),
                        (String) value.get("body_sha256"), (String) value.get("correlation_id"),
                        (String) value.get("device_id"), (String) value.get("device_key_marker"),
                        ((Long) value.get("device_key_version")).intValue(), (String) value.get("method"),
                        (String) value.get("path"), AdminDeviceProof.Purpose.ACTION,
                        (String) value.get("query_sha256"), (String) value.get("read_purpose"),
                        (String) value.get("session_id"));
                    return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .body(AdminDeviceProofTest.challengeResponse(intent, AdminDeviceProofTest.ISSUED_AT, null)).build();
                } catch (IOException error) { throw new AssertionError(error); }
            }
        };
    }
}
