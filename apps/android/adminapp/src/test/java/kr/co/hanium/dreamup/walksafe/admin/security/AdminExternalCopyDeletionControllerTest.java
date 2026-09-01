package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminExternalCopyDeletionControllerTest {
    @Test
    public void newerGenerationDropsQueuedListBeforeNetwork() throws Exception {
        Loader loader = new Loader();
        AdminExternalCopyDeletionController controller = new AdminExternalCopyDeletionController(loader);
        AdminExternalCopyDeletionController.Request old = controller.beginFirst(
            new AdminExternalCopyDeletionModels.Filter(null)
        );
        AdminExternalCopyDeletionController.Request current = controller.beginFirst(
            new AdminExternalCopyDeletionModels.Filter(AdminExternalCopyDeletionModelsTest.REQUEST_ID)
        );

        assertFalse(controller.execute(old));
        assertTrue(controller.execute(current));
        assertEquals(1, loader.loads);
    }

    @Test
    public void mutationIsOneShotGenerationBoundAndUsesFreshRevision() throws Exception {
        Loader loader = new Loader();
        AdminExternalCopyDeletionController controller = selected(loader);
        AdminExternalCopyDeletionController.Request request = controller.beginRecord(
            loader.initial,
            "REQUEST_SENT",
            "2026-09-01T01:00:00Z",
            null,
            null
        );

        assertTrue(controller.execute(request));
        assertFalse(controller.execute(request));
        assertEquals(1, loader.records);
        assertEquals(0, loader.command.expectedRevision());
        assertNotNull(loader.command.idempotencyKey());
        assertEquals("REQUEST_SENT", controller.snapshot().items().get(0).state());
    }

    @Test
    public void staleOrDisallowedMutationNeverCallsNetwork() throws Exception {
        Loader loader = new Loader();
        AdminExternalCopyDeletionController controller = selected(loader);
        assertThrows(IllegalArgumentException.class, () -> controller.beginRecord(
            loader.initial,
            "REPLY_DELETION_CONFIRMED",
            "2026-09-01T01:00:00Z",
            null,
            "a".repeat(64)
        ));
        AdminExternalCopyDeletionController.Request request = controller.beginRecord(
            loader.initial,
            "REQUEST_SENT",
            "2026-09-01T01:00:00Z",
            null,
            null
        );
        controller.invalidate();
        assertFalse(controller.execute(request));
        assertEquals(0, loader.records);
    }

    @Test
    public void conflictAppliesStrictLatestAndNeverReplaysPost() throws Exception {
        Loader loader = new Loader();
        loader.conflict = true;
        AdminExternalCopyDeletionController controller = selected(loader);

        assertTrue(controller.execute(controller.beginRecord(
            loader.initial,
            "REQUEST_SENT",
            "2026-09-01T01:00:00Z",
            null,
            null
        )));

        assertEquals(1, loader.records);
        assertEquals(AdminExternalCopyDeletionController.Phase.CONFLICT, controller.snapshot().phase());
        assertEquals(1, controller.snapshot().items().get(0).revision());
    }

    @Test
    public void explicitUnknownOutcomeRetryKeepsExactCommandAndIdempotencyKey() throws Exception {
        Loader loader = new Loader();
        loader.failFirstRecord = true;
        AdminExternalCopyDeletionController controller = selected(loader);

        assertTrue(controller.execute(controller.beginRecord(
            loader.initial,
            "REQUEST_SENT",
            "2026-09-01T01:00:00Z",
            null,
            null
        )));
        String firstKey = loader.command.idempotencyKey();
        assertTrue(controller.snapshot().canRetryRecord());
        assertTrue(controller.execute(controller.beginRecordRetry()));

        assertEquals(2, loader.records);
        assertEquals(firstKey, loader.command.idempotencyKey());
        assertEquals("REQUEST_SENT", controller.snapshot().items().get(0).state());
    }

    private static AdminExternalCopyDeletionController selected(Loader loader) throws Exception {
        AdminExternalCopyDeletionController controller = new AdminExternalCopyDeletionController(loader);
        assertTrue(controller.execute(controller.beginFirst(
            new AdminExternalCopyDeletionModels.Filter(null)
        )));
        loader.initial = controller.snapshot().items().get(0);
        return controller;
    }

    private static final class Loader implements AdminExternalCopyDeletionController.Loader {
        int records;
        int loads;
        boolean conflict;
        boolean failFirstRecord;
        AdminExternalCopyDeletionModels.Item initial;
        AdminExternalCopyDeletionModels.EventCommand command;

        @Override
        public AdminExternalCopyDeletionModels.Page load(
            AdminExternalCopyDeletionModels.Filter filter,
            String cursor
        ) throws Exception {
            loads += 1;
            return AdminExternalCopyDeletionModels.parsePage(
                AdminExternalCopyDeletionModelsTest.pageFixture().replace("\"cursor_A\"", "null")
            );
        }

        @Override
        public AdminExternalCopyDeletionModels.Item record(
            AdminExternalCopyDeletionModels.EventCommand value
        ) throws Exception {
            records += 1;
            command = value;
            if (failFirstRecord && records == 1) throw new java.io.IOException("unknown outcome");
            if (conflict) {
                throw new AdminReportRepository.ExternalCopyConflictException(
                    AdminExternalCopyDeletionModels.parseConflict(
                        AdminExternalCopyDeletionModelsTest.conflictFixture(),
                        value.requestId(),
                        value.copyId()
                    )
                );
            }
            return AdminExternalCopyDeletionModels.parseEvent(
                AdminExternalCopyDeletionModelsTest.eventFixture(),
                value
            );
        }
    }
}
