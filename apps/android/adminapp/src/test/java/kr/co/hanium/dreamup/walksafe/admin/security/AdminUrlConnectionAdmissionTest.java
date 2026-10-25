package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;

/** Real authority, endpoint adapter and URLConnection transport; only connection I/O is simulated. */
public final class AdminUrlConnectionAdmissionTest {
    private static final String ORIGIN = "http://127.0.0.1:8000";
    private static final String REPORT = "44444444-4444-4444-8444-444444444444";
    private enum Kind { REVIEW, DELIVERY, RAW_DECISION, RAW_HOLD, ORIGINAL, LOGIN, RECOVERY_START, REFRESH }

    @Test public void reviewBeforeAdmission() throws Exception { race(Kind.REVIEW, false); }
    @Test public void deliveryBeforeAdmission() throws Exception { race(Kind.DELIVERY, false); }
    @Test public void rawDecisionBeforeAdmission() throws Exception { race(Kind.RAW_DECISION, false); }
    @Test public void rawHoldBeforeAdmission() throws Exception { race(Kind.RAW_HOLD, false); }
    @Test public void loginBeforeAdmission() throws Exception { race(Kind.LOGIN, false); }
    @Test public void reviewAdmittedCancellation() throws Exception { race(Kind.REVIEW, true); }
    @Test public void deliveryAdmittedCancellation() throws Exception { race(Kind.DELIVERY, true); }
    @Test public void rawDecisionAdmittedCancellation() throws Exception { race(Kind.RAW_DECISION, true); }
    @Test public void rawHoldAdmittedCancellation() throws Exception { race(Kind.RAW_HOLD, true); }
    @Test public void loginAdmittedCancellation() throws Exception { race(Kind.LOGIN, true); }

    @Test public void originalImageBeforeAdmission() throws Exception { race(Kind.ORIGINAL, false); }
    @Test public void originalImageAdmittedCancellation() throws Exception { race(Kind.ORIGINAL, true); }
    @Test public void recoveryStartBeforeAdmission() throws Exception { race(Kind.RECOVERY_START, false); }
    @Test public void recoveryStartAdmittedCancellation() throws Exception { race(Kind.RECOVERY_START, true); }
    @Test public void securityReadBeforeAdmission() throws Exception { race(Kind.REFRESH, false); }
    @Test public void securityReadAdmittedCancellation() throws Exception { race(Kind.REFRESH, true); }

    private static boolean authentication(Kind kind) { return kind == Kind.LOGIN || kind == Kind.RECOVERY_START; }
    private static boolean security(Kind kind) { return authentication(kind) || kind == Kind.REFRESH; }

    private void race(Kind kind, boolean admitted) throws Exception {
        Fixture f = new Fixture(kind, admitted);
        AdminSecurityController controller = f.controller();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            if (!authentication(kind)) controller.login("admin-001", "synthetic-password", "123456",
                AdminLocalOperationCloseRaceProbe.DEVICE, "test");
            f.armed = true;
            Future<?> first = worker.submit(() -> operation(controller, kind));
            assertTrue("request did not reach the controlled boundary", f.entered.await(3, TimeUnit.SECONDS));
            controller.suspendForBackground(false);
            f.release.countDown();
            Throwable failure = null;
            try { first.get(2, TimeUnit.SECONDS); }
            catch (ExecutionException error) { failure = error.getCause(); }
            assertEquals("non-admitted write must not reach connection I/O", admitted ? 1 : 0, f.writes.get());
            assertNotNull("invalidated operation must fail", failure);
            assertEquals("all deadline alarms must be released", 0, f.activeAlarms.get());
            if (authentication(kind)) controller.allowFreshAuthentication();
            else controller.resumeLocalSession(controller.localGeneration());
            int resumedRequests = (admitted ? 1 : 0) + (kind == Kind.REFRESH ? 1 : 0);
            assertEquals("confirmed resume performs one security-state read", resumedRequests, f.writes.get());
            worker.submit(() -> operation(controller, kind)).get(3, TimeUnit.SECONDS);
            assertEquals("a fresh operation must still be admitted", resumedRequests + 1, f.writes.get());
            assertEquals(0, f.activeAlarms.get());
        } finally {
            f.release.countDown();
            f.disconnectAll();
            controller.close();
            worker.shutdownNow();
        }
    }

    private static Object operation(AdminSecurityController controller, Kind kind) throws Exception {
        switch (kind) {
            case REVIEW: return controller.deliveries().recordReviewDecision(REPORT,
                (AdminReportDecision) fixtureValue(AdminOperationsHttpClientTest.class, "review"), true);
            case DELIVERY: return controller.deliveries().recordDelivery(REPORT,
                (AdminInstitutionDelivery) fixtureValue(AdminOperationsHttpClientTest.class, "delivery"), true);
            case RAW_DECISION: return controller.rawCollections().decideAdminRawCollectionPurpose(
                AdminRawCollectionModelsTest.summary(), AdminRawCollectionModelsTest.reportDecision(),
                "synthetic-password".toCharArray(), "123456".toCharArray(), 10_000L, true);
            case RAW_HOLD: return controller.rawCollections().recordAdminRawCollectionLegalHold(
                AdminRawCollectionModelsTest.COLLECTION, AdminRawCollectionModelsTest.legalHold(),
                "synthetic-password".toCharArray(), "123456".toCharArray(), 10_000L, true);
            case ORIGINAL:
                try (AdminOriginalEvidence image = controller.deliveries().loadAdminOriginalEvidence(
                    REPORT, 3, "신고 위치와 원본 증거를 확인합니다.", "synthetic-password".toCharArray(),
                    "123456".toCharArray(), 10_000L, true)) { return image.byteCount(); }
            case RECOVERY_START:
                controller.startRecovery("admin-001", "synthetic-recovery-code-for-test", AdminLocalOperationCloseRaceProbe.DEVICE, "test");
                return controller.snapshot();
            case REFRESH: controller.refresh(); return controller.snapshot();
            case LOGIN:
                controller.login("admin-001", "synthetic-password", "123456",
                    AdminLocalOperationCloseRaceProbe.DEVICE, "test");
                return controller.snapshot();
            default: throw new AssertionError(kind);
        }
    }

    private static Object fixtureValue(Class<?> owner, String name) throws Exception {
        Method method = owner.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(null);
    }
    private static Object fixtureTransport(Class<?> owner) throws Exception {
        Class<?> type = Class.forName(owner.getName() + "$FakeTransport");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        return constructor.newInstance();
    }
    private static AdminDeviceProof.Signer signer() {
        return bytes -> new byte[] {0x30, 0x06, 0x02, 0x01, 1, 0x02, 0x01, 1};
    }

    private static final class Fixture {
        final Kind kind; final boolean admitted;
        volatile boolean armed;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean pauseOnce = new AtomicBoolean(true);
        final AtomicInteger writes = new AtomicInteger(), activeAlarms = new AtomicInteger();
        final List<Connection> connections = new CopyOnWriteArrayList<>();
        final AdminOperationsHttpClient.Transport operationResponses;
        final AdminRawCollectionHttpClient.Transport rawResponses;
        final AdminSecurityHttpClient.Transport securityResponses;

        Fixture(Kind kind, boolean admitted) throws Exception {
            this.kind = kind; this.admitted = admitted;
            operationResponses = (AdminOperationsHttpClient.Transport) fixtureTransport(AdminOperationsHttpClientTest.class);
            rawResponses = (AdminRawCollectionHttpClient.Transport) fixtureTransport(AdminRawCollectionHttpClientTest.class);
            securityResponses = (AdminSecurityHttpClient.Transport) fixtureTransport(AdminSecurityHttpClientTest.class);
        }
        boolean target(String url) {
            switch (kind) {
                case REVIEW: return url.endsWith("/review-decisions");
                case DELIVERY: return url.endsWith("/deliveries");
                case RAW_DECISION: return url.endsWith("/decisions");
                case RAW_HOLD: return url.endsWith("/legal-holds");
                case ORIGINAL: return url.contains("/uploads/");
                case RECOVERY_START: return url.endsWith("/recovery/start");
                case REFRESH: return url.endsWith("/security/state");
                case LOGIN: return url.equals(ORIGIN + "/admin/security/sessions");
                default: return false;
            }
        }
        HttpURLConnection open(String url) throws IOException {
            boolean pause = armed && target(url) && pauseOnce.getAndSet(false);
            Connection connection = new Connection(url, pause && admitted);
            connections.add(connection);
            if (pause && !admitted) {
                entered.countDown();
                await(release);
            }
            return connection;
        }
        Runnable alarm() {
            activeAlarms.incrementAndGet(); AtomicBoolean active = new AtomicBoolean(true);
            return () -> { if (active.getAndSet(false)) activeAlarms.decrementAndGet(); };
        }
        AdminSecurityController controller() throws Exception {
            AdminHttpRequestDeadline deadlines = new AdminHttpRequestDeadline(this::open, System::nanoTime,
                (task, delay) -> { Runnable cancel = alarm(); return cancel::run; });
            AdminOperationsHttpClient operations = new AdminOperationsHttpClient(ORIGIN, true,
                AdminDeviceProofTest.MARKER, 1, signer(), () -> AdminDeviceProofTest.ISSUED_AT + 1,
                new AdminOperationsHttpClient.UrlConnectionTransport(deadlines));
            AdminRawCollectionHttpClient raw = new AdminRawCollectionHttpClient(ORIGIN, true,
                AdminDeviceProofTest.MARKER, 1, signer(), () -> AdminDeviceProofTest.ISSUED_AT + 1,
                new AdminRawCollectionHttpClient.UrlConnectionTransport(deadlines));
            AdminSecurityApi api;
            if (security(kind)) {
                api = new AdminSecurityHttpClient(ORIGIN, true, AdminDeviceProofTest.MARKER, 1,
                    signer(), () -> AdminDeviceProofTest.ISSUED_AT + 1,
                    new AdminSecurityHttpClient.UrlConnectionTransport(this::open, System::nanoTime,
                        (task, delay) -> { Runnable cancel = alarm(); return cancel::run; }));
            } else {
                AdminSecurityApi delegate = AdminLocalOperationCloseRaceProbe.fakeApi();
                api = (AdminSecurityApi) java.lang.reflect.Proxy.newProxyInstance(AdminSecurityApi.class.getClassLoader(),
                    new Class<?>[] {AdminSecurityApi.class}, (proxy, method, args) -> {
                        if (method.getName().equals("reauthenticate")) return new AdminSecurityApi.ReauthenticationResult(
                            20_000L, (String) args[3], (String) args[4], (String) args[5]);
                        if (method.getName().equals("cancelInFlightRequests")
                            || method.getName().equals("bindRequestAdmission")) return null;
                        try { return method.invoke(delegate, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            }
            return new AdminSecurityController(api, operations, null, null, raw, event -> {});
        }
        void disconnectAll() { for (Connection connection : connections) connection.disconnect(); }

        private final class Connection extends HttpURLConnection {
            final boolean block; final CountDownLatch disconnected = new CountDownLatch(1);
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] response; int status;
            Connection(String url, boolean block) throws MalformedURLException { super(new URL(url)); this.block = block; }
            @Override public void disconnect() { disconnected.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
            @Override public OutputStream getOutputStream() {
                if (target(url.toString())) writes.incrementAndGet();
                return body;
            }
            @Override public int getResponseCode() throws IOException {
                if (armed && target(url.toString()) && !getDoOutput()) writes.incrementAndGet();
                if (block) {
                    entered.countDown(); await(disconnected);
                    throw new IOException("connection cancelled");
                }
                respond(); return status;
            }
            @Override public InputStream getInputStream() throws IOException { respond(); return new ByteArrayInputStream(response); }
            @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public long getContentLengthLong() { return kind == Kind.ORIGINAL && target(url.toString()) ? image().length : -1; }
            @Override public String getHeaderField(String name) {
                if (name.equalsIgnoreCase("Content-Type")) return kind == Kind.ORIGINAL && target(url.toString()) ? "image/png" : "application/json";
                if (name.equalsIgnoreCase("Cache-Control")) return "no-store";
                if (name.equalsIgnoreCase("Pragma")) return "no-cache";
                return null;
            }
            void respond() throws IOException {
                if (response != null) return;
                Map<String,String> headers = new LinkedHashMap<>();
                getRequestProperties().forEach((name, values) -> headers.put(name, values.get(0)));
                String text;
                if (kind == Kind.ORIGINAL && target(url.toString())) { response = image(); status = 200; return; }
                if (security(kind)) {
                    if (url.getPath().endsWith("/challenges")) {
                        AdminSecurityHttpClient.Response value = securityResponses.execute(method, url.toString(), headers, body.toByteArray());
                        status = value.statusCode; text = value.body;
                    } else if (url.getPath().endsWith("/recovery/start")) {
                        status = 200;
                        text = "{\"recovery_token\":\"synthetic-recovery-token-for-test\",\"security_state\":\"RECOVERY_IN_PROGRESS\"}";
                    } else if (method.equals("POST")) {
                        status = 200;
                        text = "{\"access_token\":\"synthetic-access-token-not-a-real-secret\",\"security_state\":\"NORMAL\",\"current_session_id\":\"" + AdminLocalOperationCloseRaceProbe.SESSION + "\"}";
                    } else if (url.getPath().endsWith("/state")) {
                        status = 200;
                        text = "{\"security_state\":\"NORMAL\",\"state_version\":\"v1\",\"observed_at\":\"2026-07-22T00:00:00Z\",\"recovery_custody_state\":\"ATTESTED\",\"recovery_custody_attested_at\":\"2026-07-21T23:00:00Z\"}";
                    } else {
                        status = 200;
                        text = "{\"sessions\":[{\"session_id\":\"" + AdminLocalOperationCloseRaceProbe.SESSION + "\",\"device_id\":\"synthetic-device\",\"device_label\":\"test\",\"current\":true,\"revoked\":false,\"last_seen_at\":\"2026-07-22T00:00:00Z\"}],\"devices\":[{\"device_id\":\"synthetic-device\",\"current\":true,\"device_label\":\"test\",\"last_seen_at\":\"2026-07-22T00:00:00Z\",\"key_version\":1,\"key_marker_prefix\":\"0123abcd\"}],\"device_total\":1,\"devices_complete\":true}";
                    }
                } else if (kind == Kind.RAW_DECISION || kind == Kind.RAW_HOLD) {
                    AdminRawCollectionHttpClient.Response value = rawResponses.execute(method, url.toString(), headers, body.toByteArray());
                    status = value.statusCode; text = new String(value.body, StandardCharsets.UTF_8);
                } else {
                    AdminOperationsHttpClient.Response value = operationResponses.execute(method, url.toString(), headers, body.toByteArray());
                    status = value.statusCode; text = value.body;
                    if (kind == Kind.REVIEW && target(url.toString())) {
                        try {
                            text = new JSONObject(text).put("session_id", AdminLocalOperationCloseRaceProbe.SESSION)
                                .put("device_id", AdminLocalOperationCloseRaceProbe.DEVICE).toString();
                        } catch (org.json.JSONException error) {
                            throw new IOException("invalid synthetic review response", error);
                        }
                    }
                }
                response = text.getBytes(StandardCharsets.UTF_8);
            }
        }
    }
    private static byte[] image() {
        try {
            Field field = AdminOperationsHttpClientTest.class.getDeclaredField("PNG"); field.setAccessible(true);
            return ((byte[]) field.get(null)).clone();
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test boundary timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
    }
}
