package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminReportControllerTest {
    private static final String REPORT_A = "11111111-1111-4111-8111-111111111111";
    private static final String REPORT_B = "22222222-2222-4222-8222-222222222222";

    @Test
    public void newerFilterGenerationDiscardsStaleResponse() {
        AdminReportController controller = new AdminReportController(new JsonLoader());
        var first = controller.beginFirstPage(filters("new"));
        var second = controller.beginFirstPage(filters("reviewed"));

        assertFalse(controller.execute(first));
        assertTrue(controller.execute(second));
        assertEquals("reviewed", controller.snapshot().filters().status());
        assertEquals(REPORT_B, controller.snapshot().items().get(0).id());
    }

    @Test
    public void emptyFailureRetryAndDetailHaveExplicitStates() {
        JsonLoader loader = new JsonLoader();
        AdminReportController controller = new AdminReportController(loader);
        assertTrue(controller.execute(controller.beginFirstPage(filters(null))));
        assertEquals(AdminReportController.Phase.EMPTY, controller.snapshot().phase());

        loader.fail = true;
        assertTrue(controller.execute(controller.beginDetail(REPORT_A)));
        assertEquals(AdminReportController.Phase.ERROR, controller.snapshot().phase());
        loader.fail = false;
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(REPORT_A, controller.snapshot().detail().summary().id());
    }

    private static AdminReportModels.Filters filters(String status) {
        return new AdminReportModels.Filters(null, status, null, null, null);
    }

    private static final class JsonLoader implements AdminReportController.Loader {
        boolean fail;

        @Override
        public AdminReportModels.Page loadPage(AdminReportModels.Filters filters, String cursor) throws Exception {
            if (fail) throw new java.io.IOException("private failure");
            if (filters.status() == null) {
                return AdminReportModels.parsePage("""
                    {"schema_version":"walksafe.admin-report-list.v1","items":[],"next_cursor":null}
                    """);
            }
            String id = "new".equals(filters.status()) ? REPORT_A : REPORT_B;
            return AdminReportModels.parsePage(summaryPage(id, filters.status()));
        }

        @Override
        public AdminReportModels.Detail loadDetail(String reportId) throws Exception {
            if (fail) throw new java.io.IOException("private failure");
            return AdminReportModels.parseDetail(detail(reportId), reportId);
        }
    }

    private static String summaryPage(String id, String status) {
        return "{\"schema_version\":\"walksafe.admin-report-list.v1\",\"items\":[{"
            + "\"id\":\"" + id + "\",\"status\":\"" + status + "\","
            + "\"status_version\":1,"
            + "\"class_name\":\"pothole\",\"confidence\":0.5,\"location_quality\":\"low\","
            + "\"duplicate_count\":0,\"captured_at\":\"2026-08-29T00:00:00Z\","
            + "\"created_at\":\"2026-08-29T00:00:01Z\",\"updated_at\":\"2026-08-29T00:00:02Z\"}],"
            + "\"next_cursor\":null}";
    }

    private static String detail(String id) {
        String prefix = "/reports/" + id;
        return "{\"schema_version\":\"walksafe.admin-report-detail.v2\",\"id\":\"" + id + "\","
            + "\"status\":\"new\",\"status_version\":1,\"allowed_next_statuses\":[\"reviewed\",\"resolved\"],"
            + "\"content_revision\":0,\"latest_delivery_revision\":0,"
            + "\"class_name\":\"pothole\",\"confidence\":0.5,"
            + "\"location_quality\":\"low\",\"captured_at\":\"2026-08-29T00:00:00Z\","
            + "\"created_at\":\"2026-08-29T00:00:01Z\",\"updated_at\":\"2026-08-29T00:00:02Z\","
            + "\"current_review\":null,\"current_delivery\":null,\"capabilities\":{"
            + "\"review_decisions_path\":\"" + prefix + "/review-decisions\","
            + "\"deliveries_path\":\"" + prefix + "/deliveries\","
            + "\"original_access_grants_path\":\"" + prefix + "/original-access-grants\","
            + "\"status_path\":\"/admin" + prefix + "/status\","
            + "\"delivery_packages_path\":\"/admin" + prefix + "/delivery-packages\"}}";
    }
}
