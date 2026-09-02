package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.Test;

public final class AdminPatchOkHttpTransportTest {
    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String REQUEST_ID = "88888888-8888-4888-8888-888888888888";
    private static final String INCIDENT_ID = "99999999-9999-4999-8999-999999999999";
    private static final String TOKEN = "opaque-access-token-for-real-transport-tests";
    private static final String RECONFIRMATION_NONCE = "AAECAwQFBgcICQoLDA0ODw";
    private static final AdminOperationsApi.SessionContext SESSION =
        new AdminOperationsApi.SessionContext(
            TOKEN,
            "admin-001",
            AdminDeviceProofTest.SESSION_ID,
            AdminDeviceProofTest.DEVICE_ID
        );
    private static final Map<String, String> RECONFIRMATION = Map.of(
        AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
        RECONFIRMATION_NONCE
    );

    @Test
    public void realTransportSendsAllThreePatchContracts() throws Exception {
        List<RecordedRequest> patches = new CopyOnWriteArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(dispatcher(patches));
            server.start();
            String origin = server.url("/").toString();
            AdminReportHttpClient reports = reportClient(origin);
            AdminIncidentHttpClient incidents = incidentClient(origin);

            reports.updateStatus(SESSION, REPORT_ID, "resolved", 2, RECONFIRMATION);
            reports.updateRequestStatus(
                SESSION,
                REQUEST_ID,
                "DELETE",
                "REJECTED",
                2,
                "요청 처리를 완료했습니다.",
                "내부 확인 완료",
                RECONFIRMATION
            );
            incidents.updateStatus(
                SESSION,
                INCIDENT_ID,
                new AdminIncidentModels.StatusRequest(
                    "ACKNOWLEDGED",
                    1,
                    "33333333-3333-4333-8333-333333333333",
                    "실제 사고를 확인한 사유입니다.",
                    "민감정보 없이 서비스 중단을 관찰했습니다.",
                    "b".repeat(64)
                ),
                RECONFIRMATION
            );
        }

        assertEquals(3, patches.size());
        RecordedRequest report = patch(patches, "/admin/reports/" + REPORT_ID + "/status");
        assertCommonPatchHeaders(report);
        Map<String, Object> reportBody = body(report);
        assertEquals(Set.of("expected_version", "status"), reportBody.keySet());
        assertEquals(2L, reportBody.get("expected_version"));
        assertEquals("resolved", reportBody.get("status"));

        RecordedRequest request = patch(
            patches,
            "/admin/report-requests/" + REQUEST_ID + "/status"
        );
        assertCommonPatchHeaders(request);
        Map<String, Object> requestBody = body(request);
        assertEquals(
            Set.of("expected_version", "internal_note", "public_response", "status"),
            requestBody.keySet()
        );
        assertEquals(2L, requestBody.get("expected_version"));
        assertEquals("REJECTED", requestBody.get("status"));
        assertEquals("요청 처리를 완료했습니다.", requestBody.get("public_response"));
        assertEquals("내부 확인 완료", requestBody.get("internal_note"));

        RecordedRequest incident = patch(
            patches,
            "/admin/incidents/" + INCIDENT_ID + "/status"
        );
        assertCommonPatchHeaders(incident);
        Map<String, Object> incidentBody = body(incident);
        assertEquals(
            Set.of(
                "evidence_sha256",
                "expected_version",
                "idempotency_key",
                "next_state",
                "observation",
                "reason"
            ),
            incidentBody.keySet()
        );
        assertEquals("ACKNOWLEDGED", incidentBody.get("next_state"));
        assertEquals(1L, incidentBody.get("expected_version"));
    }

    @Test
    public void redirectIsReturnedWithoutForwardingCredentialsOrRetrying() throws Exception {
        try (MockWebServer source = new MockWebServer(); MockWebServer target = new MockWebServer()) {
            source.start();
            target.start();
            source.enqueue(new MockResponse.Builder()
                .code(307)
                .addHeader("Location", target.url("/credential-leak"))
                .build());

            AdminOkHttpTransport.Result response = AdminOkHttpTransport.execute(
                "PATCH",
                source.url("/admin/reports/" + REPORT_ID + "/status").toString(),
                Map.of(
                    "Authorization", "Bearer " + TOKEN,
                    "Content-Type", "application/json; charset=utf-8"
                ),
                "{}".getBytes(StandardCharsets.UTF_8),
                1_024,
                "cancelled",
                "no progress",
                "too large"
            );

            assertEquals(307, response.statusCode);
            assertEquals(1, source.getRequestCount());
            assertEquals(0, target.getRequestCount());
            assertEquals("Bearer " + TOKEN, source.takeRequest().getHeaders().get("Authorization"));
        }
    }

    @Test
    public void successfulGetReturnsBoundedBodyAndResponseHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("{\"ok\":true}")
                .build());

            AdminOkHttpTransport.Result response = AdminOkHttpTransport.execute(
                "GET",
                server.url("/admin/reports?limit=1").toString(),
                Map.of("Authorization", "Bearer " + TOKEN),
                null,
                1_024,
                "cancelled",
                "no progress",
                "too large"
            );

            assertEquals(200, response.statusCode);
            assertArrayEquals(
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
                response.body
            );
            assertEquals("application/json", response.header("content-type"));
            RecordedRequest request = server.takeRequest();
            assertEquals("GET", request.getMethod());
            assertEquals("/admin/reports?limit=1", request.getTarget());
            assertEquals("Bearer " + TOKEN, request.getHeaders().get("Authorization"));
        }
    }

    @Test
    public void interruptionDuringResponseBodyReadCancelsTheRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                .code(200)
                .throttleBody(1, 5, TimeUnit.SECONDS)
                .body("delayed response")
                .build());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread requestThread = new Thread(() -> {
                try {
                    AdminOkHttpTransport.execute(
                        "GET",
                        server.url("/delayed").toString(),
                        Map.of(),
                        null,
                        1_024,
                        "cancelled",
                        "no progress",
                        "too large"
                    );
                } catch (Throwable error) {
                    failure.set(error);
                }
            });

            requestThread.start();
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            Thread.sleep(250);
            requestThread.interrupt();
            requestThread.join(2_000);

            assertFalse("interrupted request did not finish", requestThread.isAlive());
            assertNotNull(failure.get());
            assertEquals(IOException.class, failure.get().getClass());
            assertEquals("cancelled", failure.get().getMessage());
        }
    }

    @Test
    public void responseLimitAndPreInterruptedCancellationFailClosed() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200).body("x".repeat(33)).build());
            IOException oversized = assertThrows(IOException.class, () ->
                AdminOkHttpTransport.execute(
                    "GET",
                    server.url("/oversized").toString(),
                    Map.of(),
                    null,
                    32,
                    "cancelled",
                    "no progress",
                    "too large"
                )
            );
            assertEquals("too large", oversized.getMessage());

            Thread.currentThread().interrupt();
            try {
                IOException cancelled = assertThrows(IOException.class, () ->
                    AdminOkHttpTransport.execute(
                        "GET",
                        server.url("/cancelled").toString(),
                        Map.of(),
                        null,
                        32,
                        "cancelled",
                        "no progress",
                        "too large"
                    )
                );
                assertEquals("cancelled", cancelled.getMessage());
            } finally {
                Thread.interrupted();
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    private static Dispatcher dispatcher(List<RecordedRequest> patches) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String target = request.getTarget();
                try {
                    if (AdminReportHttpClient.CHALLENGE_PATH.equals(target)) {
                        return json(200, AdminDeviceProofTest.challengeResponse(
                            intent(request),
                            System.currentTimeMillis(),
                            null
                        ));
                    }
                    if (("/admin/reports/" + REPORT_ID + "/status").equals(target)) {
                        patches.add(request);
                        return json(200, reportStatus());
                    }
                    if (("/admin/report-requests/" + REQUEST_ID + "/status").equals(target)) {
                        patches.add(request);
                        return json(200, requestStatus());
                    }
                    if (("/admin/incidents/" + INCIDENT_ID + "/status").equals(target)) {
                        patches.add(request);
                        return json(200, incidentStatus());
                    }
                } catch (IOException ignored) {
                    return json(400, "{}");
                }
                return json(404, "{}");
            }
        };
    }

    private static AdminDeviceProof.Intent intent(RecordedRequest request) throws IOException {
        Map<String, Object> value = AdminStrictJson.parseObject(request.getBody().utf8());
        return new AdminDeviceProof.Intent(
            (String) value.get("action"),
            (String) value.get("admin_id"),
            (String) value.get("body_sha256"),
            (String) value.get("correlation_id"),
            (String) value.get("device_id"),
            (String) value.get("device_key_marker"),
            ((Long) value.get("device_key_version")).intValue(),
            (String) value.get("method"),
            (String) value.get("path"),
            AdminDeviceProof.Purpose.valueOf((String) value.get("purpose")),
            (String) value.get("query_sha256"),
            (String) value.get("read_purpose"),
            (String) value.get("session_id")
        );
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build();
    }

    private static AdminReportHttpClient reportClient(String origin) {
        return new AdminReportHttpClient(
            origin,
            true,
            new AdminDeviceKeyStore.Descriptor(AdminDeviceProofTest.MARKER, 1, "unused"),
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01}
        );
    }

    private static AdminIncidentHttpClient incidentClient(String origin) {
        return new AdminIncidentHttpClient(
            origin,
            true,
            new AdminDeviceKeyStore.Descriptor(AdminDeviceProofTest.MARKER, 1, "unused"),
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01}
        );
    }

    private static RecordedRequest patch(List<RecordedRequest> requests, String target) {
        return requests.stream()
            .filter(request -> target.equals(request.getTarget()))
            .findFirst()
            .orElseThrow();
    }

    private static void assertCommonPatchHeaders(RecordedRequest request) {
        assertEquals("PATCH", request.getMethod());
        assertEquals("Bearer " + TOKEN, request.getHeaders().get("Authorization"));
        assertEquals(1, request.getHeaders().values("Authorization").size());
        assertEquals("ADMIN_ANDROID", request.getHeaders().get("X-WalkSafe-App-Kind"));
        assertEquals("ADMIN", request.getHeaders().get("X-WalkSafe-Role"));
        assertEquals("walksafe-admin-api", request.getHeaders().get("X-WalkSafe-Audience"));
        assertEquals(
            AdminDeviceProofTest.DEVICE_ID,
            request.getHeaders().get("X-WalkSafe-Device-Id")
        );
        assertEquals(
            RECONFIRMATION_NONCE,
            request.getHeaders().get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
        assertEquals(
            AdminDeviceProofTest.CHALLENGE_ID,
            request.getHeaders().get(AdminDeviceProof.CHALLENGE_ID_HEADER)
        );
        assertNotNull(request.getHeaders().get(AdminDeviceProof.SIGNATURE_HEADER));
        assertEquals(
            "application/json; charset=utf-8",
            request.getHeaders().get("Content-Type")
        );
        assertNull(request.getHeaders().get("X-WalkSafe-Read-Purpose"));
    }

    private static Map<String, Object> body(RecordedRequest request) throws IOException {
        return AdminStrictJson.parseObject(request.getBody().utf8());
    }

    private static String reportStatus() {
        return "{\"schema_version\":\"walksafe.admin-report-status.v1\","
            + "\"id\":\"" + REPORT_ID + "\",\"status\":\"resolved\","
            + "\"status_version\":3,\"allowed_next_statuses\":[\"reviewed\"],"
            + "\"updated_at\":\"2026-08-29T02:00:00Z\"}";
    }

    private static String requestStatus() {
        return "{\"schema_version\":\"walksafe.admin-report-request-status.v1\","
            + "\"request_id\":\"" + REQUEST_ID + "\","
            + "\"report_id\":\"" + REPORT_ID + "\",\"status\":\"REJECTED\","
            + "\"status_version\":3,\"allowed_next_statuses\":[],"
            + "\"public_response\":\"요청 처리를 완료했습니다.\","
            + "\"updated_at\":\"2026-08-29T03:00:00Z\"}";
    }

    private static String incidentStatus() {
        return "{\"schema_version\":\"walksafe.admin-incident-status.v1\","
            + "\"incident_id\":\"" + INCIDENT_ID + "\",\"status\":\"ACKNOWLEDGED\","
            + "\"status_version\":2,\"allowed_next_states\":[\"RESOLVED\"],"
            + "\"updated_at\":\"2026-08-29T00:02:00Z\"}";
    }
}
