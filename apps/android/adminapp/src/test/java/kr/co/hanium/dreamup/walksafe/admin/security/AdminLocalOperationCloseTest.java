package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class AdminLocalOperationCloseTest {
    @Test
    public void deferredSignOutCannotReviveCredentialsInTheNextOperation() throws Exception {
        AdminSecurityController controller = controller();
        AdminLocalOperationCloseRaceProbe.login(controller);
        deferLocalClear(controller, false);

        assertThrows(IllegalStateException.class, controller::refresh);
        assertFalse(controller.snapshot().isAccessSessionActive());
        assertNull(controller.snapshot().currentSessionId());
    }

    @Test
    public void deferredClearPrecedesFreshLoginRatherThanDiscardingItsNewSession() throws Exception {
        AdminSecurityController controller = controller();
        AdminLocalOperationCloseRaceProbe.login(controller);
        deferLocalClear(controller, false);

        AdminLocalOperationCloseRaceProbe.login(controller);

        assertTrue(controller.snapshot().isAccessSessionActive());
        assertEquals(AdminLocalOperationCloseRaceProbe.SESSION, controller.snapshot().currentSessionId());
        controller.refresh();
        assertTrue(controller.snapshot().isAccessSessionActive());
    }

    @Test
    public void deferredPermanentCloseRejectsAllNewWork() throws Exception {
        AdminSecurityController controller = controller();
        AdminLocalOperationCloseRaceProbe.login(controller);
        deferLocalClear(controller, true);
        assertThrows(IllegalStateException.class, controller::refresh);
        assertThrows(IllegalStateException.class, () -> AdminLocalOperationCloseRaceProbe.login(controller));
        assertFalse(controller.snapshot().isAccessSessionActive());
    }

    @Test
    public void slowBindingCleanupCannotDelayLocalSignOut() throws Exception {
        assertSlowBindingCleanupDoesNotDelayDiscard(false);
    }

    @Test
    public void slowBindingCleanupCannotDelayPermanentClose() throws Exception {
        assertSlowBindingCleanupDoesNotDelayDiscard(true);
    }

    @Test
    public void signOutDetachesCredentialsWhileRefreshIsBlocked() throws Exception {
        assertBlockedRefreshDiscardDetachesCredentials(false);
    }

    @Test
    public void closeDetachesCredentialsWhileRefreshIsBlocked() throws Exception {
        assertBlockedRefreshDiscardDetachesCredentials(true);
    }

    /**
     * S18-18: close() locks locally first and then cancels the clients' in-flight requests,
     * without waiting for the blocked request and without sending a server revoke.
     */
    @Test
    public void closeLocksImmediatelyThenCancelsTheInFlightRequestOfEveryClient() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean blockRefresh = new AtomicBoolean();
        java.util.List<String> calls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AdminSecurityController[] holder = new AdminSecurityController[1];
        boolean[] lockedBeforeCancel = new boolean[1];
        AdminSecurityApi delegate = AdminLocalOperationCloseRaceProbe.fakeApi();
        AdminSecurityApi api = (AdminSecurityApi) Proxy.newProxyInstance(
            AdminSecurityApi.class.getClassLoader(), new Class<?>[] {AdminSecurityApi.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("cancelInFlightRequests")) {
                    calls.add("security");
                    lockedBeforeCancel[0] = !holder[0].snapshot().isAccessSessionActive()
                        && !holder[0].hasControllerOwnedCredentials();
                    cancelled.countDown();
                    return null;
                }
                if (name.equals("getState") && blockRefresh.get()) {
                    entered.countDown();
                    // A real transport fails the blocked request once it is cancelled.
                    if (!cancelled.await(3, TimeUnit.SECONDS)) throw new AssertionError("no cancellation");
                    throw new java.io.IOException(
                        "administrator HTTP request was cancelled; server outcome unknown");
                }
                if (name.equals("revokeSession")) calls.add("revoke");
                try { return method.invoke(delegate, args); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            }
        );
        AdminOperationsApi operations = (AdminOperationsApi) Proxy.newProxyInstance(
            AdminOperationsApi.class.getClassLoader(), new Class<?>[] {AdminOperationsApi.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancelInFlightRequests")) { calls.add("operations"); return null; }
                throw new UnsupportedOperationException(method.getName());
            }
        );
        AdminRawCollectionRepository raw = (AdminRawCollectionRepository) Proxy.newProxyInstance(
            AdminRawCollectionRepository.class.getClassLoader(),
            new Class<?>[] {AdminRawCollectionRepository.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancelInFlightRequests")) { calls.add("raw"); return null; }
                throw new UnsupportedOperationException(method.getName());
            }
        );
        AdminReportRepository reports = (AdminReportRepository) Proxy.newProxyInstance(
            AdminReportRepository.class.getClassLoader(), new Class<?>[] {AdminReportRepository.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancelInFlightRequests")) { calls.add("reports"); return null; }
                throw new UnsupportedOperationException(method.getName());
            });
        AdminIncidentRepository incidents = (AdminIncidentRepository) Proxy.newProxyInstance(
            AdminIncidentRepository.class.getClassLoader(), new Class<?>[] {AdminIncidentRepository.class},
            (proxy, method, args) -> {
                if (method.getName().equals("cancelInFlightRequests")) { calls.add("incidents"); return null; }
                throw new UnsupportedOperationException(method.getName());
            });
        AdminSecurityController controller = new AdminSecurityController(
            api, operations, reports, incidents, raw, event -> {});
        holder[0] = controller;
        AdminLocalOperationCloseRaceProbe.login(controller);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            blockRefresh.set(true);
            Future<?> refresh = executor.submit(() -> { controller.refresh(); return null; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            long started = System.nanoTime();
            executor.submit(controller::close).get(500, TimeUnit.MILLISECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);

            assertEquals(java.util.Arrays.asList("security", "operations", "raw", "reports", "incidents"), calls);
            assertTrue("local lock and secret release must precede cancellation", lockedBeforeCancel[0]);
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> refresh.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.io.IOException
                || failure.getCause() instanceof IllegalStateException);
            assertFalse(controller.snapshot().isAccessSessionActive());
            assertThrows(IllegalStateException.class, controller::refresh);
            assertFalse("close never sends a server revoke by itself", calls.contains("revoke"));
        } finally {
            cancelled.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertBlockedRefreshDiscardDetachesCredentials(boolean permanent)
        throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bindingCleared = new CountDownLatch(1);
        AtomicBoolean blockRefresh = new AtomicBoolean();
        AdminSecurityApi delegate = AdminLocalOperationCloseRaceProbe.fakeApi();
        AdminSecurityApi api = (AdminSecurityApi) Proxy.newProxyInstance(
            AdminSecurityApi.class.getClassLoader(), new Class<?>[] {AdminSecurityApi.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getState") && blockRefresh.get()) {
                    entered.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("refresh wait expired");
                }
                try {
                    Object result = method.invoke(delegate, args);
                    if (method.getName().equals("clearLocalBinding")) bindingCleared.countDown();
                    return result;
                } catch (InvocationTargetException error) { throw error.getCause(); }
            }
        );
        AdminSecurityController controller = new AdminSecurityController(api, event -> {});
        AdminLocalOperationCloseRaceProbe.login(controller);
        controller.refresh(); // A normal operation still succeeds before local discard.
        assertTrue(controller.hasControllerOwnedCredentials());
        long generation = controller.localGeneration();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            blockRefresh.set(true);
            Future<?> refresh = executor.submit(() -> { controller.refresh(); return null; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> discard = executor.submit(() -> {
                if (permanent) controller.close(); else controller.signOutLocal();
            });
            discard.get(500, TimeUnit.MILLISECONDS);
            assertFalse(controller.isLocalGenerationCurrent(generation));
            assertFalse(controller.hasRetainedSession());
            assertFalse(controller.snapshot().isAccessSessionActive());
            assertFalse(controller.hasControllerOwnedCredentials());

            release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> refresh.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(bindingCleared.await(2, TimeUnit.SECONDS));
            assertFalse(controller.hasControllerOwnedCredentials());
            assertThrows(IllegalStateException.class, controller::refresh);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertSlowBindingCleanupDoesNotDelayDiscard(boolean permanent)
        throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AdminSecurityApi delegate = AdminLocalOperationCloseRaceProbe.fakeApi();
        AdminSecurityApi api = (AdminSecurityApi) Proxy.newProxyInstance(
            AdminSecurityApi.class.getClassLoader(), new Class<?>[] {AdminSecurityApi.class},
            (proxy, method, args) -> {
                if (method.getName().equals("clearLocalBinding")) {
                    entered.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("cleanup wait expired");
                }
                try {
                    Object result = method.invoke(delegate, args);
                    if (method.getName().equals("clearLocalBinding")) completed.countDown();
                    return result;
                }
                catch (InvocationTargetException error) { throw error.getCause(); }
            }
        );
        AdminSecurityController controller = new AdminSecurityController(api, event -> {});
        AdminLocalOperationCloseRaceProbe.login(controller);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> discard = executor.submit(() -> {
            if (permanent) controller.close(); else controller.signOutLocal();
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            discard.get(500, TimeUnit.MILLISECONDS);
            assertFalse(executor.submit(controller::snapshot).get(500, TimeUnit.MILLISECONDS)
                .isAccessSessionActive());
            if (permanent) {
                assertThrows(IllegalStateException.class, controller::refresh);
            } else {
                executor.submit(() -> controller.suspendForBackground(false))
                    .get(500, TimeUnit.MILLISECONDS);
                assertTrue(controller.isLocallyLocked());
            }
            release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellingDeviceConfirmationKeepsAllPublicSecurityStateConcealed() throws Exception {
        AdminSecurityController controller = controller();
        AdminLocalOperationCloseRaceProbe.login(controller);
        controller.suspendForBackground(false);
        long generation = controller.localGeneration();
        controller.suspendForBackground(true); // Leaving for the credential prompt is not a logout.

        // RESULT_CANCELED does not call resumeLocalSession, and must not disclose the retained state.
        assertEquals(generation, controller.localGeneration());
        assertTrue(controller.hasRetainedSession());
        assertTrue(controller.isLocallyLocked());
        AdminSecurityController.Snapshot hidden = controller.snapshot();
        assertFalse(hidden.isAccessSessionActive());
        assertFalse(hidden.isRecoveryActive());
        assertNull(hidden.authenticatedAdminId());
        assertNull(hidden.currentSessionId());
        assertTrue(hidden.sessions().isEmpty());
        assertTrue(hidden.devices().isEmpty());
        assertNull(hidden.recoveryCustodyState());
        assertEquals(0L, hidden.reauthenticatedUntilEpochMs());
    }

    private static AdminSecurityController controller() {
        return new AdminSecurityController(AdminLocalOperationCloseRaceProbe.fakeApi(), event -> {});
    }

    private static void deferLocalClear(AdminSecurityController controller, boolean permanent)
        throws Exception {
        Field field = AdminSecurityController.class.getDeclaredField("workLock");
        field.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) field.get(controller);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        lock.lock();
        try {
            // Deterministically model the final worker-unlock window without a production test hook.
            executor.submit(() -> {
                if (permanent) controller.close(); else controller.signOutLocal();
            }).get(500, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
            executor.shutdownNow();
        }
        assertFalse(controller.snapshot().isAccessSessionActive());
    }
}
