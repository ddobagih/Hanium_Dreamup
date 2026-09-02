package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Exact-contract incident client; it records state only and performs no recovery or control. */
public final class AdminIncidentHttpClient implements AdminIncidentRepository {
    static final String LIST_PATH = "/admin/incidents";
    static final String CHALLENGE_PATH = "/admin/security/device-proof/challenges";
    static final String LIST_PURPOSE = "admin.incident.list";
    static final String DETAIL_PURPOSE = "admin.incident.detail";
    static final String HISTORY_PURPOSE = "admin.incident.history";
    static final String STATUS_ACTION = "admin.incident.status.update";
    private static final String CORRELATION_ID_HEADER = "X-WalkSafe-Correlation-Id";
    private static final String READ_PURPOSE_HEADER = "X-WalkSafe-Read-Purpose";
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final int MAX_HISTORY_RESPONSE_BYTES = 96 * 1024;
    private static final byte[] EMPTY_BODY = new byte[0];

    interface Clock { long nowEpochMs(); }

    interface Transport {
        Response execute(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException;
    }

    static final class Response {
        final int statusCode;
        final byte[] body;
        final String contentType;

        Response(int statusCode, String body) {
            this(statusCode, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), "application/json");
        }

        Response(int statusCode, byte[] body, String contentType) {
            this.statusCode = statusCode;
            this.body = body == null ? new byte[0] : body.clone();
            this.contentType = contentType;
        }
    }

    private final String origin;
    private final String deviceKeyMarker;
    private final int deviceKeyVersion;
    private final AdminDeviceProof.Signer signer;
    private final Clock clock;
    private final Transport transport;

    public AdminIncidentHttpClient(
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
            new OkHttpTransport()
        );
    }

    AdminIncidentHttpClient(
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
            throw new IllegalArgumentException("incident client dependencies are invalid");
        }
        this.origin = approved;
        this.deviceKeyMarker = deviceKeyMarker;
        this.deviceKeyVersion = deviceKeyVersion;
        this.signer = signer;
        this.clock = clock;
        this.transport = transport;
    }

    @Override
    public AdminIncidentModels.Page list(
        AdminOperationsApi.SessionContext session,
        AdminIncidentModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException {
        if (filters == null) throw new IllegalArgumentException("incident filters are required");
        List<AdminCanonicalEncoding.QueryParameter> parameters = new ArrayList<>();
        parameters.add(new AdminCanonicalEncoding.QueryParameter(
            "limit", Integer.toString(AdminIncidentModels.PAGE_SIZE)
        ));
        if (filters.status() != null) {
            parameters.add(new AdminCanonicalEncoding.QueryParameter("status", filters.status()));
        }
        if (cursor != null) {
            if (!cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
                throw new IllegalArgumentException("incident cursor is invalid");
            }
            parameters.add(new AdminCanonicalEncoding.QueryParameter("cursor", cursor));
        }
        String query = AdminCanonicalEncoding.canonicalQuery(parameters);
        Response response = executeProtected(
            requireSession(session), "GET", LIST_PATH, query, null, LIST_PURPOSE,
            EMPTY_BODY, AdminJava8Collections.map()
        );
        requireStatus(response, 200);
        return AdminIncidentModels.parsePage(jsonBody(response));
    }

    @Override
    public AdminIncidentModels.Detail detail(
        AdminOperationsApi.SessionContext session,
        String incidentId
    ) throws IOException, GeneralSecurityException {
        String safeId = AdminIncidentModels.canonicalUuid(incidentId, "incident_id");
        Response response = executeProtected(
            requireSession(session), "GET", LIST_PATH + "/" + safeId, "", null,
            DETAIL_PURPOSE, EMPTY_BODY, AdminJava8Collections.map()
        );
        if (response.statusCode == 404) throw new NotFoundException();
        requireStatus(response, 200);
        return AdminIncidentModels.parseDetail(jsonBody(response), safeId);
    }

    @Override
    public AdminIncidentModels.HistoryPage history(
        AdminOperationsApi.SessionContext session,
        String incidentId,
        String cursor
    ) throws IOException, GeneralSecurityException {
        String safeId = canonicalHistoryUuid(incidentId);
        List<AdminCanonicalEncoding.QueryParameter> parameters = new ArrayList<>();
        parameters.add(new AdminCanonicalEncoding.QueryParameter(
            "limit", Integer.toString(AdminIncidentModels.PAGE_SIZE)
        ));
        if (cursor != null) {
            if (!cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
                throw new IllegalArgumentException("incident history cursor is invalid");
            }
            parameters.add(new AdminCanonicalEncoding.QueryParameter("cursor", cursor));
        }
        String query = AdminCanonicalEncoding.canonicalQuery(parameters);
        Response response = executeProtected(
            requireSession(session), "GET", LIST_PATH + "/" + safeId + "/history",
            query, null, HISTORY_PURPOSE, EMPTY_BODY,
            AdminJava8Collections.map(
                "Cache-Control", "no-store",
                "Pragma", "no-cache"
            )
        );
        if (response.statusCode == 404) throw new NotFoundException();
        if (response.statusCode == 422) throw new HistoryCursorException();
        requireStatus(response, 200);
        AdminIncidentModels.HistoryPage page = AdminIncidentModels.parseHistoryPage(
            jsonBody(response, MAX_HISTORY_RESPONSE_BYTES), safeId
        );
        if (cursor == null && page.items().get(0).revision() != 1) {
            throw new IOException("incident history first page is not contiguous");
        }
        return page;
    }

    @Override
    public AdminIncidentModels.StatusSnapshot updateStatus(
        AdminOperationsApi.SessionContext session,
        String incidentId,
        AdminIncidentModels.StatusRequest request,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        String safeId = AdminIncidentModels.canonicalUuid(incidentId, "incident_id");
        if (request == null) throw new IllegalArgumentException("incident status request is required");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("expected_version", request.expectedVersion());
        fields.put("idempotency_key", request.idempotencyKey());
        fields.put("reason", request.reason());
        fields.put("observation", request.observation());
        fields.put("evidence_sha256", request.evidenceSha256());
        fields.put("next_state", request.nextState());
        byte[] body = AdminCanonicalEncoding.canonicalJsonBytes(fields);
        Response response = executeProtected(
            requireSession(session), "PATCH", LIST_PATH + "/" + safeId + "/status", "",
            STATUS_ACTION, null, body, requireReconfirmation(reconfirmationHeaders)
        );
        if (response.statusCode == 409) {
            throw new StatusConflictException(AdminIncidentModels.parseStatusConflict(jsonBody(response)));
        }
        requireStatus(response, 200);
        return AdminIncidentModels.parseStatus(jsonBody(response), safeId);
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
        String correlationId = UUID.randomUUID().toString();
        AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
            action,
            session.adminId(),
            AdminCanonicalEncoding.sha256Hex(body),
            correlationId,
            session.deviceId(),
            deviceKeyMarker,
            deviceKeyVersion,
            method,
            path,
            AdminDeviceProof.Purpose.ACTION,
            AdminCanonicalEncoding.sha256Hex(canonicalQuery.getBytes(StandardCharsets.UTF_8)),
            readPurpose,
            session.sessionId()
        );
        Response challenge = transport.execute(
            "POST",
            origin + CHALLENGE_PATH,
            jsonHeaders(protectedHeaders(session, correlationId, null)),
            intent.challengeRequestBytes()
        );
        requireStatus(challenge, 200);
        AdminDeviceProof.SignedChallenge proof = AdminDeviceProof.parseAndSign(
            jsonBody(challenge), intent, clock.nowEpochMs(), signer
        );
        Map<String, String> headers = new LinkedHashMap<>(
            protectedHeaders(session, correlationId, readPurpose)
        );
        headers.putAll(proof.proofHeaders());
        headers.putAll(additionalHeaders);
        headers.put("Accept", "application/json");
        if (body.length > 0) headers.put("Content-Type", "application/json; charset=utf-8");
        String url = origin + path + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        return transport.execute(
            method,
            url,
            AdminJava8Collections.copyMap(headers),
            body.length == 0 ? null : body
        );
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
            AdminIncidentModels.canonicalUuid(session.sessionId(), "session_id");
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator session binding is unavailable", error);
        }
        if (session.deviceId() == null
            || !session.deviceId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IOException("administrator device binding is unavailable");
        }
        return session;
    }

    private static Map<String, String> protectedHeaders(
        AdminOperationsApi.SessionContext session,
        String correlationId,
        String readPurpose
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + session.accessToken());
        headers.put("X-WalkSafe-App-Kind", AdminSecurityHttpClient.APP_KIND);
        headers.put("X-WalkSafe-Role", AdminSecurityHttpClient.ROLE);
        headers.put("X-WalkSafe-Audience", AdminSecurityHttpClient.AUDIENCE);
        headers.put("X-WalkSafe-Device-Id", session.deviceId());
        headers.put(CORRELATION_ID_HEADER, correlationId);
        if (readPurpose != null) headers.put(READ_PURPOSE_HEADER, readPurpose);
        return AdminJava8Collections.copyMap(headers);
    }

    private static Map<String, String> jsonHeaders(Map<String, String> source) {
        Map<String, String> headers = new LinkedHashMap<>(source);
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json; charset=utf-8");
        return AdminJava8Collections.copyMap(headers);
    }

    private static Map<String, String> requireReconfirmation(Map<String, String> headers) {
        if (headers == null || headers.size() != 1) {
            throw new IllegalArgumentException("exact reconfirmation header is required");
        }
        String nonce = headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER);
        if (!AdminHighRiskActionGate.isCanonicalNonce(nonce)) {
            throw new IllegalArgumentException("reconfirmation nonce is invalid");
        }
        return AdminJava8Collections.map(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER, nonce);
    }

    private static void requireStatus(Response response, int expected) throws IOException {
        if (response.statusCode != expected) {
            throw new IOException("administrator incident request returned unexpected status=" + response.statusCode);
        }
    }

    private static String jsonBody(Response response) throws IOException {
        return jsonBody(response, MAX_RESPONSE_BYTES);
    }

    private static String jsonBody(Response response, int maximumBytes) throws IOException {
        if (response.contentType == null
            || !response.contentType.toLowerCase(Locale.ROOT).startsWith("application/json")
            || response.body.length > maximumBytes) {
            throw new IOException("administrator incident JSON response is invalid");
        }
        return AdminStrictJson.decodeUtf8(response.body);
    }

    private static String canonicalHistoryUuid(String value) {
        String canonical = AdminIncidentModels.canonicalUuid(value, "incident_id");
        UUID parsed = UUID.fromString(canonical);
        if (parsed.variant() != 2 || parsed.version() < 1 || parsed.version() > 5) {
            throw new IllegalArgumentException("incident_id is not a supported canonical UUID");
        }
        return canonical;
    }

    private static final class OkHttpTransport implements Transport {
        @Override
        public Response execute(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
            AdminOkHttpTransport.Result response = AdminOkHttpTransport.execute(
                method,
                url,
                headers,
                body,
                MAX_RESPONSE_BYTES,
                "administrator incident response read was cancelled",
                "administrator incident response made no progress",
                "administrator incident response is too large"
            );
            return new Response(
                response.statusCode,
                response.body,
                response.header("Content-Type")
            );
        }
    }
}
