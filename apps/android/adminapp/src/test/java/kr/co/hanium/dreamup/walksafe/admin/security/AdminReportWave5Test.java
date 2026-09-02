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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
                public AdminDeliveryPackage createPackage(
                    AdminDeliveryPackage.Eligibility eligibility,
                    char[] password,
                    char[] totp
                ) {
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
    public void successfulStatusKeepsSuccessWhenDetailRefreshFailsAndRetryIsGetOnly()
        throws Exception {
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
                    return AdminReportModels.parseStatus("""
                        {"schema_version":"walksafe.admin-report-status.v1",
                         "id":"11111111-1111-4111-8111-111111111111","status":"resolved",
                         "status_version":2,"allowed_next_statuses":[],
                         "updated_at":"2026-08-29T02:00:00Z"}
                        """, reportId);
                }

                @Override
                public AdminDeliveryPackage createPackage(
                    AdminDeliveryPackage.Eligibility eligibility,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("package must not run");
                }

                @Override
                public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
                    refreshes[0] += 1;
                    if (refreshes[0] == 1) throw new IOException("detail response was lost");
                    return statusDetail(reportId, "resolved", 2);
                }
            }
        );

        assertTrue(controller.execute(controller.beginStatus(
            REPORT_ID,
            "resolved",
            1,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        )));
        assertEquals(1, updates[0]);
        assertEquals(1, refreshes[0]);
        assertEquals(
            AdminReportWorkflowController.Phase.UPDATED_DETAIL_STALE,
            controller.snapshot().phase()
        );
        assertTrue(controller.snapshot().message().contains("상태 변경은 성공했습니다"));
        assertTrue(controller.execute(controller.beginStatusDetailRetry()));
        assertEquals(1, updates[0]);
        assertEquals(2, refreshes[0]);
        assertEquals(AdminReportWorkflowController.Phase.SUCCEEDED, controller.snapshot().phase());
        assertEquals(REPORT_ID, controller.snapshot().refreshedDetail().summary().id());
    }

    @Test
    public void lifecycleFenceBeforePatchDispatchCancelsWithoutGetOnlyRecovery()
        throws Exception {
        final int[] updates = {0};
        final int[] refreshes = {0};
        AdminReportWorkflowController.Loader loader = new AdminReportWorkflowController.Loader() {
            @Override
            public AdminReportModels.StatusSnapshot updateStatus(
                String reportId,
                String nextStatus,
                int expectedVersion,
                char[] password,
                char[] totp
            ) {
                updates[0] += 1;
                throw new AssertionError("a lifecycle-fenced PATCH must not run");
            }

            @Override
            public AdminDeliveryPackage createPackage(
                AdminDeliveryPackage.Eligibility eligibility,
                char[] password,
                char[] totp
            ) {
                throw new AssertionError("package must not run");
            }

            @Override
            public AdminReportModels.Detail refreshDetail(String reportId) {
                refreshes[0] += 1;
                throw new AssertionError("an undispatched PATCH has nothing to reconcile");
            }
        };
        AdminReportWorkflowController oldController =
            new AdminReportWorkflowController(loader);
        AdminReportWorkflowController.Request oldRequest = oldController.beginStatus(
            REPORT_ID,
            "resolved",
            1,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );
        assertEquals(null, oldController.statusDetailRecoveryReportId());
        oldController.suspendForLifecycle();
        assertEquals(AdminReportWorkflowController.Phase.IDLE, oldController.snapshot().phase());
        assertFalse(oldController.execute(oldRequest));
        assertEquals(0, updates[0]);
        assertEquals(0, refreshes[0]);
    }

    @Test
    public void lifecycleFenceWhileReauthenticationIsPendingPreventsPatchDispatch()
        throws Exception {
        CountDownLatch readyToDispatch = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        final int[] patches = {0};
        AdminReportWorkflowController controller = new AdminReportWorkflowController(
            new AdminReportWorkflowController.Loader() {
                @Override
                public AdminReportModels.StatusSnapshot updateStatus(
                    String reportId,
                    String nextStatus,
                    int expectedVersion,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("the dispatch-aware loader must be used");
                }

                @Override
                public AdminReportModels.StatusSnapshot updateStatus(
                    String reportId,
                    String nextStatus,
                    int expectedVersion,
                    char[] password,
                    char[] totp,
                    AdminReportWorkflowController.StatusDispatch dispatch
                ) throws Exception {
                    readyToDispatch.countDown();
                    if (!releaseDispatch.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("test dispatch release timed out");
                    }
                    if (!dispatch.markDispatched()) {
                        throw new AdminReportWorkflowController.StatusDispatchCancelledException();
                    }
                    patches[0] += 1;
                    throw new AssertionError("a lifecycle-fenced PATCH must not run");
                }

                @Override
                public AdminDeliveryPackage createPackage(
                    AdminDeliveryPackage.Eligibility eligibility,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("package must not run");
                }

                @Override
                public AdminReportModels.Detail refreshDetail(String reportId) {
                    throw new AssertionError("an undispatched PATCH has nothing to reconcile");
                }
            }
        );
        AdminReportWorkflowController.Request request = controller.beginStatus(
            REPORT_ID,
            "resolved",
            1,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );
        AtomicReference<Boolean> applied = new AtomicReference<>();
        Thread worker = new Thread(() -> applied.set(controller.execute(request)));
        worker.start();
        assertTrue(readyToDispatch.await(2, TimeUnit.SECONDS));

        controller.suspendForLifecycle();
        assertEquals(AdminReportWorkflowController.Phase.IDLE, controller.snapshot().phase());
        releaseDispatch.countDown();
        worker.join(2_000L);

        assertFalse(worker.isAlive());
        assertEquals(Boolean.FALSE, applied.get());
        assertEquals(0, patches[0]);
        assertEquals(null, controller.statusDetailRecovery());
    }

    @Test
    public void reauthenticationFailureBeforeDispatchNeverOffersGetOnlyRecovery()
        throws Exception {
        AdminReportWorkflowController controller = new AdminReportWorkflowController(
            new AdminReportWorkflowController.Loader() {
                @Override
                public AdminReportModels.StatusSnapshot updateStatus(
                    String reportId,
                    String nextStatus,
                    int expectedVersion,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("the dispatch-aware loader must be used");
                }

                @Override
                public AdminReportModels.StatusSnapshot updateStatus(
                    String reportId,
                    String nextStatus,
                    int expectedVersion,
                    char[] password,
                    char[] totp,
                    AdminReportWorkflowController.StatusDispatch dispatch
                ) throws IOException {
                    throw new IOException("reauthentication failed before PATCH dispatch");
                }

                @Override
                public AdminDeliveryPackage createPackage(
                    AdminDeliveryPackage.Eligibility eligibility,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("package must not run");
                }

                @Override
                public AdminReportModels.Detail refreshDetail(String reportId) {
                    throw new AssertionError("an undispatched PATCH has nothing to reconcile");
                }
            }
        );

        assertTrue(controller.execute(controller.beginStatus(
            REPORT_ID,
            "resolved",
            1,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        )));
        assertEquals(AdminReportWorkflowController.Phase.ERROR, controller.snapshot().phase());
        assertEquals(null, controller.statusDetailRecovery());
        assertThrows(IllegalStateException.class, controller::beginStatusDetailRetry);
    }

    @Test
    public void lifecycleAfterPatchConfirmationRestoresExactTargetAndUsesGetOnly()
        throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        final int[] updates = {0};
        final int[] refreshes = {0};
        AdminReportWorkflowController.Loader loader = new AdminReportWorkflowController.Loader() {
            @Override
            public AdminReportModels.StatusSnapshot updateStatus(
                String reportId,
                String nextStatus,
                int expectedVersion,
                char[] password,
                char[] totp
            ) throws Exception {
                updates[0] += 1;
                return AdminReportModels.parseStatus("""
                    {"schema_version":"walksafe.admin-report-status.v1",
                     "id":"11111111-1111-4111-8111-111111111111","status":"resolved",
                     "status_version":2,"allowed_next_statuses":[],
                     "updated_at":"2026-08-29T02:00:00Z"}
                    """, reportId);
            }

            @Override
            public AdminDeliveryPackage createPackage(
                AdminDeliveryPackage.Eligibility eligibility,
                char[] password,
                char[] totp
            ) {
                throw new AssertionError("package must not run");
            }

            @Override
            public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
                refreshes[0] += 1;
                refreshStarted.countDown();
                if (!releaseRefresh.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("test refresh release timed out");
                }
                return statusDetail(reportId, "resolved", 2);
            }
        };
        AdminReportWorkflowController oldController =
            new AdminReportWorkflowController(loader);
        AdminReportWorkflowController.Request oldRequest = oldController.beginStatus(
            REPORT_ID,
            "resolved",
            1,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );
        AtomicReference<Boolean> oldApplied = new AtomicReference<>();
        Thread oldWorker = new Thread(() -> oldApplied.set(oldController.execute(oldRequest)));
        oldWorker.start();
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));

        AdminReportWorkflowController.StatusDetailRecovery beforeSuspend =
            oldController.statusDetailRecovery();
        assertTrue(beforeSuspend.patchConfirmed());
        assertEquals("resolved", beforeSuspend.targetStatus());
        assertEquals(2, beforeSuspend.targetStatusVersion());
        oldController.suspendForLifecycle();
        AdminReportWorkflowController.StatusDetailRecovery recovery =
            oldController.statusDetailRecovery();
        assertEquals(REPORT_ID, recovery.reportId());
        assertTrue(recovery.patchConfirmed());
        releaseRefresh.countDown();
        oldWorker.join(2_000L);
        assertFalse(oldWorker.isAlive());
        assertEquals(Boolean.FALSE, oldApplied.get());

        AdminReportWorkflowController restored = new AdminReportWorkflowController(loader);
        restored.restoreStatusDetailRecovery(
            recovery.reportId(),
            recovery.targetStatus(),
            recovery.targetStatusVersion(),
            recovery.patchConfirmed()
        );
        assertTrue(restored.execute(restored.beginStatusDetailRetry()));
        assertEquals(1, updates[0]);
        assertEquals(2, refreshes[0]);
        assertEquals(AdminReportWorkflowController.Phase.SUCCEEDED, restored.snapshot().phase());
    }

    @Test
    public void patchResponseLossNeverTreatsMatchingDetailAsThisRequestsSuccess()
        throws Exception {
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
                    throw new IOException("PATCH response was lost");
                }

                @Override
                public AdminDeliveryPackage createPackage(
                    AdminDeliveryPackage.Eligibility eligibility,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("package must not run");
                }

                @Override
                public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
                    refreshes[0] += 1;
                    return statusDetail(reportId, "resolved", 2);
                }
            }
        );

        assertTrue(controller.execute(controller.beginStatus(
            REPORT_ID,
            "resolved",
            1,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        )));
        assertEquals(AdminReportWorkflowController.Phase.UPDATED_DETAIL_STALE,
            controller.snapshot().phase());
        assertFalse(controller.statusDetailRecovery().patchConfirmed());
        assertTrue(controller.execute(controller.beginStatusDetailRetry()));
        assertEquals(AdminReportWorkflowController.Phase.CONFLICT,
            controller.snapshot().phase());
        assertEquals(null, controller.statusDetailRecovery());
        assertEquals(1, updates[0]);
        assertEquals(1, refreshes[0]);
    }

    @Test
    public void confirmedRecoveryRequiresExactStatusAndVersion() throws Exception {
        AdminReportWorkflowController controller = new AdminReportWorkflowController(
            new AdminReportWorkflowController.Loader() {
                @Override
                public AdminReportModels.StatusSnapshot updateStatus(
                    String reportId,
                    String nextStatus,
                    int expectedVersion,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("recovery must not PATCH");
                }

                @Override
                public AdminDeliveryPackage createPackage(
                    AdminDeliveryPackage.Eligibility eligibility,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("package must not run");
                }

                @Override
                public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
                    return statusDetail(reportId, "reviewed", 2);
                }
            }
        );
        controller.restoreStatusDetailRecovery(REPORT_ID, "resolved", 2, true);

        assertTrue(controller.execute(controller.beginStatusDetailRetry()));
        assertEquals(AdminReportWorkflowController.Phase.CONFLICT, controller.snapshot().phase());
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
        assertEquals(3, saved.contentRevision());
        assertTrue(saved.matchesDelivery(REPORT_ID, 1, 3));
        assertFalse(saved.matchesDelivery("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1, 3));
        assertFalse(saved.matchesDelivery(REPORT_ID, 2, 3));
        assertFalse(saved.matchesDelivery(REPORT_ID, 1, 4));
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
    public void existingSafPackageRequiresFreshServerAnchoredDeliveryMetadata() throws Exception {
        byte[] zip = packageZip();
        AdminReportModels.Detail detail = deliveryDetail(zip);
        AdminDeliveryPackage.Eligibility eligibility =
            AdminDeliveryPackage.Eligibility.fromDetail(detail);
        AdminDeliveryPackage.Reference reference = AdminDeliveryPackageSaver.inspectUntrusted(
            new ByteArrayInputStream(zip)
        );
        AdminDeliveryPackage.Proof proof = AdminDeliveryPackage.parseProof(
            proofJson(zip), REPORT_ID, 1
        );
        assertTrue(proof.matchesReference(reference));
        assertTrue(proof.matchesFreshDetail(detail));
        AdminDeliveryPackageSaver.Saved reopened = AdminDeliveryPackageSaver.verifyExisting(
            proof,
            new ByteArrayInputStream(zip)
        );
        assertTrue(reopened.matchesProof(proof));
        assertTrue(reopened.matchesCurrentDelivery(detail));
        assertEquals(3, reopened.contentRevision());
        AdminReportModels.Detail submitted = AdminReportModels.parseDetail(
            deliveryDetailJson(zip).replace(
                "\"status\": \"ACKNOWLEDGED\"",
                "\"status\": \"SUBMITTED\""
            ),
            REPORT_ID
        );
        assertTrue(proof.matchesFreshDetail(submitted));
        assertTrue(AdminDeliveryPackage.Eligibility.fromDetail(submitted).matchesExact(submitted));

        byte[] tampered = zip.clone();
        tampered[tampered.length / 2] ^= 0x01;
        assertThrows(IOException.class, () -> AdminDeliveryPackageSaver.verifyExisting(
            proof,
            new ByteArrayInputStream(tampered)
        ));
        assertThrows(IOException.class, () -> AdminDeliveryPackageSaver.verifyExisting(
            proof,
            new ByteArrayInputStream(java.util.Arrays.copyOf(zip, zip.length - 1))
        ));
        assertThrows(IOException.class, () -> AdminDeliveryPackageSaver.verifyExisting(
            proof,
            new ByteArrayInputStream(java.util.Arrays.copyOf(zip, zip.length + 1))
        ));

        AdminReportModels.Detail advanced = AdminReportModels.parseDetail(
            deliveryDetailJson(zip)
                .replace("\"latest_delivery_revision\": 3", "\"latest_delivery_revision\": 4")
                .replace(
                    "\"revision\": 3,\n    \"package_id\"",
                    "\"revision\": 4,\n    \"package_id\""
                ),
            REPORT_ID
        );
        assertFalse(eligibility.matchesExact(advanced));
        AdminReportModels.Detail differentPackageHash = AdminReportModels.parseDetail(
            deliveryDetailJson(zip).replace(
                AdminDeliveryPackage.digest(zip),
                "f".repeat(64)
            ),
            REPORT_ID
        );
        assertFalse(eligibility.matchesExact(differentPackageHash));
        AdminReportModels.Detail resolved = AdminReportModels.parseDetail(
            deliveryDetailJson(zip).replace(
                "\"status\": \"ACKNOWLEDGED\"",
                "\"status\": \"RESOLVED\""
            ),
            REPORT_ID
        );
        assertFalse(proof.matchesFreshDetail(resolved));
        assertThrows(
            IllegalArgumentException.class,
            () -> AdminDeliveryPackage.Eligibility.fromDetail(resolved)
        );
        AdminReportModels.Detail failed = AdminReportModels.parseDetail(
            deliveryDetailJson(zip).replace(
                "\"status\": \"ACKNOWLEDGED\"",
                "\"status\": \"FAILED\""
            ),
            REPORT_ID
        );
        assertTrue(AdminDeliveryPackage.Eligibility.fromDetail(failed).matchesExact(failed));
        assertTrue(reopened.matchesCurrentDelivery(failed));
    }

    @Test
    public void packageRejectsV1AndHeaderManifestContentRevisionMismatch() throws Exception {
        byte[] zip = packageZip();
        assertThrows(IOException.class, () -> deliveryPackage(zip, 4));

        byte[] csv = packageCsv();
        byte[] v1Manifest = "{\"schema_version\":\"walksafe.admin-report-delivery-package.v1\"}"
            .getBytes(StandardCharsets.UTF_8);
        byte[] v1Zip = zip(csv, v1Manifest);
        assertThrows(IOException.class, () -> new AdminDeliveryPackage(
            REPORT_ID,
            "22222222-2222-4222-8222-222222222222",
            1,
            0,
            2,
            "33333333-3333-4333-8333-333333333333",
            AdminDeliveryPackage.digest(v1Zip),
            AdminDeliveryPackage.digest(csv),
            AdminDeliveryPackage.digest(v1Manifest),
            v1Zip.length,
            AdminDeliveryPackage.Eligibility.fromDetail(
                AdminReportModels.parseDetail(AdminReportModelsTest.wave5DetailFixture(), REPORT_ID)
            ),
            v1Zip
        ));
        assertThrows(IOException.class, () -> AdminDeliveryPackageSaver.inspectUntrusted(
            new ByteArrayInputStream(v1Zip)
        ));
    }

    @Test
    public void safInspectionIsBoundedInterruptibleAndUsesUnicodeCodePoints() throws Exception {
        InputStream noProgress = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] value, int offset, int length) { return 0; }
        };
        assertThrows(IOException.class, () ->
            AdminDeliveryPackageSaver.inspectUntrusted(noProgress)
        );

        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> AdminDeliveryPackageSaver.inspectUntrusted(
                new ByteArrayInputStream(packageZip())
            ));
        } finally {
            Thread.interrupted();
        }

        byte[] csv = packageCsv();
        String base = new String(packageManifest(csv), StandardCharsets.UTF_8);
        String fiveHundredEmoji = "😀".repeat(500);
        byte[] accepted = zip(
            csv,
            base.replace("\"user_description\":null", "\"user_description\":\""
                + fiveHundredEmoji + "\"").getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(REPORT_ID, AdminDeliveryPackageSaver.inspectUntrusted(
            new ByteArrayInputStream(accepted)
        ).reportId());

        byte[] rejected = zip(
            csv,
            base.replace("\"user_description\":null", "\"user_description\":\""
                + fiveHundredEmoji + "😀\"").getBytes(StandardCharsets.UTF_8)
        );
        assertThrows(IOException.class, () -> AdminDeliveryPackageSaver.inspectUntrusted(
            new ByteArrayInputStream(rejected)
        ));
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
        new AdminDeviceProof.Intent(
            null, "admin-001", emptySha,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", AdminDeviceProofTest.DEVICE_ID,
            AdminDeviceProofTest.MARKER, 1, "GET",
            "/admin/reports/" + REPORT_ID + "/delivery-packages/1/proof",
            AdminDeviceProof.Purpose.ACTION, emptySha,
            "admin.report.delivery_package.proof", AdminDeviceProofTest.SESSION_ID
        );
        assertThrows(IllegalArgumentException.class, () -> new AdminDeviceProof.Intent(
            null, "admin-001", emptySha,
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", AdminDeviceProofTest.DEVICE_ID,
            AdminDeviceProofTest.MARKER, 1, "GET",
            "/admin/reports/" + REPORT_ID + "/delivery-packages/1/proof",
            AdminDeviceProof.Purpose.ACTION, emptySha,
            "admin.report.delivery_package.proof.extra", AdminDeviceProofTest.SESSION_ID
        ));
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
            approvedDetail(), "long-test-password".toCharArray(), "123456".toCharArray()
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
            approvedDetail(), "long-test-password".toCharArray(), "123456".toCharArray()
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
            approvedDetail(), "long-test-password".toCharArray(), "123456".toCharArray()
        );
        assertTrue(saved.execute(packageRequest));
        assertCredentialArrayZeroized(packageLoader.packagePassword);
        assertCredentialArrayZeroized(packageLoader.packageTotp);
        long packageGeneration = saved.generationToken();
        AdminDeliveryPackage packageValue = saved.consumePackage();
        assertTrue(packageValue != null);
        packageValue.destroy();
        saved.clearSessionState();
        assertFalse(saved.markSaved(packageGeneration, 1));
        assertFalse(saved.markSaveFailed(packageGeneration, false));
        assertEquals(AdminReportWorkflowController.Phase.IDLE, saved.snapshot().phase());
    }

    @Test
    public void packageCredentialsAreZeroizedWhenLoaderFails() throws Exception {
        final char[][] observed = new char[2][];
        AdminReportWorkflowController controller = new AdminReportWorkflowController(
            new AdminReportWorkflowController.Loader() {
                @Override
                public AdminReportModels.StatusSnapshot updateStatus(
                    String reportId,
                    String nextStatus,
                    int expectedVersion,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("status must not run");
                }

                @Override
                public AdminDeliveryPackage createPackage(
                    AdminDeliveryPackage.Eligibility eligibility,
                    char[] password,
                    char[] totp
                ) throws Exception {
                    observed[0] = password;
                    observed[1] = totp;
                    throw new IOException("private package failure");
                }

                @Override
                public AdminReportModels.Detail refreshDetail(String reportId) {
                    throw new AssertionError("detail refresh must not run");
                }
            }
        );
        var request = controller.beginPackage(
            approvedDetail(),
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );

        assertTrue(controller.execute(request));
        assertEquals(AdminReportWorkflowController.Phase.ERROR, controller.snapshot().phase());
        assertCredentialArrayZeroized(observed[0]);
        assertCredentialArrayZeroized(observed[1]);
        assertCredentialCloneZeroized(request, "password");
        assertCredentialCloneZeroized(request, "totp");
    }

    private static void assertCredentialCloneZeroized(Object request, String fieldName)
        throws Exception {
        var field = request.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        for (char value : (char[]) field.get(request)) assertEquals('\0', value);
    }

    private static void assertCredentialArrayZeroized(char[] value) {
        assertTrue(value != null);
        for (char item : value) assertEquals('\0', item);
    }

    private static AdminReportModels.Detail statusDetail(
        String reportId,
        String status,
        int statusVersion
    ) throws Exception {
        String body = AdminReportModelsTest.wave5DetailFixture()
            .replace("\"status\": \"reviewed\",", "\"status\": \"" + status + "\",")
            .replace("\"status_version\": 1,", "\"status_version\": " + statusVersion + ",");
        if ("resolved".equals(status)) {
            body = body.replace(
                "\"allowed_next_statuses\": [\n    \"new\",\n    \"resolved\"\n  ],",
                "\"allowed_next_statuses\": [],"
            );
        }
        return AdminReportModels.parseDetail(body, reportId);
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
        byte[] csv = packageCsv();
        return zip(csv, packageManifest(csv));
    }

    private static byte[] zip(byte[] csv, byte[] manifest) throws Exception {
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
        return deliveryPackage(zip, 3);
    }

    private static AdminDeliveryPackage deliveryPackage(byte[] zip, int contentRevision)
        throws Exception {
        byte[] csv = packageCsv();
        byte[] manifest = packageManifest(csv);
        return new AdminDeliveryPackage(
            REPORT_ID,
            "22222222-2222-4222-8222-222222222222",
            1,
            contentRevision,
            2,
            "33333333-3333-4333-8333-333333333333",
            AdminDeliveryPackage.digest(zip),
            AdminDeliveryPackage.digest(csv),
            AdminDeliveryPackage.digest(manifest),
            zip.length,
            AdminDeliveryPackage.Eligibility.fromDetail(deliveryDetail(zip)),
            zip
        );
    }

    private static byte[] packageCsv() {
        return "report_id,status\n1,new\n".getBytes(StandardCharsets.UTF_8);
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

    private static AdminReportModels.Detail deliveryDetail(byte[] zip) throws Exception {
        return AdminReportModels.parseDetail(deliveryDetailJson(zip), REPORT_ID);
    }

    private static String deliveryDetailJson(byte[] zip) throws Exception {
        byte[] csv = packageCsv();
        byte[] manifest = packageManifest(csv);
        return AdminReportModelsTest.wave5DetailFixture()
            .replace("\"content_revision\": 0", "\"content_revision\": 3")
            .replace("\"package_content_revision\": 0", "\"package_content_revision\": 3")
            .replace(
                "88888888-8888-4888-8888-888888888888",
                "22222222-2222-4222-8222-222222222222"
            )
            .replace(
                "99999999-9999-4999-8999-999999999999",
                "33333333-3333-4333-8333-333333333333"
            )
            .replace("c".repeat(64), AdminDeliveryPackage.digest(zip))
            .replace("d".repeat(64), AdminDeliveryPackage.digest(csv))
            .replace("e".repeat(64), AdminDeliveryPackage.digest(manifest))
            .replace("\"package_byte_count\": 4096", "\"package_byte_count\": " + zip.length);
    }

    private static AdminReportModels.Detail approvedDetail() throws Exception {
        return deliveryDetail(packageZip());
    }

    private static String proofJson(byte[] zip) {
        return "{\"schema_version\":\"walksafe.admin-report-delivery-package-proof.v1\","
            + "\"package_revision\":1,\"content_revision\":3,\"review_revision\":2,"
            + "\"package_schema_version\":\"walksafe.admin-report-delivery-package.v2\","
            + "\"package_byte_count\":" + zip.length + ",\"package_sha256\":\""
            + AdminDeliveryPackage.digest(zip) + "\"}";
    }

    private static final class CountingLoader implements AdminReportWorkflowController.Loader {
        int statusMutations;
        int packageMutations;
        char[] packagePassword;
        char[] packageTotp;

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
        public AdminDeliveryPackage createPackage(
            AdminDeliveryPackage.Eligibility eligibility,
            char[] password,
            char[] totp
        ) throws Exception {
            packageMutations += 1;
            packagePassword = password;
            packageTotp = totp;
            return deliveryPackage(packageZip());
        }

        @Override
        public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
            return statusDetail(reportId, "resolved", 2);
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
