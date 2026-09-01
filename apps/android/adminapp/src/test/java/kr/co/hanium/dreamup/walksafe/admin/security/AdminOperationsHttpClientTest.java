package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        assertNull(reviewRead.reviewHistory().get(0).userVisibleReason());
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
        assertEquals(3, body.getInt("content_revision"));
        assertEquals(EVIDENCE_GRANT_ID, body.getString("evidence_grant_id"));
        assertTrue(body.has("user_visible_reason"));
        assertTrue(body.isNull("user_visible_reason"));
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

        FakeTransport missingPublicReason = new FakeTransport();
        missingPublicReason.reviewHistoryOverride = FakeTransport.reviewHistoryJson().replace(
            "\"user_visible_reason\":null,",
            ""
        );
        assertThrows(IOException.class, () -> client(missingPublicReason, new CapturingSigner())
            .readReviewDecisions(SESSION, REPORT_ID));

        FakeTransport missingContentRevision = new FakeTransport();
        missingContentRevision.reviewHistoryOverride = FakeTransport.reviewHistoryJson().replace(
            "\"content_revision\":0,",
            ""
        );
        assertThrows(IOException.class, () -> client(missingContentRevision, new CapturingSigner())
            .readReviewDecisions(SESSION, REPORT_ID));

        FakeTransport invalidApprovedPublicReason = new FakeTransport();
        invalidApprovedPublicReason.reviewHistoryOverride = FakeTransport.reviewHistoryJson().replace(
            "\"user_visible_reason\":null",
            "\"user_visible_reason\":\"공개 거절 사유\""
        );
        assertThrows(IOException.class, () ->
            client(invalidApprovedPublicReason, new CapturingSigner())
                .readReviewDecisions(SESSION, REPORT_ID)
        );

        FakeTransport wrongRevision = new FakeTransport();
        wrongRevision.deliveryHistoryOverride = FakeTransport.deliveryHistoryJson().replace(
            "\"revision\":1,",
            "\"revision\":2,"
        );
        assertThrows(IOException.class, () -> client(wrongRevision, new CapturingSigner())
            .readDeliveries(SESSION, REPORT_ID));
    }

    @Test
    public void originalEvidenceUsesExactV2GrantReconfirmationAndOneBinaryRead() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminOperationsHttpClient client = client(transport, new CapturingSigner());

        AdminOriginalEvidence evidence = client.loadOriginalEvidence(
            SESSION,
            REPORT_ID,
            3,
            "승인 검토를 위한 원본 증거 확인",
            Map.of(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER, RECONFIRMATION_NONCE)
        );

        assertEquals(2, transport.requests.size());
        assertEquals(1, transport.binaryRequests.size());
        assertOperationPair(
            transport.requests.get(0),
            transport.requests.get(1),
            "POST",
            "/reports/" + REPORT_ID + "/original-access-grants",
            AdminOperationsHttpClient.ORIGINAL_GRANT_ACTION,
            null
        );
        Request grantRequest = transport.requests.get(1);
        JSONObject grantBody = new JSONObject(grantRequest.bodyText());
        JSONObject grantChallenge = new JSONObject(transport.requests.get(0).bodyText());
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(grantRequest.body),
            grantChallenge.getString("body_sha256")
        );
        assertEquals(Set.of("purpose", "reason", "expected_content_revision"), keys(grantBody));
        assertEquals("report_review", grantBody.getString("purpose"));
        assertEquals(3, grantBody.getInt("expected_content_revision"));
        assertEquals(
            RECONFIRMATION_NONCE,
            grantRequest.headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
        Request imageRequest = transport.binaryRequests.get(0);
        assertEquals("GET", imageRequest.method);
        assertEquals(ORIGIN + "/uploads/" + REPORT_ID + ".png", imageRequest.url);
        assertEquals(
            ORIGINAL_ACCESS_TOKEN,
            imageRequest.headers.get(AdminOperationsHttpClient.ORIGINAL_ACCESS_GRANT_HEADER)
        );
        assertEquals("no-store", imageRequest.headers.get("Cache-Control"));
        assertEquals("no-cache", imageRequest.headers.get("Pragma"));
        assertFalse(imageRequest.headers.containsKey(AdminDeviceProof.CHALLENGE_ID_HEADER));
        assertFalse(imageRequest.headers.containsKey(AdminDeviceProof.SIGNATURE_HEADER));
        assertFalse(imageRequest.headers.containsKey(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER));
        assertFalse(imageRequest.headers.containsKey(AdminOperationsHttpClient.READ_PURPOSE_HEADER));
        assertTrue(evidence.matches(REPORT_ID, 3, SESSION.sessionId(), DEVICE_ID, NOW));
        evidence.close();
    }

    @Test
    public void originalEvidenceFailsClosedBeforeOrAfterGrantOnAnyBindingMismatch() {
        FakeTransport missingReconfirmation = new FakeTransport();
        assertThrows(IllegalArgumentException.class, () -> client(
            missingReconfirmation, new CapturingSigner()
        ).loadOriginalEvidence(SESSION, REPORT_ID, 3, "long enough reason", Map.of()));
        assertTrue(missingReconfirmation.requests.isEmpty());

        FakeTransport staleRevision = new FakeTransport();
        staleRevision.grantOverride = staleRevision.grantJson().replace(
            "\"content_revision\":3",
            "\"content_revision\":4"
        );
        assertThrows(IOException.class, () -> loadOriginal(client(staleRevision, new CapturingSigner())));
        assertTrue(staleRevision.binaryRequests.isEmpty());

        FakeTransport extraField = new FakeTransport();
        extraField.grantOverride = extraField.grantJson().replace(
            "\"grant_id\"",
            "\"unexpected\":true,\"grant_id\""
        );
        assertThrows(IOException.class, () -> loadOriginal(client(extraField, new CapturingSigner())));
        assertTrue(extraField.binaryRequests.isEmpty());

        FakeTransport wrongMime = new FakeTransport();
        wrongMime.binaryContentType = "image/jpeg";
        assertThrows(IOException.class, () -> loadOriginal(client(wrongMime, new CapturingSigner())));

        FakeTransport wrongLength = new FakeTransport();
        wrongLength.binaryDeclaredLength = PNG.length + 1L;
        assertThrows(IOException.class, () -> loadOriginal(client(wrongLength, new CapturingSigner())));

        FakeTransport wrongSha = new FakeTransport();
        wrongSha.grantOverride = wrongSha.grantJson().replace(
            AdminCanonicalEncoding.sha256Hex(PNG),
            "0".repeat(64)
        );
        assertThrows(IOException.class, () -> loadOriginal(client(wrongSha, new CapturingSigner())));
    }

    @Test
    public void cancelledOriginalEvidenceFailsBeforeAnyNetworkUse() {
        FakeTransport transport = new FakeTransport();
        Thread.currentThread().interrupt();
        try {
            IOException error = assertThrows(
                IOException.class,
                () -> loadOriginal(client(transport, new CapturingSigner()))
            );
            assertEquals("original evidence request was cancelled", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(transport.requests.isEmpty());
            assertTrue(transport.binaryRequests.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void responseReadersAllowThreeConsecutiveZeroReadsAndRejectTheFourth() throws Exception {
        ZeroThenDataInputStream allowedJson = new ZeroThenDataInputStream(3, "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{}", AdminOperationsHttpClient.readBoundedResponse(allowedJson));
        assertTrue(allowedJson.closed);
        assertBufferZeroed(allowedJson.lastBuffer);

        ZeroThenDataInputStream rejectedJson = new ZeroThenDataInputStream(4, "{}".getBytes(StandardCharsets.UTF_8));
        IOException jsonError = assertThrows(
            IOException.class,
            () -> AdminOperationsHttpClient.readBoundedResponse(rejectedJson)
        );
        assertEquals("administrator API response made no progress", jsonError.getMessage());
        assertTrue(rejectedJson.closed);
        assertBufferZeroed(rejectedJson.lastBuffer);

        ZeroThenDataInputStream allowedImage = new ZeroThenDataInputStream(3, PNG);
        byte[] image = AdminOperationsHttpClient.readOriginalEvidenceBody(allowedImage, PNG.length);
        try {
            assertArrayEquals(PNG, image);
        } finally {
            java.util.Arrays.fill(image, (byte) 0);
        }
        assertTrue(allowedImage.closed);
        assertBufferZeroed(allowedImage.lastBuffer);

        ZeroThenDataInputStream rejectedImage = new ZeroThenDataInputStream(4, PNG);
        IOException imageError = assertThrows(
            IOException.class,
            () -> AdminOperationsHttpClient.readOriginalEvidenceBody(rejectedImage, PNG.length)
        );
        assertEquals("original evidence response made no progress", imageError.getMessage());
        assertTrue(rejectedImage.closed);
        assertBufferZeroed(rejectedImage.lastBuffer);
    }

    @Test
    public void responseReadersFailClosedWhenInterruptedDuringRead() {
        InterruptingInputStream json = new InterruptingInputStream("{}".getBytes(StandardCharsets.UTF_8));
        try {
            IOException error = assertThrows(
                IOException.class,
                () -> AdminOperationsHttpClient.readBoundedResponse(json)
            );
            assertEquals("administrator API response read was cancelled", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(json.closed);
        assertBufferZeroed(json.lastBuffer);

        InterruptingInputStream image = new InterruptingInputStream(PNG);
        try {
            IOException error = assertThrows(
                IOException.class,
                () -> AdminOperationsHttpClient.readOriginalEvidenceBody(image, PNG.length)
            );
            assertEquals("original evidence request was cancelled", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(image.closed);
        assertBufferZeroed(image.lastBuffer);
    }

    private static void assertBufferZeroed(byte[] buffer) {
        assertTrue(buffer != null);
        for (byte value : buffer) assertEquals(0, value);
    }

    private static AdminOriginalEvidence loadOriginal(AdminOperationsHttpClient client) throws Exception {
        return client.loadOriginalEvidence(
            SESSION,
            REPORT_ID,
            3,
            "long enough reason",
            Map.of(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER, RECONFIRMATION_NONCE)
        );
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
            null,
            true,
            true,
            true,
            3,
            EVIDENCE_GRANT_ID
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
            1L,
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

    private static class ZeroThenDataInputStream extends InputStream {
        private int zeroReadsRemaining;
        private final byte[] data;
        private int offset;
        byte[] lastBuffer;
        boolean closed;

        ZeroThenDataInputStream(int zeroReads, byte[] data) {
            this.zeroReadsRemaining = zeroReads;
            this.data = data.clone();
        }

        @Override
        public int read(byte[] buffer, int start, int length) {
            lastBuffer = buffer;
            if (zeroReadsRemaining-- > 0) return 0;
            if (offset >= data.length) return -1;
            int count = Math.min(length, data.length - offset);
            System.arraycopy(data, offset, buffer, start, count);
            offset += count;
            return count;
        }

        @Override
        public int read() {
            if (offset >= data.length) return -1;
            return data[offset++] & 0xff;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class InterruptingInputStream extends ZeroThenDataInputStream {
        private boolean interrupted;

        InterruptingInputStream(byte[] data) {
            super(0, data);
        }

        @Override
        public int read(byte[] buffer, int start, int length) {
            int read = super.read(buffer, start, length);
            if (!interrupted) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            return read;
        }
    }

    private static final class FakeTransport implements AdminOperationsHttpClient.Transport {
        final List<Request> requests = new ArrayList<>();
        final List<Request> binaryRequests = new ArrayList<>();
        boolean addUnexpectedChallengeField;
        int challengeStatus = 200;
        int businessStatus = 201;
        int readStatus = 200;
        String reviewHistoryOverride;
        String deliveryHistoryOverride;
        String grantOverride;
        int binaryStatus = 200;
        String binaryContentType = "image/png";
        long binaryDeclaredLength = PNG.length;
        byte[] binaryBody = PNG.clone();

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
            if (url.endsWith("/original-access-grants")) {
                return new AdminOperationsHttpClient.Response(
                    businessStatus,
                    grantOverride == null ? grantJson() : grantOverride
                );
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

        @Override
        public AdminOperationsHttpClient.BinaryResponse executeBinary(
            String method,
            String url,
            Map<String, String> headers,
            int expectedByteCount
        ) {
            binaryRequests.add(new Request(method, url, headers, null));
            return new AdminOperationsHttpClient.BinaryResponse(
                binaryStatus,
                binaryContentType,
                binaryDeclaredLength,
                binaryBody.clone()
            );
        }

        private String grantJson() {
            Map<String, Object> exactLocation = new LinkedHashMap<>();
            exactLocation.put("lat", 37.5665);
            exactLocation.put("lon", 126.978);
            exactLocation.put("accuracy", 4.5);
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("resource_path", "/uploads/" + REPORT_ID + ".png");
            image.put("content_type", "image/png");
            image.put("sha256", AdminCanonicalEncoding.sha256Hex(PNG));
            image.put("byte_count", PNG.length);
            image.put("access_token", ORIGINAL_ACCESS_TOKEN);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schema_version", AdminOriginalEvidence.SCHEMA_VERSION);
            root.put("grant_id", EVIDENCE_GRANT_ID);
            root.put("content_revision", 3);
            root.put("expires_at", Instant.ofEpochMilli(NOW + 120_000L).toString());
            root.put("exact_location", exactLocation);
            root.put("image", image);
            return new JSONObject(root).toString();
        }

        private static String reviewHistoryJson() {
            return """
                [{
                  "id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "report_id":"44444444-4444-4444-8444-444444444444",
                  "revision":1,
                  "content_revision":0,
                  "decision":"APPROVED",
                  "reason":"reviewed",
                  "user_visible_reason":null,
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
                  "package_id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                  "package_revision":1,
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
        "decision", "reason", "user_visible_reason", "duplicate_of_report_id",
        "location_reviewed", "photo_reviewed", "privacy_reviewed", "content_revision",
        "evidence_grant_id"
    );
    private static final String ORIGIN = "http://127.0.0.1:8000";
    private static final String REPORT_ID = "44444444-4444-4444-8444-444444444444";
    private static final String EVIDENCE_GRANT_ID = "88888888-8888-4888-8888-888888888888";
    private static final String DEVICE_ID = AdminDeviceProofTest.DEVICE_ID;
    private static final String MARKER = AdminDeviceProofTest.MARKER;
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String RECONFIRMATION_NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String ORIGINAL_ACCESS_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final long NOW = AdminDeviceProofTest.ISSUED_AT + 1L;
    private static final byte[] PNG = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final String EMPTY_SHA = AdminDeviceProofTest.EMPTY_SHA;
    private static final AdminOperationsApi.SessionContext SESSION = new AdminOperationsApi.SessionContext(
        ACCESS_TOKEN,
        "admin-001",
        AdminDeviceProofTest.SESSION_ID,
        DEVICE_ID
    );
}
