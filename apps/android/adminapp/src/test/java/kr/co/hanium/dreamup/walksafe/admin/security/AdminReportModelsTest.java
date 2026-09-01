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
        assertEquals(0, detail.contentRevision());
        assertEquals(3, detail.latestDeliveryRevision());
        assertEquals("APPROVED", detail.review().decision());
        assertNull(detail.review().userVisibleReason());
        assertEquals("ACKNOWLEDGED", detail.currentContentDelivery().status());
        assertEquals(3, detail.currentContentDelivery().revision());
        assertEquals(1, detail.currentContentDelivery().packageRevision());
        assertEquals(0, detail.currentContentDelivery().packageContentRevision());
        assertEquals(
            "88888888-8888-4888-8888-888888888888",
            detail.currentContentDelivery().packageId()
        );
        assertEquals(4096, detail.currentContentDelivery().packageByteCount());
    }

    @Test
    public void v2KeepsLatestGlobalDeliveryRevisionWhenCurrentContentHasNoDelivery()
        throws Exception {
        String detailJson = wave5DetailFixture()
            .replace("\"latest_delivery_revision\": 3", "\"latest_delivery_revision\": 4");
        int deliveryStart = detailJson.indexOf("  \"current_delivery\": {");
        int capabilitiesStart = detailJson.indexOf("  \"capabilities\":", deliveryStart);
        String withoutCurrentDelivery = detailJson.substring(0, deliveryStart)
            + "  \"current_delivery\": null,\n"
            + detailJson.substring(capabilitiesStart);

        AdminReportModels.Detail detail = AdminReportModels.parseDetail(
            withoutCurrentDelivery,
            "11111111-1111-4111-8111-111111111111"
        );

        assertEquals(4, detail.latestDeliveryRevision());
        assertNull(detail.currentContentDelivery());
        assertNull(detail.delivery());
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
            detail.replace(
                "walksafe.admin-report-detail.v2",
                "walksafe.admin-report-detail.v1"
            ),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("  \"content_revision\": 0,\n", ""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("  \"latest_delivery_revision\": 3,\n", ""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace(
                "\"latest_delivery_revision\": 3",
                "\"latest_delivery_revision\": 2"
            ),
            "11111111-1111-4111-8111-111111111111"
        ));
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
        return fixture("admin-report-detail-v2.json");
    }
}
