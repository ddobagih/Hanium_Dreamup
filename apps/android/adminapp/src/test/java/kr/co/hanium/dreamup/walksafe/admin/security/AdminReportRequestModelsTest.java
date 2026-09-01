package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import org.junit.Test;

public final class AdminReportRequestModelsTest {
    static final String REQUEST_ID = "88888888-8888-4888-8888-888888888888";
    static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    public void strictListDetailAndStatusParseMinimumContracts() throws Exception {
        AdminReportRequestModels.Page page = AdminReportRequestModels.parsePage(listFixture());
        assertEquals(1, page.items().size());
        assertEquals("CORRECTION", page.items().get(0).requestType());
        assertEquals("cursor_A", page.nextCursor());

        AdminReportRequestModels.Detail detail = AdminReportRequestModels.parseDetail(
            detailFixture(),
            REQUEST_ID
        );
        assertEquals("고장 위치 설명을 고쳐 주세요.", detail.requestText());
        assertNull(detail.publicResponse());
        assertEquals("접수 내용 확인 필요", detail.internalNote());

        AdminReportRequestModels.StatusSnapshot status = AdminReportRequestModels.parseStatus(
            statusFixture(),
            REQUEST_ID,
            "CORRECTION"
        );
        assertEquals("ACKNOWLEDGED", status.status());
        assertEquals(2, status.allowedNextStatuses().size());
    }

    @Test
    public void acknowledgedDeleteOnlyAllowsRejection() throws Exception {
        AdminReportRequestModels.Detail detail = AdminReportRequestModels.parseDetail(
            detailFixture()
                .replace("\"request_type\":\"CORRECTION\"", "\"request_type\":\"DELETE\"")
                .replace("\"status\":\"RECEIVED\"", "\"status\":\"ACKNOWLEDGED\""),
            REQUEST_ID
        );

        assertEquals(java.util.List.of("REJECTED"), detail.summary().allowedNextStatuses());
    }

    @Test
    public void unknownMissingDuplicateAndMismatchedIdsFailClosed() throws Exception {
        assertThrows(IOException.class, () -> AdminReportRequestModels.parsePage(
            listFixture().replace("\"next_cursor\":\"cursor_A\"", "\"next_cursor\":\"cursor_A\",\"gps\":true")
        ));
        assertThrows(IOException.class, () -> AdminReportRequestModels.parsePage(
            listFixture().replace(",\"updated_at\":\"2026-08-29T02:00:00Z\"", "")
        ));
        assertThrows(IOException.class, () -> AdminReportRequestModels.parsePage(
            listFixture().replace("\"status_version\":1", "\"status_version\":1,\"status_version\":1")
        ));
        assertThrows(IOException.class, () -> AdminReportRequestModels.parseDetail(
            detailFixture(),
            "99999999-9999-4999-8999-999999999999"
        ));
        assertThrows(IOException.class, () -> AdminReportRequestModels.parseStatus(
            statusFixture().replace(
                "[\"RESOLVED\",\"REJECTED\"]",
                "[\"REJECTED\",\"RESOLVED\"]"
            ),
            REQUEST_ID,
            "CORRECTION"
        ));
    }

    @Test
    public void statusAndConflictProjectionAreBoundToExpectedRequestType() throws Exception {
        String deleteStatus = statusFixture().replace(
            "[\"RESOLVED\",\"REJECTED\"]",
            "[\"REJECTED\"]"
        );
        assertEquals(
            java.util.List.of("REJECTED"),
            AdminReportRequestModels.parseStatus(deleteStatus, REQUEST_ID, "DELETE")
                .allowedNextStatuses()
        );
        assertThrows(IOException.class, () -> AdminReportRequestModels.parseStatus(
            statusFixture(),
            REQUEST_ID,
            "DELETE"
        ));
        assertThrows(IOException.class, () -> AdminReportRequestModels.parseStatusConflict(
            "{\"detail\":{\"code\":\"report_request_version_conflict\","
                + "\"message\":\"conflict\",\"latest\":"
                + statusFixture()
                    .replace("\"schema_version\":\"walksafe.admin-report-request-status.v1\",", "")
                + "}}",
            REQUEST_ID,
            "DELETE"
        ));
    }

    @Test
    public void filtersAndTextBoundsRejectUncontractedValues() {
        assertThrows(IllegalArgumentException.class, () -> new AdminReportRequestModels.Filters(
            "not-a-uuid", "CORRECTION", "RECEIVED"
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportRequestModels.Filters(
            null, "UPDATE", null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportRequestModels.Filters(
            null, null, "received"
        ));
        assertThrows(IllegalArgumentException.class, () ->
            AdminReportRequestModels.optionalResponse("x".repeat(501), "public_response")
        );
    }

    static String listFixture() {
        return "{\"schema_version\":\"walksafe.admin-report-request-list.v1\","
            + "\"items\":[" + summaryFixture() + "],\"next_cursor\":\"cursor_A\"}";
    }

    static String detailFixture() {
        return "{\"schema_version\":\"walksafe.admin-report-request-detail.v1\","
            + summaryFixture().substring(1, summaryFixture().length() - 1)
            + ",\"request_text\":\"고장 위치 설명을 고쳐 주세요.\","
            + "\"public_response\":null,\"internal_note\":\"접수 내용 확인 필요\"}";
    }

    static String statusFixture() {
        return "{\"schema_version\":\"walksafe.admin-report-request-status.v1\","
            + "\"request_id\":\"" + REQUEST_ID + "\",\"report_id\":\"" + REPORT_ID + "\","
            + "\"status\":\"ACKNOWLEDGED\",\"status_version\":2,"
            + "\"allowed_next_statuses\":[\"RESOLVED\",\"REJECTED\"],"
            + "\"public_response\":\"요청을 확인하고 있습니다.\","
            + "\"updated_at\":\"2026-08-29T03:00:00Z\"}";
    }

    static String summaryFixture() {
        return "{\"request_id\":\"" + REQUEST_ID + "\",\"report_id\":\"" + REPORT_ID + "\","
            + "\"request_type\":\"CORRECTION\",\"status\":\"RECEIVED\","
            + "\"status_version\":1,\"created_at\":\"2026-08-29T01:00:00Z\","
            + "\"updated_at\":\"2026-08-29T02:00:00Z\"}";
    }
}
