package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AdminAuditControllerLifecycleTest {
    private static AdminAuditModels.Filters filters() {
        return new AdminAuditModels.Filters("READ", "synthetic-admin");
    }

    @Test
    public void queuedRequestAfterSignOutNeverCallsLoader() {
        AtomicInteger calls = new AtomicInteger();
        AdminAuditController controller = new AdminAuditController((filters, cursor) -> {
            calls.incrementAndGet();
            return page("event-old-session", null);
        });
        AdminAuditController.Request request = controller.begin(filters());
        controller.clearSessionState();

        assertFalse(controller.execute(request));
        assertEquals("signed-out queued work must not dispatch", 0, calls.get());
        assertEquals(AdminAuditController.Phase.IDLE, controller.snapshot().phase());
        assertTrue(controller.snapshot().items().isEmpty());
        assertNull(controller.snapshot().filters().actorId());
    }

    @Test
    public void supersededQueuedRequestNeverCallsLoader() {
        List<String> actors = new ArrayList<>();
        AdminAuditController controller = new AdminAuditController((filters, cursor) -> {
            actors.add(filters.actorId());
            return page("event-current", null);
        });
        AdminAuditController.Request old = controller.begin(filters());
        AdminAuditController.Request current =
            controller.begin(new AdminAuditModels.Filters("READ", "synthetic-next"));

        assertFalse(controller.execute(old));
        assertTrue("superseded work must not dispatch", actors.isEmpty());
        assertTrue(controller.execute(current));
        assertEquals(Arrays.asList("synthetic-next"), actors);
    }

    @Test
    public void eachRequestDispatchesAtMostOnce() {
        AtomicInteger calls = new AtomicInteger();
        AdminAuditController controller = new AdminAuditController((filters, cursor) -> {
            calls.incrementAndGet();
            return page("event-once", null);
        });
        AdminAuditController.Request request = controller.begin(filters());

        assertTrue(controller.execute(request));
        assertFalse(controller.execute(request));
        assertEquals(1, calls.get());
        assertEquals(1, controller.snapshot().items().size());
    }

    @Test
    public void stoppedQueuedFirstPageBecomesRetryableWithoutDispatchingOldWork() {
        AtomicInteger calls = new AtomicInteger();
        AdminAuditController controller = new AdminAuditController((filters, cursor) -> {
            calls.incrementAndGet();
            return page("event-retried", null);
        });
        AdminAuditController.Request old = controller.begin(filters());
        controller.invalidate();

        assertEquals(AdminAuditController.Phase.ERROR, controller.snapshot().phase());
        assertFalse(controller.snapshot().canLoadMore());
        assertFalse(controller.execute(old));
        assertEquals(0, calls.get());
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(1, calls.get());
        assertEquals(AdminAuditController.Phase.CONTENT, controller.snapshot().phase());
    }

    @Test
    public void stoppedInFlightNextPageKeepsItemsAndRetriesExactlyTheSameCursor() {
        AdminAuditController[] holder = new AdminAuditController[1];
        List<String> cursors = new ArrayList<>();
        holder[0] = new AdminAuditController((filters, cursor) -> {
            cursors.add(cursor);
            if (cursors.size() == 1) return page("event-first", "page2");
            if (cursors.size() == 2) holder[0].invalidate();
            return page("event-second", null);
        });
        AdminAuditController controller = holder[0];
        assertTrue(controller.execute(controller.begin(filters())));

        assertFalse(controller.execute(controller.beginNext()));
        assertEquals(AdminAuditController.Phase.ERROR, controller.snapshot().phase());
        assertEquals(1, controller.snapshot().items().size());
        assertEquals("event-first", controller.snapshot().items().get(0).eventId());
        assertEquals("page2", controller.snapshot().nextCursor());
        assertFalse(controller.snapshot().canLoadMore());
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(Arrays.asList(null, "page2", "page2"), cursors);
        assertEquals(2, controller.snapshot().items().size());
        assertEquals(AdminAuditController.Phase.CONTENT, controller.snapshot().phase());
    }

    @Test
    public void failedPageOffersRetryInsteadOfStartingAnotherNextPage() {
        AtomicInteger calls = new AtomicInteger();
        List<String> cursors = new ArrayList<>();
        AdminAuditController controller = new AdminAuditController((filters, cursor) -> {
            cursors.add(cursor);
            int call = calls.incrementAndGet();
            if (call == 1) return page("event-first", "page2");
            if (call == 2) throw new IOException("synthetic offline");
            return page("event-second", null);
        });
        assertTrue(controller.execute(controller.begin(filters())));
        assertTrue(controller.execute(controller.beginNext()));

        assertEquals(AdminAuditController.Phase.ERROR, controller.snapshot().phase());
        assertFalse(controller.snapshot().canLoadMore());
        assertThrows(IllegalStateException.class, controller::beginNext);
        assertTrue(controller.execute(controller.beginRetry()));
        assertEquals(Arrays.asList(null, "page2", "page2"), cursors);
        assertEquals(2, controller.snapshot().items().size());
    }

    @Test
    public void nextPageDoubleTapDoesNotSupersedeThePendingRequest() {
        List<String> cursors = new ArrayList<>();
        AdminAuditController controller = new AdminAuditController((filters, cursor) -> {
            cursors.add(cursor);
            return cursor == null ? page("event-first", "page2") : page("event-second", null);
        });
        assertTrue(controller.execute(controller.begin(filters())));
        AdminAuditController.Request next = controller.beginNext();

        assertFalse(controller.snapshot().canLoadMore());
        assertThrows(IllegalStateException.class, controller::beginNext);
        assertTrue(controller.execute(next));
        assertEquals(Arrays.asList(null, "page2"), cursors);
        assertEquals(2, controller.snapshot().items().size());
    }

    @Test
    public void signOutWhileLoadIsInFlightDiscardsReturnedSessionData() {
        AdminAuditController[] holder = new AdminAuditController[1];
        holder[0] = new AdminAuditController((filters, cursor) -> {
            holder[0].clearSessionState();
            return page("event-old-session", "oldcursor");
        });

        assertFalse(holder[0].execute(holder[0].begin(filters())));
        assertEquals(AdminAuditController.Phase.IDLE, holder[0].snapshot().phase());
        assertTrue(holder[0].snapshot().items().isEmpty());
        assertNull(holder[0].snapshot().nextCursor());
        assertThrows(IllegalStateException.class, holder[0]::beginRetry);
    }

    @Test
    public void stoppingCompletedContentDoesNotDiscardTheListOrInventAnError() {
        AdminAuditController controller =
            new AdminAuditController((filters, cursor) -> page("event-first", "page2"));
        assertTrue(controller.execute(controller.begin(filters())));

        controller.invalidate();

        assertEquals(AdminAuditController.Phase.CONTENT, controller.snapshot().phase());
        assertEquals(1, controller.snapshot().items().size());
        assertTrue(controller.snapshot().canLoadMore());
        assertNull(controller.snapshot().errorMessage());
    }

    private static AdminAuditModels.Page page(String eventId, String cursor) throws IOException {
        return AdminAuditModels.parsePage(
            "{\"schema_version\":\"walksafe.admin-audit-list.v1\",\"items\":["
                + "{\"event_id\":\"" + eventId + "\",\"event_type\":\"READ\","
                + "\"action\":\"SYNTHETIC_READ\",\"outcome\":\"SUCCEEDED\","
                + "\"actor_id\":\"synthetic-admin\",\"resource_type\":\"synthetic\","
                + "\"resource_id\":\"synthetic-resource\","
                + "\"occurred_at\":\"2026-09-06T01:00:00Z\",\"correlation_id\":null}],"
                + "\"next_cursor\":" + (cursor == null ? "null" : "\"" + cursor + "\"") + "}"
        );
    }
}
