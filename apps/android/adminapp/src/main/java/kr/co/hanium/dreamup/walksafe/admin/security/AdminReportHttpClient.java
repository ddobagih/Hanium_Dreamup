package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** Device-proof protected client for strict, minimum administrator report reads. */
public final class AdminReportHttpClient implements AdminReportRepository {
    static final String LIST_PATH = "/admin/reports";
    static final String REQUEST_LIST_PATH = "/admin/report-requests";
    static final String CHALLENGE_PATH = "/admin/security/device-proof/challenges";
    static final String LIST_PURPOSE = "admin.report.list";
    static final String DETAIL_PURPOSE = "admin.report.detail";
    static final String AUDIT_PURPOSE = "admin.audit.list";
    static final String REQUEST_LIST_PURPOSE = "admin.report_request.list";
    static final String REQUEST_DETAIL_PURPOSE = "admin.report_request.detail";
    static final String STATUS_ACTION = "admin.report.status.update";
    static final String REQUEST_STATUS_ACTION = "admin.report_request.status.update";
    static final String PACKAGE_ACTION = "admin.report.delivery_package.create";
    static final String CORRELATION_ID_HEADER = "X-WalkSafe-Correlation-Id";
    static final String READ_PURPOSE_HEADER = "X-WalkSafe-Read-Purpose";
    private static final int MAX_JSON_RESPONSE_BYTES = 128 * 1024;
    private static final int MAX_PACKAGE_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final byte[] EMPTY_BODY = new byte[0];

    interface Clock {
        long nowEpochMs();
    }

    interface Transport {
        Response execute(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException;
    }

    static final class Response {
        final int statusCode;
        final byte[] bytes;
        final Map<String, String> headers;

        Response(int statusCode, String body) {
            this(
                statusCode,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                AdminJava8Collections.map("content-type", "application/json")
            );
        }

        Response(int statusCode, byte[] bytes, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
            Map<String, String> normalized = new LinkedHashMap<>();
            if (headers != null) headers.forEach((key, value) -> {
                if (key != null && value != null) normalized.put(key.toLowerCase(java.util.Locale.ROOT), value);
            });
            this.headers = AdminJava8Collections.copyMap(normalized);
        }
    }

    private final String origin;
    private final String deviceKeyMarker;
    private final int deviceKeyVersion;
    private final AdminDeviceProof.Signer signer;
    private final Clock clock;
    private final Transport transport;

    public AdminReportHttpClient(
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

    AdminReportHttpClient(
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
        if (deviceKeyVersion < 1) throw new IllegalArgumentException("device key version is invalid");
        if (signer == null || clock == null || transport == null) {
            throw new IllegalArgumentException("device signer, clock, and transport are required");
        }
        this.origin = approved;
        this.deviceKeyMarker = deviceKeyMarker;
        this.deviceKeyVersion = deviceKeyVersion;
        this.signer = signer;
        this.clock = clock;
        this.transport = transport;
    }

    @Override
    public AdminReportModels.Page list(
        AdminOperationsApi.SessionContext session,
        AdminReportModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException {
        if (filters == null) throw new IllegalArgumentException("report filters are required");
        List<AdminCanonicalEncoding.QueryParameter> parameters = new ArrayList<>();
        parameters.add(new AdminCanonicalEncoding.QueryParameter(
            "limit",
            Integer.toString(AdminReportModels.PAGE_SIZE)
        ));
        add(parameters, "report_id", filters.reportId());
        add(parameters, "status", filters.status());
        add(parameters, "class_name", filters.className());
        add(parameters, "created_from", filters.createdFrom());
        add(parameters, "created_to", filters.createdTo());
        if (cursor != null) {
            if (!cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
                throw new IllegalArgumentException("report cursor is invalid");
            }
            add(parameters, "cursor", cursor);
        }
        String query = AdminCanonicalEncoding.canonicalQuery(parameters);
        Response response = executeProtectedRead(requireSession(session), LIST_PATH, query, LIST_PURPOSE);
        requireStatus(response, 200);
        return AdminReportModels.parsePage(jsonBody(response));
    }

    @Override
    public AdminReportModels.Detail detail(
        AdminOperationsApi.SessionContext session,
        String reportId
    ) throws IOException, GeneralSecurityException {
        String safeReportId = AdminReportModels.canonicalUuid(reportId, "report_id");
        Response response = executeProtectedRead(
            requireSession(session),
            LIST_PATH + "/" + safeReportId,
            "",
            DETAIL_PURPOSE
        );
        if (response.statusCode == 404) throw new NotFoundException();
        requireStatus(response, 200);
        return AdminReportModels.parseDetail(jsonBody(response), safeReportId);
    }

    @Override
    public AdminReportModels.StatusSnapshot updateStatus(
        AdminOperationsApi.SessionContext session,
        String reportId,
        String nextStatus,
        int expectedVersion,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        if (!AdminJava8Collections.set("new", "reviewed", "resolved").contains(nextStatus)) {
            throw new IllegalArgumentException("next status is invalid");
        }
        if (expectedVersion < 1) throw new IllegalArgumentException("expected_version must be positive");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("expected_version", expectedVersion);
        fields.put("status", nextStatus);
        byte[] body = AdminCanonicalEncoding.canonicalJsonBytes(fields);
        Response response = executeProtected(
            requireSession(session),
            "PATCH",
            LIST_PATH + "/" + safeId + "/status",
            "",
            STATUS_ACTION,
            null,
            body,
            requireReconfirmation(reconfirmationHeaders),
            "application/json"
        );
        if (response.statusCode == 409) {
            throw new StatusConflictException(
                AdminReportModels.parseStatusConflict(jsonBody(response))
            );
        }
        requireStatus(response, 200);
        return AdminReportModels.parseStatus(jsonBody(response), safeId);
    }

    @Override
    public AdminDeliveryPackage createDeliveryPackage(
        AdminOperationsApi.SessionContext session,
        String reportId,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        Response response = executeProtected(
            requireSession(session),
            "POST",
            LIST_PATH + "/" + safeId + "/delivery-packages",
            "",
            PACKAGE_ACTION,
            null,
            EMPTY_BODY,
            requireReconfirmation(reconfirmationHeaders),
            "application/zip"
        );
        requireStatus(response, 201);
        if (!"application/zip".equals(response.headers.get("content-type"))) {
            throw new IOException("delivery package content type is invalid");
        }
        int revision = positiveIntHeader(response, "x-walksafe-package-revision");
        return new AdminDeliveryPackage(
            safeId,
            header(response, "x-walksafe-package-id"),
            revision,
            header(response, "x-walksafe-export-audit-id"),
            header(response, "x-walksafe-package-sha256"),
            header(response, "x-walksafe-csv-sha256"),
            header(response, "x-walksafe-manifest-sha256"),
            response.bytes
        );
    }

    @Override
    public AdminAuditModels.Page audits(
        AdminOperationsApi.SessionContext session,
        AdminAuditModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException {
        if (filters == null) throw new IllegalArgumentException("audit filters are required");
        List<AdminCanonicalEncoding.QueryParameter> parameters = new ArrayList<>();
        parameters.add(new AdminCanonicalEncoding.QueryParameter(
            "limit",
            Integer.toString(AdminAuditModels.PAGE_SIZE)
        ));
        add(parameters, "event_type", filters.eventType());
        add(parameters, "actor_id", filters.actorId());
        if (cursor != null) {
            if (!cursor.matches("[A-Za-z0-9_-]{1,1024}")) throw new IllegalArgumentException("audit cursor is invalid");
            add(parameters, "cursor", cursor);
        }
        String query = AdminCanonicalEncoding.canonicalQuery(parameters);
        Response response = executeProtectedRead(
            requireSession(session),
            LIST_PATH + "/audits",
            query,
            AUDIT_PURPOSE
        );
        requireStatus(response, 200);
        return AdminAuditModels.parsePage(jsonBody(response));
    }

    @Override
    public AdminReportRequestModels.Page listRequests(
        AdminOperationsApi.SessionContext session,
        AdminReportRequestModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException {
        if (filters == null) throw new IllegalArgumentException("report request filters are required");
        List<AdminCanonicalEncoding.QueryParameter> parameters = new ArrayList<>();
        parameters.add(new AdminCanonicalEncoding.QueryParameter(
            "limit",
            Integer.toString(AdminReportRequestModels.PAGE_SIZE)
        ));
        add(parameters, "report_id", filters.reportId());
        add(parameters, "request_type", filters.requestType());
        add(parameters, "status", filters.status());
        if (cursor != null) {
            if (!cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
                throw new IllegalArgumentException("report request cursor is invalid");
            }
            add(parameters, "cursor", cursor);
        }
        Response response = executeProtectedRead(
            requireSession(session),
            REQUEST_LIST_PATH,
            AdminCanonicalEncoding.canonicalQuery(parameters),
            REQUEST_LIST_PURPOSE
        );
        requireStatus(response, 200);
        return AdminReportRequestModels.parsePage(jsonBody(response));
    }

    @Override
    public AdminReportRequestModels.Detail requestDetail(
        AdminOperationsApi.SessionContext session,
        String requestId
    ) throws IOException, GeneralSecurityException {
        String safeId = AdminReportRequestModels.canonicalUuid(requestId, "request_id");
        Response response = executeProtectedRead(
            requireSession(session),
            REQUEST_LIST_PATH + "/" + safeId,
            "",
            REQUEST_DETAIL_PURPOSE
        );
        if (response.statusCode == 404) throw new ReportRequestNotFoundException();
        requireStatus(response, 200);
        return AdminReportRequestModels.parseDetail(jsonBody(response), safeId);
    }

    @Override
    public AdminReportRequestModels.StatusSnapshot updateRequestStatus(
        AdminOperationsApi.SessionContext session,
        String requestId,
        String nextStatus,
        int expectedVersion,
        String publicResponse,
        String internalNote,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        String safeId = AdminReportRequestModels.canonicalUuid(requestId, "request_id");
        if (!AdminJava8Collections.set("ACKNOWLEDGED", "RESOLVED", "REJECTED").contains(nextStatus)) {
            throw new IllegalArgumentException("next report request status is invalid");
        }
        if (expectedVersion < 1) throw new IllegalArgumentException("expected_version must be positive");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", nextStatus);
        fields.put("expected_version", expectedVersion);
        fields.put(
            "public_response",
            AdminReportRequestModels.optionalResponse(publicResponse, "public_response")
        );
        fields.put(
            "internal_note",
            AdminReportRequestModels.optionalResponse(internalNote, "internal_note")
        );
        byte[] body = AdminCanonicalEncoding.canonicalJsonBytes(fields);
        Response response = executeProtected(
            requireSession(session),
            "PATCH",
            REQUEST_LIST_PATH + "/" + safeId + "/status",
            "",
            REQUEST_STATUS_ACTION,
            null,
            body,
            requireReconfirmation(reconfirmationHeaders),
            "application/json"
        );
        if (response.statusCode == 409) {
            throw new ReportRequestConflictException(
                AdminReportRequestModels.parseStatusConflict(jsonBody(response), safeId)
            );
        }
        requireStatus(response, 200);
        return AdminReportRequestModels.parseStatus(jsonBody(response), safeId);
    }

    private Response executeProtectedRead(
        AdminOperationsApi.SessionContext session,
        String path,
        String canonicalQuery,
        String readPurpose
    ) throws IOException, GeneralSecurityException {
        return executeProtected(
            session,
            "GET",
            path,
            canonicalQuery,
            null,
            readPurpose,
            EMPTY_BODY,
            AdminJava8Collections.map(),
            "application/json"
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
        Map<String, String> additionalHeaders,
        String accept
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
        Map<String, String> challengeHeaders = jsonHeaders(protectedHeaders(session, correlationId, null));
        Response challenge = transport.execute(
            "POST",
            origin + CHALLENGE_PATH,
            challengeHeaders,
            intent.challengeRequestBytes()
        );
        requireStatus(challenge, 200);
        AdminDeviceProof.SignedChallenge proof = AdminDeviceProof.parseAndSign(
            jsonBody(challenge),
            intent,
            clock.nowEpochMs(),
            signer
        );
        Map<String, String> headers = new LinkedHashMap<>(
            protectedHeaders(session, correlationId, readPurpose)
        );
        headers.putAll(proof.proofHeaders());
        headers.putAll(additionalHeaders);
        headers.put("Accept", accept);
        if (body.length > 0) headers.put("Content-Type", "application/json; charset=utf-8");
        String url = origin + path + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        return transport.execute(method, url, AdminJava8Collections.copyMap(headers), body.length == 0 ? null : body);
    }

    private static AdminOperationsApi.SessionContext requireSession(
        AdminOperationsApi.SessionContext session
    ) throws IOException {
        if (session == null) throw new IOException("administrator session is unavailable");
        String token = session.accessToken();
        if (token == null || token.length() < 32 || token.length() > 512
            || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException("administrator access token is unavailable");
        }
        if (session.adminId() == null
            || !session.adminId().matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IOException("administrator identity is unavailable");
        }
        try {
            AdminReportModels.canonicalUuid(session.sessionId(), "session_id");
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

    private static void add(
        List<AdminCanonicalEncoding.QueryParameter> parameters,
        String name,
        String value
    ) {
        if (value != null) parameters.add(new AdminCanonicalEncoding.QueryParameter(name, value));
    }

    private static void requireStatus(Response response, int expected) throws IOException {
        if (response.statusCode != expected) {
            throw new IOException("administrator report request returned unexpected status=" + response.statusCode);
        }
    }

    private static String jsonBody(Response response) throws IOException {
        String contentType = response.headers.get("content-type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")
            || response.bytes.length > MAX_JSON_RESPONSE_BYTES) {
            throw new IOException("administrator JSON response is invalid");
        }
        return AdminStrictJson.decodeUtf8(response.bytes);
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

    private static String header(Response response, String name) throws IOException {
        String value = response.headers.get(name);
        if (value == null || value.trim().isEmpty() || value.length() > 160
            || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IOException("delivery package header is invalid: " + name);
        }
        return value;
    }

    private static int positiveIntHeader(Response response, String name) throws IOException {
        String value = header(response, name);
        if (!value.matches("[1-9][0-9]{0,9}")) throw new IOException("delivery package revision is invalid");
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new IOException("delivery package revision is invalid");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("delivery package revision is invalid", error);
        }
    }

    private static final class UrlConnectionTransport implements Transport {
        @Override
        public Response execute(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setUseCaches(false);
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
                byte[] bytes = stream == null ? new byte[0] : readBounded(stream);
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                connection.getHeaderFields().forEach((key, values) -> {
                    if (key != null && values != null && values.size() == 1) {
                        responseHeaders.put(key, values.get(0));
                    }
                });
                return new Response(status, bytes, responseHeaders);
            } finally {
                connection.disconnect();
            }
        }

        private static byte[] readBounded(InputStream input) throws IOException {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4_096];
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    if (output.size() + read > MAX_PACKAGE_RESPONSE_BYTES) {
                        throw new IOException("administrator report response is too large");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }
}
