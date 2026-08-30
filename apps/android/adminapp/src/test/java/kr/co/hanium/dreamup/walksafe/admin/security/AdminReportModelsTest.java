package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminReportModelsTest {
    @Test
    public void fixturesParseAsStrictMinimumListAndDetail() throws Exception {
        AdminReportModels.Page page = AdminReportModels.parsePage(wave5ListFixture());
        assertEquals(1, page.items().size());
        assertEquals("new", page.items().get(0).status());
        assertEquals(0.75d, page.items().get(0).confidence(), 0d);
        assertNull(page.nextCursor());

        String reportId = page.items().get(0).id();
        AdminReportModels.Detail detail = AdminReportModels.parseDetail(
            wave5DetailFixture(),
            reportId
        );
        assertEquals("APPROVED", detail.review().decision());
        assertNull(detail.review().userVisibleReason());
        assertEquals("ACKNOWLEDGED", detail.delivery().status());
        assertEquals(3, detail.delivery().revision());
    }

    @Test
    public void sensitiveUnknownDuplicateAndMismatchedCapabilityFieldsFailClosed() throws Exception {
        String list = wave5ListFixture();
        assertThrows(IOException.class, () -> AdminReportModels.parsePage(
            list.replace("\"status\": \"new\"", "\"status\": \"new\",\"image_path\":\"private\"")
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parsePage(
            list.replace("\"next_cursor\": null", "\"next_cursor\":null,\"next_cursor\":null")
        ));
        String detail = wave5DetailFixture();
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("    \"user_visible_reason\": null,\n", ""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace(
                "\"user_visible_reason\": null",
                "\"user_visible_reason\": \"승인에 공개 거절 사유가 있으면 안 됨\""
            ),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("\"decision\": \"APPROVED\"", "\"decision\": \"REJECTED\""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("/review-decisions", "/raw-original"),
            "11111111-1111-4111-8111-111111111111"
        ));
    }

    @Test
    public void filtersRequireCanonicalIdEnumsAndOrderedAwareDates() {
        assertThrows(IllegalArgumentException.class, () -> new AdminReportModels.Filters(
            "11111111-1111-4111-8111-11111111111A", "new", "pothole", null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportModels.Filters(
            null, "NEW", null, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportModels.Filters(
            null, null, null, "2026-08-30T00:00:00Z", "2026-08-29T00:00:00Z"
        ));
    }

    private static String fixture(String name) throws Exception {
        return new String(
            Files.readAllBytes(Paths.get("../../../contracts/fixtures/" + name)),
            StandardCharsets.UTF_8
        );
    }

    static String wave5ListFixture() throws Exception {
        return fixture("admin-report-list-v1.json").replace(
            "\"status\": \"new\",",
            "\"status\": \"new\",\n      \"status_version\": 1,"
        );
    }

    static String wave5DetailFixture() throws Exception {
        return fixture("admin-report-detail-v1.json")
            .replace(
                "\"status\": \"reviewed\",",
                "\"status\": \"reviewed\",\n  \"status_version\": 2,\n  \"allowed_next_statuses\": [\"new\", \"resolved\"],"
            )
            .replace(
                "\"decision\": \"APPROVED\",",
                "\"decision\": \"APPROVED\",\n    \"user_visible_reason\": null,"
            )
            .replace(
                "\"revision\": 3,\n    \"status\"",
                "\"revision\": 3,\n    \"package_revision\": 1,\n    \"status\""
            )
            .replace(
                "\"original_access_grants_path\": \"/reports/11111111-1111-4111-8111-111111111111/original-access-grants\"",
                "\"original_access_grants_path\": \"/reports/11111111-1111-4111-8111-111111111111/original-access-grants\",\n"
                    + "    \"status_path\": \"/admin/reports/11111111-1111-4111-8111-111111111111/status\",\n"
                    + "    \"delivery_packages_path\": \"/admin/reports/11111111-1111-4111-8111-111111111111/delivery-packages\""
            );
    }
}
