package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public final class AdminReportHttpClientTest {
    @Test
    public void listAndDetailBindExactCanonicalQueryPurposeAndActorHeaders() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminReportHttpClient client = client(transport);
        AdminReportModels.Filters filters = new AdminReportModels.Filters(
            REPORT_ID, "new", "pothole", "2026-08-29T00:00:00Z", null
        );

        client.list(SESSION, filters, null);
        client.detail(SESSION, REPORT_ID);

        assertEquals(4, transport.requests.size());
        Request listChallenge = transport.requests.get(0);
        Request listRead = transport.requests.get(1);
        Map<String, Object> listIntent = AdminStrictJson.parseObject(listChallenge.bodyText());
        String rawQuery = listRead.url.substring(listRead.url.indexOf('?') + 1);
        assertEquals("admin.report.list", listIntent.get("read_purpose"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(rawQuery.getBytes(StandardCharsets.UTF_8)),
            listIntent.get("query_sha256")
        );
        assertEquals("Bearer " + TOKEN, listRead.headers.get("Authorization"));
        assertEquals("ADMIN_ANDROID", listRead.headers.get("X-WalkSafe-App-Kind"));
        assertEquals("ADMIN", listRead.headers.get("X-WalkSafe-Role"));
        assertEquals("walksafe-admin-api", listRead.headers.get("X-WalkSafe-Audience"));
        assertEquals("admin.report.list", listRead.headers.get(AdminReportHttpClient.READ_PURPOSE_HEADER));
        assertFalse(listRead.headers.containsKey("X-WalkSafe-Admin-Token"));

        Map<String, Object> detailIntent = AdminStrictJson.parseObject(transport.requests.get(2).bodyText());
        assertEquals("admin.report.detail", detailIntent.get("read_purpose"));
        assertEquals("/admin/reports/" + REPORT_ID, detailIntent.get("path"));
        assertEquals("", transport.requests.get(3).url.contains("?") ? "unexpected" : "");
    }

    @Test
    public void partialOrPrivateResponsesNeverParseAsReportData() {
        FakeTransport transport = new FakeTransport();
        transport.listBody = "{\"schema_version\":\"walksafe.admin-report-list.v1\",\"items\":[],"
            + "\"next_cursor\":null,\"metadata\":{\"gps\":true}}";
        assertThrows(IOException.class, () -> client(transport).list(
            SESSION,
            new AdminReportModels.Filters(null, null, null, null, null),
            null
        ));

        FakeTransport missing = new FakeTransport();
        missing.detailStatus = 404;
        assertThrows(AdminReportRepository.NotFoundException.class, () ->
            client(missing).detail(SESSION, REPORT_ID)
        );
    }

    @Test
    public void wave5ActionsAndAuditReadUseExactBindingsAndPackageHeaders() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminReportHttpClient client = client(transport);
        Map<String, String> reconfirmation = Map.of(
            AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
            "AAECAwQFBgcICQoLDA0ODw"
        );

        AdminReportModels.StatusSnapshot status = client.updateStatus(
            SESSION, REPORT_ID, "resolved", 2, reconfirmation
        );
        AdminDeliveryPackage packageValue = client.createDeliveryPackage(
            SESSION, REPORT_ID, reconfirmation
        );
        AdminAuditModels.Page audits = client.audits(
            SESSION, new AdminAuditModels.Filters("STATUS", "admin-001"), null
        );

        assertEquals("resolved", status.status());
        assertEquals(3, status.statusVersion());
        assertEquals(1, packageValue.revision());
        assertEquals(1, audits.items().size());
        Map<String, Object> statusIntent = AdminStrictJson.parseObject(transport.requests.get(0).bodyText());
        Request statusRequest = transport.requests.get(1);
        assertEquals("admin.report.status.update", statusIntent.get("action"));
        assertEquals("PATCH", statusRequest.method);
        assertEquals(2L, AdminStrictJson.parseObject(statusRequest.bodyText()).get("expected_version"));
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODw",
            statusRequest.headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
        Map<String, Object> packageIntent = AdminStrictJson.parseObject(transport.requests.get(2).bodyText());
        assertEquals("admin.report.delivery_package.create", packageIntent.get("action"));
        assertEquals("POST", transport.requests.get(3).method);
        Map<String, Object> auditIntent = AdminStrictJson.parseObject(transport.requests.get(4).bodyText());
        assertEquals("admin.audit.list", auditIntent.get("read_purpose"));
        assertTrue(transport.requests.get(5).url.contains("actor_id=admin-001"));
        packageValue.destroy();
    }

    @Test
    public void userRequestsBindFiltersIdsProofActionExactBodyAndCas() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminReportHttpClient client = client(transport);
        AdminReportRequestModels.Filters filters = new AdminReportRequestModels.Filters(
            REPORT_ID,
            "DELETE",
            "ACKNOWLEDGED"
        );
        Map<String, String> reconfirmation = Map.of(
            AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
            "AAECAwQFBgcICQoLDA0ODw"
        );

        client.listRequests(SESSION, filters, "cursor_A");
        client.requestDetail(SESSION, REQUEST_ID);
        AdminReportRequestModels.StatusSnapshot updated = client.updateRequestStatus(
            SESSION,
            REQUEST_ID,
            "RESOLVED",
            2,
            "요청 처리를 완료했습니다.",
            "내부 확인 완료",
            reconfirmation
        );

        assertEquals(6, transport.requests.size());
        Map<String, Object> listProof = AdminStrictJson.parseObject(
            transport.requests.get(0).bodyText()
        );
        Request list = transport.requests.get(1);
        assertEquals("admin.report_request.list", listProof.get("read_purpose"));
        assertTrue(list.url.contains("cursor=cursor_A"));
        assertTrue(list.url.contains("limit=25"));
        assertTrue(list.url.contains("report_id=" + REPORT_ID));
        assertTrue(list.url.contains("request_type=DELETE"));
        assertTrue(list.url.contains("status=ACKNOWLEDGED"));
        assertEquals(
            "admin.report_request.list",
            list.headers.get(AdminReportHttpClient.READ_PURPOSE_HEADER)
        );

        Map<String, Object> detailProof = AdminStrictJson.parseObject(
            transport.requests.get(2).bodyText()
        );
        assertEquals("admin.report_request.detail", detailProof.get("read_purpose"));
        assertEquals(
            "/admin/report-requests/" + REQUEST_ID,
            detailProof.get("path")
        );

        Map<String, Object> patchProof = AdminStrictJson.parseObject(
            transport.requests.get(4).bodyText()
        );
        Request patch = transport.requests.get(5);
        assertEquals("admin.report_request.status.update", patchProof.get("action"));
        assertEquals("PATCH", patch.method);
        assertEquals(
            Set.of("status", "expected_version", "public_response", "internal_note"),
            AdminStrictJson.parseObject(patch.bodyText()).keySet()
        );
        assertEquals(
            "AAECAwQFBgcICQoLDA0ODw",
            patch.headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)
        );
        assertEquals(3, updated.statusVersion());
        assertEquals(REQUEST_ID, updated.requestId());
    }

    @Test
    public void userRequestConflictRequiresStrictLatestAndNeverHidesAsSuccess() {
        FakeTransport transport = new FakeTransport();
        transport.requestStatusCode = 409;
        assertThrows(AdminReportRepository.ReportRequestConflictException.class, () ->
            client(transport).updateRequestStatus(
                SESSION,
                REQUEST_ID,
                "RESOLVED",
                2,
                null,
                null,
                Map.of(
                    AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
                    "AAECAwQFBgcICQoLDA0ODw"
                )
            )
        );
        assertEquals(2, transport.requests.size());

        FakeTransport malformed = new FakeTransport();
        malformed.requestStatusCode = 409;
        malformed.requestConflictBody = requestConflict().replace(
            "\"latest\":{",
            "\"latest\":{\"private_note\":\"x\","
        );
        assertThrows(IOException.class, () -> client(malformed).updateRequestStatus(
            SESSION,
            REQUEST_ID,
            "RESOLVED",
            2,
            null,
            null,
            Map.of(
                AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
                "AAECAwQFBgcICQoLDA0ODw"
            )
        ));
    }

    private static AdminReportHttpClient client(FakeTransport transport) {
        return new AdminReportHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            payload -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static final class FakeTransport implements AdminReportHttpClient.Transport {
        final List<Request> requests = new ArrayList<>();
        String listBody = wave5List();
        String detailBody = wave5Detail();
        int detailStatus = 200;
        int requestStatusCode = 200;
        String requestConflictBody = requestConflict();

        @Override
        public AdminReportHttpClient.Response execute(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body
        ) throws IOException {
            Request request = new Request(method, url, headers, body);
            requests.add(request);
            if (url.endsWith(AdminReportHttpClient.CHALLENGE_PATH)) {
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
                return new AdminReportHttpClient.Response(
                    200,
                    AdminDeviceProofTest.challengeResponse(
                        intent,
                        AdminDeviceProofTest.ISSUED_AT,
                        null
                    )
                );
            }
            if (url.startsWith("http://127.0.0.1:8000/admin/report-requests/")) {
                if (url.endsWith("/status")) {
                    return new AdminReportHttpClient.Response(
                        requestStatusCode,
                        requestStatusCode == 409 ? requestConflictBody : requestStatus()
                    );
                }
                return new AdminReportHttpClient.Response(200, requestDetail());
            }
            if (url.contains("/admin/report-requests?")) {
                return new AdminReportHttpClient.Response(200, requestList());
            }
            if (url.endsWith("/status")) {
                return new AdminReportHttpClient.Response(200,
                    "{\"schema_version\":\"walksafe.admin-report-status.v1\","
                        + "\"id\":\"" + REPORT_ID + "\",\"status\":\"resolved\","
                        + "\"status_version\":3,\"allowed_next_statuses\":[\"reviewed\"],"
                        + "\"updated_at\":\"2026-08-29T02:00:00Z\"}"
                );
            }
            if (url.endsWith("/delivery-packages")) return packageResponse();
            if (url.contains("/admin/reports/audits?")) {
                return new AdminReportHttpClient.Response(200,
                    "{\"schema_version\":\"walksafe.admin-audit-list.v1\",\"items\":[{"
                        + "\"event_id\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\","
                        + "\"event_type\":\"STATUS\",\"action\":\"admin.report.status.update\","
                        + "\"outcome\":\"SUCCEEDED\",\"actor_id\":\"admin-001\","
                        + "\"resource_type\":\"report\",\"resource_id\":\"" + REPORT_ID + "\","
                        + "\"occurred_at\":\"2026-08-29T02:00:00Z\",\"correlation_id\":null}],"
                        + "\"next_cursor\":null}"
                );
            }
            if (url.contains("?")) return new AdminReportHttpClient.Response(200, listBody);
            return new AdminReportHttpClient.Response(detailStatus, detailBody);
        }

        private static AdminReportHttpClient.Response packageResponse() throws IOException {
            byte[] csv = "report_id,status\n1,new\n".getBytes(StandardCharsets.UTF_8);
            byte[] manifest = "{\"schema_version\":\"walksafe.admin-report-delivery-package.v1\"}"
                .getBytes(StandardCharsets.UTF_8);
            byte[] zip;
            try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                 ZipOutputStream archive = new ZipOutputStream(output)) {
                archive.putNextEntry(new ZipEntry("report.csv"));
                archive.write(csv);
                archive.closeEntry();
                archive.putNextEntry(new ZipEntry("manifest.json"));
                archive.write(manifest);
                archive.closeEntry();
                archive.finish();
                zip = output.toByteArray();
            }
            return new AdminReportHttpClient.Response(201, zip, Map.of(
                "Content-Type", "application/zip",
                "X-WalkSafe-Package-Id", "22222222-2222-4222-8222-222222222222",
                "X-WalkSafe-Package-Revision", "1",
                "X-WalkSafe-Export-Audit-Id", "33333333-3333-4333-8333-333333333333",
                "X-WalkSafe-Package-SHA256", AdminDeliveryPackage.digest(zip),
                "X-WalkSafe-CSV-SHA256", AdminDeliveryPackage.digest(csv),
                "X-WalkSafe-Manifest-SHA256", AdminDeliveryPackage.digest(manifest)
            ));
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

    private static String fixture(String name) {
        try {
            return new String(
                Files.readAllBytes(Paths.get("../../../contracts/fixtures/" + name)),
                StandardCharsets.UTF_8
            );
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String wave5List() {
        try { return AdminReportModelsTest.wave5ListFixture(); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static String wave5Detail() {
        try { return AdminReportModelsTest.wave5DetailFixture(); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static String requestList() {
        return "{\"schema_version\":\"walksafe.admin-report-request-list.v1\","
            + "\"items\":[" + requestSummary() + "],\"next_cursor\":null}";
    }

    private static String requestDetail() {
        return "{\"schema_version\":\"walksafe.admin-report-request-detail.v1\","
            + requestSummary().substring(1, requestSummary().length() - 1)
            + ",\"request_text\":\"잘못된 내용을 고쳐 주세요.\","
            + "\"public_response\":null,\"internal_note\":null}";
    }

    private static String requestStatus() {
        return "{\"schema_version\":\"walksafe.admin-report-request-status.v1\","
            + "\"request_id\":\"" + REQUEST_ID + "\","
            + "\"report_id\":\"" + REPORT_ID + "\",\"status\":\"RESOLVED\","
            + "\"status_version\":3,\"allowed_next_statuses\":[],"
            + "\"public_response\":\"요청 처리를 완료했습니다.\","
            + "\"updated_at\":\"2026-08-29T03:00:00Z\"}";
    }

    private static String requestConflict() {
        return "{\"detail\":{\"code\":\"report_request_version_conflict\","
            + "\"message\":\"conflict\",\"latest\":{"
            + requestStatus().substring(
                requestStatus().indexOf("\"request_id\""),
                requestStatus().length() - 1
            ).replace("\"schema_version\":\"walksafe.admin-report-request-status.v1\",", "")
            + "}}}";
    }

    private static String requestSummary() {
        return "{\"request_id\":\"" + REQUEST_ID + "\","
            + "\"report_id\":\"" + REPORT_ID + "\",\"request_type\":\"DELETE\","
            + "\"status\":\"ACKNOWLEDGED\",\"status_version\":2,"
            + "\"created_at\":\"2026-08-29T01:00:00Z\","
            + "\"updated_at\":\"2026-08-29T02:00:00Z\"}";
    }

    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String REQUEST_ID = "88888888-8888-4888-8888-888888888888";
    private static final String TOKEN = "opaque-access-token-for-tests-123456";
    private static final AdminOperationsApi.SessionContext SESSION = new AdminOperationsApi.SessionContext(
        TOKEN,
        "admin-001",
        AdminDeviceProofTest.SESSION_ID,
        AdminDeviceProofTest.DEVICE_ID
    );
}
