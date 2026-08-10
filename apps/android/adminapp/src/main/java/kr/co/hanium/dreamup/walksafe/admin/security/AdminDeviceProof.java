package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict parser and signer for walksafe.admin-device-proof.v2 challenges. */
public final class AdminDeviceProof {
    public static final String SCHEMA_VERSION = "walksafe.admin-device-proof.v2";
    public static final long CHALLENGE_TTL_MS = 120_000L;
    public static final String CHALLENGE_ID_HEADER = "X-WalkSafe-Device-Challenge-Id";
    public static final String SIGNATURE_HEADER = "X-WalkSafe-Device-Signature";

    public enum Purpose {
        LOGIN,
        ACTION,
        RECOVERY_COMPLETE
    }

    @FunctionalInterface
    public interface Signer {
        byte[] sign(byte[] payload) throws GeneralSecurityException;
    }

    public static final class Intent {
        private static final Set<String> EXACT_KEYS = Set.of(
            "action",
            "admin_id",
            "body_sha256",
            "correlation_id",
            "device_id",
            "device_key_marker",
            "device_key_version",
            "method",
            "path",
            "purpose",
            "query_sha256",
            "read_purpose",
            "session_id"
        );

        private final String action;
        private final String adminId;
        private final String bodySha256;
        private final String correlationId;
        private final String deviceId;
        private final String deviceKeyMarker;
        private final int deviceKeyVersion;
        private final String method;
        private final String path;
        private final Purpose purpose;
        private final String querySha256;
        private final String readPurpose;
        private final String sessionId;

        public Intent(
            String action,
            String adminId,
            String bodySha256,
            String correlationId,
            String deviceId,
            String deviceKeyMarker,
            int deviceKeyVersion,
            String method,
            String path,
            Purpose purpose,
            String querySha256,
            String readPurpose,
            String sessionId
        ) {
            this.action = nullableOperationAction(action);
            this.adminId = identifier(adminId, "admin_id", "[A-Za-z0-9][A-Za-z0-9._@-]{0,63}");
            this.bodySha256 = lowerSha256(bodySha256, "body_sha256");
            this.correlationId = canonicalUuid(correlationId, "correlation_id");
            this.deviceId = identifier(deviceId, "device_id", "[A-Za-z0-9][A-Za-z0-9._:-]{7,127}");
            this.deviceKeyMarker = lowerSha256(deviceKeyMarker, "device_key_marker");
            if (deviceKeyVersion < 1) throw new IllegalArgumentException("device_key_version must be positive");
            this.deviceKeyVersion = deviceKeyVersion;
            this.method = AdminDeviceProof.method(method);
            this.path = AdminDeviceProof.path(path);
            if (purpose == null) throw new IllegalArgumentException("purpose is required");
            this.purpose = purpose;
            this.querySha256 = lowerSha256(querySha256, "query_sha256");
            this.readPurpose = nullableReadPurpose(readPurpose);
            this.sessionId = sessionId == null ? null : canonicalUuid(sessionId, "session_id");
            requirePurposeShape();
        }

        public String action() { return action; }
        public String adminId() { return adminId; }
        public String bodySha256() { return bodySha256; }
        public String correlationId() { return correlationId; }
        public String deviceId() { return deviceId; }
        public String deviceKeyMarker() { return deviceKeyMarker; }
        public int deviceKeyVersion() { return deviceKeyVersion; }
        public String method() { return method; }
        public String path() { return path; }
        public Purpose purpose() { return purpose; }
        public String querySha256() { return querySha256; }
        public String readPurpose() { return readPurpose; }
        public String sessionId() { return sessionId; }

        public byte[] challengeRequestBytes() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("action", action);
            fields.put("admin_id", adminId);
            fields.put("body_sha256", bodySha256);
            fields.put("correlation_id", correlationId);
            fields.put("device_id", deviceId);
            fields.put("device_key_marker", deviceKeyMarker);
            fields.put("device_key_version", deviceKeyVersion);
            fields.put("method", method);
            fields.put("path", path);
            fields.put("purpose", purpose.name());
            fields.put("query_sha256", querySha256);
            fields.put("read_purpose", readPurpose);
            fields.put("session_id", sessionId);
            if (!fields.keySet().equals(EXACT_KEYS)) throw new IllegalStateException("invalid exact13 request shape");
            return AdminCanonicalEncoding.canonicalJsonBytes(fields);
        }

        private void requirePurposeShape() {
            if (purpose == Purpose.LOGIN || purpose == Purpose.RECOVERY_COMPLETE) {
                if (action != null || readPurpose != null || sessionId != null || !"POST".equals(method)) {
                    throw new IllegalArgumentException("login and recovery proof fields do not match the contract");
                }
                return;
            }
            if (sessionId == null) throw new IllegalArgumentException("ACTION proof requires session_id");
            if ("GET".equals(method)) {
                if (action != null
                    || !("report.review_decisions".equals(readPurpose)
                    || "report.delivery_events".equals(readPurpose))) {
                    throw new IllegalArgumentException("protected read proof fields do not match the contract");
                }
                return;
            }
            if (!"POST".equals(method)
                || readPurpose != null
                || !("report.review.decide".equals(action) || "report.delivery.create".equals(action))) {
                throw new IllegalArgumentException("administrator action proof fields do not match the contract");
            }
        }
    }

    public static final class SignedChallenge {
        private final String challengeId;
        private final String signingPayload;
        private final String encodedSignature;

        private SignedChallenge(String challengeId, String signingPayload, String encodedSignature) {
            this.challengeId = challengeId;
            this.signingPayload = signingPayload;
            this.encodedSignature = encodedSignature;
        }

        public String challengeId() { return challengeId; }
        public String signingPayload() { return signingPayload; }
        public String encodedSignature() { return encodedSignature; }

        public Map<String, String> proofHeaders() {
            return Map.of(
                CHALLENGE_ID_HEADER, challengeId,
                SIGNATURE_HEADER, encodedSignature
            );
        }
    }

    private static final Set<String> EXACT_RESPONSE_KEYS = Set.of(
        "action",
        "admin_id",
        "body_sha256",
        "challenge_id",
        "correlation_id",
        "device_id",
        "device_key_marker",
        "device_key_version",
        "expires_at_epoch_ms",
        "issued_at_epoch_ms",
        "method",
        "nonce",
        "path",
        "purpose",
        "query_sha256",
        "read_purpose",
        "schema_version",
        "session_id",
        "signing_payload"
    );

    private AdminDeviceProof() {}

    public static SignedChallenge parseAndSign(
        String responseBody,
        Intent expected,
        long nowEpochMs,
        Signer signer
    ) throws IOException, GeneralSecurityException {
        if (expected == null || signer == null) throw new IllegalArgumentException("proof context is required");
        Map<String, Object> response = AdminStrictJson.parseObject(responseBody);
        requireExactKeys(response, EXACT_RESPONSE_KEYS);

        String action = nullableText(response, "action", 128);
        String adminId = requiredText(response, "admin_id", 64);
        String bodySha256 = requiredText(response, "body_sha256", 64);
        String challengeId = canonicalUuid(requiredText(response, "challenge_id", 36), "challenge_id");
        String correlationId = canonicalUuid(requiredText(response, "correlation_id", 36), "correlation_id");
        String deviceId = requiredText(response, "device_id", 128);
        String deviceKeyMarker = requiredText(response, "device_key_marker", 64);
        int deviceKeyVersion = requiredPositiveInt(response, "device_key_version");
        long expiresAt = requiredLong(response, "expires_at_epoch_ms");
        long issuedAt = requiredLong(response, "issued_at_epoch_ms");
        String method = requiredText(response, "method", 16);
        String nonce = canonicalNonce(requiredText(response, "nonce", 43));
        String path = requiredText(response, "path", 512);
        String purpose = requiredText(response, "purpose", 32);
        String querySha256 = requiredText(response, "query_sha256", 64);
        String readPurpose = nullableText(response, "read_purpose", 64);
        String schemaVersion = requiredText(response, "schema_version", 64);
        String sessionId = nullableText(response, "session_id", 128);
        String suppliedSigningPayload = requiredText(response, "signing_payload", 8_192);

        if (!same(action, expected.action)
            || !adminId.equals(expected.adminId)
            || !bodySha256.equals(expected.bodySha256)
            || !correlationId.equals(expected.correlationId)
            || !deviceId.equals(expected.deviceId)
            || !deviceKeyMarker.equals(expected.deviceKeyMarker)
            || deviceKeyVersion != expected.deviceKeyVersion
            || !method.equals(expected.method)
            || !path.equals(expected.path)
            || !purpose.equals(expected.purpose.name())
            || !querySha256.equals(expected.querySha256)
            || !same(readPurpose, expected.readPurpose)
            || !same(sessionId, expected.sessionId)) {
            throw new IOException("device challenge does not match the request binding");
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IOException("unsupported device proof schema");
        if (issuedAt < 0L
            || issuedAt > Long.MAX_VALUE - CHALLENGE_TTL_MS
            || expiresAt != issuedAt + CHALLENGE_TTL_MS
            || issuedAt > nowEpochMs
            || nowEpochMs >= expiresAt) {
            throw new IOException("device challenge is outside the freshness window");
        }

        Map<String, Object> exact18 = new LinkedHashMap<>();
        exact18.put("action", action);
        exact18.put("admin_id", adminId);
        exact18.put("body_sha256", lowerSha256(bodySha256, "body_sha256"));
        exact18.put("challenge_id", challengeId);
        exact18.put("correlation_id", correlationId);
        exact18.put("device_id", identifier(deviceId, "device_id", "[A-Za-z0-9][A-Za-z0-9._:-]{7,127}"));
        exact18.put("device_key_marker", lowerSha256(deviceKeyMarker, "device_key_marker"));
        exact18.put("device_key_version", deviceKeyVersion);
        exact18.put("expires_at_epoch_ms", expiresAt);
        exact18.put("issued_at_epoch_ms", issuedAt);
        exact18.put("method", method(method));
        exact18.put("nonce", nonce);
        exact18.put("path", path(path));
        exact18.put("purpose", purpose);
        exact18.put("query_sha256", lowerSha256(querySha256, "query_sha256"));
        exact18.put("read_purpose", nullableReadPurpose(readPurpose));
        exact18.put("schema_version", schemaVersion);
        exact18.put("session_id", sessionId == null ? null : canonicalUuid(sessionId, "session_id"));
        String canonical = AdminCanonicalEncoding.canonicalJson(exact18);
        if (!canonical.equals(suppliedSigningPayload)) {
            throw new IOException("device challenge signing_payload is not canonical exact18 JSON");
        }

        byte[] signature = signer.sign(canonical.getBytes(StandardCharsets.UTF_8));
        if (!isCanonicalDerEcdsa(signature)) {
            throw new GeneralSecurityException("device signature is not canonical ASN.1 DER ECDSA");
        }
        return new SignedChallenge(
            challengeId,
            canonical,
            Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        );
    }

    static boolean isCanonicalDerEcdsa(byte[] value) {
        if (value == null || value.length < 8 || value.length > 72 || value[0] != 0x30) return false;
        int sequenceLength = value[1] & 0xff;
        if ((sequenceLength & 0x80) != 0 || sequenceLength != value.length - 2) return false;
        int offset = 2;
        offset = consumePositiveDerInteger(value, offset);
        if (offset < 0) return false;
        offset = consumePositiveDerInteger(value, offset);
        return offset == value.length;
    }

    private static int consumePositiveDerInteger(byte[] value, int offset) {
        if (offset + 2 > value.length || value[offset] != 0x02) return -1;
        int length = value[offset + 1] & 0xff;
        int start = offset + 2;
        int end = start + length;
        if (length < 1 || length > 33 || end > value.length) return -1;
        if ((value[start] & 0x80) != 0) return -1;
        if (length > 1 && value[start] == 0 && (value[start + 1] & 0x80) == 0) return -1;
        boolean nonZero = false;
        for (int index = start; index < end; index++) nonZero |= value[index] != 0;
        return nonZero ? end : -1;
    }

    private static int requiredPositiveInt(Map<String, Object> value, String key) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) throw new IOException("invalid integer field: " + key);
        long parsed = ((Number) raw).longValue();
        if (parsed < 1L || parsed > Integer.MAX_VALUE) throw new IOException("invalid integer field: " + key);
        return (int) parsed;
    }

    private static long requiredLong(Map<String, Object> value, String key) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) throw new IOException("invalid integer field: " + key);
        return ((Number) raw).longValue();
    }

    private static String requiredText(Map<String, Object> value, String key, int maxLength) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.isEmpty() || text.length() > maxLength || containsControl(text)) {
            throw new IOException("invalid response field: " + key);
        }
        return text;
    }

    private static String nullableText(Map<String, Object> value, String key, int maxLength) throws IOException {
        Object raw = value.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String text) || text.isEmpty() || text.length() > maxLength || containsControl(text)) {
            throw new IOException("invalid nullable response field: " + key);
        }
        return text;
    }

    private static String canonicalNonce(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_-]{43}")) throw new IOException("invalid challenge nonce");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 32
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
                throw new IOException("invalid challenge nonce");
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid challenge nonce", error);
        }
    }

    private static String method(String value) {
        if (value == null || !(value.equals("GET") || value.equals("POST"))) {
            throw new IllegalArgumentException("method is outside the administrator proof contract");
        }
        return value;
    }

    private static String path(String value) {
        if (value == null || value.length() > 512 || !value.startsWith("/")
            || value.contains("?") || value.contains("#") || containsControlOrSpace(value)) {
            throw new IllegalArgumentException("path is invalid");
        }
        return value;
    }

    private static String nullableOperationAction(String value) {
        if (value == null) return null;
        return identifier(value, "action", "[a-z][a-z0-9._:-]{2,127}");
    }

    private static String nullableReadPurpose(String value) {
        if (value == null) return null;
        return identifier(value, "read_purpose", "[a-z][a-z0-9._:-]{2,63}");
    }

    private static String identifier(String value, String label, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException(label + " is invalid");
        return value;
    }

    private static String lowerSha256(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256 hex");
        }
        return value;
    }

    private static String canonicalUuid(String value, String label) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException(label + " is not canonical UUID");
            return value;
        } catch (NullPointerException | IllegalArgumentException error) {
            throw new IllegalArgumentException(label + " is not canonical UUID", error);
        }
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static boolean containsControlOrSpace(String value) {
        return value.chars().anyMatch(character -> character <= 0x20 || character == 0x7f);
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> expected) throws IOException {
        if (!value.keySet().equals(expected)) throw new IOException("device challenge response fields do not match exact19");
        for (String key : expected) {
            if (!value.containsKey(key)) {
                throw new IOException("device challenge response field is not physically present: " + key);
            }
        }
    }
}
