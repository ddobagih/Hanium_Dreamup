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
        public AdminIncidentModels.Detail loadDetail(String incidentId) throws Exception {
            if (fail) throw new java.io.IOException("private failure");
            return AdminIncidentModels.parseDetail(
                AdminIncidentModelsTest.detailJson().replace(INCIDENT_A, incidentId),
                incidentId
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
}
