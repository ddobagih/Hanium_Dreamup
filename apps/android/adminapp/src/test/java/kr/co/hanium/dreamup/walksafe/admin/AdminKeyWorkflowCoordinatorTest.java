package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyUseAuthorization;
import org.junit.Test;

public final class AdminKeyWorkflowCoordinatorTest {
    @Test public void credentialBoundFailureNeverReplaysAndDuplicateResultIsTerminal() {
        AdminKeyWorkflowCoordinator owner = new AdminKeyWorkflowCoordinator();
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL,
            owner.onFailure(new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null), null));
        owner.credentialLaunched();
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.REENTER_CREDENTIALS,
            owner.credentialResult(true));
        assertNull(owner.takeReady());
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, owner.credentialResult(true));
    }
    @Test public void repeatableOperationHasExactlyOneRetryAndCloseDropsIt() {
        AdminKeyWorkflowCoordinator owner = new AdminKeyWorkflowCoordinator();
        AtomicInteger runs = new AtomicInteger();
        owner.onFailure(new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null), runs::incrementAndGet);
        owner.credentialLaunched();
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.RETRY_ONCE, owner.credentialResult(true));
        owner.takeReady().run();
        assertNull(owner.takeReady());
        assertEquals(1, runs.get());
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP,
            owner.onFailure(new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null), runs::incrementAndGet));
        owner.onSuccess();
        owner.onFailure(new AdminDeviceKeyUseAuthorization.AuthenticationRequiredException(null), runs::incrementAndGet);
        owner.credentialLaunched();
        long generation = owner.generation();
        assertTrue(owner.beginReconcile());
        assertFalse(owner.beginReconcile());
        owner.close();
        assertFalse(owner.isCurrent(generation));
        assertFalse(owner.applyPending(generation, null));
        assertFalse(owner.finishReconcile(generation));
        assertEquals(AdminDeviceKeyUseAuthorization.Decision.STOP, owner.credentialResult(true));
        assertNull(owner.takeReady());
    }
}
