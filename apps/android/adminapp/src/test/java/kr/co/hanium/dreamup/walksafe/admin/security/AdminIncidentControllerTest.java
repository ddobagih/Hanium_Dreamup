package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminIncidentControllerTest {
    private static final String INCIDENT_A = "11111111-1111-4111-8111-111111111111";
    private static final String INCIDENT_B = "22222222-2222-4222-8222-222222222222";

    @Test
    public void newerFilterDiscardsStalePage() {
        JsonLoader loader = new JsonLoader();
        AdminIncidentController controller = new AdminIncidentController(loader);
        var first = controller.beginFirstPage(new AdminIncidentModels.Filters("OPEN"));
        var second = controller.beginFirstPage(new AdminIncidentModels.Filters("RESOLVED"));

        assertFalse(controller.execute(first));
        assertTrue(controller.execute(second));
        assertEquals("RESOLVED", controller.snapshot().filters().status());
        assertEquals(INCIDENT_B, controller.snapshot().items().get(0).incidentId());
    }

    @Test
    public void emptyErrorRetryAndDetailAreExplicit() {
        JsonLoader loader = new JsonLoader();
        AdminIncidentController controller = new AdminIncidentController(loader);
        assertTrue(controller.execute(controller.beginFirstPage(new AdminIncidentModels.Filters(null))));
        assertEquals(AdminIncidentController.Phase.EMPTY, controller.snapshot().phase());

        loader.fail = true;
        assertTrue(controller.execute(controller.beginDetail(INCIDENT_A)));
        assertEquals(AdminIncidentController.Phase.ERROR, controller.snapshot().phase());
        loader.fail = false;
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(INCIDENT_A, controller.snapshot().detail().summary().incidentId());
    }

    @Test
    public void nextHistoryFailurePreservesPageCursorAndTypedFailuresReloadOrClear()
        throws Exception {
        PagedHistoryLoader loader = new PagedHistoryLoader();
        AdminIncidentController controller = new AdminIncidentController(loader);
        assertTrue(controller.execute(controller.beginDetail(INCIDENT_A)));
        assertEquals(1, controller.snapshot().historyItems().size());
        assertEquals("cursor_A", controller.snapshot().historyNextCursor());

        loader.failNext = true;
        assertTrue(controller.execute(controller.beginNextHistoryPage()));
        assertEquals(AdminIncidentController.Phase.ERROR, controller.snapshot().phase());
        assertEquals(1, controller.snapshot().historyItems().size());
        assertEquals("cursor_A", controller.snapshot().historyNextCursor());
        loader.failNext = false;
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(2, controller.snapshot().historyItems().size());
        assertEquals(2, controller.snapshot().historyTotalCount());

        controller = new AdminIncidentController(loader);
        assertTrue(controller.execute(controller.beginDetail(INCIDENT_A)));
        loader.cursorInvalid = true;
        assertTrue(controller.execute(controller.beginNextHistoryPage()));
        assertEquals(1, controller.snapshot().historyItems().size());
        assertTrue(controller.snapshot().errorMessage().contains("첫 페이지부터 다시 조회"));
        assertFalse(controller.snapshot().canLoadMoreHistory());
        loader.cursorInvalid = false;
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(1, controller.snapshot().historyItems().size());
        assertEquals("cursor_A", controller.snapshot().historyNextCursor());

        loader.notFound = true;
        assertTrue(controller.execute(controller.beginNextHistoryPage()));
        assertEquals(null, controller.snapshot().selectedIncidentId());
        assertEquals(null, controller.snapshot().detail());
        assertEquals(0, controller.snapshot().historyItems().size());

        loader.notFound = false;
        loader.cursorInvalidFirst = true;
        controller = new AdminIncidentController(loader);
        assertTrue(controller.execute(controller.beginDetail(INCIDENT_A)));
        loader.cursorInvalidFirst = false;
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(INCIDENT_A, controller.snapshot().detail().summary().incidentId());
    }

    private static final class JsonLoader implements AdminIncidentController.Loader {
        boolean fail;

        @Override
        public AdminIncidentModels.Page loadPage(AdminIncidentModels.Filters filters, String cursor)
            throws Exception {
            if (fail) throw new java.io.IOException("private failure");
            if (filters.status() == null) {
                return AdminIncidentModels.parsePage(
                    "{\"schema_version\":\"walksafe.admin-incident-list.v1\","
                        + "\"items\":[],\"next_cursor\":null}"
                );
            }
            String id = "OPEN".equals(filters.status()) ? INCIDENT_A : INCIDENT_B;
            return AdminIncidentModels.parsePage(page(id, filters.status()));
        }

        @Override
        public AdminIncidentModels.HistoryPage loadHistory(String incidentId, String cursor)
            throws Exception {
            if (fail) throw new java.io.IOException("private failure");
            return AdminIncidentModels.parseHistoryPage(history(incidentId), incidentId);
        }
    }

    private static final class PagedHistoryLoader implements AdminIncidentController.Loader {
        boolean failNext;
        boolean cursorInvalid;
        boolean cursorInvalidFirst;
        boolean notFound;

        @Override
        public AdminIncidentModels.Page loadPage(AdminIncidentModels.Filters filters, String cursor)
            throws Exception {
            return AdminIncidentModels.parsePage(
                "{\"schema_version\":\"walksafe.admin-incident-list.v1\","
                    + "\"items\":[],\"next_cursor\":null}"
            );
        }

        @Override
        public AdminIncidentModels.HistoryPage loadHistory(String incidentId, String cursor)
            throws Exception {
            if (cursor == null && cursorInvalidFirst) {
                throw new AdminIncidentRepository.HistoryCursorException();
            }
            if (cursor != null && notFound) throw new AdminIncidentRepository.NotFoundException();
            if (cursor != null && cursorInvalid) {
                throw new AdminIncidentRepository.HistoryCursorException();
            }
            if (cursor != null && failNext) throw new java.io.IOException("private failure");
            return AdminIncidentModels.parseHistoryPage(
                pagedHistory(incidentId, cursor == null), incidentId
            );
        }
    }

    private static String page(String id, String status) {
        int version = "OPEN".equals(status) ? 1 : 3;
        return "{\"schema_version\":\"walksafe.admin-incident-list.v1\",\"items\":[{"
            + "\"incident_id\":\"" + id + "\",\"severity\":\"CRITICAL\","
            + "\"status\":\"" + status + "\",\"status_version\":" + version + ","
            + "\"reason_code\":\"USER_SAFETY_RISK\",\"summary\":\"안전 안내 기능 중단\","
            + "\"started_at\":\"2026-08-29T00:00:00Z\","
            + "\"detected_at\":\"2026-08-29T00:01:00Z\","
            + "\"updated_at\":\"2026-08-29T00:02:00Z\"}],\"next_cursor\":null}";
    }

    private static String history(String id) {
        return "{\"schema_version\":\"walksafe.admin-incident-history-page.v1\","
            + "\"incident\":{"
            + "\"incident_id\":\"" + id + "\",\"severity\":\"CRITICAL\","
            + "\"status\":\"OPEN\",\"status_version\":1,"
            + "\"reason_code\":\"USER_SAFETY_RISK\",\"summary\":\"안전 안내 기능 중단\","
            + "\"started_at\":\"2026-08-29T00:00:00Z\","
            + "\"detected_at\":\"2026-08-29T00:01:00Z\","
            + "\"updated_at\":\"2026-08-29T00:01:00Z\"},"
            + "\"allowed_next_states\":[\"ACKNOWLEDGED\"],"
            + "\"snapshot_revision\":1,\"total_count\":1,\"items\":[{"
            + "\"event_id\":\"33333333-3333-4333-8333-333333333333\","
            + "\"revision\":1,\"event_type\":\"OPENED\",\"previous_state\":null,"
            + "\"next_state\":\"OPEN\",\"reason\":\"USER_SAFETY_RISK\","
            + "\"observation\":\"안전 안내 기능 중단\",\"evidence_sha256\":\""
            + "a".repeat(64) + "\",\"observed_at\":\"2026-08-29T00:01:00Z\","
            + "\"recorded_at\":\"2026-08-29T00:01:01Z\",\"actor_id\":null}],"
            + "\"next_cursor\":null}";
    }

    private static String pagedHistory(String id, boolean first) {
        String event = first
            ? "{\"event_id\":\"33333333-3333-4333-8333-333333333333\","
                + "\"revision\":1,\"event_type\":\"OPENED\",\"previous_state\":null,"
                + "\"next_state\":\"OPEN\",\"reason\":\"USER_SAFETY_RISK\","
                + "\"observation\":\"안전 안내 기능 중단\",\"evidence_sha256\":\""
                + "a".repeat(64) + "\",\"observed_at\":\"2026-08-29T00:01:00Z\","
                + "\"recorded_at\":\"2026-08-29T00:01:01Z\",\"actor_id\":null}"
            : "{\"event_id\":\"44444444-4444-4444-8444-444444444444\","
                + "\"revision\":2,\"event_type\":\"ACKNOWLEDGED\","
                + "\"previous_state\":\"OPEN\",\"next_state\":\"ACKNOWLEDGED\","
                + "\"reason\":\"실제 사고 확인 완료\",\"observation\":\"민감정보 없는 상태 관찰\","
                + "\"evidence_sha256\":\"" + "b".repeat(64) + "\","
                + "\"observed_at\":\"2026-08-29T00:02:00Z\","
                + "\"recorded_at\":\"2026-08-29T00:02:01Z\",\"actor_id\":\"admin-001\"}";
        return "{\"schema_version\":\"walksafe.admin-incident-history-page.v1\","
            + "\"incident\":{\"incident_id\":\"" + id + "\",\"severity\":\"CRITICAL\","
            + "\"status\":\"ACKNOWLEDGED\",\"status_version\":2,"
            + "\"reason_code\":\"USER_SAFETY_RISK\",\"summary\":\"안전 안내 기능 중단\","
            + "\"started_at\":\"2026-08-29T00:00:00Z\","
            + "\"detected_at\":\"2026-08-29T00:01:00Z\","
            + "\"updated_at\":\"2026-08-29T00:02:01Z\"},"
            + "\"allowed_next_states\":[\"RESOLVED\"],"
            + "\"snapshot_revision\":2,\"total_count\":2,\"items\":[" + event + "],"
            + "\"next_cursor\":" + (first ? "\"cursor_A\"" : "null") + "}";
    }
}
