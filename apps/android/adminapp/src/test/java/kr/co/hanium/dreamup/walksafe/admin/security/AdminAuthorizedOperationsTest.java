package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AdminAuthorizedOperationsTest {
    @Test public void retainedSessionCannotBorrowTheNextOperationsLease() throws Exception {
        AdminOperationsApi.SessionContext[] first = new AdminOperationsApi.SessionContext[1];
        AtomicInteger reads = new AtomicInteger();
        AdminReportRepository repository = (AdminReportRepository) Proxy.newProxyInstance(
            AdminReportRepository.class.getClassLoader(), new Class<?>[] {AdminReportRepository.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancelInFlightRequests")) return null;
                AdminOperationsApi.SessionContext current = (AdminOperationsApi.SessionContext) args[0];
                current.requireCurrentOperation();
                if (reads.getAndIncrement() == 0) first[0] = current;
                else {
                    assertThrows(IllegalStateException.class, first[0]::requireCurrentOperation);
                    assertThrows(IllegalStateException.class,
                        () -> first[0].dispatch(() -> {}, () -> fail("closed lease dispatched")));
                }
                return null;
            });
        AdminSecurityController controller = new AdminSecurityController(
            AdminLocalOperationCloseRaceProbe.fakeApi(), null, repository, event -> {});
        try {
            AdminLocalOperationCloseRaceProbe.login(controller);
            controller.reports().getAdminReportDetail("report", true);
            controller.reports().getAdminReportDetail("report", true);
            assertEquals(2, reads.get());
        } finally { controller.close(); }
    }

    @Test public void dispatchFailureAndCompletedRequestsDoNotRetainCancellation() throws Exception {
        AtomicInteger cancelled = new AtomicInteger();
        AdminReportRepository repository = (AdminReportRepository) Proxy.newProxyInstance(
            AdminReportRepository.class.getClassLoader(), new Class<?>[] {AdminReportRepository.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancelInFlightRequests")) return null;
                AdminOperationsApi.SessionContext session = (AdminOperationsApi.SessionContext) args[0];
                assertThrows(IllegalStateException.class, () -> session.dispatch(
                    cancelled::incrementAndGet, () -> { throw new IllegalStateException("enqueue failed"); }));
                session.dispatch(cancelled::incrementAndGet, () -> {}).close();
                return null;
            });
        AdminSecurityController controller = new AdminSecurityController(
            AdminLocalOperationCloseRaceProbe.fakeApi(), null, repository, event -> {});
        try {
            AdminLocalOperationCloseRaceProbe.login(controller);
            controller.reports().getAdminReportDetail("report", true);
            controller.suspendForBackground(false);
            assertEquals("failed or completed admission must be removed", 0, cancelled.get());
        } finally { controller.close(); }
    }

    @Test public void directReportCapabilityCannotOutliveTheSessionAuthority() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AdminReportRepository repository = (AdminReportRepository) Proxy.newProxyInstance(
            AdminReportRepository.class.getClassLoader(), new Class<?>[] {AdminReportRepository.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancelInFlightRequests")) return null;
                requests.incrementAndGet();
                AdminOperationsApi.SessionContext session = (AdminOperationsApi.SessionContext) args[0];
                assertEquals(AdminLocalOperationCloseRaceProbe.SESSION, session.sessionId());
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return null;
            });
        AdminSecurityController controller = new AdminSecurityController(
            AdminLocalOperationCloseRaceProbe.fakeApi(), null, repository, event -> {});
        AdminLocalOperationCloseRaceProbe.login(controller);
        AdminReportOperations reports = controller.reports();
        assertThrows(IllegalStateException.class, () -> reports.getAdminReportDetail("report", false));
        assertEquals(0, requests.get());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = worker.submit(() -> reports.getAdminReportDetail("report", true));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            controller.close();
            assertFalse(controller.snapshot().isAccessSessionActive());
            assertFalse(controller.hasControllerOwnedCredentials());
            release.countDown();
            result.get(3, TimeUnit.SECONDS);
            assertFalse(controller.snapshot().isAccessSessionActive());
            assertThrows(IllegalStateException.class, () -> reports.getAdminReportDetail("report", true));
            assertEquals(1, requests.get());
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
