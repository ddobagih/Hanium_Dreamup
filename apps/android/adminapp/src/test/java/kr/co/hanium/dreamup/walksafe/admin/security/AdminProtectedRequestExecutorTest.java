package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class AdminProtectedRequestExecutorTest {
    private static final AdminOperationsApi.SessionContext SESSION = new AdminOperationsApi.SessionContext(
        "t".repeat(48), "admin-001", AdminDeviceProofTest.SESSION_ID, AdminDeviceProofTest.DEVICE_ID);
    private static final String PATH = "/admin/reports/" + AdminDeviceProofTest.REPORT_ID + "/status";
    private static final String NONCE_HEADER = "X-WalkSafe-Reconfirmation-Nonce";

    @Test public void canonicalIntentAndOneTimeHeaderReachOnlyTheExactProtectedRequest() throws Exception {
        Capture capture = new Capture();
        byte[] body = "{\"next_status\":\"REVIEWED\"}".getBytes(StandardCharsets.UTF_8);
        AdminProtectedRequestExecutor.Result<String> result = executor(capture).execute(
            SESSION, "PATCH", PATH, "limit=20", "admin.report.status.update", null, body,
            Map.of(NONCE_HEADER, "one-time-value"), "application/json", null);
        assertEquals(2, capture.urls.size());
        for (Map<String, String> headers : capture.headers) {
            assertEquals("no-store", headers.get("Cache-Control"));
            assertEquals("no-cache", headers.get("Pragma"));
        }
        assertFalse(capture.headers.get(0).containsKey(NONCE_HEADER));
        assertEquals("one-time-value", capture.headers.get(1).get(NONCE_HEADER));
        assertEquals("Bearer " + SESSION.accessToken(), capture.headers.get(1).get("Authorization"));
        assertEquals("PATCH", capture.methods.get(1));
        assertEquals("http://127.0.0.1:8000" + PATH + "?limit=20", capture.urls.get(1));
        assertArrayEquals(body, capture.bodies.get(1));
        assertEquals(AdminCanonicalEncoding.sha256Hex(body), capture.intent.bodySha256());
        assertEquals(AdminCanonicalEncoding.sha256Hex("limit=20".getBytes(StandardCharsets.UTF_8)),
            capture.intent.querySha256());
        assertEquals(SESSION.sessionId(), capture.intent.sessionId());
        assertEquals(result.correlationId, capture.intent.correlationId());
        assertEquals("accepted", result.response);
    }
    @Test public void staleDispatchAdmissionNeverSendsTheMutation() {
        Capture capture = new Capture();
        assertThrows(AdminReportWorkflowController.StatusDispatchCancelledException.class, () ->
            executor(capture).execute(SESSION, "PATCH", PATH, "", "admin.report.status.update",
                null, new byte[] {1}, Map.of(NONCE_HEADER, "one-time-value"), "application/json",
                () -> { throw new AdminReportWorkflowController.StatusDispatchCancelledException(); }));
        assertEquals(1, capture.urls.size());
    }
    @Test public void malformedNonceOrSessionMismatchDoesNotDispatchOrRetry() {
        for (Map<String, Object> invalid : List.of(
            Map.<String, Object>of("nonce", "malformed"),
            Map.<String, Object>of("session_id", "66666666-6666-4666-8666-666666666666"))) {
            Capture capture = new Capture();
            capture.overrides = invalid;
            assertThrows(IOException.class, () -> executor(capture).execute(
                SESSION, "PATCH", PATH, "", "admin.report.status.update", null,
                new byte[] {1}, Map.of(), "application/json", null));
            assertEquals(1, capture.urls.size());
        }
    }
    private static AdminProtectedRequestExecutor<String> executor(Capture capture) {
        return new AdminProtectedRequestExecutor<>("http://127.0.0.1:8000",
            AdminDeviceProofTest.MARKER, 1,
            bytes -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            new AdminProtectedRequestExecutor.Clock() {
                @Override public long nowEpochMs() { return AdminDeviceProofTest.ISSUED_AT + 1; }
                @Override public long monotonicNanos() { return 0; }
            }, capture::execute, response -> response,
            Map.of("Cache-Control", "no-store", "Pragma", "no-cache"));
    }
    private static final class Capture {
        final List<String> urls = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        AdminDeviceProof.Intent intent;
        Map<String, Object> overrides;
        String execute(String method, String url, Map<String, String> requestHeaders, byte[] body)
            throws IOException {
            urls.add(url); methods.add(method); headers.add(new LinkedHashMap<>(requestHeaders)); bodies.add(body);
            if (urls.size() != 1) return "accepted";
            Map<String, Object> fields = AdminStrictJson.parseObject(new String(body, StandardCharsets.UTF_8));
            intent = new AdminDeviceProof.Intent((String) fields.get("action"), (String) fields.get("admin_id"),
                (String) fields.get("body_sha256"), (String) fields.get("correlation_id"),
                (String) fields.get("device_id"), (String) fields.get("device_key_marker"),
                ((Long) fields.get("device_key_version")).intValue(), (String) fields.get("method"),
                (String) fields.get("path"), AdminDeviceProof.Purpose.valueOf((String) fields.get("purpose")),
                (String) fields.get("query_sha256"), (String) fields.get("read_purpose"),
                (String) fields.get("session_id"));
            return AdminDeviceProofTest.challengeResponse(intent, AdminDeviceProofTest.ISSUED_AT, overrides);
        }
    }
}
