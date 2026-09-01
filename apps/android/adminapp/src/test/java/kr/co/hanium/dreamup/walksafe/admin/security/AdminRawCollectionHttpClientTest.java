package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class AdminRawCollectionHttpClientTest {
    private static final String TOKEN = "t".repeat(48);
    private static final AdminOperationsApi.SessionContext SESSION =
        new AdminOperationsApi.SessionContext(
            TOKEN,
            "admin-001",
            "66666666-6666-4666-8666-666666666666",
            "device-12345678"
        );
    private static final Map<String, String> RECONFIRMATION = Map.of(
        AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
        "AAECAwQFBgcICQoLDA0ODw"
    );

    @Test
    public void listAndMutationsUseExactProofQueryBodiesAndNoStore() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminRawCollectionHttpClient client = client(transport);
        var decision = AdminRawCollectionModelsTest.reportDecision();
        var hold = AdminRawCollectionModelsTest.legalHold();
        var source = AdminRawCollectionModelsTest.summary();

        client.listQuarantine(SESSION, RECONFIRMATION);
        client.decidePurpose(
            SESSION, source, decision, RECONFIRMATION
        );
        client.recordLegalHold(
            SESSION, AdminRawCollectionModelsTest.COLLECTION, hold, RECONFIRMATION
        );

        assertEquals(6, transport.requests.size());
        Map<String, Object> listProof = AdminStrictJson.parseObject(
            transport.requests.get(0).bodyText()
        );
        Request list = transport.requests.get(1);
        assertEquals("admin.raw_collection.list", listProof.get("read_purpose"));
        assertEquals("GET", listProof.get("method"));
        assertEquals(AdminRawCollectionHttpClient.LIST_PATH, listProof.get("path"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(
                AdminRawCollectionHttpClient.LIST_QUERY.getBytes(StandardCharsets.UTF_8)
            ),
            listProof.get("query_sha256")
        );
        assertEquals(
            "http://127.0.0.1:8000/admin/raw-collections/quarantine?limit=100&state=QUARANTINED",
            list.url
        );
        assertEquals("no-store", list.headers.get("Cache-Control"));
        assertEquals("no-cache", list.headers.get("Pragma"));
        assertEquals(
            RECONFIRMATION.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER),
            list.headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );

        Map<String, Object> decisionProof = AdminStrictJson.parseObject(
            transport.requests.get(2).bodyText()
        );
        Request decisionRequest = transport.requests.get(3);
        Map<String, Object> decisionBody = AdminStrictJson.parseObject(
            decisionRequest.bodyText()
        );
        assertEquals("admin.raw_collection.purpose_decide", decisionProof.get("action"));
        assertEquals("POST", decisionRequest.method);
        assertEquals(
            Set.of(
                "scope", "decision", "expected_revision", "idempotency_key", "reason",
                "training_consent_receipt_sha256", "deidentification_receipt_sha256",
                "sanitized_manifest_sha256", "target_dataset_id", "exact_location_excluded",
                "raw_audio_excluded", "third_party_faces_excluded"
            ),
            decisionBody.keySet()
        );
        assertEquals(2L, decisionBody.get("expected_revision"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(decisionRequest.body),
            decisionProof.get("body_sha256")
        );
        assertEquals(
            RECONFIRMATION.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER),
            decisionRequest.headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );

        Map<String, Object> holdProof = AdminStrictJson.parseObject(
            transport.requests.get(4).bodyText()
        );
        Map<String, Object> holdBody = AdminStrictJson.parseObject(
            transport.requests.get(5).bodyText()
        );
        assertEquals("admin.raw_collection.legal_hold", holdProof.get("action"));
        assertEquals(4L, holdBody.get("expected_revision"));
        assertEquals("AUTH-2026-001", holdBody.get("authority_reference"));
    }

    @Test
    public void sessionStepUpNoStoreAndBodyLimitsFailClosedWithoutRetry() {
        FakeTransport noStore = new FakeTransport();
        noStore.missingActualNoStore = true;
        assertThrows(IOException.class, () ->
            client(noStore).listQuarantine(SESSION, RECONFIRMATION)
        );
        assertEquals(2, noStore.requests.size());

        FakeTransport oversized = new FakeTransport();
        oversized.oversizedList = true;
        assertThrows(IOException.class, () ->
            client(oversized).listQuarantine(SESSION, RECONFIRMATION)
        );
        assertEquals(2, oversized.requests.size());

        FakeTransport disguisedJson = new FakeTransport();
        disguisedJson.disguisedJsonContentType = true;
        assertThrows(IOException.class, () ->
            client(disguisedJson).listQuarantine(SESSION, RECONFIRMATION)
        );
        assertEquals(2, disguisedJson.requests.size());

        FakeTransport conflict = new FakeTransport();
        conflict.decisionConflict = true;
        assertThrows(AdminRawCollectionRepository.ConflictException.class, () ->
            client(conflict).decidePurpose(
                SESSION,
                AdminRawCollectionModelsTest.summary(),
                AdminRawCollectionModelsTest.reportDecision(),
                RECONFIRMATION
            )
        );
        assertEquals(2, conflict.requests.size());

        FakeTransport mismatchedSource = new FakeTransport();
        mismatchedSource.decisionSourceMismatch = true;
        assertThrows(IOException.class, () ->
            client(mismatchedSource).decidePurpose(
                SESSION,
                AdminRawCollectionModelsTest.summary(),
                AdminRawCollectionModelsTest.reportDecision(),
                RECONFIRMATION
            )
        );
        assertEquals(2, mismatchedSource.requests.size());

        FakeTransport noStepUp = new FakeTransport();
        assertThrows(IllegalArgumentException.class, () ->
            client(noStepUp).decidePurpose(
                SESSION,
                AdminRawCollectionModelsTest.summary(),
                AdminRawCollectionModelsTest.reportDecision(),
                Map.of()
            )
        );
        assertTrue(noStepUp.requests.isEmpty());

        assertThrows(IllegalArgumentException.class, () ->
            client(new FakeTransport()).listQuarantine(SESSION, Map.of())
        );

        AdminOperationsApi.SessionContext missingSession = new AdminOperationsApi.SessionContext(
            "short", "admin-001", SESSION.sessionId(), SESSION.deviceId()
        );
        assertThrows(IOException.class, () ->
            client(new FakeTransport()).listQuarantine(missingSession, RECONFIRMATION)
        );
    }

    private static AdminRawCollectionHttpClient client(FakeTransport transport) {
        return new AdminRawCollectionHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            payload -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static final class FakeTransport implements AdminRawCollectionHttpClient.Transport {
        final List<Request> requests = new ArrayList<>();
        boolean missingActualNoStore;
        boolean oversizedList;
        boolean disguisedJsonContentType;
        boolean decisionConflict;
        boolean decisionSourceMismatch;

        @Override
        public AdminRawCollectionHttpClient.Response execute(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body
        ) throws IOException {
            Request request = new Request(method, url, headers, body);
            requests.add(request);
            if (url.endsWith(AdminRawCollectionHttpClient.CHALLENGE_PATH)) {
                Map<String, Object> value = AdminStrictJson.parseObject(request.bodyText());
                AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
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
                return new AdminRawCollectionHttpClient.Response(
                    200,
                    AdminDeviceProofTest.challengeResponse(
                        intent, AdminDeviceProofTest.ISSUED_AT, null
                    )
                );
            }
            if (url.endsWith("/decisions")) {
                String receipt = AdminRawCollectionModelsTest.decisionReceiptJson();
                if (decisionSourceMismatch) {
                    receipt = receipt.replace(
                        "\"source_manifest_sha256\":\"" + "a".repeat(64) + "\"",
                        "\"source_manifest_sha256\":\"" + "c".repeat(64) + "\""
                    );
                }
                return decisionConflict
                    ? new AdminRawCollectionHttpClient.Response(409, "{}")
                    : new AdminRawCollectionHttpClient.Response(201, receipt);
            }
            if (url.endsWith("/legal-holds")) {
                return new AdminRawCollectionHttpClient.Response(
                    200, AdminRawCollectionModelsTest.legalHoldReceiptJson()
                );
            }
            if (oversizedList) {
                return new AdminRawCollectionHttpClient.Response(
                    200,
                    new byte[128 * 1024 + 1],
                    "application/json",
                    "no-store",
                    "no-cache"
                );
            }
            if (missingActualNoStore) {
                return new AdminRawCollectionHttpClient.Response(
                    200,
                    AdminRawCollectionModelsTest.listJson().getBytes(StandardCharsets.UTF_8),
                    "application/json",
                    "private",
                    "no-cache"
                );
            }
            if (disguisedJsonContentType) {
                return new AdminRawCollectionHttpClient.Response(
                    200,
                    AdminRawCollectionModelsTest.listJson().getBytes(StandardCharsets.UTF_8),
                    "application/jsonp",
                    "no-store",
                    "no-cache"
                );
            }
            return new AdminRawCollectionHttpClient.Response(
                200, AdminRawCollectionModelsTest.listJson()
            );
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

        String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }
}
