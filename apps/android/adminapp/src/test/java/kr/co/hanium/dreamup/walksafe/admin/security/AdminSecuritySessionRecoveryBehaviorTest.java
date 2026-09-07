package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AdminSecuritySessionRecoveryBehaviorTest {
    private static final String ADMIN_ID = "synthetic-admin";
    private static final String DEVICE_ID = "synthetic-device";
    private static final String PASSWORD = "synthetic-password-not-a-real-account";
    private static final String TOTP = "123456";

    @Test
    public void rejectedLoginCanRetryWithoutKeepingAnyRejectedSession() throws Exception {
        for (int status : new int[] {401, 403}) {
            Fixture fixture = new Fixture();
            fixture.transport.loginFailure = rejected(status);
            assertThrows(IOException.class, fixture::login);
            assertEquals(AdminSecurityState.FAIL_CLOSED, fixture.controller.snapshot().securityState());
            assertFalse(fixture.controller.snapshot().isAccessSessionActive());
            assertNull(fixture.controller.snapshot().currentSessionId());

            fixture.login();

            assertEquals(2, fixture.transport.loginCalls);
            assertNormal(fixture);
            assertEquals(1, fixture.controller.snapshot().sessions().size());
        }
    }

    @Test
    public void timeoutAfterSessionIssueRecoversByRefreshWithoutIssuingAnotherSession() throws Exception {
        Fixture fixture = new Fixture();
        fixture.transport.inventoryFailure = new SocketTimeoutException("synthetic timeout");
        assertThrows(SocketTimeoutException.class, fixture::login);
        assertEquals(AdminSecurityState.FAIL_CLOSED, fixture.controller.snapshot().securityState());
        assertTrue(fixture.controller.snapshot().isAccessSessionActive());
        String issuedSession = fixture.controller.snapshot().currentSessionId();
        assertFrozen(fixture);

        fixture.controller.refresh();

        assertNormal(fixture);
        assertEquals(issuedSession, fixture.controller.snapshot().currentSessionId());
        assertEquals(1, fixture.transport.loginCalls);
    }

    @Test
    public void expiredOrForbiddenStateRequiresExplicitReloginAndRejectsTheOldToken() throws Exception {
        for (int status : new int[] {401, 403}) {
            Fixture fixture = new Fixture();
            fixture.login();
            String oldToken = fixture.transport.accessToken;
            String oldSession = fixture.controller.snapshot().currentSessionId();
            fixture.transport.stateFailure = rejected(status);

            assertThrows(IOException.class, fixture.controller::refresh);
            assertFrozen(fixture);
            assertEquals(1, fixture.transport.loginCalls);

            fixture.login();

            assertNormal(fixture);
            assertNotEquals(oldSession, fixture.controller.snapshot().currentSessionId());
            int requests = fixture.transport.requestCount;
            assertThrows(IOException.class, () -> fixture.client.getState(oldToken));
            assertEquals("old token must fail before transport", requests, fixture.transport.requestCount);
        }
    }

    @Test
    public void refreshTimeoutIsNotAutomaticallyRetriedAndExplicitRefreshRestoresAuthority() throws Exception {
        Fixture fixture = new Fixture();
        fixture.login();
        String session = fixture.controller.snapshot().currentSessionId();
        int requests = fixture.transport.requestCount;
        fixture.transport.stateFailure = new SocketTimeoutException("synthetic timeout");

        assertThrows(SocketTimeoutException.class, fixture.controller::refresh);

        assertEquals(requests + 1, fixture.transport.requestCount);
        assertFrozen(fixture);
        assertTrue(fixture.controller.snapshot().isAccessSessionActive());
        fixture.controller.refresh();
        assertNormal(fixture);
        assertEquals(requests + 3, fixture.transport.requestCount);
        assertEquals(session, fixture.controller.snapshot().currentSessionId());
        assertEquals(1, fixture.transport.loginCalls);
    }

    @Test
    public void forbiddenRefreshStaysFrozenUntilAnExplicitSuccessfulServerRefresh() throws Exception {
        Fixture fixture = new Fixture();
        fixture.login();
        fixture.transport.stateFailure = rejected(403);

        assertThrows(IOException.class, fixture.controller::refresh);
        assertFrozen(fixture);
        int requests = fixture.transport.requestCount;
        assertFrozen(fixture);
        assertEquals(requests, fixture.transport.requestCount);

        fixture.controller.refresh();

        assertNormal(fixture);
        assertEquals(1, fixture.transport.loginCalls);
    }

    @Test
    public void uncertainLogoutAlwaysClearsBindingAndRejectsQueuedProtectedReads() throws Exception {
        Object[] failures = {
            rejected(401), rejected(403), new SocketTimeoutException("synthetic timeout")
        };
        for (Object failure : failures) {
            Fixture fixture = new Fixture();
            fixture.login();
            String oldToken = fixture.transport.accessToken;
            String session = fixture.controller.snapshot().currentSessionId();
            fixture.transport.revokeFailure = failure;

            assertThrows(IOException.class, () -> fixture.controller.revokeSession(session));

            assertEquals(AdminSecurityState.SIGNED_OUT, fixture.controller.snapshot().securityState());
            assertFalse(fixture.controller.snapshot().isAccessSessionActive());
            assertTrue(fixture.controller.snapshot().sessions().isEmpty());
            assertTrue(fixture.controller.snapshot().devices().isEmpty());
            assertNull(fixture.controller.snapshot().currentSessionId());
            int requests = fixture.transport.requestCount;
            assertThrows(IOException.class, () -> fixture.client.getState(oldToken));
            try {
                fixture.controller.refresh();
                fail("signed-out controller must reject a queued refresh");
            } catch (IOException | IllegalStateException expected) {
                // Both are fail-closed API boundary outcomes, with no transport call.
            }
            assertEquals("logout must fence subsequent protected work", requests, fixture.transport.requestCount);

            fixture.login();
            assertNormal(fixture);
        }
    }

    @Test
    public void failedReloginCannotReuseThePreviouslyBoundAccessToken() throws Exception {
        Fixture fixture = new Fixture();
        fixture.login();
        String previousToken = fixture.transport.accessToken;
        fixture.transport.loginFailure = rejected(401);

        assertThrows(IOException.class, fixture::login);

        assertFalse(fixture.controller.snapshot().isAccessSessionActive());
        assertNull(fixture.controller.snapshot().currentSessionId());
        int requests = fixture.transport.requestCount;
        assertThrows(IOException.class, () -> fixture.client.getState(previousToken));
        assertEquals(requests, fixture.transport.requestCount);
        fixture.login();
        assertNormal(fixture);
    }

    @Test
    public void missingLoginFieldsMakeNoHttpRequestAndAllowAValidRetry() throws Exception {
        String[][] cases = {
            {"", "", "", "invalid_admin_id", "관리자 ID를 입력하고 형식을 확인한 뒤 다시 로그인하세요."},
            {"", PASSWORD, TOTP, "invalid_admin_id", "관리자 ID를 입력하고 형식을 확인한 뒤 다시 로그인하세요."},
            {ADMIN_ID, "", TOTP, "invalid_password", "비밀번호를 12자 이상 256자 이하로 입력한 뒤 다시 로그인하세요."},
            {ADMIN_ID, PASSWORD, "", "invalid_totp_code", "6자리 숫자 추가 인증 코드를 입력한 뒤 다시 로그인하세요."}
        };
        for (String[] testCase : cases) {
            Fixture fixture = new Fixture();
            IOException error = assertThrows(IOException.class, () -> fixture.controller.login(
                testCase[0], testCase[1], testCase[2], DEVICE_ID, "synthetic phone"
            ));

            assertEquals(testCase[3], error.getMessage());
            assertEquals("local validation must precede even the proof challenge", 0, fixture.transport.requestCount);
            assertEquals(0, fixture.transport.loginCalls);
            assertFrozen(fixture);
            assertFalse(fixture.controller.snapshot().isAccessSessionActive());
            assertNull(fixture.controller.snapshot().currentSessionId());
            assertEquals(testCase[4], AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.GENERAL, error
            ));

            fixture.login();

            assertNormal(fixture);
            assertEquals(1, fixture.transport.loginCalls);
        }
    }

    @Test
    public void missingInputOnReloginStillInvalidatesTheOldBindingWithoutHttp() throws Exception {
        Fixture fixture = new Fixture();
        fixture.login();
        String oldToken = fixture.transport.accessToken;
        int requests = fixture.transport.requestCount;

        assertThrows(IOException.class, () -> fixture.controller.login(
            ADMIN_ID, "", TOTP, DEVICE_ID, "synthetic phone"
        ));

        assertFrozen(fixture);
        assertFalse(fixture.controller.snapshot().isAccessSessionActive());
        assertEquals(requests, fixture.transport.requestCount);
        assertThrows(IOException.class, () -> fixture.client.getState(oldToken));
        assertEquals("old binding must remain unusable without another request", requests, fixture.transport.requestCount);

        fixture.login();
        assertNormal(fixture);
    }

    @Test
    public void serverRejectionsContainingLocalReasonTextNeverBecomeInputGuidance() throws Exception {
        for (int status : new int[] {401, 403, 503}) {
            Fixture fixture = new Fixture();
            fixture.transport.loginFailure = new AdminSecurityHttpClient.Response(
                status,
                "{\"detail\":{\"code\":\"invalid_password\","
                    + "\"message\":\"synthetic-private-data invalid_admin_id invalid_totp_code\"}}"
            );

            IOException error = assertThrows(IOException.class, fixture::login);
            String shown = AdminRecoveryMessagePolicy.failureMessage(
                AdminRecoveryMessagePolicy.Phase.GENERAL, error
            );

            assertEquals("요청을 확인하지 못했습니다. 안전을 위해 관리자 업무를 잠갔습니다.", shown);
            assertFalse(shown.contains("synthetic-private-data"));
            assertFalse(shown.contains("invalid_password"));
            assertFalse(error.getMessage().contains("synthetic-private-data"));
            assertEquals(2, fixture.transport.requestCount);
            assertFrozen(fixture);

            fixture.login();
            assertNormal(fixture);
        }
    }

    @Test
    public void reauthenticationFailuresDiscardPriorGrantAndAllowExplicitFreshNonceRetry() throws Exception {
        Object[] failures = {
            rejected(401), rejected(403), new SocketTimeoutException("synthetic timeout")
        };
        for (Object failure : failures) {
            Fixture fixture = new Fixture();
            fixture.login();
            String session = fixture.controller.snapshot().currentSessionId();
            fixture.controller.reauthenticate(
                PASSWORD, TOTP, AdminHighRiskActionGate.Action.DATA_DELETE, 10_000L
            );
            assertEquals(20_000L, fixture.controller.snapshot().reauthenticatedUntilEpochMs());
            String previousNonce = fixture.transport.reauthenticationNonce;
            int requests = fixture.transport.requestCount;
            fixture.transport.reauthenticationFailure = failure;

            assertThrows(IOException.class, () -> fixture.controller.reauthenticate(
                PASSWORD, TOTP, AdminHighRiskActionGate.Action.DATA_DELETE, 10_001L
            ));

            String failedNonce = fixture.transport.reauthenticationNonce;
            assertNotEquals(previousNonce, failedNonce);
            assertEquals(2, fixture.transport.reauthenticationCalls);
            assertEquals(requests + 1, fixture.transport.requestCount);
            assertEquals(0L, fixture.controller.snapshot().reauthenticatedUntilEpochMs());
            assertNormal(fixture);
            assertEquals(session, fixture.controller.snapshot().currentSessionId());
            assertFalse(fixture.controller.consumeHighRiskAuthorization(
                AdminHighRiskActionGate.Action.DATA_DELETE, 10_002L, true
            ).isAllowed());
            assertEquals("denied authorization must not send a request", requests + 1,
                fixture.transport.requestCount);

            fixture.controller.reauthenticate(
                PASSWORD, TOTP, AdminHighRiskActionGate.Action.DATA_DELETE, 10_003L
            );

            String retriedNonce = fixture.transport.reauthenticationNonce;
            assertTrue(retriedNonce.matches("[A-Za-z0-9_-]{22}"));
            assertNotEquals(previousNonce, retriedNonce);
            assertNotEquals(failedNonce, retriedNonce);
            assertEquals(3, fixture.transport.reauthenticationCalls);
            assertEquals(requests + 2, fixture.transport.requestCount);
            assertEquals(1, fixture.transport.loginCalls);
            assertNormal(fixture);
            assertEquals(session, fixture.controller.snapshot().currentSessionId());
            var allowed = fixture.controller.consumeHighRiskAuthorization(
                AdminHighRiskActionGate.Action.DATA_DELETE, 10_004L, true
            );
            assertTrue(allowed.isAllowed());
            assertEquals(retriedNonce,
                allowed.requestHeaders().get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER));
            assertFalse(fixture.controller.consumeHighRiskAuthorization(
                AdminHighRiskActionGate.Action.DATA_DELETE, 10_005L, true
            ).isAllowed());
            assertEquals(requests + 2, fixture.transport.requestCount);
        }
    }

    private static void assertFrozen(Fixture fixture) {
        assertEquals(AdminSecurityState.FAIL_CLOSED, fixture.controller.snapshot().securityState());
        assertFalse(fixture.controller.highRiskDecision(
            AdminHighRiskActionGate.Action.RELEASE_APPROVAL, 1L, true
        ).isAllowed());
    }

    private static void assertNormal(Fixture fixture) {
        assertEquals(AdminSecurityState.NORMAL, fixture.controller.snapshot().securityState());
        assertTrue(fixture.controller.snapshot().isAccessSessionActive());
        assertEquals(ADMIN_ID, fixture.controller.snapshot().authenticatedAdminId());
        assertEquals(AdminRecoveryCustodyState.ATTESTED, fixture.controller.snapshot().recoveryCustodyState());
    }

    private static AdminSecurityHttpClient.Response rejected(int status) {
        return new AdminSecurityHttpClient.Response(status, "{\"detail\":\"synthetic rejection\"}");
    }

    private static final class Fixture {
        final FakeTransport transport = new FakeTransport();
        final AdminSecurityHttpClient client = new AdminSecurityHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
        final AdminSecurityController controller = new AdminSecurityController(client, event -> {});

        void login() throws IOException {
            controller.login(ADMIN_ID, PASSWORD, TOTP, DEVICE_ID, "synthetic phone");
        }
    }

    private static final class FakeTransport implements AdminSecurityHttpClient.Transport {
        Object loginFailure;
        Object stateFailure;
        Object inventoryFailure;
        Object revokeFailure;
        Object reauthenticationFailure;
        int loginCalls;
        int requestCount;
        int reauthenticationCalls;
        String reauthenticationNonce;
        String accessToken;
        String sessionId;

        @Override
        public AdminSecurityHttpClient.Response execute(
            String method, String url, Map<String, String> headers, byte[] body
        ) throws IOException {
            requestCount += 1;
            if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                return challenge(new String(body, StandardCharsets.UTF_8));
            }
            if ("POST".equals(method) && url.endsWith("/admin/security/sessions")) {
                loginCalls += 1;
                Object failure = loginFailure;
                loginFailure = null;
                if (failure != null) return respond(failure);
                accessToken = "synthetic-access-token-for-behavior-" + loginCalls;
                sessionId = String.format(java.util.Locale.ROOT, "00000000-0000-4000-8000-%012d", loginCalls);
                return new AdminSecurityHttpClient.Response(200,
                    "{\"access_token\":\"" + accessToken + "\",\"security_state\":\"NORMAL\","
                        + "\"current_session_id\":\"" + sessionId + "\"}");
            }
            assertEquals("Bearer " + accessToken, headers.get("Authorization"));
            if ("POST".equals(method) && url.endsWith("/admin/security/reauthenticate")) {
                reauthenticationCalls += 1;
                try {
                    JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    reauthenticationNonce = request.getString("nonce");
                    Object failure = reauthenticationFailure;
                    reauthenticationFailure = null;
                    if (failure != null) return respond(failure);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("reauthenticated_until_epoch_ms", 20_000L);
                    for (String key : new String[] {"action", "method", "path"}) {
                        response.put(key, request.getString(key));
                    }
                    return new AdminSecurityHttpClient.Response(200,
                        AdminCanonicalEncoding.canonicalJson(response));
                } catch (org.json.JSONException exception) {
                    throw new AssertionError("invalid synthetic reauthentication request", exception);
                }
            }
            if (url.endsWith("/admin/security/state")) {
                Object failure = stateFailure;
                stateFailure = null;
                return failure == null ? state() : respond(failure);
            }
            if ("GET".equals(method) && url.endsWith("/admin/security/sessions")) {
                Object failure = inventoryFailure;
                inventoryFailure = null;
                if (failure != null) return respond(failure);
                return new AdminSecurityHttpClient.Response(200,
                    "{\"sessions\":[{\"session_id\":\"" + sessionId + "\","
                        + "\"device_id\":\"" + DEVICE_ID + "\",\"device_label\":\"synthetic phone\","
                        + "\"current\":true,\"revoked\":false,\"last_seen_at\":\"2026-09-06T02:00:00Z\"}],"
                        + "\"devices\":[{\"device_id\":\"" + DEVICE_ID + "\",\"current\":true}]}");
            }
            if (url.endsWith("/revoke")) {
                Object failure = revokeFailure;
                revokeFailure = null;
                return failure == null ? state() : respond(failure);
            }
            throw new AssertionError("unexpected synthetic transport route");
        }

        private static AdminSecurityHttpClient.Response respond(Object response) throws IOException {
            if (response instanceof IOException exception) throw exception;
            return (AdminSecurityHttpClient.Response) response;
        }

        private static AdminSecurityHttpClient.Response state() {
            return new AdminSecurityHttpClient.Response(200,
                "{\"security_state\":\"NORMAL\",\"state_version\":\"synthetic-v1\","
                    + "\"observed_at\":\"2026-09-06T02:00:00Z\","
                    + "\"recovery_custody_state\":\"ATTESTED\","
                    + "\"recovery_custody_attested_at\":\"2026-09-06T01:00:00Z\"}");
        }

        private static AdminSecurityHttpClient.Response challenge(String requestBody) {
            try {
                JSONObject request = new JSONObject(requestBody);
                Map<String, Object> value = new LinkedHashMap<>();
                for (String key : new String[] {
                    "action", "admin_id", "body_sha256", "correlation_id", "device_id",
                    "device_key_marker", "method", "path", "purpose", "query_sha256",
                    "read_purpose", "session_id"
                }) {
                    value.put(key, request.isNull(key) ? null : request.getString(key));
                }
                value.put("device_key_version", request.getInt("device_key_version"));
                value.put("challenge_id", AdminDeviceProofTest.CHALLENGE_ID);
                value.put("nonce", AdminDeviceProofTest.NONCE);
                value.put("issued_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT);
                value.put("expires_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT + AdminDeviceProof.CHALLENGE_TTL_MS);
                value.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
                Map<String, Object> response = new LinkedHashMap<>(value);
                response.put("signing_payload", AdminCanonicalEncoding.canonicalJson(value));
                return new AdminSecurityHttpClient.Response(200, AdminCanonicalEncoding.canonicalJson(response));
            } catch (Exception exception) {
                throw new AssertionError("invalid synthetic proof challenge", exception);
            }
        }
    }
}
