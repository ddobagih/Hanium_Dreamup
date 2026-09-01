package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.Test;

public final class AdminExternalCopyDeletionModelsTest {
    static final String REQUEST_ID = "11111111-1111-4111-8111-111111111111";
    static final String COPY_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    public void pageAndTransitionsMatchManualBackendContract() throws Exception {
        AdminExternalCopyDeletionModels.Page page =
            AdminExternalCopyDeletionModels.parsePage(pageFixture());

        assertEquals(1, page.items().size());
        assertEquals("NOT_REQUESTED", page.items().get(0).state());
        assertEquals(List.of("REQUEST_SENT"), page.items().get(0).allowedNextStates());
        assertEquals("cursor_A", page.nextCursor());
        assertEquals(
            List.of("REPLY_DELETION_CONFIRMED", "REPLY_DECLINED"),
            AdminExternalCopyDeletionModels.allowedNextStatesFor("REPLY_ACKNOWLEDGED")
        );
    }

    @Test
    public void eventAndConflictAreBoundToExactIdsAndIntent() throws Exception {
        AdminExternalCopyDeletionModels.EventCommand command = command("REQUEST_SENT", 0, null, null);
        AdminExternalCopyDeletionModels.Item recorded =
            AdminExternalCopyDeletionModels.parseEvent(eventFixture(), command);
        assertEquals(1, recorded.revision());

        assertThrows(IOException.class, () -> AdminExternalCopyDeletionModels.parseEvent(
            eventFixture().replace("\"state\":\"REQUEST_SENT\"", "\"state\":\"REPLY_DECLINED\""),
            command
        ));
        assertThrows(IOException.class, () -> AdminExternalCopyDeletionModels.parseConflict(
            conflictFixture().replace(COPY_ID, "33333333-3333-4333-8333-333333333333"),
            REQUEST_ID,
            COPY_ID
        ));
    }

    @Test
    public void replyFactsRequireMinimumReferenceOrDigestAndNeverAcceptRawReply() {
        assertThrows(IllegalArgumentException.class, () -> command(
            "REPLY_ACKNOWLEDGED", 1, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> command(
            "REPLY_DELETION_CONFIRMED", 1, "case:12345678", null
        ));
        assertThrows(IOException.class, () -> AdminExternalCopyDeletionModels.parsePage(
            pageFixture().replace("\"status_recorded_at\":null", "\"status_recorded_at\":null,\"reply_body\":\"raw\"")
        ));
    }

    static AdminExternalCopyDeletionModels.EventCommand command(
        String state,
        int revision,
        String reference,
        String digest
    ) {
        return new AdminExternalCopyDeletionModels.EventCommand(
            REQUEST_ID,
            COPY_ID,
            state,
            revision,
            "44444444-4444-4444-8444-444444444444",
            "2026-09-01T01:00:00Z",
            reference,
            digest
        );
    }

    static String pageFixture() {
        return "{\"schema_version\":\"walksafe.admin-report-deletion-external-copy-list.v1\","
            + "\"items\":[" + itemFixture("NOT_REQUESTED", 0, "[\"REQUEST_SENT\"]") + "],"
            + "\"next_cursor\":\"cursor_A\"}";
    }

    static String eventFixture() {
        return "{\"schema_version\":\"walksafe.admin-report-deletion-external-copy-event.v1\","
            + itemFixture("REQUEST_SENT", 1, "[\"REPLY_ACKNOWLEDGED\","
                + "\"REPLY_DELETION_CONFIRMED\",\"REPLY_DECLINED\"]").substring(1);
    }

    static String conflictFixture() {
        return "{\"detail\":{\"code\":\"report_external_copy_revision_conflict\","
            + "\"message\":\"conflict\",\"latest\":"
            + itemFixture("REQUEST_SENT", 1, "[\"REPLY_ACKNOWLEDGED\","
                + "\"REPLY_DELETION_CONFIRMED\",\"REPLY_DECLINED\"]") + "}}";
    }

    static String itemFixture(String state, int revision, String allowed) {
        return "{\"request_id\":\"" + REQUEST_ID + "\",\"copy_id\":\"" + COPY_ID + "\","
            + "\"institution\":\"서울시\",\"delivery_status_at_local_deletion\":\"RESOLVED\","
            + "\"state\":\"" + state + "\",\"revision\":" + revision + ","
            + "\"allowed_next_states\":" + allowed + ","
            + "\"status_observed_at\":" + (revision == 0 ? "null" : "\"2026-09-01T01:00:00Z\"") + ","
            + "\"status_recorded_at\":" + (revision == 0 ? "null" : "\"2026-09-01T01:00:01Z\"") + "}";
    }
}
