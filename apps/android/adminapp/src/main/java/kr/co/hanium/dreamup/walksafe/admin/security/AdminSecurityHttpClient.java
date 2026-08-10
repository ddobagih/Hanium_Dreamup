package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AdminSecurityHttpClient implements AdminSecurityApi {
    static final String APP_KIND = "ADMIN_ANDROID";
    static final String ROLE = "ADMIN";
    static final String AUDIENCE = "walksafe-admin-api";

    interface Transport {
        Response execute(String method, String url, Map<String, String> headers, byte[] body) throws IOException;
    }

    static final class Response {
        final int statusCode;
        final String body;
        final Map<String, String> headers;

        Response(int statusCode, String body) {
            this(statusCode, body, Map.of());
        }

        Response(int statusCode, String body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private final String origin;
    private final Transport transport;
    private final String deviceKeyMarker;
    private final int deviceKeyVersion;
    private final AdminDeviceProof.Signer signer;
    private final AdminOperationsHttpClient.Clock clock;
    private String boundDeviceId;
    private String recoveryAdminId;

    public AdminSecurityHttpClient(
        String configuredOrigin,
        boolean debugBuild,
        AdminDeviceKeyStore.Descriptor descriptor,
        AdminDeviceProof.Signer signer
    ) {
        this(
            configuredOrigin,
            debugBuild,
            descriptor == null ? null : descriptor.marker(),
            descriptor == null ? 0 : descriptor.version(),
            signer,
            System::currentTimeMillis,
            new UrlConnectionTransport()
        );
    }

    AdminSecurityHttpClient(
        String configuredOrigin,
        boolean debugBuild,
        String deviceKeyMarker,
        int deviceKeyVersion,
        AdminDeviceProof.Signer signer,
        AdminOperationsHttpClient.Clock clock,
        Transport transport
    ) {
        String approved = AdminEndpointPolicy.approvedOriginOrNull(configuredOrigin, debugBuild);
        if (approved == null) throw new IllegalArgumentException("admin API origin is not approved");
        if (deviceKeyMarker == null || !deviceKeyMarker.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("device key marker is invalid");
        }
        if (deviceKeyVersion < 1) throw new IllegalArgumentException("device key version is invalid");
        if (signer == null || clock == null) throw new IllegalArgumentException("device proof signer and clock are required");
        if (transport == null) throw new IllegalArgumentException("transport is required");
        this.origin = approved;
        this.deviceKeyMarker = deviceKeyMarker;
        this.deviceKeyVersion = deviceKeyVersion;
        this.signer = signer;
        this.clock = clock;
        this.transport = transport;
    }

    @Override
    public synchronized LoginResult login(
        String adminId,
        String password,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        recoveryAdminId = null;
        String safeAdminId = requireAdminId(adminId);
        String safeDeviceId = requireIdentifier(deviceId, "invalid_device_id");
        byte[] body = jsonBytes(
            jsonObject(
                "admin_id", safeAdminId,
                "password", requireSecret(password, "invalid_password", 12, 256),
                "totp_code", requireTotp(totpCode),
                "device_id", safeDeviceId,
                "device_label", requireLabel(deviceLabel)
            )
        );
        JSONObject response = proofPost(
            "/admin/security/sessions",
            AdminDeviceProof.Purpose.LOGIN,
            safeAdminId,
            safeDeviceId,
            body,
            publicHeaders(safeDeviceId)
        );
        LoginResult result = parseLoginResult(response);
        boundDeviceId = safeDeviceId;
        return result;
    }

    @Override
    public synchronized StateSnapshot getState(String accessToken) throws IOException {
        return parseState(get("/admin/security/state", protectedHeaders(accessToken)));
    }

    @Override
    public synchronized List<SessionInfo> getSessions(String accessToken) throws IOException {
        JSONObject response = get("/admin/security/sessions", protectedHeaders(accessToken));
        requireExactKeys(response, Set.of("sessions"));
        JSONArray array = requiredArray(response, "sessions");
        if (array.length() > 100) throw new IOException("too many administrator sessions");
        List<SessionInfo> sessions = new ArrayList<>();
        Set<String> sessionIds = new HashSet<>();
        int currentCount = 0;
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) throw new IOException("invalid administrator session item");
            requireExactKeys(item, Set.of(
                "session_id", "device_id", "device_label", "current", "revoked", "last_seen_at"
            ));
            if (!(item.opt("current") instanceof Boolean) || !(item.opt("revoked") instanceof Boolean)) {
                throw new IOException("invalid administrator session flags");
            }
            String sessionId = requiredCanonicalUuid(item, "session_id");
            String deviceId = requiredIdentifier(item, "device_id");
            boolean current = item.optBoolean("current", false);
            boolean revoked = item.optBoolean("revoked", false);
            if (!sessionIds.add(sessionId)) throw new IOException("duplicate administrator session id");
            if (current) {
                currentCount += 1;
                if (revoked || !deviceId.equals(boundDeviceId)) {
                    throw new IOException("current administrator session binding is invalid");
                }
            }
            sessions.add(new SessionInfo(
                sessionId,
                deviceId,
                requiredText(item, "device_label", 128),
                current,
                revoked,
                requiredText(item, "last_seen_at", 128)
            ));
        }
        if (currentCount != 1) throw new IOException("one current administrator session is required");
        return List.copyOf(sessions);
    }

    @Override
    public synchronized StateSnapshot revokeSession(String accessToken, String sessionId) throws IOException {
        String safeSessionId = requireCanonicalUuid(sessionId, "invalid_session_id");
        JSONObject response = post(
            "/admin/security/sessions/" + safeSessionId + "/revoke",
            new JSONObject(),
            protectedHeaders(accessToken)
        );
        return parseState(response);
    }

    @Override
    public synchronized ReauthenticationResult reauthenticate(
        String accessToken,
        String password,
        String totpCode,
        String action,
        String method,
        String path,
        String nonce
    ) throws IOException {
        String safeAction = requireAction(action);
        String safeMethod = requireMethod(method);
        String safePath = requirePath(path);
        String safeNonce = requireNonce(nonce);
        JSONObject response = post(
            "/admin/security/reauthenticate",
            jsonObject(
                "password", requireSecret(password, "invalid_password", 12, 256),
                "totp_code", requireTotp(totpCode),
                "action", safeAction,
                "method", safeMethod,
                "path", safePath,
                "nonce", safeNonce
            ),
            protectedHeaders(accessToken)
        );
        requireExactKeys(response, Set.of(
            "reauthenticated_until_epoch_ms", "action", "method", "path"
        ));
        if (!(response.opt("reauthenticated_until_epoch_ms") instanceof Number)) {
            throw new IOException("invalid reauthentication expiry type");
        }
        long until = response.optLong("reauthenticated_until_epoch_ms", 0L);
        if (until <= 0L) throw new IOException("invalid reauthentication expiry");
        String echoedAction = requireAction(requiredText(response, "action", 128));
        String echoedMethod = requireMethod(requiredText(response, "method", 16));
        String echoedPath = requirePath(requiredText(response, "path", 256));
        if (!safeAction.equals(echoedAction)
            || !safeMethod.equals(echoedMethod)
            || !safePath.equals(echoedPath)) {
            throw new IOException("reauthentication binding response does not match the request");
        }
        return new ReauthenticationResult(until, echoedAction, echoedMethod, echoedPath);
    }

    @Override
    public synchronized RecoveryStartResult startRecovery(
        String adminId,
        String recoveryCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        recoveryAdminId = null;
        JSONObject response = post(
            "/admin/security/recovery/start",
            jsonObject(
                "admin_id", requireAdminId(adminId),
                "recovery_code", requireSecret(recoveryCode, "invalid_recovery_code", 24, 256),
                "device_id", requireIdentifier(deviceId, "invalid_device_id"),
                "device_label", requireLabel(deviceLabel)
            ),
            publicHeaders(deviceId)
        );
        requireExactKeys(response, Set.of("recovery_token", "security_state"));
        String token = requiredToken(response, "recovery_token");
        AdminSecurityState state = requiredState(response);
        if (state != AdminSecurityState.RECOVERY_IN_PROGRESS) throw new IOException("recovery did not start");
        recoveryAdminId = requireAdminId(adminId);
        return new RecoveryStartResult(token, state);
    }

    @Override
    public synchronized LoginResult completeRecovery(
        String recoveryToken,
        String newPassword,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        if (recoveryAdminId == null) throw new IOException("recovery administrator identity is unavailable");
        String safeDeviceId = requireIdentifier(deviceId, "invalid_device_id");
        byte[] body = jsonBytes(
            jsonObject(
                "recovery_token", requireToken(recoveryToken),
                "new_password", requireSecret(newPassword, "invalid_new_password", 12, 256),
                "totp_code", requireTotp(totpCode),
                "device_id", safeDeviceId,
                "device_label", requireLabel(deviceLabel)
            )
        );
        JSONObject response = proofPost(
            "/admin/security/recovery/complete",
            AdminDeviceProof.Purpose.RECOVERY_COMPLETE,
            recoveryAdminId,
            safeDeviceId,
            body,
            publicHeaders(safeDeviceId)
        );
        LoginResult result = parseLoginResult(response);
        boundDeviceId = safeDeviceId;
        recoveryAdminId = null;
        return result;
    }

    private JSONObject get(String path, Map<String, String> headers) throws IOException {
        return execute("GET", path, headers, null);
    }

    private JSONObject post(String path, JSONObject body, Map<String, String> headers) throws IOException {
        return execute("POST", path, headers, jsonBytes(body));
    }

    private JSONObject proofPost(
        String path,
        AdminDeviceProof.Purpose purpose,
        String adminId,
        String deviceId,
        byte[] body,
        Map<String, String> baseHeaders
    ) throws IOException {
        String correlationId = UUID.randomUUID().toString();
        AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
            null,
            adminId,
            AdminCanonicalEncoding.sha256Hex(body),
            correlationId,
            deviceId,
            deviceKeyMarker,
            deviceKeyVersion,
            "POST",
            path,
            purpose,
            AdminCanonicalEncoding.sha256Hex(new byte[0]),
            null,
            null
        );
        Map<String, String> challengeHeaders = new LinkedHashMap<>(baseHeaders);
        challengeHeaders.put(AdminOperationsHttpClient.CORRELATION_ID_HEADER, correlationId);
        Response challenge = executeRaw(
            "POST",
            AdminOperationsHttpClient.CHALLENGE_PATH,
            Map.copyOf(challengeHeaders),
            intent.challengeRequestBytes()
        );
        requireExactStatus(challenge.statusCode, 200, "administrator device challenge");
        final AdminDeviceProof.SignedChallenge proof;
        try {
            proof = AdminDeviceProof.parseAndSign(
                challenge.body,
                intent,
                clock.nowEpochMs(),
                signer
            );
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("administrator device proof could not be signed", error);
        }
        Map<String, String> protectedHeaders = new LinkedHashMap<>(challengeHeaders);
        protectedHeaders.putAll(proof.proofHeaders());
        Response operation = executeRaw("POST", path, Map.copyOf(protectedHeaders), body);
        requireExactStatus(operation.statusCode, 200, "administrator proof-bound request");
        return parseJsonResponse(operation.body);
    }

    private JSONObject execute(String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        return parseJsonResponse(executeRaw(method, path, headers, body).body);
    }

    private Response executeRaw(String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Accept", "application/json");
        if (body != null) requestHeaders.put("Content-Type", "application/json; charset=utf-8");
        requestHeaders.putAll(headers);
        Response response = transport.execute(method, origin + path, Map.copyOf(requestHeaders), body);
        if (response.statusCode < 200 || response.statusCode > 299) {
            AdminSecurityApiException typedError = parseTypedError(response);
            if (typedError != null) throw typedError;
            throw new IOException("administrator API request failed: status=" + response.statusCode);
        }
        return response;
    }

    private static JSONObject parseJsonResponse(String body) throws IOException {
        try {
            AdminStrictJson.parseObject(body);
            return new JSONObject(body);
        } catch (JSONException error) {
            throw new IOException("administrator API returned invalid JSON", error);
        }
    }

    private static void requireExactStatus(int actual, int expected, String operation) throws IOException {
        if (actual != expected) throw new IOException(operation + " returned unexpected status=" + actual);
    }

    private Map<String, String> protectedHeaders(String token) throws IOException {
        if (boundDeviceId == null) throw new IOException("administrator device binding is unavailable");
        Map<String, String> headers = new LinkedHashMap<>(publicHeaders(boundDeviceId));
        headers.put("Authorization", "Bearer " + requireToken(token));
        return Map.copyOf(headers);
    }

    private static Map<String, String> publicHeaders(String deviceId) throws IOException {
        return Map.of(
            "X-WalkSafe-App-Kind", APP_KIND,
            "X-WalkSafe-Role", ROLE,
            "X-WalkSafe-Audience", AUDIENCE,
            "X-WalkSafe-Device-Id", requireIdentifier(deviceId, "invalid_device_id")
        );
    }

    private static LoginResult parseLoginResult(JSONObject response) throws IOException {
        requireExactKeys(response, Set.of("access_token", "security_state", "current_session_id"));
        return new LoginResult(
            requiredToken(response, "access_token"),
            requiredState(response),
            requiredCanonicalUuid(response, "current_session_id")
        );
    }

    private static StateSnapshot parseState(JSONObject response) throws IOException {
        requireExactKeys(response, Set.of("security_state", "state_version", "observed_at"));
        return new StateSnapshot(
            requiredState(response),
            requiredText(response, "state_version", 128),
            requiredText(response, "observed_at", 128)
        );
    }

    private static AdminSecurityState requiredState(JSONObject response) throws IOException {
        Object raw = response.opt("security_state");
        if (!(raw instanceof String)) throw new IOException("invalid administrator security state type");
        AdminSecurityState state = AdminSecurityState.fromWireValue((String) raw);
        if (state == AdminSecurityState.FAIL_CLOSED) throw new IOException("unknown administrator security state");
        return state;
    }

    private static JSONArray requiredArray(JSONObject response, String key) throws IOException {
        JSONArray value = response.optJSONArray(key);
        if (value == null) throw new IOException("missing response field: " + key);
        return value;
    }

    private static String requiredIdentifier(JSONObject response, String key) throws IOException {
        return requireIdentifier(requiredText(response, key, 128), "invalid response field: " + key);
    }

    private static String requiredCanonicalUuid(JSONObject response, String key) throws IOException {
        return requireCanonicalUuid(requiredText(response, key, 36), "invalid response field: " + key);
    }

    private static String requireCanonicalUuid(String value, String reason) throws IOException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IOException(reason);
            return value;
        } catch (NullPointerException | IllegalArgumentException error) {
            throw new IOException(reason, error);
        }
    }

    private static String requiredText(JSONObject response, String key, int maxLength) throws IOException {
        Object raw = response.opt(key);
        if (!(raw instanceof String)) throw new IOException("invalid response field type: " + key);
        String value = (String) raw;
        if (value.isBlank() || value.length() > maxLength || containsControl(value)) {
            throw new IOException("invalid response field: " + key);
        }
        return value;
    }

    private static String requiredToken(JSONObject response, String key) throws IOException {
        Object raw = response.opt(key);
        if (!(raw instanceof String)) throw new IOException("invalid response field type: " + key);
        return requireToken((String) raw);
    }

    private static String requireToken(String value) throws IOException {
        String token = requireSecret(value, "invalid opaque token", 32, 512);
        if (token.chars().anyMatch(Character::isWhitespace)) throw new IOException("invalid opaque token");
        return token;
    }

    private static String requireAdminId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IOException("invalid_admin_id");
        }
        String normalized = value;
        if (normalized.toLowerCase(Locale.ROOT).endsWith("-shared")) {
            throw new IOException("shared administrator id is forbidden");
        }
        return normalized;
    }

    private static String requireIdentifier(String value, String reason) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) throw new IOException(reason);
        return value;
    }

    private static String requireLabel(String value) throws IOException {
        if (value == null || value.isBlank() || value.length() > 80 || containsControl(value)) {
            throw new IOException("invalid_device_label");
        }
        return value.trim();
    }

    private static String requireTotp(String value) throws IOException {
        if (value == null || !value.matches("[0-9]{6}")) throw new IOException("invalid_totp_code");
        return value;
    }

    private static String requireAction(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IOException("invalid_reconfirmation_action");
        }
        return value;
    }

    private static String requireMethod(String value) throws IOException {
        if (value == null || !value.matches("[A-Z]{3,16}")) {
            throw new IOException("invalid_reconfirmation_method");
        }
        return value;
    }

    private static String requirePath(String value) throws IOException {
        if (value == null
            || value.length() > 256
            || !value.startsWith("/")
            || value.contains("?")
            || value.contains("#")
            || value.chars().anyMatch(character -> character <= 0x20 || character == 0x7f)) {
            throw new IOException("invalid_reconfirmation_path");
        }
        return value;
    }

    private static String requireNonce(String value) throws IOException {
        if (!AdminHighRiskActionGate.isCanonicalNonce(value)) {
            throw new IOException("invalid_reconfirmation_nonce");
        }
        return value;
    }

    private static String requireSecret(String value, String reason, int minLength, int maxLength) throws IOException {
        if (value == null || value.length() < minLength || value.length() > maxLength || containsControl(value)) {
            throw new IOException(reason);
        }
        return value;
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static AdminSecurityApiException parseTypedError(Response response) {
        try {
            JSONObject root = new JSONObject(response.body);
            requireExactKeys(root, Set.of("detail"));
            JSONObject detail = root.optJSONObject("detail");
            if (detail == null) return null;
            requireExactKeys(detail, Set.of("code", "message"));
            String wireCode = requiredText(detail, "code", 128);
            requiredText(detail, "message", 1024);
            AdminSecurityApiException.Code code = AdminSecurityApiException.Code.fromWireValue(wireCode);
            if (code == null) return null;
            return new AdminSecurityApiException(code, retryAfterSeconds(response.headers));
        } catch (IOException | JSONException | RuntimeException ignored) {
            return null;
        }
    }

    private static Long retryAfterSeconds(Map<String, String> headers) {
        String value = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("Retry-After")) {
                value = entry.getValue();
                break;
            }
        }
        if (value == null || !value.matches("[0-9]{1,5}")) return null;
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 1L && seconds <= 3_600L ? seconds : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void requireExactKeys(JSONObject value, Set<String> expected) throws IOException {
        Set<String> actual = new HashSet<>();
        value.keys().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IOException("administrator API response fields do not match the contract");
    }

    private static JSONObject jsonObject(Object... keyValues) throws IOException {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("JSON key/value pairs are incomplete");
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index < keyValues.length; index += 2) {
                result.put((String) keyValues[index], keyValues[index + 1]);
            }
            return result;
        } catch (JSONException error) {
            throw new IOException("administrator request could not be encoded", error);
        }
    }

    private static byte[] jsonBytes(JSONObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class UrlConnectionTransport implements Transport {
        @Override
        public Response execute(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(false);
            connection.setDoInput(true);
            connection.setDoOutput(body != null);
            headers.forEach(connection::setRequestProperty);
            try {
                if (body != null) {
                    try (var output = connection.getOutputStream()) {
                        output.write(body);
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status <= 299
                    ? connection.getInputStream()
                    : connection.getErrorStream();
                String retryAfter = connection.getHeaderField("Retry-After");
                Map<String, String> responseHeaders = retryAfter == null
                    ? Map.of()
                    : Map.of("Retry-After", retryAfter);
                return new Response(status, stream == null ? "" : readBounded(stream), responseHeaders);
            } finally {
                connection.disconnect();
            }
        }

        private static String readBounded(InputStream input) throws IOException {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    if (output.size() + read > 64 * 1024) throw new IOException("administrator API response is too large");
                    output.write(buffer, 0, read);
                }
                return AdminStrictJson.decodeUtf8(output.toByteArray());
            }
        }
    }
}
