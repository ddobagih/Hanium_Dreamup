package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public final class AdminDeviceProofTest {
    @Test
    public void exact19BecomesCanonicalExact18AndSignsUtf8AsDerBase64Url() throws Exception {
        AdminDeviceProof.Intent intent = readIntent();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();

        AdminDeviceProof.SignedChallenge proof = AdminDeviceProof.parseAndSign(
            challengeResponse(intent, ISSUED_AT, null),
            intent,
            ISSUED_AT + 1,
            payload -> {
                Signature signature = Signature.getInstance("SHA256withECDSA");
                signature.initSign(keyPair.getPrivate());
                signature.update(payload);
                return signature.sign();
            }
        );

        assertEquals(CHALLENGE_ID, proof.challengeId());
        assertTrue(proof.signingPayload().startsWith("{\"action\":null,\"admin_id\":\"admin-001\""));
        assertTrue(proof.signingPayload().contains("\"read_purpose\":\"report.review_decisions\""));
        assertFalse(proof.encodedSignature().contains("="));
        byte[] der = Base64.getUrlDecoder().decode(proof.encodedSignature());
        assertTrue(AdminDeviceProof.isCanonicalDerEcdsa(der));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(proof.signingPayload().getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(der));
        assertEquals(CHALLENGE_ID, proof.proofHeaders().get(AdminDeviceProof.CHALLENGE_ID_HEADER));
    }

    @Test
    public void challengeRequestHasPhysicalNullsAndExactlyThirteenFields() throws Exception {
        JSONObject request = new JSONObject(new String(readIntent().challengeRequestBytes(), StandardCharsets.UTF_8));

        assertEquals(13, request.length());
        assertTrue(request.has("action"));
        assertTrue(request.isNull("action"));
        assertTrue(request.has("read_purpose"));
        assertFalse(request.isNull("read_purpose"));
        assertEquals("ACTION", request.getString("purpose"));
    }

    @Test
    public void missingUnknownMismatchedOrNoncanonicalResponsesFailClosed() throws Exception {
        AdminDeviceProof.Intent intent = readIntent();
        String valid = challengeResponse(intent, ISSUED_AT, null);

        String withUnknown = valid.substring(0, valid.length() - 1) + ",\"unexpected\":true}";
        assertThrows(IOException.class, () -> parse(withUnknown, intent, ISSUED_AT + 1));
        String duplicateAction = "{\"action\":null," + valid.substring(1);
        assertThrows(IOException.class, () -> parse(duplicateAction, intent, ISSUED_AT + 1));
        assertThrows(IOException.class, () -> parse(valid + " trailing", intent, ISSUED_AT + 1));

        Map<String, Object> missingNull = responseFields(intent, ISSUED_AT);
        missingNull.remove("action");
        assertThrows(IOException.class, () -> parse(AdminCanonicalEncoding.canonicalJson(missingNull), intent, ISSUED_AT + 1));

        assertThrows(IOException.class, () -> parse(
            challengeResponse(intent, ISSUED_AT, Map.of("admin_id", "admin-002")),
            intent,
            ISSUED_AT + 1
        ));
        assertThrows(IOException.class, () -> parse(
            challengeResponse(intent, ISSUED_AT, Map.of("signing_payload", "{}")),
            intent,
            ISSUED_AT + 1
        ));
    }

    @Test
    public void exactTtlAndFreshnessWindowAreRequired() throws Exception {
        AdminDeviceProof.Intent intent = readIntent();
        assertThrows(IOException.class, () -> parse(
            challengeResponse(intent, ISSUED_AT, Map.of("expires_at_epoch_ms", ISSUED_AT + 119_999L)),
            intent,
            ISSUED_AT + 1
        ));
        assertThrows(IOException.class, () -> parse(challengeResponse(intent, ISSUED_AT, null), intent, ISSUED_AT - 1));
        assertThrows(IOException.class, () -> parse(
            challengeResponse(intent, ISSUED_AT, null),
            intent,
            ISSUED_AT + AdminDeviceProof.CHALLENGE_TTL_MS
        ));
    }

    @Test
    public void purposeShapesRequirePhysicalNullableContract() {
        assertThrows(IllegalArgumentException.class, () -> new AdminDeviceProof.Intent(
            "report.review.decide", "admin-001", EMPTY_SHA, CORRELATION_ID, DEVICE_ID, MARKER, 1,
            "POST", "/reports/" + REPORT_ID + "/review-decisions", AdminDeviceProof.Purpose.LOGIN,
            EMPTY_SHA, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminDeviceProof.Intent(
            null, "admin-001", EMPTY_SHA, CORRELATION_ID, DEVICE_ID, MARKER, 1,
            "GET", "/reports/" + REPORT_ID + "/review-decisions", AdminDeviceProof.Purpose.ACTION,
            EMPTY_SHA, null, SESSION_ID
        ));
    }

    private static AdminDeviceProof.SignedChallenge parse(
        String response,
        AdminDeviceProof.Intent intent,
        long now
    ) throws Exception {
        return AdminDeviceProof.parseAndSign(
            response,
            intent,
            now,
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01}
        );
    }

    static String challengeResponse(AdminDeviceProof.Intent intent, long issuedAt, Map<String, Object> overrides) {
        Map<String, Object> fields = responseFields(intent, issuedAt);
        if (overrides != null) fields.putAll(overrides);
        return AdminCanonicalEncoding.canonicalJson(fields);
    }

    private static Map<String, Object> responseFields(AdminDeviceProof.Intent intent, long issuedAt) {
        Map<String, Object> exact18 = new LinkedHashMap<>();
        exact18.put("action", intent.action());
        exact18.put("admin_id", intent.adminId());
        exact18.put("body_sha256", intent.bodySha256());
        exact18.put("challenge_id", CHALLENGE_ID);
        exact18.put("correlation_id", intent.correlationId());
        exact18.put("device_id", intent.deviceId());
        exact18.put("device_key_marker", intent.deviceKeyMarker());
        exact18.put("device_key_version", intent.deviceKeyVersion());
        exact18.put("expires_at_epoch_ms", issuedAt + AdminDeviceProof.CHALLENGE_TTL_MS);
        exact18.put("issued_at_epoch_ms", issuedAt);
        exact18.put("method", intent.method());
        exact18.put("nonce", NONCE);
        exact18.put("path", intent.path());
        exact18.put("purpose", intent.purpose().name());
        exact18.put("query_sha256", intent.querySha256());
        exact18.put("read_purpose", intent.readPurpose());
        exact18.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
        exact18.put("session_id", intent.sessionId());
        Map<String, Object> exact19 = new LinkedHashMap<>(exact18);
        exact19.put("signing_payload", AdminCanonicalEncoding.canonicalJson(exact18));
        return exact19;
    }

    private static AdminDeviceProof.Intent readIntent() {
        return new AdminDeviceProof.Intent(
            null,
            "admin-001",
            EMPTY_SHA,
            CORRELATION_ID,
            DEVICE_ID,
            MARKER,
            1,
            "GET",
            "/reports/" + REPORT_ID + "/review-decisions",
            AdminDeviceProof.Purpose.ACTION,
            EMPTY_SHA,
            "report.review_decisions",
            SESSION_ID
        );
    }

    static final long ISSUED_AT = 1_700_000_000_000L;
    static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    static final String CORRELATION_ID = "22222222-2222-4222-8222-222222222222";
    static final String CHALLENGE_ID = "11111111-1111-4111-8111-111111111111";
    static final String REPORT_ID = "44444444-4444-4444-8444-444444444444";
    static final String SESSION_ID = "33333333-3333-4333-8333-333333333333";
    static final String DEVICE_ID = "admin-device-12345678-1234-1234-1234-123456789abc";
    static final String MARKER = "a".repeat(64);
    static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
}
