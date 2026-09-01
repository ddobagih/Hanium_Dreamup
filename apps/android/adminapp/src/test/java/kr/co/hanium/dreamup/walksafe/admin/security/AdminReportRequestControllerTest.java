package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminReportRequestControllerTest {
    @Test
    public void newerGenerationDiscardsQueuedReadBeforeNetwork() throws Exception {
        CountingLoader loader = new CountingLoader();
        AdminReportRequestController controller = new AdminReportRequestController(loader);
        var old = controller.beginFirstPage(
            new AdminReportRequestModels.Filters(null, "CORRECTION", null)
        );
        var current = controller.beginFirstPage(
            new AdminReportRequestModels.Filters(null, "DELETE", null)
        );

        assertFalse(controller.execute(old));
        assertTrue(controller.execute(current));
        assertEquals(1, loader.pageLoads);
    }

    @Test
    public void stableCursorPaginationRejectsRepeatedPageData() throws Exception {
        CountingLoader loader = new CountingLoader();
        AdminReportRequestController controller = new AdminReportRequestController(loader);
        assertTrue(controller.execute(controller.beginFirstPage(
            new AdminReportRequestModels.Filters(null, null, null)
        )));
        assertTrue(controller.execute(controller.beginNextPage()));
        assertEquals(AdminReportRequestController.Phase.ERROR, controller.snapshot().phase());
        assertEquals(2, loader.pageLoads);
    }

    @Test
    public void failedReadCanRetryToAnExplicitEmptyState() {
        final int[] calls = {0};
        AdminReportRequestController controller = new AdminReportRequestController(
            new AdminReportRequestController.Loader() {
                @Override
                public AdminReportRequestModels.Page loadPage(
                    AdminReportRequestModels.Filters filters,
                    String cursor
                ) throws Exception {
                    calls[0] += 1;
                    if (calls[0] == 1) throw new java.io.IOException("temporary failure");
                    return AdminReportRequestModels.parsePage(
                        "{\"schema_version\":\"walksafe.admin-report-request-list.v1\","
                            + "\"items\":[],\"next_cursor\":null}"
                    );
                }

                @Override
                public AdminReportRequestModels.Detail loadDetail(String requestId) {
                    throw new AssertionError("detail must not run");
                }

                @Override
                public AdminReportRequestModels.StatusSnapshot updateStatus(
                    String requestId,
                    String requestType,
                    String nextStatus,
                    int expectedVersion,
                    String publicResponse,
                    String internalNote,
                    char[] password,
                    char[] totp
                ) {
                    throw new AssertionError("status must not run");
                }
            }
        );
        assertTrue(controller.execute(controller.beginFirstPage(
            new AdminReportRequestModels.Filters(null, null, "RECEIVED")
        )));
        assertEquals(AdminReportRequestController.Phase.ERROR, controller.snapshot().phase());
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(AdminReportRequestController.Phase.EMPTY, controller.snapshot().phase());
        assertEquals("RECEIVED", controller.snapshot().filters().status());
    }

    @Test
    public void mutationRunsOnceBlocksDoubleTapAndZeroizesCredentials() throws Exception {
        CountingLoader loader = new CountingLoader();
        AdminReportRequestController controller = selectedController(loader);
        var request = controller.beginStatusUpdate(
            AdminReportRequestModelsTest.REQUEST_ID,
            "ACKNOWLEDGED",
            1,
            "요청을 확인하고 있습니다.",
            "내부 확인 시작",
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );

        assertThrows(IllegalStateException.class, () -> controller.beginStatusUpdate(
            AdminReportRequestModelsTest.REQUEST_ID,
            "ACKNOWLEDGED",
            1,
            null,
            null,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        ));
        assertTrue(controller.execute(request));
        assertFalse(controller.execute(request));
        assertEquals(1, loader.statusUpdates);
        assertTrue(credentialsCleared(request));
        assertArrayEquals(new char[loader.lastPassword.length], loader.lastPassword);
        assertArrayEquals(new char[loader.lastTotp.length], loader.lastTotp);
        assertEquals(AdminReportRequestController.Phase.CONTENT, controller.snapshot().phase());
    }

    @Test
    public void conflictRefreshesLatestOnceAndNeverReplaysPatch() throws Exception {
        CountingLoader loader = new CountingLoader();
        loader.conflict = true;
        AdminReportRequestController controller = selectedController(loader);
        loader.detailLoads = 0;
        var request = controller.beginStatusUpdate(
            AdminReportRequestModelsTest.REQUEST_ID,
            "ACKNOWLEDGED",
            1,
            null,
            "내부 확인",
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );

        assertTrue(controller.execute(request));
        assertEquals(1, loader.statusUpdates);
        assertEquals(1, loader.detailLoads);
        assertEquals(AdminReportRequestController.Phase.CONFLICT, controller.snapshot().phase());
        assertTrue(controller.snapshot().message().contains("자동 재제출하지 않았습니다"));
        assertTrue(credentialsCleared(request));
    }

    @Test
    public void invalidatePreventsMutationNetworkAndClearsCredentials() throws Exception {
        CountingLoader loader = new CountingLoader();
        AdminReportRequestController controller = selectedController(loader);
        var request = controller.beginStatusUpdate(
            AdminReportRequestModelsTest.REQUEST_ID,
            "ACKNOWLEDGED",
            1,
            null,
            null,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        );

        controller.invalidate();
        assertTrue(credentialsCleared(request));
        assertFalse(controller.execute(request));
        assertEquals(0, loader.statusUpdates);
    }

    @Test
    public void successfulPatchKeepsSuccessWhenDetailRefreshFailsAndNeverReplaysPatch()
        throws Exception {
        CountingLoader loader = new CountingLoader();
        AdminReportRequestController controller = selectedController(loader);
        loader.failDetailAfterPatch = true;

        assertTrue(controller.execute(controller.beginStatusUpdate(
            AdminReportRequestModelsTest.REQUEST_ID,
            "ACKNOWLEDGED",
            1,
            "확인 중입니다.",
            "내부 확인",
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        )));

        assertEquals(1, loader.statusUpdates);
        assertEquals(AdminReportRequestController.Phase.UPDATED_DETAIL_STALE, controller.snapshot().phase());
        assertEquals(2, controller.snapshot().statusSnapshot().statusVersion());
        assertTrue(controller.snapshot().message().contains("상태 변경은 성공했습니다"));
        assertTrue(controller.snapshot().items().isEmpty());
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(1, loader.statusUpdates);
    }

    @Test
    public void successfulPatchReplacesMatchingListSummary() throws Exception {
        CountingLoader loader = new CountingLoader();
        AdminReportRequestController controller = new AdminReportRequestController(loader);
        assertTrue(controller.execute(controller.beginFirstPage(
            new AdminReportRequestModels.Filters(null, null, null)
        )));
        assertTrue(controller.execute(controller.beginDetail(AdminReportRequestModelsTest.REQUEST_ID)));

        assertTrue(controller.execute(controller.beginStatusUpdate(
            AdminReportRequestModelsTest.REQUEST_ID,
            "ACKNOWLEDGED",
            1,
            null,
            null,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        )));

        assertEquals("ACKNOWLEDGED", controller.snapshot().items().get(0).status());
        assertEquals(2, controller.snapshot().items().get(0).statusVersion());
    }

    @Test
    public void acknowledgedDeleteRejectsResolvedBeforeNetwork() throws Exception {
        CountingLoader loader = new CountingLoader();
        loader.detailRequestType = "DELETE";
        loader.detailStatus = "ACKNOWLEDGED";
        loader.detailStatusVersion = 2;
        AdminReportRequestController controller = selectedController(loader);

        assertThrows(IllegalArgumentException.class, () -> controller.beginStatusUpdate(
            AdminReportRequestModelsTest.REQUEST_ID,
            "RESOLVED",
            2,
            null,
            null,
            "long-test-password".toCharArray(),
            "123456".toCharArray()
        ));
        assertEquals(0, loader.statusUpdates);
    }

    private static AdminReportRequestController selectedController(CountingLoader loader)
        throws Exception {
        AdminReportRequestController controller = new AdminReportRequestController(loader);
        assertTrue(controller.execute(controller.beginDetail(AdminReportRequestModelsTest.REQUEST_ID)));
        return controller;
    }

    private static boolean credentialsCleared(AdminReportRequestController.Request request)
        throws Exception {
        for (String fieldName : new String[] {"password", "totp"}) {
            var field = AdminReportRequestController.Request.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            for (char value : (char[]) field.get(request)) if (value != '\0') return false;
        }
        return true;
    }

    private static final class CountingLoader implements AdminReportRequestController.Loader {
        int pageLoads;
        int detailLoads;
        int statusUpdates;
        boolean conflict;
        boolean failDetailAfterPatch;
        String detailRequestType = "CORRECTION";
        String detailStatus = "RECEIVED";
        int detailStatusVersion = 1;
        char[] lastPassword;
        char[] lastTotp;

        @Override
        public AdminReportRequestModels.Page loadPage(
            AdminReportRequestModels.Filters filters,
            String cursor
        ) throws Exception {
            pageLoads += 1;
            String type = filters.requestType() == null ? "CORRECTION" : filters.requestType();
            return AdminReportRequestModels.parsePage(
                AdminReportRequestModelsTest.listFixture().replace("CORRECTION", type)
            );
        }

        @Override
        public AdminReportRequestModels.Detail loadDetail(String requestId) throws Exception {
            detailLoads += 1;
            if (statusUpdates > 0 && failDetailAfterPatch) {
                failDetailAfterPatch = false;
                throw new java.io.IOException("detail refresh failed");
            }
            String body = AdminReportRequestModelsTest.detailFixture()
                .replace("\"request_type\":\"CORRECTION\"", "\"request_type\":\"" + detailRequestType + "\"")
                .replace("\"status\":\"RECEIVED\"", "\"status\":\"" + detailStatus + "\"")
                .replace("\"status_version\":1", "\"status_version\":" + detailStatusVersion);
            if (statusUpdates > 0) {
                body = body.replace("\"status\":\"" + detailStatus + "\"", "\"status\":\"ACKNOWLEDGED\"")
                    .replace("\"status_version\":" + detailStatusVersion, "\"status_version\":2");
            }
            return AdminReportRequestModels.parseDetail(body, requestId);
        }

        @Override
        public AdminReportRequestModels.StatusSnapshot updateStatus(
            String requestId,
            String requestType,
            String nextStatus,
            int expectedVersion,
            String publicResponse,
            String internalNote,
            char[] password,
            char[] totp
        ) throws Exception {
            statusUpdates += 1;
            lastPassword = password;
            lastTotp = totp;
            assertEquals(detailRequestType, requestType);
            assertArrayEquals("long-test-password".toCharArray(), password);
            assertArrayEquals("123456".toCharArray(), totp);
            if (conflict) {
                throw new AdminReportRepository.ReportRequestConflictException(
                    AdminReportRequestModels.parseStatusConflict(
                        conflictFixture(),
                        requestId,
                        requestType
                    )
                );
            }
            return AdminReportRequestModels.parseStatus(
                AdminReportRequestModelsTest.statusFixture(),
                requestId,
                requestType
            );
        }
    }

    private static String conflictFixture() {
        return "{\"detail\":{\"code\":\"report_request_version_conflict\","
            + "\"message\":\"conflict\",\"latest\":{"
            + "\"request_id\":\"" + AdminReportRequestModelsTest.REQUEST_ID + "\","
            + "\"report_id\":\"" + AdminReportRequestModelsTest.REPORT_ID + "\","
            + "\"status\":\"ACKNOWLEDGED\",\"status_version\":2,"
            + "\"allowed_next_statuses\":[\"RESOLVED\",\"REJECTED\"],"
            + "\"public_response\":null,\"updated_at\":\"2026-08-29T03:00:00Z\"}}}";
    }
}
