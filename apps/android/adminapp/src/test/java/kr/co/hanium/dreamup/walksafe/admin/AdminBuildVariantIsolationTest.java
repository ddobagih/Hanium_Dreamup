package kr.co.hanium.dreamup.walksafe.admin;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdminBuildVariantIsolationTest {
    @Test public void releaseNeverEnablesDebugOperations() {
        assertEquals("ADMIN", BuildConfig.WALKSAFE_PRODUCT_ROLE);
        assertTrue(BuildConfig.ADMIN_SECURITY_WORKFLOWS_ENABLED);
        if (!BuildConfig.DEBUG) {
            assertFalse(BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED);
            assertEquals("SECURITY_CONTROL_ENABLED_OPERATIONS_LOCKED", BuildConfig.ADMIN_WORKFLOW_STATE);
        } else {
            assertEquals(Boolean.getBoolean("walksafe.test.expectedAdminOperations"),
                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED);
            assertEquals(BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                ? "INTERNAL_DEBUG_REVIEW_DELIVERY_ENABLED" : "SECURITY_CONTROL_ENABLED_OPERATIONS_LOCKED",
                BuildConfig.ADMIN_WORKFLOW_STATE);
        }
    }
}
