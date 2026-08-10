package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class AdminEndpointPolicyTest {
    @Test
    public void releaseAllowsOnlyOneRootHttpsOrigin() {
        assertEquals(
            "https://admin.example:8443",
            AdminEndpointPolicy.approvedOriginOrNull("https://ADMIN.example:8443/", false)
        );
        assertNull(AdminEndpointPolicy.approvedOriginOrNull("http://admin.example", false));
        assertNull(AdminEndpointPolicy.approvedOriginOrNull("https://admin.example/api", false));
        assertNull(AdminEndpointPolicy.approvedOriginOrNull("https://secret@admin.example", false));
        assertNull(AdminEndpointPolicy.approvedOriginOrNull("https://admin.example?token=value", false));
    }

    @Test
    public void debugCleartextIsLimitedToLoopback() {
        assertEquals(
            "http://127.0.0.1:8000",
            AdminEndpointPolicy.approvedOriginOrNull("http://127.0.0.1:8000", true)
        );
        assertEquals(
            "http://localhost:8000",
            AdminEndpointPolicy.approvedOriginOrNull("http://LOCALHOST:8000", true)
        );
        assertNull(AdminEndpointPolicy.approvedOriginOrNull("http://192.168.0.2:8000", true));
    }
}
