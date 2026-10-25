package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AdminCapturedRevokeHttpClientTest {
    @Test public void capturedRevokeUsesOnlyDedicatedAdmissionAfterRetainedClientBindingWasCleared() throws Exception {
        AtomicInteger requests = new AtomicInteger(), registrations = new AtomicInteger(), closes = new AtomicInteger();
        AdminSecurityHttpClient.Transport transport = new AdminSecurityHttpClient.Transport() {
            @Override public AdminSecurityHttpClient.Response execute(String method, String url, Map<String, String> headers, byte[] body) {
                throw new AssertionError("capture must not use the unadmitted transport fallback");
            }
            @Override public AdminSecurityHttpClient.Response execute(AdminRequestAdmission admission, String method, String url,
                Map<String, String> headers, byte[] body) throws IOException {
                try (AdminRequestAdmission.Registration ignored = admission.dispatch(() -> {}, requests::incrementAndGet)) {
                    assertEquals("POST", method);
                    assertEquals("http://127.0.0.1:8000/admin/security/sessions/66666666-6666-4666-8666-666666666666/revoke", url);
                    assertEquals("Bearer synthetic-captured-revoke-token-0000000000000000", headers.get("Authorization"));
                    assertEquals("synthetic-captured-device", headers.get("X-WalkSafe-Device-Id"));
                    assertEquals("{}", new String(body, StandardCharsets.UTF_8));
                    return new AdminSecurityHttpClient.Response(200,
                        "{\"security_state\":\"NORMAL\",\"state_version\":\"v1\",\"observed_at\":\"2026-10-07T00:00:00Z\"}");
                }
            }
        };
        AdminSecurityHttpClient client = new AdminSecurityHttpClient("http://127.0.0.1:8000", true, "a".repeat(64), 1,
            payload -> { throw new AssertionError("current-session revoke requires no signing challenge"); }, () -> 1L, transport);
        client.bindRequestAdmission((cancel, start) -> { throw new IllegalStateException("old authority retired"); });
        client.clearLocalBinding();
        assertThrows(IOException.class, () -> client.getState("synthetic-captured-revoke-token-0000000000000000"));
        AdminRequestAdmission dedicated = (cancel, start) -> {
            registrations.incrementAndGet(); start.run(); return closes::incrementAndGet;
        };
        assertEquals(AdminSecurityState.NORMAL, client.revokeSession("synthetic-captured-revoke-token-0000000000000000",
            "66666666-6666-4666-8666-666666666666", "synthetic-captured-device", dedicated).securityState());
        assertEquals(1, requests.get()); assertEquals(1, registrations.get()); assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> client.revokeSession("synthetic-captured-revoke-token-0000000000000000",
            "66666666-6666-4666-8666-666666666666", "synthetic-captured-device",
            (cancel, start) -> { throw new IllegalStateException("capture retired"); }));
        assertEquals(1, requests.get());
    }
}
