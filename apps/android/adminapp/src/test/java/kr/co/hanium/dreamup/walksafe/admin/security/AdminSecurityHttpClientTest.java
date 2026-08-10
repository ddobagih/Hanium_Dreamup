package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public final class AdminSecurityHttpClientTest {
    @Test
    public void loginAndProtectedStateUseExactAdminContextAndDeviceBinding() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());
        transport.enqueue(200, stateJson("NORMAL", "state-v1"));
        AdminSecurityHttpClient client = client(transport);

        var login = client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        var state = client.getState(login.accessToken());

        Request loginRequest = transport.requests.get(0);
        assertEquals("POST", loginRequest.method);
        assertEquals("http://127.0.0.1:8000/admin/security/sessions", loginRequest.url);
        assertEquals("ADMIN_ANDROID", loginRequest.headers.get("X-WalkSafe-App-Kind"));
        assertEquals("ADMIN", loginRequest.headers.get("X-WalkSafe-Role"));
        assertEquals("walksafe-admin-api", loginRequest.headers.get("X-WalkSafe-Audience"));
        assertEquals(DEVICE_ID, loginRequest.headers.get("X-WalkSafe-Device-Id"));
        assertFalse(loginRequest.headers.containsKey("Authorization"));
        JSONObject loginBody = new JSONObject(loginRequest.body);
        assertEquals(PASSWORD, loginBody.getString("password"));
        assertEquals("123456", loginBody.getString("totp_code"));

        Request stateRequest = transport.requests.get(1);
        assertEquals("Bearer " + ACCESS_TOKEN, stateRequest.headers.get("Authorization"));
        assertEquals(DEVICE_ID, stateRequest.headers.get("X-WalkSafe-Device-Id"));
        assertEquals("state-v1", state.stateVersion());
    }

    @Test
    public void loginFixesBodyBytesBeforeLoginProofAndReusesThemWithProofHeaders() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());

        client(transport).login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertEquals(1, transport.challengeRequests.size());
        Request challengeRequest = transport.challengeRequests.get(0);
        Request loginRequest = transport.requests.get(0);
        JSONObject challenge = new JSONObject(challengeRequest.body);
        assertEquals(13, challenge.length());
        assertEquals("LOGIN", challenge.getString("purpose"));
        assertEquals("POST", challenge.getString("method"));
        assertEquals("/admin/security/sessions", challenge.getString("path"));
        assertTrue(challenge.isNull("action"));
        assertTrue(challenge.isNull("read_purpose"));
        assertTrue(challenge.isNull("session_id"));
        assertEquals(
            AdminCanonicalEncoding.sha256Hex(loginRequest.body.getBytes(StandardCharsets.UTF_8)),
            challenge.getString("body_sha256")
        );
        assertEquals(
            challenge.getString("correlation_id"),
            loginRequest.headers.get(AdminOperationsHttpClient.CORRELATION_ID_HEADER)
        );
        assertEquals(
            AdminDeviceProofTest.CHALLENGE_ID,
            loginRequest.headers.get(AdminDeviceProof.CHALLENGE_ID_HEADER)
        );
        assertFalse(loginRequest.headers.get(AdminDeviceProof.SIGNATURE_HEADER).contains("="));
    }

    @Test
    public void proofBoundLoginRequiresExactChallengeAndFinalSuccessStatuses() {
        FakeTransport wrongChallenge = new FakeTransport();
        wrongChallenge.challengeStatus = 201;
        wrongChallenge.enqueue(200, loginJson());
        assertThrows(IOException.class, () -> client(wrongChallenge)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));
        assertEquals(1, wrongChallenge.challengeRequests.size());
        assertTrue(wrongChallenge.requests.isEmpty());

        FakeTransport wrongFinal = new FakeTransport();
        wrongFinal.enqueue(201, loginJson());
        assertThrows(IOException.class, () -> client(wrongFinal)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));
        assertEquals(1, wrongFinal.challengeRequests.size());
        assertEquals(1, wrongFinal.requests.size());
    }

    @Test
    public void securityResponsesRejectDuplicateKeysAndTrailingData() {
        String duplicateAccessToken = "{\"access_token\":\"" + ACCESS_TOKEN + "\"," + loginJson().substring(1);
        FakeTransport duplicate = new FakeTransport();
        duplicate.enqueue(200, duplicateAccessToken);
        assertThrows(IOException.class, () -> client(duplicate)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));

        FakeTransport trailing = new FakeTransport();
        trailing.enqueue(200, loginJson() + " trailing");
        assertThrows(IOException.class, () -> client(trailing)
            .login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"));
    }

    @Test
    public void namedAdministratorIdsMayUseTheServerContractEmailForm() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());

        client(transport).login("owner@example.com", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertEquals(
            "owner@example.com",
            new JSONObject(transport.requests.get(0).body).getString("admin_id")
        );
    }

    @Test
    public void recoveryCallsCarryAdminContextButNeverBearerAccessCredentials() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"recovery_token\":\"" + RECOVERY_TOKEN + "\",\"security_state\":\"RECOVERY_IN_PROGRESS\"}");
        transport.enqueue(200, loginJson());
        AdminSecurityHttpClient client = client(transport);

        client.startRecovery("admin-01", RECOVERY_CODE, DEVICE_ID, "replacement phone");
        client.completeRecovery(RECOVERY_TOKEN, NEW_PASSWORD, "654321", DEVICE_ID, "replacement phone");

        for (Request request : transport.requests) {
            assertEquals("ADMIN_ANDROID", request.headers.get("X-WalkSafe-App-Kind"));
            assertEquals(DEVICE_ID, request.headers.get("X-WalkSafe-Device-Id"));
            assertFalse(request.headers.containsKey("Authorization"));
        }
        assertTrue(transport.requests.get(0).body.contains(RECOVERY_CODE));
        assertTrue(transport.requests.get(1).body.contains(RECOVERY_TOKEN));
        assertEquals(1, transport.challengeRequests.size());
        JSONObject completionChallenge = new JSONObject(transport.challengeRequests.get(0).body);
        assertEquals("RECOVERY_COMPLETE", completionChallenge.getString("purpose"));
        assertEquals("/admin/security/recovery/complete", completionChallenge.getString("path"));
        assertTrue(completionChallenge.isNull("action"));
        assertTrue(completionChallenge.isNull("read_purpose"));
        assertTrue(completionChallenge.isNull("session_id"));
        assertFalse(transport.requests.get(0).headers.containsKey(AdminDeviceProof.CHALLENGE_ID_HEADER));
        assertEquals(
            AdminDeviceProofTest.CHALLENGE_ID,
            transport.requests.get(1).headers.get(AdminDeviceProof.CHALLENGE_ID_HEADER)
        );
    }

    @Test
    public void unknownStatesExtraFieldsAndStringExpiryAreRejected() throws Exception {
        FakeTransport extra = new FakeTransport();
        extra.enqueue(200, loginJson().replace("}", ",\"unexpected\":true}"));
        assertThrows(IOException.class, () -> client(extra).login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));

        FakeTransport unknown = loggedInTransport();
        unknown.enqueue(200, stateJson("AUTHENTICATED_NORMAL", "state-v1"));
        AdminSecurityHttpClient unknownClient = client(unknown);
        unknownClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> unknownClient.getState(ACCESS_TOKEN));

        FakeTransport expiry = loggedInTransport();
        expiry.enqueue(200, """
            {"reauthenticated_until_epoch_ms":"20000","action":"report.status.update",
             "method":"PATCH","path":"/reports/report-1/status"}
            """);
        AdminSecurityHttpClient expiryClient = client(expiry);
        expiryClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> expiryClient.reauthenticate(
            ACCESS_TOKEN,
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            RECONFIRMATION_NONCE
        ));
    }

    @Test
    public void reauthenticationUsesExactActionBoundRequestAndRequiresMatchingResponseEcho() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"reauthenticated_until_epoch_ms":20000,"action":"report.status.update",
             "method":"PATCH","path":"/reports/report-1/status"}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        var result = client.reauthenticate(
            ACCESS_TOKEN,
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            RECONFIRMATION_NONCE
        );

        JSONObject body = new JSONObject(transport.requests.get(1).body);
        Set<String> bodyKeys = new java.util.TreeSet<>();
        body.keys().forEachRemaining(bodyKeys::add);
        assertEquals(
            Set.of("password", "totp_code", "action", "method", "path", "nonce"),
            bodyKeys
        );
        assertEquals(RECONFIRMATION_NONCE, body.getString("nonce"));
        assertEquals("report.status.update", result.action());
        assertEquals("PATCH", result.method());
        assertEquals("/reports/report-1/status", result.path());

        FakeTransport mismatch = loggedInTransport();
        mismatch.enqueue(200, """
            {"reauthenticated_until_epoch_ms":20000,"action":"report.status.update",
             "method":"POST","path":"/reports/report-1/status"}
            """);
        AdminSecurityHttpClient mismatchClient = client(mismatch);
        mismatchClient.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");
        assertThrows(IOException.class, () -> mismatchClient.reauthenticate(
            ACCESS_TOKEN,
            PASSWORD,
            "123456",
            "report.status.update",
            "PATCH",
            "/reports/report-1/status",
            RECONFIRMATION_NONCE
        ));
    }

    @Test
    public void reauthenticationRejectsNonCanonicalNonceBeforeNetworkUse() throws Exception {
        FakeTransport transport = loggedInTransport();
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        for (String nonce : new String[] {
            "A".repeat(21),
            "A".repeat(23),
            "AAECAwQFBgcICQoLDA0ODw==",
            "AAECAwQFBgcICQoLDA0ODx"
        }) {
            assertThrows(IOException.class, () -> client.reauthenticate(
                ACCESS_TOKEN,
                PASSWORD,
                "123456",
                "report.status.update",
                "PATCH",
                "/reports/report-1/status",
                nonce
            ));
        }
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void sessionsRequireExactTypedBoundedItems() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"sessions":[{
              "session_id":"11111111-1111-4111-8111-111111111111",
              "device_id":"admin-device-12345678-1234-1234-1234-123456789abc",
              "device_label":"test phone",
              "current":true,
              "revoked":false,
              "last_seen_at":"2026-07-22T00:00:00Z"
            }]}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        var sessions = client.getSessions(ACCESS_TOKEN);

        assertEquals(1, sessions.size());
        assertTrue(sessions.get(0).isCurrent());
    }

    @Test
    public void sessionsRejectAResponseWithoutOneCurrentDeviceBoundSession() throws Exception {
        FakeTransport transport = loggedInTransport();
        transport.enqueue(200, """
            {"sessions":[{
              "session_id":"22222222-2222-4222-8222-222222222222",
              "device_id":"admin-device-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "device_label":"other phone",
              "current":false,
              "revoked":false,
              "last_seen_at":"2026-07-22T00:00:00Z"
            }]}
            """);
        AdminSecurityHttpClient client = client(transport);
        client.login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone");

        assertThrows(IOException.class, () -> client.getSessions(ACCESS_TOKEN));
    }

    @Test
    public void httpErrorsNeverReflectResponseBodiesOrSubmittedSecrets() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(401, "server accidentally echoed " + PASSWORD);

        IOException error = assertThrows(IOException.class, () -> client(transport).login(
            "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
        ));

        assertFalse(error.getMessage().contains(PASSWORD));
        assertEquals("administrator API request failed: status=401", error.getMessage());
    }

    @Test
    public void allowlistedStructuredErrorsExposeOnlyTypedCodesAndBoundedRetrySeconds() {
        String[] wireCodes = {
            "admin_recovery_expired",
            "admin_recovery_invalid",
            "admin_auth_rate_limited",
            "admin_recovery_in_progress",
            "admin_totp_secret_not_replaced",
            "admin_recovery_verification_failed"
        };
        AdminSecurityApiException.Code[] expectedCodes = AdminSecurityApiException.Code.values();

        for (int index = 0; index < wireCodes.length; index++) {
            FakeTransport transport = new FakeTransport();
            transport.enqueue(
                429,
                errorJson(wireCodes[index], "server-only detail " + PASSWORD),
                Map.of("retry-after", "45")
            );

            AdminSecurityApiException error = assertThrows(AdminSecurityApiException.class, () ->
                client(transport).login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone")
            );

            assertEquals(expectedCodes[index], error.code());
            assertEquals(Long.valueOf(45L), error.retryAfterSeconds());
            assertFalse(error.getMessage().contains(PASSWORD));
            assertFalse(error.getMessage().contains("server-only"));
        }
    }

    @Test
    public void unknownMalformedAndExtendedErrorBodiesRemainGenericAndFailClosed() {
        String[] bodies = {
            errorJson("admin_new_unapproved_error", "must stay private"),
            "{\"detail\":{\"code\":\"admin_recovery_expired\"}}",
            "{\"detail\":{\"code\":\"admin_recovery_expired\",\"message\":\"private\",\"extra\":true}}",
            "not-json"
        };

        for (String body : bodies) {
            FakeTransport transport = new FakeTransport();
            transport.enqueue(400, body, Map.of("Retry-After", "99999"));

            IOException error = assertThrows(IOException.class, () -> client(transport).login(
                "admin-01", PASSWORD, "123456", DEVICE_ID, "test phone"
            ));

            assertFalse(error instanceof AdminSecurityApiException);
            assertEquals("administrator API request failed: status=400", error.getMessage());
        }
    }

    @Test
    public void invalidOrExcessiveRetryAfterIsNotPresentedAsTrustedTiming() {
        String[] retryValues = {"0", "3601", "tomorrow", " 45", "45 "};
        for (String retryValue : retryValues) {
            FakeTransport transport = new FakeTransport();
            transport.enqueue(
                429,
                errorJson("admin_auth_rate_limited", "wait"),
                Map.of("Retry-After", retryValue)
            );

            AdminSecurityApiException error = assertThrows(AdminSecurityApiException.class, () ->
                client(transport).login("admin-01", PASSWORD, "123456", DEVICE_ID, "test phone")
            );

            assertEquals(null, error.retryAfterSeconds());
        }
    }

    @Test
    public void passwordLengthMatchesTheServerTwelveToTwoHundredFiftySixContract() {
        FakeTransport transport = new FakeTransport();
        AdminSecurityHttpClient client = client(transport);
        String tooLong = new String(new char[257]).replace('\0', 'x');

        assertThrows(IOException.class, () -> client.login(
            "admin-01", "12345678901", "123456", DEVICE_ID, "test phone"
        ));
        assertThrows(IOException.class, () -> client.login(
            "admin-01", tooLong, "123456", DEVICE_ID, "test phone"
        ));
        assertTrue(transport.requests.isEmpty());
    }

    private static AdminSecurityHttpClient client(FakeTransport transport) {
        return new AdminSecurityHttpClient(
            "http://127.0.0.1:8000",
            true,
            AdminDeviceProofTest.MARKER,
            1,
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static FakeTransport loggedInTransport() {
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, loginJson());
        return transport;
    }

    private static String loginJson() {
        return "{\"access_token\":\"" + ACCESS_TOKEN
            + "\",\"security_state\":\"NORMAL\",\"current_session_id\":\"11111111-1111-4111-8111-111111111111\"}";
    }

    private static String stateJson(String state, String version) {
        return "{\"security_state\":\"" + state + "\",\"state_version\":\"" + version
            + "\",\"observed_at\":\"2026-07-22T00:00:00Z\"}";
    }

    private static String errorJson(String code, String message) {
        return new JSONObject(Map.of("detail", Map.of("code", code, "message", message))).toString();
    }

    private static final class FakeTransport implements AdminSecurityHttpClient.Transport {
        final Deque<AdminSecurityHttpClient.Response> responses = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        final List<Request> challengeRequests = new ArrayList<>();
        int challengeStatus = 200;

        void enqueue(int status, String body) {
            responses.addLast(new AdminSecurityHttpClient.Response(status, body));
        }

        void enqueue(int status, String body, Map<String, String> headers) {
            responses.addLast(new AdminSecurityHttpClient.Response(status, body, headers));
        }

        @Override
        public AdminSecurityHttpClient.Response execute(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body
        ) {
            Request request = new Request(
                method,
                url,
                headers,
                body == null ? "" : new String(body, StandardCharsets.UTF_8)
            );
            if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                challengeRequests.add(request);
                return challengeResponse(request.body);
            }
            requests.add(request);
            return responses.removeFirst();
        }

        private AdminSecurityHttpClient.Response challengeResponse(String requestBody) {
            try {
                JSONObject request = new JSONObject(requestBody);
                Map<String, Object> exact18 = new java.util.LinkedHashMap<>();
                exact18.put("action", request.isNull("action") ? null : request.getString("action"));
                exact18.put("admin_id", request.getString("admin_id"));
                exact18.put("body_sha256", request.getString("body_sha256"));
                exact18.put("challenge_id", AdminDeviceProofTest.CHALLENGE_ID);
                exact18.put("correlation_id", request.getString("correlation_id"));
                exact18.put("device_id", request.getString("device_id"));
                exact18.put("device_key_marker", request.getString("device_key_marker"));
                exact18.put("device_key_version", request.getInt("device_key_version"));
                exact18.put(
                    "expires_at_epoch_ms",
                    AdminDeviceProofTest.ISSUED_AT + AdminDeviceProof.CHALLENGE_TTL_MS
                );
                exact18.put("issued_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT);
                exact18.put("method", request.getString("method"));
                exact18.put("nonce", AdminDeviceProofTest.NONCE);
                exact18.put("path", request.getString("path"));
                exact18.put("purpose", request.getString("purpose"));
                exact18.put("query_sha256", request.getString("query_sha256"));
                exact18.put("read_purpose", request.isNull("read_purpose") ? null : request.getString("read_purpose"));
                exact18.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
                exact18.put("session_id", request.isNull("session_id") ? null : request.getString("session_id"));
                Map<String, Object> exact19 = new java.util.LinkedHashMap<>(exact18);
                exact19.put("signing_payload", AdminCanonicalEncoding.canonicalJson(exact18));
                return new AdminSecurityHttpClient.Response(
                    challengeStatus,
                    AdminCanonicalEncoding.canonicalJson(exact19)
                );
            } catch (Exception error) {
                throw new AssertionError("failed to build device challenge", error);
            }
        }
    }

    private static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Request(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final String DEVICE_ID = "admin-device-12345678-1234-1234-1234-123456789abc";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "new correct horse battery staple";
    private static final String RECOVERY_CODE = "recovery-code-for-tests-123456";
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String RECOVERY_TOKEN = "opaque-recovery-token-for-tests-123456";
    private static final String RECONFIRMATION_NONCE = "AAECAwQFBgcICQoLDA0ODw";
}
