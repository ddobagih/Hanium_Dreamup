package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/**
 * S18-20: login, recovery start and recovery completion carry the secrets from caller-owned
 * char[] buffers to the wire without a String of the secret, and the encoded request bytes
 * are zeroed after success, rejection and transport failure. This does not claim that every
 * copy in memory is erased (platform text fields and the HTTP stack are outside this code).
 */
public final class AdminMutableCredentialHttpClientTest {
    private static final String ADMIN_ID = "admin-01";
    private static final String DEVICE_ID = "admin-device-12345678-1234-1234-1234-123456789abc";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "new correct horse battery staple";
    private static final String RECOVERY_CODE = "recovery-code-for-tests-123456";
    private static final String RECOVERY_TOKEN = "opaque-recovery-token-for-tests-123456";
    private static final String ACCESS_TOKEN = "opaque-access-token-for-tests-123456";
    private static final String SESSION_ID = "11111111-1111-4111-8111-111111111111";

    @Test
    public void mutableLoginSendsTheStringLoginContractAndZeroesTheSentBytes() throws Exception {
        CapturingTransport strings = new CapturingTransport();
        strings.enqueue(200, loginJson());
        client(strings).login(ADMIN_ID, PASSWORD, "123456", DEVICE_ID, "test phone");

        CapturingTransport mutable = new CapturingTransport();
        mutable.enqueue(200, loginJson());
        char[] password = PASSWORD.toCharArray();
        char[] totp = "123456".toCharArray();
        AdminSecurityApi.LoginResult result =
            client(mutable).login(ADMIN_ID, password, totp, DEVICE_ID, "test phone");

        assertEquals(SESSION_ID, result.currentSessionId());
        JSONObject expected = new JSONObject(strings.sentText(0));
        JSONObject actual = new JSONObject(mutable.sentText(0));
        assertEquals(expected.length(), actual.length());
        for (String key : new String[] {"admin_id", "password", "totp_code", "device_id", "device_label"}) {
            assertEquals(key, expected.getString(key), actual.getString(key));
        }
        // The device proof binds the exact bytes that were sent.
        JSONObject challenge = new JSONObject(mutable.challengeBodies.get(0));
        assertEquals(AdminCanonicalEncoding.sha256Hex(mutable.sentCopies.get(0)),
            challenge.getString("body_sha256"));
        assertEquals("LOGIN", challenge.getString("purpose"));
        assertZeroed(mutable.sentArrays.get(0));
        // The caller owns its buffers and zeroes them (the Activity does so in finally).
        assertArrayEquals(PASSWORD.toCharArray(), password);
        assertArrayEquals("123456".toCharArray(), totp);
    }

    @Test
    public void mutableLoginZeroesTheSentBytesWhenRejectedOrWhenTheTransportFails() throws Exception {
        CapturingTransport rejected = new CapturingTransport();
        rejected.enqueue(401, "{\"detail\":\"synthetic rejection\"}");
        assertThrows(IOException.class, () -> client(rejected).login(
            ADMIN_ID, PASSWORD.toCharArray(), "123456".toCharArray(), DEVICE_ID, "test phone"));
        assertEquals(1, rejected.sentArrays.size());
        assertZeroed(rejected.sentArrays.get(0));

        CapturingTransport timedOut = new CapturingTransport();
        timedOut.failure = new SocketTimeoutException("synthetic timeout");
        assertThrows(SocketTimeoutException.class, () -> client(timedOut).login(
            ADMIN_ID, PASSWORD.toCharArray(), "123456".toCharArray(), DEVICE_ID, "test phone"));
        assertEquals(1, timedOut.sentArrays.size());
        assertZeroed(timedOut.sentArrays.get(0));
    }

    @Test
    public void mutableLoginValidatesLikeTheStringLoginBeforeAnyRequest() {
        Object[][] cases = {
            {"", PASSWORD.toCharArray(), "123456".toCharArray(), "invalid_admin_id"},
            {ADMIN_ID, new char[0], "123456".toCharArray(), "invalid_password"},
            {ADMIN_ID, "short".toCharArray(), "123456".toCharArray(), "invalid_password"},
            {ADMIN_ID, PASSWORD.toCharArray(), "12345".toCharArray(), "invalid_totp_code"},
            {ADMIN_ID, PASSWORD.toCharArray(), "12345a".toCharArray(), "invalid_totp_code"}
        };
        for (Object[] testCase : cases) {
            CapturingTransport transport = new CapturingTransport();
            IOException error = assertThrows(IOException.class, () -> client(transport).login(
                (String) testCase[0], (char[]) testCase[1], (char[]) testCase[2], DEVICE_ID, "test phone"));
            assertEquals(testCase[3], error.getMessage());
            assertEquals(0, transport.requestCount);
        }
    }

    @Test
    public void mutableRecoveryStartAndCompletionUseMutableBodiesAndZeroThem() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        transport.enqueue(200,
            "{\"recovery_token\":\"" + RECOVERY_TOKEN + "\",\"security_state\":\"RECOVERY_IN_PROGRESS\"}");
        transport.enqueue(200, loginJson());
        AdminSecurityHttpClient client = client(transport);

        AdminSecurityApi.RecoveryStartResult started = client.startRecovery(
            ADMIN_ID, RECOVERY_CODE.toCharArray(), DEVICE_ID, "replacement phone");
        AdminSecurityApi.LoginResult completed = client.completeRecovery(
            started.recoveryToken(), NEW_PASSWORD.toCharArray(), "654321".toCharArray(),
            DEVICE_ID, "replacement phone");

        assertEquals(SESSION_ID, completed.currentSessionId());
        JSONObject start = new JSONObject(transport.sentText(0));
        assertEquals(4, start.length());
        assertEquals(ADMIN_ID, start.getString("admin_id"));
        assertEquals(RECOVERY_CODE, start.getString("recovery_code"));
        assertEquals(DEVICE_ID, start.getString("device_id"));
        assertEquals("replacement phone", start.getString("device_label"));
        JSONObject complete = new JSONObject(transport.sentText(1));
        assertEquals(5, complete.length());
        assertEquals(RECOVERY_TOKEN, complete.getString("recovery_token"));
        assertEquals(NEW_PASSWORD, complete.getString("new_password"));
        assertEquals("654321", complete.getString("totp_code"));
        JSONObject challenge = new JSONObject(transport.challengeBodies.get(0));
        assertEquals("RECOVERY_COMPLETE", challenge.getString("purpose"));
        assertEquals(AdminCanonicalEncoding.sha256Hex(transport.sentCopies.get(1)),
            challenge.getString("body_sha256"));
        assertZeroed(transport.sentArrays.get(0));
        assertZeroed(transport.sentArrays.get(1));
        for (Map<String, String> headers : transport.sentHeaders) {
            assertFalse(headers.containsKey("Authorization"));
        }
    }

    @Test
    public void controllerPassesMutableCredentialsToTheApiWithoutAStringOverload() throws Exception {
        List<String> calls = new ArrayList<>();
        AdminSecurityApi api = new StringRefusingApi(calls);
        AdminSecurityController controller = new AdminSecurityController(api, event -> {});

        controller.login(ADMIN_ID, PASSWORD.toCharArray(), "123456".toCharArray(), DEVICE_ID, "test");
        controller.startRecovery(ADMIN_ID, RECOVERY_CODE.toCharArray(), DEVICE_ID, "test");
        controller.completeRecovery(NEW_PASSWORD.toCharArray(), "654321".toCharArray(), DEVICE_ID, "test");

        assertEquals(Arrays.asList("login(char[])", "startRecovery(char[])", "completeRecovery(char[])"), calls);
        assertTrue(controller.snapshot().isAccessSessionActive());
    }

    @Test
    public void mutablePathsBuildNoStringOfTheSecret() throws Exception {
        String client = read("AdminSecurityHttpClient.java");
        for (String signature : new String[] {
            "public synchronized LoginResult login(\n        String adminId,\n        char[] password,",
            "public synchronized RecoveryStartResult startRecovery(\n        String adminId,\n        char[] recoveryCode,",
            "public synchronized LoginResult completeRecovery(\n        String recoveryToken,\n        char[] newPassword,"
        }) {
            int start = client.indexOf(signature);
            assertTrue(signature, start >= 0);
            String body = client.substring(start, client.indexOf("\n    }\n", start));
            assertFalse(body.contains("new String("));
            assertFalse(body.contains("String.valueOf("));
            assertFalse(body.contains("jsonObject("));
            assertTrue(body.contains("SensitiveByteBuffer"));
            assertTrue(body.contains("Arrays.fill(body, (byte) 0)"));
            assertTrue(body.contains("encoded.destroy()"));
        }
        String credentials = read("AdminMutableCredentials.java");
        assertFalse(credentials.contains("new String("));
        assertTrue(credentials.contains("public synchronized char[][] takeForDispatch()"));
        String activity = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java")),
            StandardCharsets.UTF_8);
        int dispatch = activity.indexOf("private void runCredentialOperation(");
        String worker = activity.substring(dispatch, activity.indexOf("private void attestRecoveryCustody()", dispatch));
        assertTrue(worker.contains("char[][] values = credentials.takeForDispatch();"));
        assertTrue(worker.contains("Arrays.fill(values[0], '\\0');"));
        assertTrue(worker.contains("Arrays.fill(values[1], '\\0');"));
        assertTrue(worker.contains("void run(char[] primary, char[] secondary) throws Exception;"));
        assertFalse(worker.contains("String[] values"));
    }

    private static void assertZeroed(byte[] value) {
        assertTrue(value.length > 0);
        for (byte item : value) assertEquals(0, item);
    }

    private static String read(String name) throws IOException {
        return new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/" + name)), StandardCharsets.UTF_8);
    }

    private static String loginJson() {
        return "{\"access_token\":\"" + ACCESS_TOKEN
            + "\",\"security_state\":\"NORMAL\",\"current_session_id\":\"" + SESSION_ID + "\"}";
    }

    private static AdminSecurityHttpClient client(AdminSecurityHttpClient.Transport transport) {
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

    /** Keeps the exact byte[] the client passed, a copy taken at send time, and headers. */
    private static final class CapturingTransport implements AdminSecurityHttpClient.Transport {
        final Deque<AdminSecurityHttpClient.Response> responses = new ArrayDeque<>();
        final List<byte[]> sentArrays = new ArrayList<>();
        final List<byte[]> sentCopies = new ArrayList<>();
        final List<Map<String, String>> sentHeaders = new ArrayList<>();
        final List<String> challengeBodies = new ArrayList<>();
        IOException failure;
        int requestCount;

        void enqueue(int status, String body) {
            responses.addLast(new AdminSecurityHttpClient.Response(status, body));
        }

        String sentText(int index) {
            return new String(sentCopies.get(index), StandardCharsets.UTF_8);
        }

        @Override
        public AdminSecurityHttpClient.Response execute(
            String method, String url, Map<String, String> headers, byte[] body
        ) throws IOException {
            requestCount += 1;
            if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                String text = new String(body, StandardCharsets.UTF_8);
                challengeBodies.add(text);
                return challenge(text);
            }
            sentArrays.add(body);
            sentCopies.add(body.clone());
            sentHeaders.add(headers);
            if (failure != null) throw failure;
            return responses.removeFirst();
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
            } catch (Exception error) {
                throw new AssertionError("invalid synthetic proof challenge", error);
            }
        }
    }

    /** Only the mutable overloads succeed; a String overload call fails the test. */
    private static final class StringRefusingApi implements AdminSecurityApi {
        private final List<String> calls;

        StringRefusingApi(List<String> calls) {
            this.calls = calls;
        }

        private StateSnapshot state() {
            return new StateSnapshot(AdminSecurityState.NORMAL, "synthetic-v1",
                "2026-09-17T00:00:00Z", AdminRecoveryCustodyState.ATTESTED, "2026-09-17T00:00:00Z");
        }

        @Override public LoginResult login(String admin, String password, String totp, String device,
            String label) {
            throw new AssertionError("String login must not be used");
        }

        @Override public LoginResult login(String admin, char[] password, char[] totp, String device,
            String label) {
            calls.add("login(char[])");
            return new LoginResult(ACCESS_TOKEN, AdminSecurityState.NORMAL, SESSION_ID);
        }

        @Override public StateSnapshot getState(String token) { return state(); }

        @Override public DeviceInventory getDeviceInventory(String token) {
            return new DeviceInventory(java.util.Collections.singletonList(
                new SessionInfo(SESSION_ID, DEVICE_ID, "test", true, false, "2026-09-17T00:00:00Z")),
                java.util.Collections.singletonList(new DeviceInfo(DEVICE_ID, true)));
        }

        @Override public StateSnapshot revokeSession(String token, String session) { return state(); }

        @Override public StateSnapshot attestRecoveryCustody(String token,
            RecoveryCustodyAttestation attestation) { return state(); }

        @Override public StateSnapshot reportLostDevice(String token, String device,
            Map<String, String> headers) { return state(); }

        @Override public void clearLocalBinding() { }

        @Override public ReauthenticationResult reauthenticate(String token, String password, String totp,
            String action, String method, String path, String nonce) {
            throw new AssertionError("not used");
        }

        @Override public RecoveryStartResult startRecovery(String admin, String code, String device,
            String label) {
            throw new AssertionError("String recovery start must not be used");
        }

        @Override public RecoveryStartResult startRecovery(String admin, char[] code, String device,
            String label) {
            calls.add("startRecovery(char[])");
            return new RecoveryStartResult(
                "synthetic-recovery-token-not-a-real-secret", AdminSecurityState.RECOVERY_IN_PROGRESS);
        }

        @Override public LoginResult completeRecovery(String token, String password, String totp,
            String device, String label) {
            throw new AssertionError("String recovery completion must not be used");
        }

        @Override public LoginResult completeRecovery(String token, char[] password, char[] totp,
            String device, String label) {
            calls.add("completeRecovery(char[])");
            return new LoginResult(ACCESS_TOKEN, AdminSecurityState.NORMAL, SESSION_ID);
        }
    }
}
