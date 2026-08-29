package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public final class AdminReportWave5Test {
    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    public void statusAndConflictDtosAreStrictAndVersionBound() throws Exception {
        AdminReportModels.StatusSnapshot status = AdminReportModels.parseStatus("""
            {"schema_version":"walksafe.admin-report-status.v1",
             "id":"11111111-1111-4111-8111-111111111111","status":"resolved",
             "status_version":3,"allowed_next_statuses":["reviewed"],
             "updated_at":"2026-08-29T02:00:00Z"}
            """, REPORT_ID);
        assertEquals(3, status.statusVersion());
        assertEquals("reviewed", status.allowedNextStatuses().get(0));

        AdminReportModels.StatusConflict latest = AdminReportModels.parseStatusConflict(conflictJson());
        assertEquals(4, latest.statusVersion());
        assertThrows(IOException.class, () -> AdminReportModels.parseStatusConflict(
            conflictJson().replace("\"latest\":{", "\"latest\":{\"metadata\":{},")
        ));
    }

    @Test
    public void conflictRefreshesDetailOnceAndNeverResubmitsStatus() throws Exception {
        final int[] updates = {0};
        final int[] refreshes = {0};
        AdminReportWorkflowController controller = new AdminReportWorkflowController(
            new AdminReportWorkflowController.Loader() {
                @Override
                public AdminReportModels.StatusSnapshot updateStatus(
                    String reportId,
                    String nextStatus,
                    int expectedVersion,
                    char[] password,
                    char[] totp
                ) throws Exception {
                    updates[0] += 1;
                    throw new AdminReportRepository.StatusConflictException(
                        AdminReportModels.parseStatusConflict(conflictJson())
                    );
                }

                @Override
                public AdminDeliveryPackage createPackage(String reportId, String password, String totp) {
                    throw new AssertionError("package must not run");
                }

                @Override
                public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
                    refreshes[0] += 1;
                    return AdminReportModels.parseDetail(AdminReportModelsTest.wave5DetailFixture(), reportId);
                }
            }
        );
        var request = controller.beginStatus(
            REPORT_ID,
            "resolved",
            1,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );
        assertTrue(controller.execute(request));
        assertEquals(AdminReportWorkflowController.Phase.CONFLICT, controller.snapshot().phase());
        assertEquals(1, updates[0]);
        assertEquals(1, refreshes[0]);
        assertTrue(controller.snapshot().message().contains("자동 재제출하지 않았습니다"));
    }

    @Test
    public void safSaveRechecksLengthAndDigestAndDeletesShortWrite() throws Exception {
        byte[] zip = packageZip();
        AdminDeliveryPackage packageValue = deliveryPackage(zip);
        MemoryDestination savedDestination = new MemoryDestination(false);
        AdminDeliveryPackageSaver.Saved saved = AdminDeliveryPackageSaver.save(
            packageValue,
            savedDestination
        );
        assertEquals(zip.length, saved.byteCount());
        assertEquals(REPORT_ID, saved.reportId());
        assertTrue(saved.matchesDelivery(REPORT_ID, 1));
        assertFalse(saved.matchesDelivery("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1));
        assertFalse(saved.matchesDelivery(REPORT_ID, 2));
        assertFalse(savedDestination.deleted);
        assertEquals(0, packageValue.byteCount());

        AdminDeliveryPackage shortPackage = deliveryPackage(zip);
        MemoryDestination shortDestination = new MemoryDestination(true);
        assertThrows(IOException.class, () -> AdminDeliveryPackageSaver.save(
            shortPackage,
            shortDestination
        ));
        assertTrue(shortDestination.deleted);
        assertEquals(0, shortPackage.byteCount());
    }

    @Test
    public void auditParserAndControllerRejectSensitiveFieldsAndStaleGeneration() throws Exception {
        String security = auditPage("SECURITY", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        AdminAuditModels.Page parsed = AdminAuditModels.parsePage(security);
        assertEquals("admin-001", parsed.items().get(0).actorId());
        assertThrows(IOException.class, () -> AdminAuditModels.parsePage(
            security.replace("\"resource_id\":\"report-1\",", "\"resource_id\":\"report-1\",\"details\":{},")
        ));

        AdminAuditController controller = new AdminAuditController((filters, cursor) ->
            AdminAuditModels.parsePage(auditPage(
                filters.eventType(),
                "SECURITY".equals(filters.eventType())
                    ? "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                    : "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            ))
        );
        var old = controller.begin(new AdminAuditModels.Filters("SECURITY", null));
        var current = controller.begin(new AdminAuditModels.Filters("READ", null));
        assertFalse(controller.execute(old));
        assertTrue(controller.execute(current));
        assertEquals("READ", controller.snapshot().items().get(0).eventType());
    }

    @Test
    public void deviceProofAcceptsOnlyFrozenWave5ActionsAndAuditReadPurpose() {
        String emptySha = AdminCanonicalEncoding.sha256Hex(new byte[0]);
        new AdminDeviceProof.Intent(
            "admin.report.status.update", "admin-001", emptySha,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", AdminDeviceProofTest.DEVICE_ID,
            AdminDeviceProofTest.MARKER, 1, "PATCH", "/admin/reports/" + REPORT_ID + "/status",
            AdminDeviceProof.Purpose.ACTION, emptySha, null, AdminDeviceProofTest.SESSION_ID
        );
        new AdminDeviceProof.Intent(
            null, "admin-001", emptySha,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", AdminDeviceProofTest.DEVICE_ID,
            AdminDeviceProofTest.MARKER, 1, "GET", "/admin/reports/audits",
            AdminDeviceProof.Purpose.ACTION, emptySha, "admin.audit.list", AdminDeviceProofTest.SESSION_ID
        );
        assertThrows(IllegalArgumentException.class, () -> new AdminDeviceProof.Intent(
            "admin.report.status.update.extra", "admin-001", emptySha,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", AdminDeviceProofTest.DEVICE_ID,
            AdminDeviceProofTest.MARKER, 1, "PATCH", "/admin/reports/" + REPORT_ID + "/status",
            AdminDeviceProof.Purpose.ACTION, emptySha, null, AdminDeviceProofTest.SESSION_ID
        ));
        for (String rejectedMethod : new String[] {"PUT", "DELETE"}) {
            assertThrows(IllegalArgumentException.class, () -> new AdminDeviceProof.Intent(
                "admin.report.status.update", "admin-001", emptySha,
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", AdminDeviceProofTest.DEVICE_ID,
                AdminDeviceProofTest.MARKER, 1, rejectedMethod,
                "/admin/reports/" + REPORT_ID + "/status",
                AdminDeviceProof.Purpose.ACTION, emptySha, null, AdminDeviceProofTest.SESSION_ID
            ));
        }
    }

    @Test
    public void mutationRequestCanRunOnceAndQueuedStaleRequestsNeverReachNetwork() throws Exception {
        CountingLoader loader = new CountingLoader();
        AdminReportWorkflowController controller = new AdminReportWorkflowController(loader);
        var current = controller.beginStatus(
            REPORT_ID, "resolved", 1,
            "long-test-password".toCharArray(), "123456".toCharArray()
        );
        assertThrows(IllegalStateException.class, () -> controller.beginStatus(
            REPORT_ID, "resolved", 1,
            "long-test-password".toCharArray(), "123456".toCharArray()
        ));
        assertThrows(IllegalStateException.class, () -> controller.beginPackage(
            REPORT_ID, "long-test-password".toCharArray(), "123456".toCharArray()
        ));
        assertTrue(controller.execute(current));
        assertFalse(controller.execute(current));
        assertEquals(1, loader.statusMutations);

        CountingLoader staleStatusLoader = new CountingLoader();
        AdminReportWorkflowController staleStatus = new AdminReportWorkflowController(staleStatusLoader);
        var staleStatusRequest = staleStatus.beginStatus(
            REPORT_ID, "resolved", 1,
            "long-test-password".toCharArray(), "123456".toCharArray()
        );
        staleStatus.invalidate();
        assertCredentialCloneZeroized(staleStatusRequest, "password");
        assertCredentialCloneZeroized(staleStatusRequest, "totp");
        assertEquals(AdminReportWorkflowController.Phase.IDLE, staleStatus.snapshot().phase());
        assertEquals(null, staleStatus.snapshot().reportId());
        assertFalse(staleStatus.execute(staleStatusRequest));
        assertEquals(0, staleStatusLoader.statusMutations);

        CountingLoader stalePackageLoader = new CountingLoader();
        AdminReportWorkflowController stalePackage = new AdminReportWorkflowController(stalePackageLoader);
        var stalePackageRequest = stalePackage.beginPackage(
            REPORT_ID, "long-test-password".toCharArray(), "123456".toCharArray()
        );
        stalePackage.invalidate();
        assertCredentialCloneZeroized(stalePackageRequest, "password");
        assertCredentialCloneZeroized(stalePackageRequest, "totp");
        assertEquals(AdminReportWorkflowController.Phase.IDLE, stalePackage.snapshot().phase());
        assertEquals(null, stalePackage.snapshot().message());
        assertFalse(stalePackage.execute(stalePackageRequest));
        assertEquals(0, stalePackageLoader.packageMutations);
    }

    @Test
    public void sessionClearInvalidatesOutstandingCredentialsAndSafCompletionToken() throws Exception {
        CountingLoader pendingLoader = new CountingLoader();
        AdminReportWorkflowController pending = new AdminReportWorkflowController(pendingLoader);
        var outstanding = pending.beginStatus(
            REPORT_ID, "resolved", 1,
            "long-test-password".toCharArray(), "123456".toCharArray()
        );
        long outstandingGeneration = pending.generationToken();
        pending.clearSessionState();
        assertCredentialCloneZeroized(outstanding, "password");
        assertCredentialCloneZeroized(outstanding, "totp");
        assertTrue(pending.generationToken() > outstandingGeneration);
        assertEquals(AdminReportWorkflowController.Phase.IDLE, pending.snapshot().phase());
        assertFalse(pending.execute(outstanding));
        assertEquals(0, pendingLoader.statusMutations);

        CountingLoader packageLoader = new CountingLoader();
        AdminReportWorkflowController saved = new AdminReportWorkflowController(packageLoader);
        var packageRequest = saved.beginPackage(
            REPORT_ID, "long-test-password".toCharArray(), "123456".toCharArray()
        );
        assertTrue(saved.execute(packageRequest));
        long packageGeneration = saved.generationToken();
        AdminDeliveryPackage packageValue = saved.consumePackage();
        assertTrue(packageValue != null);
        packageValue.destroy();
        saved.clearSessionState();
        assertFalse(saved.markSaved(packageGeneration, 1));
        assertFalse(saved.markSaveFailed(packageGeneration, false));
        assertEquals(AdminReportWorkflowController.Phase.IDLE, saved.snapshot().phase());
    }

    private static void assertCredentialCloneZeroized(Object request, String fieldName)
        throws Exception {
        var field = request.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        for (char value : (char[]) field.get(request)) assertEquals('\0', value);
    }

    private static String conflictJson() {
        return "{\"detail\":{\"code\":\"report_status_version_conflict\","
            + "\"message\":\"conflict\",\"latest\":{\"status\":\"resolved\","
            + "\"status_version\":4,\"allowed_next_statuses\":[\"reviewed\"]}}}";
    }

    private static String auditPage(String type, String id) {
        return "{\"schema_version\":\"walksafe.admin-audit-list.v1\",\"items\":[{"
            + "\"event_id\":\"" + id + "\",\"event_type\":\"" + type + "\","
            + "\"action\":\"admin.report.list\",\"outcome\":\"SUCCEEDED\","
            + "\"actor_id\":\"admin-001\",\"resource_type\":\"report\","
            + "\"resource_id\":\"report-1\",\"occurred_at\":\"2026-08-29T00:00:00Z\","
            + "\"correlation_id\":null}],\"next_cursor\":null}";
    }

    private static byte[] packageZip() throws Exception {
        byte[] csv = "report_id,status\n1,new\n".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = "{\"schema_version\":\"walksafe.admin-report-delivery-package.v1\"}"
            .getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("report.csv"));
            zip.write(csv);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest);
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }

    private static AdminDeliveryPackage deliveryPackage(byte[] zip) throws Exception {
        byte[] csv = "report_id,status\n1,new\n".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = "{\"schema_version\":\"walksafe.admin-report-delivery-package.v1\"}"
            .getBytes(StandardCharsets.UTF_8);
        return new AdminDeliveryPackage(
            REPORT_ID,
            "22222222-2222-4222-8222-222222222222",
            1,
            "33333333-3333-4333-8333-333333333333",
            AdminDeliveryPackage.digest(zip),
            AdminDeliveryPackage.digest(csv),
            AdminDeliveryPackage.digest(manifest),
            zip
        );
    }

    private static final class CountingLoader implements AdminReportWorkflowController.Loader {
        int statusMutations;
        int packageMutations;

        @Override
        public AdminReportModels.StatusSnapshot updateStatus(
            String reportId,
            String nextStatus,
            int expectedVersion,
            char[] password,
            char[] totp
        ) throws Exception {
            statusMutations += 1;
            return AdminReportModels.parseStatus(
                "{\"schema_version\":\"walksafe.admin-report-status.v1\","
                    + "\"id\":\"" + reportId + "\",\"status\":\"resolved\","
                    + "\"status_version\":2,\"allowed_next_statuses\":[\"reviewed\"],"
                    + "\"updated_at\":\"2026-08-29T02:00:00Z\"}",
                reportId
            );
        }

        @Override
        public AdminDeliveryPackage createPackage(String reportId, String password, String totp)
            throws Exception {
            packageMutations += 1;
            return deliveryPackage(packageZip());
        }

        @Override
        public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
            return AdminReportModels.parseDetail(AdminReportModelsTest.wave5DetailFixture(), reportId);
        }
    }

    private static final class MemoryDestination implements AdminDeliveryPackageSaver.Destination {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final boolean shortWrite;
        boolean deleted;

        MemoryDestination(boolean shortWrite) { this.shortWrite = shortWrite; }

        @Override
        public OutputStream openOutput() {
            if (!shortWrite) return bytes;
            return new OutputStream() {
                @Override public void write(int value) { }
                @Override public void write(byte[] value, int offset, int length) {
                    bytes.write(value, offset, Math.max(0, length - 1));
                }
            };
        }

        @Override public InputStream openInput() { return new ByteArrayInputStream(bytes.toByteArray()); }
        @Override public void delete() { deleted = true; bytes.reset(); }
    }
}
