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
import java.util.LinkedHashMap;
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
        AdminDeliveryPackage.Eligibility eligibility = packageEligibility();
        AdminDeliveryPackage packageValue = client.createDeliveryPackage(
            SESSION, eligibility, reconfirmation
        );
        AdminAuditModels.Page audits = client.audits(
            SESSION, new AdminAuditModels.Filters("STATUS", "admin-001"), null
        );

        assertEquals("resolved", status.status());
        assertEquals(3, status.statusVersion());
        assertEquals(1, packageValue.revision());
        assertEquals(3, packageValue.contentRevision());
        assertEquals(2, packageValue.reviewRevision());
        assertTrue(packageValue.expectedByteCount() > 0);
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
        Request packageRequest = transport.requests.get(3);
        assertEquals("POST", packageRequest.method);
        Map<String, Object> packageBody = AdminStrictJson.parseObject(packageRequest.bodyText());
        assertEquals(
            Set.of("expected_content_revision", "expected_review_revision"),
            packageBody.keySet()
        );
        assertEquals(3L, packageBody.get("expected_content_revision"));
        assertEquals(2L, packageBody.get("expected_review_revision"));
        Map<String, Object> auditIntent = AdminStrictJson.parseObject(transport.requests.get(4).bodyText());
        assertEquals("admin.audit.list", auditIntent.get("read_purpose"));
        assertTrue(transport.requests.get(5).url.contains("actor_id=admin-001"));
        packageValue.destroy();
    }

    @Test
    public void packageRequiresExactStartDetailAndNewResponseHeaders() {
        Map<String, String> reconfirmation = Map.of(
            AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
            "AAECAwQFBgcICQoLDA0ODw"
        );
        AdminDeliveryPackage.Eligibility eligibility = packageEligibility();
        FakeTransport mismatch = new FakeTransport();
        mismatch.packageContentRevisionHeader = "4";
        assertThrows(IOException.class, () -> client(mismatch).createDeliveryPackage(
            SESSION, eligibility, reconfirmation
        ));

        FakeTransport missing = new FakeTransport();
        missing.packageContentRevisionHeader = null;
        assertThrows(IOException.class, () -> client(missing).createDeliveryPackage(
            SESSION, eligibility, reconfirmation
        ));

        FakeTransport reviewMismatch = new FakeTransport();
        reviewMismatch.packageReviewRevisionHeader = "3";
        assertThrows(IOException.class, () -> client(reviewMismatch).createDeliveryPackage(
            SESSION, eligibility, reconfirmation
        ));

        FakeTransport missingReview = new FakeTransport();
        missingReview.packageReviewRevisionHeader = null;
        assertThrows(IOException.class, () -> client(missingReview).createDeliveryPackage(
            SESSION, eligibility, reconfirmation
        ));

        FakeTransport byteCountMismatch = new FakeTransport();
        byteCountMismatch.packageByteCountDelta = 1;
        assertThrows(IOException.class, () -> client(byteCountMismatch).createDeliveryPackage(
            SESSION, eligibility, reconfirmation
        ));

        FakeTransport missingByteCount = new FakeTransport();
        missingByteCount.omitPackageByteCountHeader = true;
        assertThrows(IOException.class, () -> client(missingByteCount).createDeliveryPackage(
            SESSION, eligibility, reconfirmation
        ));
    }

    @Test
    public void packageProofUsesExactRevisionPathReadPurposeAndStrictResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminDeliveryPackage.Proof proof = client(transport).deliveryPackageProof(
            SESSION,
            REPORT_ID,
            7
        );

        assertEquals(2, transport.requests.size());
        Map<String, Object> intent = AdminStrictJson.parseObject(
            transport.requests.get(0).bodyText()
        );
        assertEquals("GET", intent.get("method"));
        assertEquals(
            "/admin/reports/" + REPORT_ID + "/delivery-packages/7/proof",
            intent.get("path")
        );
        assertEquals("admin.report.delivery_package.proof", intent.get("read_purpose"));
        assertEquals(null, intent.get("action"));

        Request request = transport.requests.get(1);
        assertEquals("GET", request.method);
        assertEquals(
            "http://127.0.0.1:8000/admin/reports/" + REPORT_ID
                + "/delivery-packages/7/proof",
            request.url
        );
        assertEquals(
            "admin.report.delivery_package.proof",
            request.headers.get(AdminReportHttpClient.READ_PURPOSE_HEADER)
        );
        assertEquals(REPORT_ID, proof.reportId());
        assertEquals(7, proof.packageRevision());
        assertEquals(3, proof.contentRevision());
        assertEquals(2, proof.reviewRevision());
        assertEquals(4_096, proof.byteCount());

        FakeTransport unknown = new FakeTransport();
        unknown.proofBody = proofBody().replace(
            "\"package_sha256\":",
            "\"private_metadata\":{},\"package_sha256\":"
        );
        assertThrows(IOException.class, () -> client(unknown).deliveryPackageProof(
            SESSION, REPORT_ID, 7
        ));

        FakeTransport schemaMismatch = new FakeTransport();
        schemaMismatch.proofBody = proofBody().replace(
            "walksafe.admin-report-delivery-package-proof.v1",
            "walksafe.admin-report-delivery-package-proof.v0"
        );
        assertThrows(IOException.class, () -> client(schemaMismatch).deliveryPackageProof(
            SESSION, REPORT_ID, 7
        ));

        FakeTransport revisionMismatch = new FakeTransport();
        revisionMismatch.proofBody = proofBody().replace(
            "\"package_revision\":7",
            "\"package_revision\":8"
        );
        assertThrows(IOException.class, () -> client(revisionMismatch).deliveryPackageProof(
            SESSION, REPORT_ID, 7
        ));
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
            "DELETE",
            "REJECTED",
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
                "DELETE",
                "REJECTED",
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
            "DELETE",
            "REJECTED",
            2,
            null,
            null,
            Map.of(
                AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
                "AAECAwQFBgcICQoLDA0ODw"
            )
        ));
    }

    @Test
    public void userRequestSuccessAndConflictBindProjectionToDeleteType() {
        FakeTransport malformedSuccess = new FakeTransport();
        malformedSuccess.requestStatusBody = acknowledgedRequestStatus(
            "[\"RESOLVED\",\"REJECTED\"]"
        );
        assertThrows(IOException.class, () -> client(malformedSuccess).updateRequestStatus(
            SESSION,
            REQUEST_ID,
            "DELETE",
            "REJECTED",
            2,
            null,
            null,
            Map.of(
                AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
                "AAECAwQFBgcICQoLDA0ODw"
            )
        ));

        FakeTransport malformedConflict = new FakeTransport();
        malformedConflict.requestStatusCode = 409;
        malformedConflict.requestConflictBody = "{\"detail\":{"
            + "\"code\":\"report_request_version_conflict\",\"message\":\"conflict\","
            + "\"latest\":"
            + acknowledgedRequestStatus("[\"RESOLVED\",\"REJECTED\"]")
                .replace("\"schema_version\":\"walksafe.admin-report-request-status.v1\",", "")
            + "}}";
        assertThrows(IOException.class, () -> client(malformedConflict).updateRequestStatus(
            SESSION,
            REQUEST_ID,
            "DELETE",
            "REJECTED",
            2,
            null,
            null,
            Map.of(
                AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER,
                "AAECAwQFBgcICQoLDA0ODw"
            )
        ));
    }

    @Test
    public void externalCopyListAndManualRecordUseExactStandardDeviceProof() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminReportHttpClient client = client(transport);
        AdminExternalCopyDeletionModels.EventCommand command = externalCopyCommand();

        AdminExternalCopyDeletionModels.Page page = client.listExternalCopyDeletions(
            SESSION,
            new AdminExternalCopyDeletionModels.Filter(REQUEST_ID),
            "cursor_A"
        );
        AdminExternalCopyDeletionModels.Item recorded =
            client.recordExternalCopyDeletion(SESSION, command);

        assertEquals(1, page.items().size());
        assertEquals("REQUEST_SENT", recorded.state());
        Map<String, Object> listIntent = AdminStrictJson.parseObject(
            transport.requests.get(0).bodyText()
        );
        Request list = transport.requests.get(1);
        assertEquals(AdminReportHttpClient.EXTERNAL_COPY_LIST_PURPOSE, listIntent.get("read_purpose"));
        assertTrue(list.url.contains("limit=25"));
        assertTrue(list.url.contains("request_id=" + REQUEST_ID));
        assertTrue(list.url.contains("cursor=cursor_A"));
        assertEquals(
            AdminReportHttpClient.EXTERNAL_COPY_LIST_PURPOSE,
            list.headers.get(AdminReportHttpClient.READ_PURPOSE_HEADER)
        );

        Map<String, Object> recordIntent = AdminStrictJson.parseObject(
            transport.requests.get(2).bodyText()
        );
        Request post = transport.requests.get(3);
        assertEquals(AdminReportHttpClient.EXTERNAL_COPY_RECORD_ACTION, recordIntent.get("action"));
        assertEquals("POST", post.method);
        assertFalse(post.headers.containsKey(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER));
        assertEquals(
            Set.of(
                "state", "expected_revision", "idempotency_key", "observed_at",
                "institution_reference", "evidence_sha256"
            ),
            AdminStrictJson.parseObject(post.bodyText()).keySet()
        );
        assertFalse(post.bodyText().contains("reply_body"));
        assertFalse(post.bodyText().contains("recipient"));
    }

    @Test
    public void externalCopyReplayAndConflictRequireExactBoundProjection() throws Exception {
        FakeTransport replay = new FakeTransport();
        replay.externalCopyStatus = 200;
        assertEquals(
            "REQUEST_SENT",
            client(replay).recordExternalCopyDeletion(SESSION, externalCopyCommand()).state()
        );

        FakeTransport conflict = new FakeTransport();
        conflict.externalCopyStatus = 409;
        assertThrows(AdminReportRepository.ExternalCopyConflictException.class, () ->
            client(conflict).recordExternalCopyDeletion(SESSION, externalCopyCommand())
        );

        FakeTransport mismatched = new FakeTransport();
        mismatched.externalCopyStatus = 409;
        mismatched.externalCopyConflictBody = externalCopyConflict().replace(
            EXTERNAL_COPY_ID,
            "99999999-9999-4999-8999-999999999999"
        );
        assertThrows(IOException.class, () ->
            client(mismatched).recordExternalCopyDeletion(SESSION, externalCopyCommand())
        );

        FakeTransport oversized = new FakeTransport();
        oversized.externalCopyListBody = "x".repeat(128 * 1024 + 1);
        assertThrows(IOException.class, () -> client(oversized).listExternalCopyDeletions(
            SESSION,
            new AdminExternalCopyDeletionModels.Filter(null),
            null
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
        String requestStatusBody = requestStatus();
        String requestConflictBody = requestConflict();
        int externalCopyStatus = 201;
        String externalCopyEventBody = externalCopyEvent();
        String externalCopyConflictBody = externalCopyConflict();
        String externalCopyListBody = externalCopyList();
        String packageContentRevisionHeader = "3";
        String packageReviewRevisionHeader = "2";
        int packageByteCountDelta;
        boolean omitPackageByteCountHeader;
        String proofBody = AdminReportHttpClientTest.proofBody();

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
            if (url.contains("/admin/report-deletions/external-copies?")) {
                return new AdminReportHttpClient.Response(200, externalCopyListBody);
            }
            if (url.contains("/admin/report-deletions/")) {
                return new AdminReportHttpClient.Response(
                    externalCopyStatus,
                    externalCopyStatus == 409 ? externalCopyConflictBody : externalCopyEventBody
                );
            }
            if (url.startsWith("http://127.0.0.1:8000/admin/report-requests/")) {
                if (url.endsWith("/status")) {
                    return new AdminReportHttpClient.Response(
                        requestStatusCode,
                        requestStatusCode == 409 ? requestConflictBody : requestStatusBody
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
            if (url.contains("/delivery-packages/") && url.endsWith("/proof")) {
                return new AdminReportHttpClient.Response(200, proofBody);
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

        private AdminReportHttpClient.Response packageResponse() throws IOException {
            byte[] csv = "report_id,status\n1,new\n".getBytes(StandardCharsets.UTF_8);
            byte[] manifest = packageManifest(csv);
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
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/zip");
            headers.put("X-WalkSafe-Package-Id", "22222222-2222-4222-8222-222222222222");
            headers.put("X-WalkSafe-Package-Revision", "1");
            if (packageContentRevisionHeader != null) {
                headers.put("X-WalkSafe-Content-Revision", packageContentRevisionHeader);
            }
            if (packageReviewRevisionHeader != null) {
                headers.put("X-WalkSafe-Review-Revision", packageReviewRevisionHeader);
            }
            if (!omitPackageByteCountHeader) {
                headers.put(
                    "X-WalkSafe-Package-Byte-Count",
                    Integer.toString(zip.length + packageByteCountDelta)
                );
            }
            headers.put("X-WalkSafe-Export-Audit-Id", "33333333-3333-4333-8333-333333333333");
            headers.put("X-WalkSafe-Package-SHA256", AdminDeliveryPackage.digest(zip));
            headers.put("X-WalkSafe-CSV-SHA256", AdminDeliveryPackage.digest(csv));
            headers.put("X-WalkSafe-Manifest-SHA256", AdminDeliveryPackage.digest(manifest));
            return new AdminReportHttpClient.Response(201, zip, headers);
        }

        private static byte[] packageManifest(byte[] csv) {
            return ("{\"category_hint\":null,\"content_revision\":3,"
                + "\"content_sha256\":\"" + "a".repeat(64) + "\","
                + "\"csv_bytes\":" + csv.length + ",\"csv_name\":\"report.csv\","
                + "\"csv_sha256\":\"" + AdminDeliveryPackage.digest(csv) + "\","
                + "\"export_audit_id\":\"33333333-3333-4333-8333-333333333333\","
                + "\"package_revision\":1,\"package_version\":2,"
                + "\"report_id\":\"" + REPORT_ID + "\","
                + "\"review_decision_id\":\"44444444-4444-4444-8444-444444444444\","
                + "\"review_revision\":2,\"row_count\":1,"
                + "\"schema_version\":\"walksafe.admin-report-delivery-package.v2\","
                + "\"supersedes_package_id\":null,\"user_description\":null}")
                .getBytes(StandardCharsets.UTF_8);
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

    private static AdminDeliveryPackage.Eligibility packageEligibility() {
        try {
            String detail = wave5Detail()
                .replace("\"content_revision\": 0", "\"content_revision\": 3")
                .replace(
                    "\"package_content_revision\": 0",
                    "\"package_content_revision\": 3"
                );
            return AdminDeliveryPackage.Eligibility.fromDetail(
                AdminReportModels.parseDetail(detail, REPORT_ID)
            );
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String proofBody() {
        return "{\"schema_version\":\"walksafe.admin-report-delivery-package-proof.v1\","
            + "\"package_revision\":7,\"content_revision\":3,\"review_revision\":2,"
            + "\"package_schema_version\":\"walksafe.admin-report-delivery-package.v2\","
            + "\"package_byte_count\":4096,\"package_sha256\":\"" + "f".repeat(64) + "\"}";
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
            + "\"report_id\":\"" + REPORT_ID + "\",\"status\":\"REJECTED\","
            + "\"status_version\":3,\"allowed_next_statuses\":[],"
            + "\"public_response\":\"요청 처리를 완료했습니다.\","
            + "\"updated_at\":\"2026-08-29T03:00:00Z\"}";
    }

    private static String acknowledgedRequestStatus(String allowedNextStatuses) {
        return "{\"schema_version\":\"walksafe.admin-report-request-status.v1\","
            + "\"request_id\":\"" + REQUEST_ID + "\","
            + "\"report_id\":\"" + REPORT_ID + "\",\"status\":\"ACKNOWLEDGED\","
            + "\"status_version\":3,\"allowed_next_statuses\":" + allowedNextStatuses + ","
            + "\"public_response\":null,"
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

    private static AdminExternalCopyDeletionModels.EventCommand externalCopyCommand() {
        return new AdminExternalCopyDeletionModels.EventCommand(
            REQUEST_ID,
            EXTERNAL_COPY_ID,
            "REQUEST_SENT",
            0,
            "77777777-7777-4777-8777-777777777777",
            "2026-09-01T01:00:00Z",
            null,
            null
        );
    }

    private static String externalCopyList() {
        return "{\"schema_version\":\"walksafe.admin-report-deletion-external-copy-list.v1\","
            + "\"items\":[" + externalCopyItem("NOT_REQUESTED", 0, "[\"REQUEST_SENT\"]")
            + "],\"next_cursor\":null}";
    }

    private static String externalCopyEvent() {
        return "{\"schema_version\":\"walksafe.admin-report-deletion-external-copy-event.v1\","
            + externalCopyItem(
                "REQUEST_SENT",
                1,
                "[\"REPLY_ACKNOWLEDGED\",\"REPLY_DELETION_CONFIRMED\",\"REPLY_DECLINED\"]"
            ).substring(1);
    }

    private static String externalCopyConflict() {
        return "{\"detail\":{\"code\":\"report_external_copy_revision_conflict\","
            + "\"message\":\"conflict\",\"latest\":"
            + externalCopyItem(
                "REQUEST_SENT",
                1,
                "[\"REPLY_ACKNOWLEDGED\",\"REPLY_DELETION_CONFIRMED\",\"REPLY_DECLINED\"]"
            ) + "}}";
    }

    private static String externalCopyItem(String state, int revision, String allowed) {
        return "{\"request_id\":\"" + REQUEST_ID + "\",\"copy_id\":\"" + EXTERNAL_COPY_ID + "\","
            + "\"institution\":\"서울시\",\"delivery_status_at_local_deletion\":\"RESOLVED\","
            + "\"state\":\"" + state + "\",\"revision\":" + revision + ","
            + "\"allowed_next_states\":" + allowed + ","
            + "\"status_observed_at\":" + (revision == 0 ? "null" : "\"2026-09-01T01:00:00Z\"") + ","
            + "\"status_recorded_at\":" + (revision == 0 ? "null" : "\"2026-09-01T01:00:01Z\"") + "}";
    }

    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String REQUEST_ID = "88888888-8888-4888-8888-888888888888";
    private static final String EXTERNAL_COPY_ID = "22222222-2222-4222-8222-222222222222";
    private static final String TOKEN = "opaque-access-token-for-tests-123456";
    private static final AdminOperationsApi.SessionContext SESSION = new AdminOperationsApi.SessionContext(
        TOKEN,
        "admin-001",
        AdminDeviceProofTest.SESSION_ID,
        AdminDeviceProofTest.DEVICE_ID
    );
}
