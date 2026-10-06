package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import static kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceFixtures.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real Android transport with an explicitly synthetic signing key and simulated server authority. */
@RunWith(AndroidJUnit4.class)
public final class AdminAuthenticatedTransportDeviceTest {
    private static final String TOKEN = "synthetic-admin-device-test-token-0000000000000000";
    private static final String SESSION_ID = "66666666-6666-4666-8666-666666666666";
    private static final String DEVICE_ID = "device-instrumentation-only";
    private static final String NONCE = "AAECAwQFBgcICQoLDA0ODw";
    private static final String PASSWORD = "Synthetic-device-test-only!";
    private static final AdminOperationsApi.SessionContext SESSION =
        new AdminOperationsApi.SessionContext(TOKEN, "admin-001", SESSION_ID, DEVICE_ID);
    private static java.security.PrivateKey privateKey;
    private static AdminDeviceKeyStore.Descriptor descriptor;
    private static PublicKey publicKey;
    private MockWebServer server;
    private final Map<String, Map<String, Object>> challenges = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private AdminHighRiskActionGate.Operation expected;
    private byte[] expectedBody;
    private String response;
    private int businessCount;
    private int signatureCount;
    private String approvedDigest;

    @BeforeClass public static void createSyntheticSigningKey() throws Exception {
        // This tests the wire contract, not AndroidKeyStore or device-authentication assurance.
        var generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair(); privateKey = pair.getPrivate(); publicKey = pair.getPublic();
        byte[] spki = publicKey.getEncoded();
        descriptor = new AdminDeviceKeyStore.Descriptor(AdminCanonicalEncoding.sha256Hex(spki), 1,
            Base64.getUrlEncoder().withoutPadding().encodeToString(spki));
    }
    private static byte[] sign(byte[] payload) throws java.security.GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey); signer.update(payload); return signer.sign();
    }
    @Before public void startFixtureServer() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try { return serve(request); }
                catch (Throwable failure) { serverFailure.set(failure); return json(500, "{}"); }
            }
        });
        server.start();
    }
    @After public void closeFixtureServer() throws Exception {
        server.close();
        if (serverFailure.get() != null) throw new AssertionError("fixture request validation failed", serverFailure.get());
    }
    private String origin() { return server.url("/").toString(); }
    private AdminReportHttpClient reports() {
        return new AdminReportHttpClient(origin(), true, descriptor, AdminAuthenticatedTransportDeviceTest::sign);
    }
    private AdminRawCollectionHttpClient raw() {
        return new AdminRawCollectionHttpClient(origin(), true, descriptor, AdminAuthenticatedTransportDeviceTest::sign);
    }
    @Test public void reportStatusBodyAndSignatureReachRealAndroidPatch() throws Exception {
        var headers = approve(AdminHighRiskActionGate.reportStatus(REPORT_ID),
            AdminReportHttpClient.statusBody("resolved", 2), reportStatus());
        reports().updateStatus(SESSION, REPORT_ID, "resolved", 2, headers);
        assertExchange();
    }
    @Test public void reportRequestBodyAndSignatureReachRealAndroidPatch() throws Exception {
        var headers = approve(AdminHighRiskActionGate.reportRequestStatus(REQUEST_ID),
            AdminReportHttpClient.requestStatusBody("DELETE", "REJECTED", 2,
                "요청 처리를 완료했습니다.", "내부 확인 완료"), requestStatus());
        reports().updateRequestStatus(SESSION, REQUEST_ID, "DELETE", "REJECTED", 2,
            "요청 처리를 완료했습니다.", "내부 확인 완료", headers);
        assertExchange();
    }
    @Test public void incidentBodyAndSignatureReachRealAndroidPatch() throws Exception {
        var request = new AdminIncidentModels.StatusRequest("ACKNOWLEDGED", 1, IDEMPOTENCY,
            "실제 사고를 확인한 사유입니다.", "민감정보 없이 서비스 중단을 관찰했습니다.", "b".repeat(64));
        var headers = approve(AdminHighRiskActionGate.incidentStatus(INCIDENT_ID),
            AdminIncidentHttpClient.statusBody(request), incidentStatus());
        new AdminIncidentHttpClient(origin(), true, descriptor, AdminAuthenticatedTransportDeviceTest::sign)
            .updateStatus(SESSION, INCIDENT_ID, request, headers);
        assertExchange();
    }
    @Test public void quarantineGetBindsEmptyBodyAndActualQuery() throws Exception {
        var headers = approve(AdminHighRiskActionGate.rawCollectionList(), new byte[0], listJson());
        assertEquals(1, raw().listQuarantine(SESSION, headers).items().size());
        assertExchange();
    }
    @Test public void purposeDecisionBindsExactKoreanUtf8Body() throws Exception {
        var request = reportDecision();
        var headers = approve(AdminHighRiskActionGate.rawCollectionPurposeDecision(COLLECTION),
            AdminRawCollectionHttpClient.purposeBody(request), decisionReceiptJson());
        assertEquals(3, raw().decidePurpose(SESSION, summary(), request, headers).revision());
        assertExchange();
    }
    @Test public void legalHoldBindsExactKoreanUtf8Body() throws Exception {
        var request = legalHold();
        var headers = approve(AdminHighRiskActionGate.rawCollectionLegalHold(COLLECTION),
            AdminRawCollectionHttpClient.legalHoldBody(request), legalHoldReceiptJson());
        assertEquals(5, raw().recordLegalHold(SESSION, COLLECTION, request, headers).revision());
        assertExchange();
    }
    private Map<String, String> approve(AdminHighRiskActionGate.Operation operation, byte[] body,
        String successResponse) throws Exception {
        expected = AdminHighRiskActionGate.bindBody(operation, body);
        expectedBody = body.clone(); response = successResponse;
        var security = new AdminSecurityHttpClient(origin(), true, descriptor, AdminAuthenticatedTransportDeviceTest::sign);
        security.login("admin-001", PASSWORD.toCharArray(), "123456".toCharArray(), DEVICE_ID, "synthetic test");
        security.reauthenticate(TOKEN, PASSWORD.toCharArray(), "123456".toCharArray(),
            expected.reauthenticationAction(), expected.method(), expected.path(), NONCE, expected.intentSha256());
        long now = System.currentTimeMillis();
        var binding = new AdminHighRiskActionGate.Binding(expected, NONCE, now + 30000);
        assertFalse(AdminHighRiskActionGate.evaluate(AdminHighRiskActionGate.bindBody(operation,
            "changed".getBytes(StandardCharsets.UTF_8)), AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED, binding, now, true).isAllowed());
        assertFalse(AdminHighRiskActionGate.evaluate(operation, AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED, binding, now, true).isAllowed());
        var allowed = AdminHighRiskActionGate.evaluate(expected, AdminSecurityState.NORMAL,
            AdminRecoveryCustodyState.ATTESTED, binding, now, true);
        assertTrue(allowed.reason(), allowed.isAllowed());
        return allowed.requestHeaders();
    }
    private void assertExchange() {
        assertEquals(1, businessCount);
        assertEquals(2, signatureCount); // login and business operation
        assertEquals(5, server.getRequestCount()); // challenge/login, reauth, challenge/operation
        assertTrue(challenges.isEmpty());
    }
    private MockResponse serve(RecordedRequest request) throws Exception {
        String target = request.getTarget();
        byte[] body = request.getBody() == null ? new byte[0] : request.getBody().toByteArray();
        if (target.equals(AdminReportHttpClient.CHALLENGE_PATH)) {
            Map<String, Object> fields = new LinkedHashMap<>(AdminStrictJson.parseObject(new String(body, StandardCharsets.UTF_8)));
            String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
            fields.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
            fields.put("challenge_id", id); fields.put("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            fields.put("issued_at_epoch_ms", now); fields.put("expires_at_epoch_ms", now + AdminDeviceProof.CHALLENGE_TTL_MS);
            challenges.put(id, new LinkedHashMap<>(fields));
            fields.put("signing_payload", AdminCanonicalEncoding.canonicalJson(fields));
            return json(200, AdminCanonicalEncoding.canonicalJson(fields));
        }
        if (target.equals("/admin/security/reauthenticate")) {
            assertEquals("Bearer " + TOKEN, request.getHeaders().get("Authorization"));
            var fields = AdminStrictJson.parseObject(new String(body, StandardCharsets.UTF_8));
            assertEquals(expected.reauthenticationAction(), fields.get("action"));
            assertEquals(expected.method(), fields.get("method")); assertEquals(expected.path(), fields.get("path"));
            assertEquals(NONCE, fields.get("nonce"));
            approvedDigest = (String) fields.get("intent_sha256");
            assertEquals(AdminCanonicalEncoding.sha256Hex(expectedBody), approvedDigest);
            return json(200, AdminCanonicalEncoding.canonicalJson(Map.of("action", fields.get("action"),
                "method", fields.get("method"), "path", fields.get("path"),
                "reauthenticated_until_epoch_ms", System.currentTimeMillis() + 30000)));
        }
        var challenge = challenges.remove(request.getHeaders().get(AdminDeviceProof.CHALLENGE_ID_HEADER));
        assertNotNull("one-use challenge required", challenge);
        assertEquals(request.getMethod(), challenge.get("method"));
        String[] parts = target.split("\\?", 2);
        assertEquals(parts[0], challenge.get("path"));
        assertEquals(AdminCanonicalEncoding.sha256Hex((parts.length == 2 ? parts[1] : "").getBytes(StandardCharsets.UTF_8)), challenge.get("query_sha256"));
        assertEquals(AdminCanonicalEncoding.sha256Hex(body), challenge.get("body_sha256"));
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey); verifier.update(AdminCanonicalEncoding.canonicalJsonBytes(challenge));
        assertTrue("synthetic key signature over actual request challenge", verifier.verify(Base64.getUrlDecoder().decode(
            request.getHeaders().get(AdminDeviceProof.SIGNATURE_HEADER))));
        signatureCount++;
        if (target.equals("/admin/security/sessions")) {
            return json(200, "{\"access_token\":\"" + TOKEN + "\",\"security_state\":\"NORMAL\",\"current_session_id\":\"" + SESSION_ID + "\"}");
        }
        assertEquals(expected.path(), parts[0]); assertEquals(expected.method(), request.getMethod());
        assertEquals("Bearer " + TOKEN, request.getHeaders().get("Authorization"));
        assertEquals(NONCE, request.getHeaders().get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER));
        assertEquals(approvedDigest, AdminCanonicalEncoding.sha256Hex(body));
        assertArrayEquals(expectedBody, body); businessCount++;
        return json(200, response);
    }
    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code).setHeader("Content-Type", "application/json")
            .setHeader("Cache-Control", "no-store").setHeader("Pragma", "no-cache").body(body).build();
    }
}
