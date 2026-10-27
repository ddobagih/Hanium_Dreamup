package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Exact-contract client for metadata-only raw quarantine review. */
public final class AdminRawCollectionHttpClient implements AdminRawCollectionRepository {
    static final String LIST_PATH = "/admin/raw-collections/quarantine";
    static final String CHALLENGE_PATH = "/admin/security/device-proof/challenges";
    static final String LIST_PURPOSE = "admin.raw_collection.list";
    static final String DECISION_ACTION = "admin.raw_collection.purpose_decide";
    static final String LEGAL_HOLD_ACTION = "admin.raw_collection.legal_hold";
    static final String LIST_QUERY = "limit=100&state=QUARANTINED";
    private static final String CORRELATION_ID_HEADER = "X-WalkSafe-Correlation-Id";
    private static final String READ_PURPOSE_HEADER = "X-WalkSafe-Read-Purpose";
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final byte[] EMPTY_BODY = new byte[0];

    interface Clock extends AdminProtectedRequestExecutor.Clock {
        long nowEpochMs();

        default long monotonicNanos() { return System.nanoTime(); }
    }

    interface Transport extends AdminProtectedRequestExecutor.Exchange<Response> {
        Response execute(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException;

        /** S18-18: explicit cancellation of requests still in flight; never waits. */
        default void cancelInFlight() {}
    }

    static final class Response {
        final int statusCode;
        final byte[] body;
        final String contentType;
        final String cacheControl;
        final String pragma;

        Response(int statusCode, String body) {
            this(
                statusCode,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                "application/json",
                "no-store",
                "no-cache"
            );
        }

        Response(
            int statusCode,
            byte[] body,
            String contentType,
            String cacheControl,
            String pragma
        ) {
            this.statusCode = statusCode;
            this.body = body == null ? new byte[0] : body.clone();
            this.contentType = contentType;
            this.cacheControl = cacheControl;
            this.pragma = pragma;
        }
    }

    private final String origin;
    private final Transport transport;
    private final AdminProtectedRequestExecutor<Response> protectedRequests;

    public AdminRawCollectionHttpClient(
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

    AdminRawCollectionHttpClient(
        String configuredOrigin,
        boolean debugBuild,
        String deviceKeyMarker,
        int deviceKeyVersion,
        AdminDeviceProof.Signer signer,
        Clock clock,
        Transport transport
    ) {
        String approved = AdminEndpointPolicy.approvedOriginOrNull(configuredOrigin, debugBuild);
        if (approved == null) throw new IllegalArgumentException("admin API origin is not approved");
        if (deviceKeyMarker == null || !deviceKeyMarker.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("device key marker is invalid");
        }
        if (deviceKeyVersion < 1 || signer == null || clock == null || transport == null) {
            throw new IllegalArgumentException("raw collection client dependencies are invalid");
        }
        this.origin = approved;
        this.transport = transport;
        this.protectedRequests = new AdminProtectedRequestExecutor<>(
            this.origin, deviceKeyMarker, deviceKeyVersion, signer, clock, transport,
            response -> {
                requireStatus(response, 200);
                return jsonBody(response);
            },
            AdminJava8Collections.map("Cache-Control", "no-store", "Pragma", "no-cache")
        );
    }

    static byte[] purposeBody(AdminRawCollectionModels.PurposeDecisionRequest request) {
        if (request == null) throw new IllegalArgumentException("decision request is required");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope", request.scope());
        fields.put("decision", request.decision());
        fields.put("expected_revision", request.expectedRevision());
        fields.put("idempotency_key", request.idempotencyKey());
        fields.put("reason", request.reason());
        fields.put("training_consent_receipt_sha256", request.trainingConsentReceiptSha256());
        fields.put("deidentification_receipt_sha256", request.deidentificationReceiptSha256());
        fields.put("sanitized_manifest_sha256", request.sanitizedManifestSha256());
        fields.put("target_dataset_id", request.targetDatasetId());
        fields.put("exact_location_excluded", request.exactLocationExcluded());
        fields.put("raw_audio_excluded", request.rawAudioExcluded());
        fields.put("third_party_faces_excluded", request.thirdPartyFacesExcluded());
        return AdminCanonicalEncoding.canonicalJsonBytes(fields);
    }

    static byte[] legalHoldBody(AdminRawCollectionModels.LegalHoldRequest request) {
        if (request == null) throw new IllegalArgumentException("legal hold request is required");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", request.action());
        fields.put("expected_revision", request.expectedRevision());
        fields.put("idempotency_key", request.idempotencyKey());
        fields.put("reason", request.reason());
        fields.put("legal_basis", request.legalBasis());
        fields.put("authority_reference", request.authorityReference());
        fields.put("contact", request.contact());
        fields.put("expires_at", request.expiresAt());
        return AdminCanonicalEncoding.canonicalJsonBytes(fields);
    }

    @Override
    public AdminRawCollectionModels.Page listQuarantine(
        AdminOperationsApi.SessionContext session,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        Response response = executeProtected(
            requireSession(session),
            "GET",
            LIST_PATH,
            LIST_QUERY,
            null,
            LIST_PURPOSE,
            EMPTY_BODY,
            requireReconfirmation(reconfirmationHeaders)
        );
        requireNoStore(response);
        requireStatus(response, 200);
        return AdminRawCollectionModels.parsePage(jsonBody(response));
    }

    @Override
    public AdminRawCollectionModels.PurposeDecisionReceipt decidePurpose(
        AdminOperationsApi.SessionContext session,
        AdminRawCollectionModels.Summary source,
        AdminRawCollectionModels.PurposeDecisionRequest request,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        AdminOperationsApi.SessionContext boundSession = requireSession(session);
        if (source == null) throw new IllegalArgumentException("decision source is required");
        String safeId = AdminRawCollectionModels.canonicalUuid(
            source.collectionId(), "collection_id"
        );
        byte[] body = purposeBody(request);
        Response response = executeProtected(
            boundSession,
            "POST",
            "/admin/raw-collections/" + safeId + "/decisions",
            "",
            DECISION_ACTION,
            null,
            body,
            requireReconfirmation(reconfirmationHeaders)
        );
        requireNoStore(response);
        if (response.statusCode == 409) throw new ConflictException();
        requireCreatedOrReplay(response);
        return AdminRawCollectionModels.parsePurposeDecisionReceipt(
            jsonBody(response), source, request, boundSession.adminId()
        );
    }

    @Override
    public AdminRawCollectionModels.LegalHoldReceipt recordLegalHold(
        AdminOperationsApi.SessionContext session,
        String collectionId,
        AdminRawCollectionModels.LegalHoldRequest request,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        AdminOperationsApi.SessionContext boundSession = requireSession(session);
        String safeId = AdminRawCollectionModels.canonicalUuid(collectionId, "collection_id");
        byte[] body = legalHoldBody(request);
        Response response = executeProtected(
            boundSession,
            "POST",
            "/admin/raw-collections/" + safeId + "/legal-holds",
            "",
            LEGAL_HOLD_ACTION,
            null,
            body,
            requireReconfirmation(reconfirmationHeaders)
        );
        requireNoStore(response);
        if (response.statusCode == 409) throw new ConflictException();
        requireCreatedOrReplay(response);
        return AdminRawCollectionModels.parseLegalHoldReceipt(
            jsonBody(response), safeId, request, boundSession.adminId()
        );
    }

    private Response executeProtected(
        AdminOperationsApi.SessionContext session,
        String method,
        String path,
        String canonicalQuery,
        String action,
        String readPurpose,
        byte[] body,
        Map<String, String> additionalHeaders
    ) throws IOException, GeneralSecurityException {
        return protectedRequests.execute(session, method, path, canonicalQuery,
            action, readPurpose, body.length == 0 ? null : body, additionalHeaders,
            "application/json", null).response;
    }

    private static AdminOperationsApi.SessionContext requireSession(
        AdminOperationsApi.SessionContext session
    ) throws IOException {
        if (session == null || session.accessToken() == null
            || session.accessToken().length() < 32 || session.accessToken().length() > 512
            || session.accessToken().chars().anyMatch(Character::isWhitespace)) {
            throw new IOException("administrator session is unavailable");
        }
        if (session.adminId() == null
            || !session.adminId().matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IOException("administrator identity is unavailable");
        }
        try {
            AdminRawCollectionModels.canonicalUuid(session.sessionId(), "session_id");
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator session binding is unavailable", error);
        }
        if (session.deviceId() == null
            || !session.deviceId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IOException("administrator device binding is unavailable");
        }
        return session;
    }

    private static Map<String, String> requireReconfirmation(Map<String, String> headers) {
        if (headers == null || headers.size() != 1) {
            throw new IllegalArgumentException("exact reconfirmation header is required");
        }
        String nonce = headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER);
        if (!AdminHighRiskActionGate.isCanonicalNonce(nonce)) {
            throw new IllegalArgumentException("reconfirmation nonce is invalid");
        }
        return AdminJava8Collections.map(
            AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER, nonce
        );
    }

    private static void requireStatus(Response response, int expected) throws IOException {
        if (response.statusCode != expected) {
            throw new IOException(
                "administrator raw collection request returned unexpected status="
                    + response.statusCode
            );
        }
    }

    private static void requireCreatedOrReplay(Response response) throws IOException {
        if (response.statusCode != 200 && response.statusCode != 201) {
            throw new IOException(
                "administrator raw collection mutation returned unexpected status="
                    + response.statusCode
            );
        }
    }

    private static String jsonBody(Response response) throws IOException {
        if (response.contentType == null
            || !"application/json".equals(
                response.contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)
            )
            || response.body.length > MAX_RESPONSE_BYTES) {
            throw new IOException("administrator raw collection JSON response is invalid");
        }
        requireNoStore(response);
        return AdminStrictJson.decodeUtf8(response.body);
    }

    /** S18-18: not synchronized; it only signals the transport, which never waits. */
    @Override
    public void cancelInFlightRequests() {
        transport.cancelInFlight();
    }

    private static void requireNoStore(Response response) throws IOException {
        if (!hasDirective(response.cacheControl, "no-store")
            || !hasDirective(response.pragma, "no-cache")) {
            throw new IOException("administrator raw collection response is cacheable");
        }
    }

    private static boolean hasDirective(String value, String expected) {
        if (value == null) return false;
        for (String directive : value.split(",")) {
            if (expected.equals(directive.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * S18-19: every request has the overall deadline of {@link AdminHttpRequestDeadline} on
     * top of the connect/read phase limits. A timeout fails as "server outcome unknown" and a
     * purpose decision or legal hold write is never re-sent automatically.
     */
    static final class UrlConnectionTransport implements Transport {
        private final AdminHttpRequestDeadline deadlines;

        UrlConnectionTransport() {
            this(AdminHttpRequestDeadline.system());
        }

        UrlConnectionTransport(AdminHttpRequestDeadline deadlines) {
            if (deadlines == null) throw new IllegalArgumentException("request deadline is required");
            this.deadlines = deadlines;
        }

        /** Explicit cancellation of every request still in flight (it does not wait). */
        @Override
        public void cancelInFlight() {
            deadlines.cancelInFlight();
        }

        @Override
        public Response execute(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
            return execute(null, method, url, headers, body);
        }

        @Override
        public Response execute(AdminOperationsApi.SessionContext session, String method, String url,
            Map<String, String> headers, byte[] body) throws IOException {
            AdminHttpRequestDeadline.Owner owner = deadlines.open(session, url);
            try {
                HttpURLConnection connection = owner.connection();
                connection.setUseCaches(false);
                connection.setRequestMethod(method);
                connection.setInstanceFollowRedirects(false);
                connection.setDoInput(true);
                connection.setDoOutput(body != null);
                // adm-F5: stream the exact body; the platform keeps no retryable copy to re-send.
                if (body != null) connection.setFixedLengthStreamingMode(body.length);
                headers.forEach(connection::setRequestProperty);
                owner.check();
                if (body != null) {
                    try (var output = connection.getOutputStream()) {
                        owner.check();
                        output.write(body);
                        owner.check();
                    }
                }
                owner.check();
                int status = connection.getResponseCode();
                owner.check();
                InputStream stream = status >= 200 && status <= 299
                    ? connection.getInputStream() : connection.getErrorStream();
                owner.check();
                byte[] bytes = stream == null ? new byte[0] : readBounded(stream, owner);
                String contentType = connection.getHeaderField("Content-Type");
                String cacheControl = connection.getHeaderField("Cache-Control");
                String pragma = connection.getHeaderField("Pragma");
                owner.check();
                return new Response(status, bytes, contentType, cacheControl, pragma);
            } finally {
                owner.close();
            }
        }

        private static byte[] readBounded(InputStream input, AdminHttpRequestDeadline.Owner owner)
            throws IOException {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4_096];
                int zeroReads = 0;
                while (true) {
                    owner.check();
                    int read = input.read(buffer);
                    owner.check();
                    if (read < 0) break;
                    if (read == 0) {
                        // Match the existing operations/image reader's no-progress limit.
                        if (++zeroReads > 3) throw new IOException("administrator raw collection response made no progress");
                        continue;
                    }
                    zeroReads = 0;
                    if (output.size() + read > MAX_RESPONSE_BYTES) {
                        throw new IOException("administrator raw collection response is too large");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }
}
