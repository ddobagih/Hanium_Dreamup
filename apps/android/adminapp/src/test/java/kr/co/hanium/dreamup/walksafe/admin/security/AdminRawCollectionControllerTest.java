package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public final class AdminRawCollectionControllerTest {
    @Test
    public void newerLoadDiscardsStaleResponseAndSelectionBindsToCurrentList() {
        Loader loader = new Loader();
        AdminRawCollectionController controller = new AdminRawCollectionController(loader);
        var stale = beginLoad(controller);
        var current = beginLoad(controller);

        assertFalse(controller.execute(stale));
        assertTrue(controller.execute(current));
        assertEquals(AdminRawCollectionController.Phase.CONTENT, controller.snapshot().phase());
        controller.select(AdminRawCollectionModelsTest.COLLECTION);
        assertEquals(
            AdminRawCollectionModelsTest.COLLECTION,
            controller.snapshot().selectedCollectionId()
        );
        assertThrows(IllegalArgumentException.class, () -> controller.select(
            "99999999-9999-4999-8999-999999999999"
        ));
    }

    @Test
    public void emptyFailureRetryInvalidateAndSessionClearAreExplicit() {
        Loader loader = new Loader();
        AdminRawCollectionController controller = new AdminRawCollectionController(loader);
        loader.empty = true;
        assertTrue(controller.execute(beginLoad(controller)));
        assertEquals(AdminRawCollectionController.Phase.EMPTY, controller.snapshot().phase());

        loader.empty = false;
        loader.fail = true;
        assertTrue(controller.execute(beginLoad(controller)));
        assertEquals(AdminRawCollectionController.Phase.ERROR, controller.snapshot().phase());
        loader.fail = false;
        assertTrue(controller.execute(controller.beginRetry(
            "password".toCharArray(), "123456".toCharArray()
        )));
        assertEquals(1, controller.snapshot().items().size());

        controller.invalidate();
        controller.clearSessionState();
        assertEquals(AdminRawCollectionController.Phase.IDLE, controller.snapshot().phase());
        assertTrue(controller.snapshot().items().isEmpty());
        assertThrows(IllegalStateException.class, () -> controller.beginRetry(
            "password".toCharArray(), "123456".toCharArray()
        ));
    }

    private static AdminRawCollectionController.Request beginLoad(
        AdminRawCollectionController controller
    ) {
        char[] password = "password".toCharArray();
        char[] totp = "123456".toCharArray();
        AdminRawCollectionController.Request request = controller.beginLoad(password, totp);
        for (char value : password) assertEquals('\0', value);
        for (char value : totp) assertEquals('\0', value);
        return request;
    }

    private static final class Loader implements AdminRawCollectionController.Loader {
        boolean empty;
        boolean fail;

        @Override
        public AdminRawCollectionModels.Page load(char[] password, char[] totp) throws Exception {
            if (fail) throw new IOException("private failure");
            return AdminRawCollectionModels.parsePage(
                empty
                    ? "{\"schema_version\":\"walksafe.admin-raw-collection-list.v1\",\"items\":[]}"
                    : AdminRawCollectionModelsTest.listJson()
            );
        }
    }
}
