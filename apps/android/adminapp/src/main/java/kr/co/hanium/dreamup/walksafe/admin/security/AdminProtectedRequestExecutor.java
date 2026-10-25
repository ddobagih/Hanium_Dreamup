package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns the challenge -> receipt clock -> proof -> single dispatch protocol.
 * Endpoint adapters retain transport limits, response validation and error projection.
 */
final class AdminProtectedRequestExecutor<R> {
    interface Clock {
        long nowEpochMs();
        long monotonicNanos();
    }
    interface Exchange<R> {
        R execute(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException;
        default R execute(AdminOperationsApi.SessionContext session, String method, String url,
            Map<String, String> headers, byte[] body) throws IOException {
            return execute(method, url, headers, body);
        }
    }
    interface ChallengeBody<R> { String decode(R response) throws IOException; }
    interface ChallengeExchange<R> { R execute() throws IOException; }
    interface BeforeDispatch { void check() throws IOException; }

    static final class Result<R> {
        final String correlationId;
        final R response;
        Result(String correlationId, R response) {
            this.correlationId = correlationId;
            this.response = response;
        }
    }

    private final String origin;
    private final String keyMarker;
    private final int keyVersion;
    private final AdminDeviceProof.Signer signer;
    private final Clock clock;
    private final Exchange<R> exchange;
    private final ChallengeBody<R> challengeBody;
    private final Map<String, String> transportHeaders;

    AdminProtectedRequestExecutor(String origin, String keyMarker, int keyVersion,
        AdminDeviceProof.Signer signer, Clock clock, Exchange<R> exchange,
        ChallengeBody<R> challengeBody) {
        this(origin, keyMarker, keyVersion, signer, clock, exchange, challengeBody,
            AdminJava8Collections.map());
    }

    AdminProtectedRequestExecutor(String origin, String keyMarker, int keyVersion,
        AdminDeviceProof.Signer signer, Clock clock, Exchange<R> exchange,
        ChallengeBody<R> challengeBody, Map<String, String> transportHeaders) {
        this.origin = origin;
        this.keyMarker = keyMarker;
        this.keyVersion = keyVersion;
        this.signer = signer;
        this.clock = clock;
        this.exchange = exchange;
        this.challengeBody = challengeBody;
        this.transportHeaders = AdminJava8Collections.copyMap(transportHeaders);
    }

    Result<R> execute(AdminOperationsApi.SessionContext session, String method, String path,
        String canonicalQuery, String action, String readPurpose, byte[] body,
        Map<String, String> additionalHeaders, String accept, BeforeDispatch beforeDispatch)
        throws IOException, GeneralSecurityException {
        return execute(session, method, path, canonicalQuery, action, readPurpose, body,
            additionalHeaders, accept, null, beforeDispatch);
    }

    Result<R> execute(AdminOperationsApi.SessionContext session, String method, String path,
        String canonicalQuery, String action, String readPurpose, byte[] body,
        Map<String, String> additionalHeaders, String accept, BeforeDispatch afterChallenge,
        BeforeDispatch beforeDispatch) throws IOException, GeneralSecurityException {
        session.requireCurrentOperation();
        String correlationId = UUID.randomUUID().toString();
        byte[] signedBody = body == null ? new byte[0] : body;
        AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
            action, session.adminId(), AdminCanonicalEncoding.sha256Hex(signedBody),
            correlationId, session.deviceId(), keyMarker, keyVersion, method, path,
            AdminDeviceProof.Purpose.ACTION,
            AdminCanonicalEncoding.sha256Hex(canonicalQuery.getBytes(StandardCharsets.UTF_8)),
            readPurpose, session.sessionId()
        );
        Map<String, String> challengeHeaders = headers(session, correlationId, null);
        challengeHeaders.putAll(transportHeaders);
        challengeHeaders.put("Accept", "application/json");
        challengeHeaders.put("Content-Type", "application/json; charset=utf-8");
        AdminDeviceProof.SignedChallenge proof = challengeAndSign(
            () -> exchange.execute(session, "POST", origin + "/admin/security/device-proof/challenges",
                AdminJava8Collections.copyMap(challengeHeaders), intent.challengeRequestBytes()),
            response -> {
                session.requireCurrentOperation();
                if (afterChallenge != null) afterChallenge.check();
                return challengeBody.decode(response);
            }, intent, clock, signer
        );
        Map<String, String> operationHeaders = headers(session, correlationId, readPurpose);
        operationHeaders.putAll(transportHeaders);
        operationHeaders.putAll(proof.proofHeaders());
        // Reconfirmation and other one-time headers belong only to the final request.
        operationHeaders.putAll(additionalHeaders);
        operationHeaders.put("Accept", accept);
        if (body != null) operationHeaders.put("Content-Type", "application/json; charset=utf-8");
        session.requireCurrentOperation();
        if (beforeDispatch != null) beforeDispatch.check();
        session.requireCurrentOperation();
        R response = exchange.execute(session, method,
            origin + path + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery),
            AdminJava8Collections.copyMap(operationHeaders), body);
        return new Result<>(correlationId, response);
    }

    static <R> AdminDeviceProof.SignedChallenge challengeAndSign(
        ChallengeExchange<R> exchange, ChallengeBody<R> decoder, AdminDeviceProof.Intent intent,
        Clock clock, AdminDeviceProof.Signer signer) throws IOException, GeneralSecurityException {
        R response = exchange.execute();
        // Capture receipt before parsing. Wall-clock changes after receipt cannot extend a nonce.
        AdminChallengeTime received = new AdminChallengeTime(clock.nowEpochMs(), clock.monotonicNanos());
        String body = decoder.decode(response);
        return AdminDeviceProof.parseAndSign(body, intent,
            received.estimatedNowEpochMs(clock.monotonicNanos()), signer);
    }

    private static Map<String, String> headers(AdminOperationsApi.SessionContext session,
        String correlationId, String readPurpose) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + session.accessToken());
        headers.put("X-WalkSafe-App-Kind", AdminSecurityHttpClient.APP_KIND);
        headers.put("X-WalkSafe-Role", AdminSecurityHttpClient.ROLE);
        headers.put("X-WalkSafe-Audience", AdminSecurityHttpClient.AUDIENCE);
        headers.put("X-WalkSafe-Device-Id", session.deviceId());
        headers.put("X-WalkSafe-Correlation-Id", correlationId);
        if (readPurpose != null) headers.put("X-WalkSafe-Read-Purpose", readPurpose);
        return headers;
    }
}
