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

public final class AdminIncidentHttpClientTest {
    private static final String INCIDENT = "11111111-1111-4111-8111-111111111111";
    private static final String TOKEN = "t".repeat(48);
    private static final AdminOperationsApi.SessionContext SESSION =
        new AdminOperationsApi.SessionContext(
            TOKEN,
            "admin-001",
            "22222222-2222-4222-8222-222222222222",
            "device-12345678"
        );
    private static final Map<String, String> RECONFIRMATION = Map.of(
        AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
        "AAECAwQFBgcICQoLDA0ODw"
    );

    @Test
    public void listDetailAndPatchUseExactProofBindingsAndBody() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminIncidentHttpClient client = client(transport);

        client.list(SESSION, new AdminIncidentModels.Filters("OPEN"), "cursor_A");
        client.detail(SESSION, INCIDENT);
        client.updateStatus(
            SESSION,
            INCIDENT,
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

        assertEquals(6, transport.requests.size());
        Request listProof = transport.requests.get(0);
        Request list = transport.requests.get(1);
        assertEquals("admin.incident.list", AdminStrictJson.parseObject(listProof.bodyText()).get("read_purpose"));
        assertTrue(list.url.contains("limit=25"));
        assertTrue(list.url.contains("status=OPEN"));
        assertTrue(list.url.contains("cursor=cursor_A"));
        assertEquals("admin.incident.list", list.headers.get("X-WalkSafe-Read-Purpose"));

        assertEquals(
            "admin.incident.detail",
            AdminStrictJson.parseObject(transport.requests.get(2).bodyText()).get("read_purpose")
        );
        assertEquals("http://127.0.0.1:8000/admin/incidents/" + INCIDENT, transport.requests.get(3).url);

        Map<String, Object> patchProof = AdminStrictJson.parseObject(transport.requests.get(4).bodyText());
        Request patch = transport.requests.get(5);
        Map<String, Object> body = AdminStrictJson.parseObject(patch.bodyText());
        assertEquals("admin.incident.status.update", patchProof.get("action"));
        assertEquals("PATCH", patch.method);
        assertEquals(
            Set.of(
                "expected_version", "idempotency_key", "reason", "observation",
                "evidence_sha256", "next_state"
            ),
            body.keySet()
        );
        assertEquals("ACKNOWLEDGED", body.get("next_state"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(patch.body),
            patchProof.get("body_sha256")
        );
        assertEquals(
            RECONFIRMATION.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER),
            patch.headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
    }

    @Test
    public void conflictIsReturnedOnceAndNeverAutomaticallyResubmitted() {
        FakeTransport transport = new FakeTransport();
        transport.conflict = true;

        assertThrows(AdminIncidentRepository.StatusConflictException.class, () ->
            client(transport).updateStatus(
                SESSION,
                INCIDENT,
                new AdminIncidentModels.StatusRequest(
                    "ACKNOWLEDGED",
                    1,
                    "33333333-3333-4333-8333-333333333333",
                    "실제 사고를 확인한 사유입니다.",
                    "민감정보 없이 서비스 중단을 관찰했습니다.",
                    "b".repeat(64)
                ),
                RECONFIRMATION
            )
        );
        assertEquals(2, transport.requests.size());
    }

    @Test
    public void privateOrMalformedFieldsAreRejected() {
        FakeTransport transport = new FakeTransport();
        transport.listBody = listJson().replace(
            "\"next_cursor\":null",
            "\"private_log\":\"secret\",\"next_cursor\":null"
        );
        assertThrows(IOException.class, () -> client(transport).list(
            SESSION, new AdminIncidentModels.Filters(null), null
        ));
    }

    private static AdminIncidentHttpClient client(FakeTransport transport) {
        return new AdminIncidentHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            payload -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static final class FakeTransport implements AdminIncidentHttpClient.Transport {
        final List<Request> requests = new ArrayList<>();
        String listBody = listJson();
        boolean conflict;

        @Override
        public AdminIncidentHttpClient.Response execute(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body
        ) throws IOException {
            Request request = new Request(method, url, headers, body);
            requests.add(request);
            if (url.endsWith(AdminIncidentHttpClient.CHALLENGE_PATH)) {
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
                return new AdminIncidentHttpClient.Response(
                    200,
                    AdminDeviceProofTest.challengeResponse(
                        intent, AdminDeviceProofTest.ISSUED_AT, null
                    )
                );
            }
            if (url.endsWith("/status")) {
                return conflict
                    ? new AdminIncidentHttpClient.Response(409, conflictJson())
                    : new AdminIncidentHttpClient.Response(200, statusJson());
            }
            if (url.contains("?")) return new AdminIncidentHttpClient.Response(200, listBody);
            return new AdminIncidentHttpClient.Response(200, AdminIncidentModelsTest.detailJson());
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

    private static String listJson() {
        return "{\"schema_version\":\"walksafe.admin-incident-list.v1\",\"items\":[],"
            + "\"next_cursor\":null}";
    }

    private static String statusJson() {
        return "{\"schema_version\":\"walksafe.admin-incident-status.v1\","
            + "\"incident_id\":\"" + INCIDENT + "\",\"status\":\"ACKNOWLEDGED\","
            + "\"status_version\":2,\"allowed_next_states\":[\"RESOLVED\"],"
            + "\"updated_at\":\"2026-08-29T00:02:00Z\"}";
    }

    private static String conflictJson() {
        return "{\"detail\":{\"code\":\"incident_status_version_conflict\","
            + "\"message\":\"state changed\",\"latest\":{\"status\":\"ACKNOWLEDGED\","
            + "\"status_version\":2,\"allowed_next_states\":[\"RESOLVED\"]}}}";
    }
}
