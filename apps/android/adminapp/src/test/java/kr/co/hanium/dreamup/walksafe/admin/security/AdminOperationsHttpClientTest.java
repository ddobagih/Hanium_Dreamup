package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public final class AdminOperationsHttpClientTest {
    @Test
    public void reviewAndDeliveryUseOnlyFrozenRoutesProofAndCorrelationHeaders() throws Exception {
        FakeTransport transport = new FakeTransport();
        CapturingSigner signer = new CapturingSigner();
        AdminOperationsHttpClient client = client(transport, signer);

        var reviewResult = client.recordReviewDecision(SESSION, REPORT_ID, review());
        var reviewRead = client.readReviewDecisions(SESSION, REPORT_ID);
        var deliveryResult = client.recordDelivery(SESSION, REPORT_ID, delivery());
        var deliveryRead = client.readDeliveries(SESSION, REPORT_ID);

        assertEquals(8, transport.requests.size());
        assertOperationPair(
            transport.requests.get(0), transport.requests.get(1),
            "POST", "/reports/" + REPORT_ID + "/review-decisions",
            "report.review.decide", null
        );
        assertOperationPair(
            transport.requests.get(2), transport.requests.get(3),
            "GET", "/reports/" + REPORT_ID + "/review-decisions",
            null, "report.review_decisions"
        );
        assertOperationPair(
            transport.requests.get(4), transport.requests.get(5),
            "POST", "/reports/" + REPORT_ID + "/deliveries",
            "report.delivery.create", null
        );
        assertOperationPair(
            transport.requests.get(6), transport.requests.get(7),
            "GET", "/reports/" + REPORT_ID + "/deliveries",
            null, "report.delivery_events"
        );
        assertEquals(4, signer.payloads.size());
        assertEquals(Integer.valueOf(1), reviewRead.returnedItemCount());
        assertEquals(Integer.valueOf(1), deliveryRead.returnedItemCount());
        assertEquals("APPROVED", reviewRead.reviewHistory().get(0).decision().name());
        assertEquals(1, deliveryRead.deliveryHistory().get(0).revision());
        assertEquals("SUBMITTED", deliveryRead.deliveryHistory().get(0).status().name());
        assertEquals("서울시 도로관리과", deliveryRead.deliveryHistory().get(0).institution());
        assertEquals("2026-08-09T01:02:03Z", deliveryRead.deliveryHistory().get(0).observedAt());
        assertEquals("2026-08-09T01:02:04Z", deliveryRead.deliveryHistory().get(0).recordedAt());
        assertNull(deliveryRead.deliveryHistory().get(0).externalReceiptId());
        assertNull(reviewResult.returnedItemCount());
        assertNull(deliveryResult.returnedItemCount());
        for (Request request : transport.requests) {
            assertTrue(request.url.startsWith(ORIGIN + "/"));
            assertFalse(request.url.contains("서울시"));
            assertFalse(request.url.contains("institution.example"));
        }
    }

    @Test
    public void exactBodiesBindTheHashOfActuallyTransmittedUtf8Bytes() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminOperationsHttpClient client = client(transport, new CapturingSigner());

        client.recordReviewDecision(SESSION, REPORT_ID, review());
        JSONObject challenge = new JSONObject(transport.requests.get(0).bodyText());
        Request operation = transport.requests.get(1);

        assertEquals(
            AdminCanonicalEncoding.sha256Hex(operation.body),
            challenge.getString("body_sha256")
        );
        JSONObject body = new JSONObject(operation.bodyText());
        assertEquals(EXACT_REVIEW_KEYS, keys(body));
        assertTrue(body.isNull("duplicate_of_report_id"));
        assertEquals(EMPTY_SHA, challenge.getString("query_sha256"));
    }

    @Test
    public void malformedChallengeAndBusinessFailuresStopBeforeAnyFurtherEffect() throws Exception {
        FakeTransport malformed = new FakeTransport();
        malformed.addUnexpectedChallengeField = true;
        assertThrows(IOException.class, () -> client(malformed, new CapturingSigner())
            .recordReviewDecision(SESSION, REPORT_ID, review()));
        assertEquals(1, malformed.requests.size());

        FakeTransport failed = new FakeTransport();
        failed.businessStatus = 409;
        IOException error = assertThrows(IOException.class, () -> client(failed, new CapturingSigner())
            .recordDelivery(SESSION, REPORT_ID, delivery()));
        assertEquals("administrator operation returned unexpected status=409", error.getMessage());
        assertFalse(error.getMessage().contains("private server detail"));
    }

    @Test
    public void frozenSuccessStatusesAreExactForChallengeMutationAndHistory() throws Exception {
        FakeTransport wrongChallenge = new FakeTransport();
        wrongChallenge.challengeStatus = 201;
        assertThrows(IOException.class, () -> client(wrongChallenge, new CapturingSigner())
            .recordReviewDecision(SESSION, REPORT_ID, review()));
        assertEquals(1, wrongChallenge.requests.size());

        FakeTransport wrongMutation = new FakeTransport();
        wrongMutation.businessStatus = 200;
        assertThrows(IOException.class, () -> client(wrongMutation, new CapturingSigner())
            .recordDelivery(SESSION, REPORT_ID, delivery()));

        FakeTransport partialHistory = new FakeTransport();
        partialHistory.readStatus = 206;
        assertThrows(IOException.class, () -> client(partialHistory, new CapturingSigner())
            .readDeliveries(SESSION, REPORT_ID));
    }

    @Test
    public void invalidReportIdIsRejectedWithoutNetworkUse() {
        FakeTransport transport = new FakeTransport();
        assertThrows(IllegalArgumentException.class, () -> client(transport, new CapturingSigner())
            .readDeliveries(SESSION, "../../external"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void historyRequiresStrictArrayExactFieldsAndAppendOnlyRevision() {
        FakeTransport duplicate = new FakeTransport();
        duplicate.reviewHistoryOverride = FakeTransport.reviewHistoryJson().replace(
            "\"revision\":1,",
            "\"revision\":1,\"revision\":1,"
        );
        assertThrows(IOException.class, () -> client(duplicate, new CapturingSigner())
            .readReviewDecisions(SESSION, REPORT_ID));

        FakeTransport wrongRevision = new FakeTransport();
        wrongRevision.deliveryHistoryOverride = FakeTransport.deliveryHistoryJson().replace(
            "\"revision\":1,",
            "\"revision\":2,"
        );
        assertThrows(IOException.class, () -> client(wrongRevision, new CapturingSigner())
            .readDeliveries(SESSION, REPORT_ID));
    }

    private static void assertOperationPair(
        Request challengeRequest,
        Request operationRequest,
        String method,
        String path,
        String action,
        String readPurpose
    ) throws Exception {
        assertEquals("POST", challengeRequest.method);
        assertEquals(ORIGIN + AdminOperationsHttpClient.CHALLENGE_PATH, challengeRequest.url);
        JSONObject challenge = new JSONObject(challengeRequest.bodyText());
        assertEquals(EXACT_CHALLENGE_KEYS, keys(challenge));
        assertEquals("ACTION", challenge.getString("purpose"));
        assertEquals(method, challenge.getString("method"));
        assertEquals(path, challenge.getString("path"));
        if (action == null) assertTrue(challenge.isNull("action"));
        else assertEquals(action, challenge.getString("action"));
        if (readPurpose == null) assertTrue(challenge.isNull("read_purpose"));
        else assertEquals(readPurpose, challenge.getString("read_purpose"));

        assertEquals(method, operationRequest.method);
        assertEquals(ORIGIN + path, operationRequest.url);
        assertEquals("Bearer " + ACCESS_TOKEN, operationRequest.headers.get("Authorization"));
        assertEquals("ADMIN_ANDROID", operationRequest.headers.get("X-WalkSafe-App-Kind"));
        assertEquals("ADMIN", operationRequest.headers.get("X-WalkSafe-Role"));
        assertEquals(DEVICE_ID, operationRequest.headers.get("X-WalkSafe-Device-Id"));
        assertEquals(
            challenge.getString("correlation_id"),
            operationRequest.headers.get(AdminOperationsHttpClient.CORRELATION_ID_HEADER)
        );
        assertEquals(
            AdminDeviceProofTest.CHALLENGE_ID,
            operationRequest.headers.get(AdminDeviceProof.CHALLENGE_ID_HEADER)
        );
        assertFalse(operationRequest.headers.get(AdminDeviceProof.SIGNATURE_HEADER).contains("="));
        assertEquals(readPurpose, operationRequest.headers.get(AdminOperationsHttpClient.READ_PURPOSE_HEADER));
    }

    private static AdminOperationsHttpClient client(FakeTransport transport, CapturingSigner signer) {
        return new AdminOperationsHttpClient(
            ORIGIN,
            true,
            MARKER,
            1,
            signer,
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static AdminReportDecision review() {
        return new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED,
            "reviewed",
            null,
            true,
            true,
            true
        );
    }

    private static AdminInstitutionDelivery delivery() {
        return new AdminInstitutionDelivery(
            "서울시 도로관리과",
            "phone",
            "duty officer",
            AdminInstitutionDelivery.Status.SUBMITTED,
            null,
            "manually submitted outside the app",
            null,
            "2026-08-09T01:02:03Z",
            0L,
            "55555555-5555-4555-8555-555555555555"
        );
    }

    private static Set<String> keys(JSONObject value) {
        Set<String> result = new HashSet<>();
        value.keys().forEachRemaining(result::add);
        return result;
    }

    private static final class CapturingSigner implements AdminDeviceProof.Signer {
        final List<byte[]> payloads = new ArrayList<>();

        @Override
        public byte[] sign(byte[] payload) {
            payloads.add(payload.clone());
            return new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01};
        }
    }

    private static final class FakeTransport implements AdminOperationsHttpClient.Transport {
        final List<Request> requests = new ArrayList<>();
        boolean addUnexpectedChallengeField;
        int challengeStatus = 200;
        int businessStatus = 201;
        int readStatus = 200;
        String reviewHistoryOverride;
        String deliveryHistoryOverride;

        @Override
        public AdminOperationsHttpClient.Response execute(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body
        ) throws IOException {
            Request request = new Request(method, url, headers, body);
            requests.add(request);
            if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                try {
                    JSONObject intent = new JSONObject(request.bodyText());
                    Map<String, Object> exact18 = new LinkedHashMap<>();
                    exact18.put("action", nullable(intent, "action"));
                    exact18.put("admin_id", intent.getString("admin_id"));
                    exact18.put("body_sha256", intent.getString("body_sha256"));
                    exact18.put("challenge_id", AdminDeviceProofTest.CHALLENGE_ID);
                    exact18.put("correlation_id", intent.getString("correlation_id"));
                    exact18.put("device_id", intent.getString("device_id"));
                    exact18.put("device_key_marker", intent.getString("device_key_marker"));
                    exact18.put("device_key_version", intent.getInt("device_key_version"));
                    exact18.put(
                        "expires_at_epoch_ms",
                        AdminDeviceProofTest.ISSUED_AT + AdminDeviceProof.CHALLENGE_TTL_MS
                    );
                    exact18.put("issued_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT);
                    exact18.put("method", intent.getString("method"));
                    exact18.put("nonce", AdminDeviceProofTest.NONCE);
                    exact18.put("path", intent.getString("path"));
                    exact18.put("purpose", intent.getString("purpose"));
                    exact18.put("query_sha256", intent.getString("query_sha256"));
                    exact18.put("read_purpose", nullable(intent, "read_purpose"));
                    exact18.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
                    exact18.put("session_id", nullable(intent, "session_id"));
                    Map<String, Object> exact19 = new LinkedHashMap<>(exact18);
                    exact19.put("signing_payload", AdminCanonicalEncoding.canonicalJson(exact18));
                    if (addUnexpectedChallengeField) exact19.put("unexpected", true);
                    return new AdminOperationsHttpClient.Response(
                        challengeStatus,
                        AdminCanonicalEncoding.canonicalJson(exact19)
                    );
                } catch (Exception error) {
                    throw new IOException("fake challenge failed", error);
                }
            }
            if (businessStatus < 200 || businessStatus > 299) {
                return new AdminOperationsHttpClient.Response(businessStatus, "private server detail");
            }
            if ("GET".equals(method) && url.endsWith("/review-decisions")) {
                return new AdminOperationsHttpClient.Response(
                    readStatus,
                    reviewHistoryOverride == null ? reviewHistoryJson() : reviewHistoryOverride
                );
            }
            if ("GET".equals(method) && url.endsWith("/deliveries")) {
                return new AdminOperationsHttpClient.Response(
                    readStatus,
                    deliveryHistoryOverride == null ? deliveryHistoryJson() : deliveryHistoryOverride
                );
            }
            return new AdminOperationsHttpClient.Response(businessStatus, "{}");
        }

        private static String reviewHistoryJson() {
            return """
                [{
                  "id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "report_id":"44444444-4444-4444-8444-444444444444",
                  "revision":1,
                  "decision":"APPROVED",
                  "reason":"reviewed",
                  "duplicate_of_report_id":null,
                  "location_reviewed":true,
                  "photo_reviewed":true,
                  "privacy_reviewed":true,
                  "admin_id":"admin-001",
                  "session_id":"33333333-3333-4333-8333-333333333333",
                  "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
                  "correlation_id":"66666666-6666-4666-8666-666666666666",
                  "decided_at":"2026-08-09T01:00:00Z",
                  "created_at":"2026-08-09T01:00:00Z"
                }]
                """;
        }

        private static String deliveryHistoryJson() {
            return """
                [{
                  "id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                  "report_id":"44444444-4444-4444-8444-444444444444",
                  "review_decision_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "revision":1,
                  "institution":"서울시 도로관리과",
                  "channel":"phone",
                  "recipient":"duty officer",
                  "status":"SUBMITTED",
                  "external_receipt_id":null,
                  "reason":"manually submitted outside the app",
                  "evidence_sha256":null,
                  "observed_at":"2026-08-09T01:02:03Z",
                  "expected_revision":0,
                  "idempotency_key":"55555555-5555-4555-8555-555555555555",
                  "admin_id":"admin-001",
                  "session_id":"33333333-3333-4333-8333-333333333333",
                  "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
                  "correlation_id":"77777777-7777-4777-8777-777777777777",
                  "recorded_at":"2026-08-09T01:02:04Z"
                }]
                """;
        }

        private static Object nullable(JSONObject object, String key) throws Exception {
            return object.isNull(key) ? null : object.getString(key);
        }
    }

    private static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = Map.copyOf(headers);
            this.body = body == null ? new byte[0] : body.clone();
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final Set<String> EXACT_CHALLENGE_KEYS = Set.of(
        "action", "admin_id", "body_sha256", "correlation_id", "device_id",
        "device_key_marker", "device_key_version", "method", "path", "purpose",
        "query_sha256", "read_purpose", "session_id"
    );
    private static final Set<String> EXACT_REVIEW_KEYS = Set.of(
        "decision", "reason", "duplicate_of_report_id",
        "location_reviewed", "photo_reviewed", "privacy_reviewed"
    );
    private static final String ORIGIN = "http://127.0.0.1:8000";
    private static final String REPORT_ID = "44444444-4444-4444-8444-444444444444";
    private static final String DEVICE_ID = AdminDeviceProofTest.DEVICE_ID;
    private static final String MARKER = AdminDeviceProofTest.MARKER;
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String EMPTY_SHA = AdminDeviceProofTest.EMPTY_SHA;
    private static final AdminOperationsApi.SessionContext SESSION = new AdminOperationsApi.SessionContext(
        ACCESS_TOKEN,
        "admin-001",
        AdminDeviceProofTest.SESSION_ID,
        DEVICE_ID
    );
}
