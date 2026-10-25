package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AdminSubmissionRejectionTest {
    @Test public void rejectedWorkflowDropsBusyStateAndDestroysItsCopiesBeforeAnyDispatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AdminReportWorkflowController.Loader loader = (AdminReportWorkflowController.Loader) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{AdminReportWorkflowController.Loader.class},
            (proxy, method, args) -> { calls.incrementAndGet(); throw new AssertionError("submission rejected"); });
        AdminReportWorkflowController controller = new AdminReportWorkflowController(loader);
        char[] password = "synthetic-password".toCharArray(), totp = "123456".toCharArray();
        AdminReportWorkflowController.Request request = controller.beginStatus(
            "11111111-1111-4111-8111-111111111111", "reviewed", 1, password, totp);
        assertTrue(controller.rejectSubmission(request));
        assertEquals(AdminReportWorkflowController.Phase.ERROR, controller.snapshot().phase());
        assertFalse(controller.execute(request));
        assertEquals(0, calls.get());
        assertCleared(request, "password"); assertCleared(request, "totp");
    }

    @Test public void rejectedOrStoppedRawRequestCannotReuseOwnedCredentials() throws Exception {
        for (int boundary = 0; boundary < 3; boundary++) {
            AtomicInteger calls = new AtomicInteger();
            AdminRawCollectionController controller = new AdminRawCollectionController((password, totp) -> {
                calls.incrementAndGet(); return null;
            });
            AdminRawCollectionController.Request request = controller.beginLoad(
                "synthetic-password".toCharArray(), "123456".toCharArray());
            if (boundary == 0) {
                assertTrue(controller.rejectSubmission(request));
                assertThrows(IllegalStateException.class, () -> controller.execute(request));
            } else if (boundary == 1) controller.invalidate();
            else controller.clearSessionState();
            assertEquals(boundary == 2 ? AdminRawCollectionController.Phase.IDLE : AdminRawCollectionController.Phase.ERROR, controller.snapshot().phase());
            if (boundary != 0) assertFalse(controller.execute(request));
            assertEquals(0, calls.get());
            assertCleared(request, "password"); assertCleared(request, "totp");
        }
    }

    private static void assertCleared(Object request, String name) throws Exception {
        Field field = request.getClass().getDeclaredField(name); field.setAccessible(true);
        char[] values = (char[]) field.get(request);
        assertArrayEquals(new char[values.length], values);
    }
}
