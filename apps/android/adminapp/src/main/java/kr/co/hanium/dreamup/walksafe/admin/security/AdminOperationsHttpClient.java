package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Internal API client. It records manual delivery facts and never dispatches to institutions. */
public final class AdminOperationsHttpClient implements AdminOperationsApi {
    static final String CHALLENGE_PATH = "/admin/security/device-proof/challenges";
    static final String CORRELATION_ID_HEADER = "X-WalkSafe-Correlation-Id";
    static final String READ_PURPOSE_HEADER = "X-WalkSafe-Read-Purpose";
    static final String REVIEW_ACTION = "report.review.decide";
    static final String DELIVERY_ACTION = "report.delivery.create";
    static final String REVIEW_READ_PURPOSE = "report.review_decisions";
    static final String DELIVERY_READ_PURPOSE = "report.delivery_events";
    private static final int MAX_HISTORY_ITEMS = 256;

    private enum HistoryType {
        NONE,
        REVIEW,
        DELIVERY
    }

    interface Clock {
        long nowEpochMs();
    }

    interface Transport {
        Response execute(String method, String url, Map<String, String> headers, byte[] body) throws IOException;
    }

    static final class Response {
        final int statusCode;
        final String body;

        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }
    }

    private final String origin;
    private final String deviceKeyMarker;
    private final int deviceKeyVersion;
    private final AdminDeviceProof.Signer signer;
    private final Clock clock;
    private final Transport transport;

    public AdminOperationsHttpClient(
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

    AdminOperationsHttpClient(
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
    public Result recordReviewDecision(SessionContext session, String reportId, AdminReportDecision decision)
        throws IOException, GeneralSecurityException {
        if (decision == null) throw new IllegalArgumentException("review decision is required");
        String safeReportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
        return executeProtected(
            requireSession(session),
            "POST",
            "/reports/" + safeReportId + "/review-decisions",
            REVIEW_ACTION,
            null,
            decision.requestBody(safeReportId),
            HistoryType.NONE
        );
    }

    @Override
    public Result readReviewDecisions(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        String safeReportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
        return executeProtected(
            requireSession(session),
            "GET",
            "/reports/" + safeReportId + "/review-decisions",
            null,
            REVIEW_READ_PURPOSE,
            null,
            HistoryType.REVIEW
        );
    }

    @Override
    public Result recordDelivery(SessionContext session, String reportId, AdminInstitutionDelivery delivery)
        throws IOException, GeneralSecurityException {
        if (delivery == null) throw new IllegalArgumentException("delivery record is required");
        String safeReportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
        return executeProtected(
            requireSession(session),
            "POST",
            "/reports/" + safeReportId + "/deliveries",
            DELIVERY_ACTION,
            null,
            delivery.requestBody(),
            HistoryType.NONE
        );
    }

    @Override
    public Result readDeliveries(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        String safeReportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
        return executeProtected(
            requireSession(session),
            "GET",
            "/reports/" + safeReportId + "/deliveries",
            null,
            DELIVERY_READ_PURPOSE,
            null,
            HistoryType.DELIVERY
        );
    }

    private Result executeProtected(
        SessionContext session,
        String method,
        String path,
        String action,
        String readPurpose,
        byte[] body,
        HistoryType historyType
    ) throws IOException, GeneralSecurityException {
        String correlationId = UUID.randomUUID().toString();
        byte[] transmittedBody = body == null ? new byte[0] : body;
        String emptyQuery = AdminCanonicalEncoding.canonicalQuery(AdminJava8Collections.list());
        AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
            action,
            session.adminId(),
            AdminCanonicalEncoding.sha256Hex(transmittedBody),
            correlationId,
            session.deviceId(),
            deviceKeyMarker,
            deviceKeyVersion,
            method,
            path,
            AdminDeviceProof.Purpose.ACTION,
            AdminCanonicalEncoding.sha256Hex(emptyQuery.getBytes(StandardCharsets.UTF_8)),
            readPurpose,
            session.sessionId()
        );

        Map<String, String> challengeHeaders = protectedHeaders(session, correlationId, null);
        Response challengeResponse = transport.execute(
            "POST",
            origin + CHALLENGE_PATH,
            jsonHeaders(challengeHeaders),
            intent.challengeRequestBytes()
        );
        requireExactStatus(challengeResponse.statusCode, 200, "device challenge request");
        AdminDeviceProof.SignedChallenge proof = AdminDeviceProof.parseAndSign(
            challengeResponse.body,
            intent,
            clock.nowEpochMs(),
            signer
        );

        Map<String, String> operationHeaders = new LinkedHashMap<>(
            protectedHeaders(session, correlationId, readPurpose)
        );
        operationHeaders.putAll(proof.proofHeaders());
        Map<String, String> finalHeaders = body == null
            ? acceptHeaders(operationHeaders)
            : jsonHeaders(operationHeaders);
        Response operationResponse = transport.execute(
            method,
            origin + path,
            finalHeaders,
            body
        );
        requireExactStatus(
            operationResponse.statusCode,
            "POST".equals(method) ? 201 : 200,
            "administrator operation"
        );
        return switch (historyType) {
            case NONE -> new Result(correlationId, operationResponse.statusCode);
            case REVIEW -> Result.reviewHistory(
                correlationId,
                operationResponse.statusCode,
                parseReviewHistory(operationResponse.body, reportIdFromPath(path))
            );
            case DELIVERY -> Result.deliveryHistory(
                correlationId,
                operationResponse.statusCode,
                parseDeliveryHistory(operationResponse.body, reportIdFromPath(path))
            );
        };
    }

    private static SessionContext requireSession(SessionContext session) throws IOException {
        if (session == null) throw new IOException("administrator session is unavailable");
        requireOpaqueToken(session.accessToken());
        if (session.adminId() == null || !session.adminId().matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IOException("administrator identity is unavailable");
        }
        try {
            AdminReportDecision.canonicalUuid(session.sessionId(), "session_id");
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator session binding is unavailable");
        }
        if (session.deviceId() == null || !session.deviceId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IOException("administrator device binding is unavailable");
        }
        return session;
    }

    private static Map<String, String> protectedHeaders(
        SessionContext session,
        String correlationId,
        String readPurpose
    ) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + requireOpaqueToken(session.accessToken()));
        headers.put("X-WalkSafe-App-Kind", AdminSecurityHttpClient.APP_KIND);
        headers.put("X-WalkSafe-Role", AdminSecurityHttpClient.ROLE);
        headers.put("X-WalkSafe-Audience", AdminSecurityHttpClient.AUDIENCE);
        headers.put("X-WalkSafe-Device-Id", session.deviceId());
        headers.put(CORRELATION_ID_HEADER, correlationId);
        if (readPurpose != null) headers.put(READ_PURPOSE_HEADER, readPurpose);
        return AdminJava8Collections.copyMap(headers);
    }

    private static Map<String, String> acceptHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("Accept", "application/json");
        result.putAll(headers);
        return AdminJava8Collections.copyMap(result);
    }

    private static Map<String, String> jsonHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>(acceptHeaders(headers));
        result.put("Content-Type", "application/json; charset=utf-8");
        return AdminJava8Collections.copyMap(result);
    }

    private static String requireOpaqueToken(String token) throws IOException {
        if (token == null || token.length() < 32 || token.length() > 512
            || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException("administrator access token is unavailable");
        }
        return token;
    }

    private static void requireExactStatus(int actual, int expected, String operation) throws IOException {
        if (actual != expected) throw new IOException(operation + " returned unexpected status=" + actual);
    }

    private static List<ReviewHistoryItem> parseReviewHistory(String body, String expectedReportId)
        throws IOException {
        List<Map<String, Object>> array = historyArray(body);
        List<ReviewHistoryItem> result = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            Map<String, Object> item = array.get(index);
            requireExactKeys(item, AdminJava8Collections.set(
                "id", "report_id", "revision", "decision", "reason", "user_visible_reason",
                "duplicate_of_report_id",
                "location_reviewed", "photo_reviewed", "privacy_reviewed", "admin_id",
                "session_id", "device_id", "correlation_id", "decided_at", "created_at"
            ));
            requiredUuid(item, "id");
            String reportId = requiredUuid(item, "report_id");
            if (!expectedReportId.equals(reportId)) throw new IOException("review history report binding is invalid");
            int revision = requiredInt(item, "revision", 1);
            if (revision != index + 1) throw new IOException("review history revisions are not append-only");
            AdminReportDecision.Decision decision = reviewDecision(requiredText(item, "decision", 16));
            String reason = requiredText(item, "reason", 500);
            String userVisibleReason = nullableText(item, "user_visible_reason", 500);
            String duplicateId = nullableUuid(item, "duplicate_of_report_id");
            boolean locationReviewed = requiredBoolean(item, "location_reviewed");
            boolean photoReviewed = requiredBoolean(item, "photo_reviewed");
            boolean privacyReviewed = requiredBoolean(item, "privacy_reviewed");
            if (decision == AdminReportDecision.Decision.APPROVED
                && (duplicateId != null || !locationReviewed || !photoReviewed || !privacyReviewed)) {
                throw new IOException("approved review history evidence is invalid");
            }
            if (decision == AdminReportDecision.Decision.DUPLICATE) {
                if (duplicateId == null || duplicateId.equals(reportId)) {
                    throw new IOException("duplicate review history target is invalid");
                }
            } else if (duplicateId != null) {
                throw new IOException("review history duplicate target is invalid");
            }
            if ((decision == AdminReportDecision.Decision.APPROVED) != (userVisibleReason == null)) {
                throw new IOException("review history user-visible reason binding is invalid");
            }
            requiredAdminId(item, "admin_id");
            requiredUuid(item, "session_id");
            requiredDeviceId(item, "device_id");
            requiredUuid(item, "correlation_id");
            String decidedAt = requiredInstant(item, "decided_at", false);
            requiredInstant(item, "created_at", false);
            result.add(new ReviewHistoryItem(
                revision,
                decision,
                reason,
                userVisibleReason,
                duplicateId,
                decidedAt
            ));
        }
        return AdminJava8Collections.copyList(result);
    }

    private static List<DeliveryHistoryItem> parseDeliveryHistory(String body, String expectedReportId)
        throws IOException {
        List<Map<String, Object>> array = historyArray(body);
        List<DeliveryHistoryItem> result = new ArrayList<>(array.size());
        AdminInstitutionDelivery.Status previousStatus = null;
        for (int index = 0; index < array.size(); index++) {
            Map<String, Object> item = array.get(index);
            requireExactKeys(item, AdminJava8Collections.set(
                "id", "report_id", "review_decision_id", "package_id", "package_revision",
                "revision", "institution", "channel",
                "recipient", "status", "external_receipt_id", "reason", "evidence_sha256",
                "observed_at", "expected_revision", "idempotency_key", "admin_id", "session_id",
                "device_id", "correlation_id", "recorded_at"
            ));
            requiredUuid(item, "id");
            String reportId = requiredUuid(item, "report_id");
            if (!expectedReportId.equals(reportId)) throw new IOException("delivery history report binding is invalid");
            requiredUuid(item, "review_decision_id");
            requiredUuid(item, "package_id");
            int packageRevision = requiredInt(item, "package_revision", 1);
            int revision = requiredInt(item, "revision", 1);
            if (revision != index + 1) throw new IOException("delivery history revisions are not append-only");
            String institution = requiredText(item, "institution", 160);
            requiredText(item, "channel", 32);
            requiredText(item, "recipient", 255);
            AdminInstitutionDelivery.Status status = deliveryStatus(requiredText(item, "status", 16));
            if (!validDeliveryTransition(previousStatus, status)) {
                throw new IOException("delivery history transition is invalid");
            }
            previousStatus = status;
            String receipt = nullableText(item, "external_receipt_id", 160);
            if ((status == AdminInstitutionDelivery.Status.ACKNOWLEDGED
                || status == AdminInstitutionDelivery.Status.RESOLVED) && receipt == null) {
                throw new IOException("confirmed delivery history requires external receipt id");
            }
            requiredText(item, "reason", 500);
            String evidenceSha = nullableText(item, "evidence_sha256", 64);
            if (evidenceSha != null && !evidenceSha.matches("[0-9a-f]{64}")) {
                throw new IOException("delivery history evidence hash is invalid");
            }
            String observedAt = requiredInstant(item, "observed_at", true);
            int expectedRevision = requiredInt(item, "expected_revision", 0);
            if (expectedRevision != revision - 1) {
                throw new IOException("delivery history expected revision is invalid");
            }
            requiredUuid(item, "idempotency_key");
            requiredAdminId(item, "admin_id");
            requiredUuid(item, "session_id");
            requiredDeviceId(item, "device_id");
            requiredUuid(item, "correlation_id");
            String recordedAt = requiredInstant(item, "recorded_at", false);
            result.add(new DeliveryHistoryItem(
                revision,
                packageRevision,
                status,
                receipt,
                institution,
                observedAt,
                recordedAt
            ));
        }
        return AdminJava8Collections.copyList(result);
    }

    private static List<Map<String, Object>> historyArray(String body) throws IOException {
        List<Map<String, Object>> array = AdminStrictJson.parseObjectArray(body);
        if (array.size() > MAX_HISTORY_ITEMS) throw new IOException("administrator history is too large");
        return array;
    }

    private static String reportIdFromPath(String path) throws IOException {
        int start = "/reports/".length();
        int end = path.indexOf('/', start);
        if (!path.startsWith("/reports/") || end < 0) throw new IOException("history path is invalid");
        try {
            return AdminReportDecision.canonicalUuid(path.substring(start, end), "report_id");
        } catch (IllegalArgumentException error) {
            throw new IOException("history path is invalid", error);
        }
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> expected) throws IOException {
        if (!value.keySet().equals(expected)) {
            throw new IOException("administrator history fields do not match the contract");
        }
    }

    private static String requiredText(Map<String, Object> value, String key, int maxLength) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.trim().isEmpty() || text.length() > maxLength
            || text.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IOException("administrator history text is invalid: " + key);
        }
        return text;
    }

    private static String nullableText(Map<String, Object> value, String key, int maxLength) throws IOException {
        Object raw = value.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String text) || text.trim().isEmpty() || text.length() > maxLength
            || text.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IOException("administrator history nullable text is invalid: " + key);
        }
        return text;
    }

    private static int requiredInt(Map<String, Object> value, String key, int minimum) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IOException("administrator history integer is invalid: " + key);
        }
        long parsed = ((Number) raw).longValue();
        if (parsed < minimum || parsed > Integer.MAX_VALUE) {
            throw new IOException("administrator history integer is invalid: " + key);
        }
        return (int) parsed;
    }

    private static boolean requiredBoolean(Map<String, Object> value, String key) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Boolean parsed)) throw new IOException("administrator history flag is invalid: " + key);
        return parsed;
    }

    private static String requiredUuid(Map<String, Object> value, String key) throws IOException {
        String text = requiredText(value, key, 36);
        try {
            return AdminReportDecision.canonicalUuid(text, key);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator history UUID is invalid: " + key, error);
        }
    }

    private static String nullableUuid(Map<String, Object> value, String key) throws IOException {
        String text = nullableText(value, key, 36);
        if (text == null) return null;
        try {
            return AdminReportDecision.canonicalUuid(text, key);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator history UUID is invalid: " + key, error);
        }
    }

    private static String requiredAdminId(Map<String, Object> value, String key) throws IOException {
        String text = requiredText(value, key, 64);
        if (!text.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IOException("administrator history admin identity is invalid");
        }
        return text;
    }

    private static String requiredDeviceId(Map<String, Object> value, String key) throws IOException {
        String text = requiredText(value, key, 128);
        if (!text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IOException("administrator history device identity is invalid");
        }
        return text;
    }

    private static String requiredInstant(Map<String, Object> value, String key, boolean requireUtc) throws IOException {
        String text = requiredText(value, key, 64);
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(text);
            if (requireUtc && parsed.getOffset().getTotalSeconds() != 0) {
                throw new IOException("administrator history UTC instant is invalid: " + key);
            }
            return parsed.toInstant().toString();
        } catch (DateTimeException error) {
            throw new IOException("administrator history instant is invalid: " + key, error);
        }
    }

    private static AdminReportDecision.Decision reviewDecision(String value) throws IOException {
        try {
            return AdminReportDecision.Decision.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator review decision is invalid", error);
        }
    }

    private static AdminInstitutionDelivery.Status deliveryStatus(String value) throws IOException {
        try {
            return AdminInstitutionDelivery.Status.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator delivery status is invalid", error);
        }
    }

    private static boolean validDeliveryTransition(
        AdminInstitutionDelivery.Status previous,
        AdminInstitutionDelivery.Status next
    ) {
        if (previous == null) {
            return next == AdminInstitutionDelivery.Status.SUBMITTED
                || next == AdminInstitutionDelivery.Status.FAILED;
        }
        return switch (previous) {
            case FAILED -> next == AdminInstitutionDelivery.Status.FAILED
                || next == AdminInstitutionDelivery.Status.SUBMITTED;
            case SUBMITTED -> next == AdminInstitutionDelivery.Status.ACKNOWLEDGED
                || next == AdminInstitutionDelivery.Status.FAILED;
            case ACKNOWLEDGED -> next == AdminInstitutionDelivery.Status.RESOLVED;
            case RESOLVED -> false;
        };
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
                return new Response(status, stream == null ? "" : readBounded(stream));
            } finally {
                connection.disconnect();
            }
        }

        private static String readBounded(InputStream input) throws IOException {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4_096];
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    if (output.size() + read > 64 * 1_024) {
                        throw new IOException("administrator API response is too large");
                    }
                    output.write(buffer, 0, read);
                }
                return AdminStrictJson.decodeUtf8(output.toByteArray());
            }
        }
    }
}
