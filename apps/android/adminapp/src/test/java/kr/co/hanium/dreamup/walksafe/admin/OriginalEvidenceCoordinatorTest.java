package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOriginalEvidence;
import org.junit.Test;

public final class OriginalEvidenceCoordinatorTest {
    private static final long NOW = 1_700_000_000_001L;
    private static final String REPORT = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION = "33333333-3333-4333-8333-333333333333";
    private static final String DEVICE = "device-12345678";
    private final AtomicInteger imagesDestroyed = new AtomicInteger();
    private final OriginalEvidenceCoordinator<String> owner =
        new OriginalEvidenceCoordinator<>(image -> imagesDestroyed.incrementAndGet());
    private OriginalEvidenceCoordinator.Binding binding(long sessionGeneration) {
        return new OriginalEvidenceCoordinator.Binding(REPORT, 3, SESSION, DEVICE, sessionGeneration);
    }
    private OriginalEvidenceCoordinator.Request begin() {
        return owner.begin(binding(4), "password".toCharArray(), "123456".toCharArray());
    }
    @Test public void stopCancelsQueuedWorkClearsCredentialsAndRejectsLateBytes() throws Exception {
        OriginalEvidenceCoordinator.Request request = begin();
        char[][] dispatched = request.takeCredentials();
        FutureTask<Void> task = new FutureTask<>(() -> null);
        owner.attachTask(request, task);
        assertTrue(owner.clear());
        assertTrue(task.isCancelled());
        for (char[] secret : dispatched) for (char value : secret) assertEquals(0, value);
        assertThrows(IllegalStateException.class, request::takeCredentials);
        AdminOriginalEvidence loaded = evidence();
        assertFalse(owner.adopt(request, binding(4), true, loaded, "decoded", NOW));
        assertTrue(loaded.isDestroyed());
        assertEquals(1, imagesDestroyed.get());
    }
    @Test public void sessionReportRevisionAndExpiryRejectLateCompletion() throws Exception {
        OriginalEvidenceCoordinator.Binding[] staleBindings = {
            binding(5),
            new OriginalEvidenceCoordinator.Binding("22222222-2222-4222-8222-222222222222",
                3, SESSION, DEVICE, 4),
            new OriginalEvidenceCoordinator.Binding(REPORT, 4, SESSION, DEVICE, 4)
        };
        for (OriginalEvidenceCoordinator.Binding current : staleBindings) {
            OriginalEvidenceCoordinator.Request request = begin();
            AdminOriginalEvidence loaded = evidence();
            assertFalse(owner.adopt(request, current, true, loaded, "decoded", NOW));
            assertTrue(loaded.isDestroyed());
        }
        OriginalEvidenceCoordinator.Request expired = begin();
        AdminOriginalEvidence loaded = evidence();
        assertFalse(owner.adopt(expired, binding(4), true, loaded, "decoded", NOW + 120_000));
        assertTrue(loaded.isDestroyed());
    }
    @Test public void foregroundGateAndOneTerminalAdoptionAreEnforced() throws Exception {
        OriginalEvidenceCoordinator.Request hidden = begin();
        assertFalse(owner.adopt(hidden, binding(4), false, evidence(), "hidden", NOW));
        OriginalEvidenceCoordinator.Request current = begin();
        AdminOriginalEvidence visible = evidence();
        assertTrue(owner.adopt(current, binding(4), true, visible, "visible", NOW));
        assertFalse(owner.isCurrent(current));
        assertSame(visible, owner.evidence());
        AdminOriginalEvidence duplicate = evidence();
        assertFalse(owner.adopt(current, binding(4), true, duplicate, "duplicate", NOW));
        assertTrue(duplicate.isDestroyed());
        assertFalse(visible.isDestroyed());
        FakeScheduler scheduler = new FakeScheduler();
        owner.scheduleExpiry(visible, NOW, scheduler, owner::clear);
        owner.clear();
        assertTrue(scheduler.cancelled);
        assertTrue(visible.isDestroyed());
        assertNull(owner.evidence());
    }
    @Test public void scheduledExpirySurvivesWallRollbackAndDetachesViewBeforeDisposal() throws Exception {
        java.util.List<String> effects = new java.util.ArrayList<>();
        OriginalEvidenceCoordinator<String> owned = new OriginalEvidenceCoordinator<>(image -> {
            assertEquals(java.util.List.of("detach"), effects);
            effects.add("dispose");
        });
        OriginalEvidenceCoordinator.Request request = owned.begin(binding(4),
            "password".toCharArray(), "123456".toCharArray());
        AdminOriginalEvidence visible = evidence();
        assertTrue(owned.adopt(request, binding(4), true, visible, "image", NOW));
        FakeScheduler scheduler = new FakeScheduler();
        owned.scheduleExpiry(visible, scheduler.wallNow, scheduler, () -> {
            effects.add("detach");
            owned.clear();
        });
        assertEquals(120_000L, scheduler.due);
        scheduler.wallNow -= 600_000L;
        scheduler.advance(119_999L);
        assertTrue(effects.isEmpty());
        scheduler.advance(1L);
        assertEquals(java.util.List.of("detach", "dispose"), effects);
        assertTrue(visible.isDestroyed());
        assertNull(owned.evidence());
    }
    @Test public void cancelledOldExpiryCannotClearTheNextDisplay() throws Exception {
        OriginalEvidenceCoordinator.Request first = begin();
        AdminOriginalEvidence old = evidence();
        assertTrue(owner.adopt(first, binding(4), true, old, "old", NOW));
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger expirations = new AtomicInteger();
        owner.scheduleExpiry(old, NOW, scheduler, () -> {
            expirations.incrementAndGet();
            owner.clear();
        });
        Runnable alreadyQueued = scheduler.callback;
        OriginalEvidenceCoordinator.Request next = begin();
        AdminOriginalEvidence visible = evidence();
        assertTrue(owner.adopt(next, binding(4), true, visible, "next", NOW));
        alreadyQueued.run();
        assertEquals(0, expirations.get());
        assertSame(visible, owner.evidence());
        assertFalse(visible.isDestroyed());
        owner.clear();
    }
    private static final class FakeScheduler implements OriginalEvidenceCoordinator.ExpiryScheduler {
        long wallNow = NOW;
        long elapsed;
        long due;
        Runnable callback;
        boolean cancelled;
        @Override public Runnable schedule(long delay, Runnable callback) {
            this.callback = callback;
            due = elapsed + delay;
            return () -> cancelled = true;
        }
        void advance(long millis) {
            elapsed += millis;
            if (!cancelled && elapsed >= due) callback.run();
        }
    }
    private static AdminOriginalEvidence evidence() throws Exception {
        return kr.co.hanium.dreamup.walksafe.admin.security.AdminOriginalEvidenceFixtures.verified(
            REPORT, SESSION, DEVICE, 3, NOW);
    }
}
