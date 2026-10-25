package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackage;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportModels;
import org.junit.Test;

public final class DeliveryPackageCoordinatorTest {
    @Test public void reconnectResultIsConsumedOnceAndSessionChangesInvalidateTheWorker() {
        DeliveryPackageCoordinator owner = new DeliveryPackageCoordinator();
        DeliveryPackageCoordinator.Reconnect request = owner.beginReconnect(3, "session", null, null);
        assertTrue(owner.awaitingReconnect());
        assertThrows(IllegalStateException.class, () -> owner.beginReconnect(3, "session", null, null));
        assertSame(request, owner.takeReconnect());
        assertNull(owner.takeReconnect());
        assertTrue(owner.isCurrentReconnect(request.generation, 3, 3, "session", "session"));
        assertFalse(owner.isCurrentReconnect(request.generation, 3, 4, "session", "session"));
        assertFalse(owner.isCurrentReconnect(request.generation, 3, 3, "session", "different"));
        owner.clearSession();
        assertFalse(owner.isCurrentReconnect(request.generation, 3, 3, "session", "session"));
    }
    @Test public void deniedSessionNeverOpensTheSelectedFileOrReadsProof() {
        DeliveryPackageCoordinator owner = new DeliveryPackageCoordinator();
        DeliveryPackageCoordinator.Reconnect request = owner.beginReconnect(3, "session", null, null);
        owner.takeReconnect();
        AtomicInteger reads = new AtomicInteger();
        assertThrows(IOException.class, () -> owner.reconnect(request,
            () -> { reads.incrementAndGet(); throw new AssertionError("must not open"); },
            new DeliveryPackageCoordinator.SessionAccess() {
                @Override public boolean refresh(String id) { return false; }
                @Override public AdminReportModels.Detail detail(String id) { throw new AssertionError("must not read"); }
                @Override public AdminDeliveryPackage.Proof proof(String id, int revision) { throw new AssertionError("must not read"); }
            }, (eligibility, followUp) -> true));
        assertEquals(0, reads.get());
    }
}
