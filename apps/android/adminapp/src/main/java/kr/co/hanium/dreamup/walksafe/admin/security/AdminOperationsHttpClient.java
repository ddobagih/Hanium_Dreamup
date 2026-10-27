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
import java.util.Arrays;
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
    static final String DELIVERY_CORRECTION_ACTION = "report.delivery.correct";
    static final String DELIVERY_CORRECTION_ID_CONFLICT = "delivery_correction_idempotency_conflict";
    static final String ORIGINAL_GRANT_ACTION = "report.original.grant";
    static final String ORIGINAL_ACCESS_GRANT_HEADER = "X-WalkSafe-Original-Access-Grant";
    static final String REVIEW_READ_PURPOSE = "report.review_decisions";
    static final String DELIVERY_READ_PURPOSE = "report.delivery_events";
    private static final int HISTORY_PAGE_SIZE = 25;
    private static final int MAX_HISTORY_RESPONSE_BYTES = 48 * 1024;
    private static final int MAX_CONSECUTIVE_ZERO_READS = 3;

    private enum HistoryType {
        NONE,
        REVIEW,
        DELIVERY,
        DECISION_LOOKUP
    }

    interface Clock extends AdminProtectedRequestExecutor.Clock {
        long nowEpochMs();

        default long monotonicNanos() { return System.nanoTime(); }
    }

    interface Transport extends AdminProtectedRequestExecutor.Exchange<Response> {
        Response execute(String method, String url, Map<String, String> headers, byte[] body) throws IOException;

        default BinaryResponse executeBinary(
            String method,
            String url,
            Map<String, String> headers,
            int expectedByteCount
        ) throws IOException {
            throw new IOException("binary administrator transport is unavailable");
        }

        default BinaryResponse executeBinary(SessionContext session, String method, String url,
            Map<String, String> headers, int expectedByteCount) throws IOException {
            return executeBinary(method, url, headers, expectedByteCount);
        }

        /** S18-18: explicit cancellation of requests still in flight; never waits. */
        default void cancelInFlight() {}
    }

    static final class Response {
        final int statusCode;
        final String body;

        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }
    }

    static final class BinaryResponse implements AutoCloseable {
        final int statusCode;
        final String contentType;
        final long declaredLength;
        private byte[] body;

        BinaryResponse(int statusCode, String contentType, long declaredLength, byte[] ownedBody) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.declaredLength = declaredLength;
            this.body = ownedBody == null ? new byte[0] : ownedBody;
        }

        synchronized byte[] takeBody() throws IOException {
            if (body == null) throw new IOException("binary administrator response was already consumed");
            byte[] taken = body;
            body = null;
            return taken;
        }

        @Override
        public synchronized void close() {
            if (body != null) Arrays.fill(body, (byte) 0);
            body = null;
        }
    }

    private final String origin;
    private final Clock clock;
    private final Transport transport;
    private final AdminProtectedRequestExecutor<Response> protectedRequests;

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
        this.clock = clock;
        this.transport = transport;
        this.protectedRequests = new AdminProtectedRequestExecutor<>(
            this.origin, deviceKeyMarker, deviceKeyVersion, signer, clock, transport,
            response -> {
                requireExactStatus(response.statusCode, 200, "device challenge request");
                return response.body;
            }
        );
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
            "",
            REVIEW_ACTION,
            null,
            decision.requestBody(safeReportId),
            HistoryType.NONE,
            decision.decisionId()
        );
    }

    @Override
    public Result readReviewDecisions(SessionContext session, String reportId, String cursor)
        throws IOException, GeneralSecurityException {
        String safeReportId = canonicalHistoryUuid(reportId);
        String query = historyQuery(cursor);
        Result result = executeProtected(
            requireSession(session),
            "GET",
            "/reports/" + safeReportId + "/review-decisions/history",
            query,
            null,
            REVIEW_READ_PURPOSE,
            null,
            HistoryType.REVIEW,
            null
        );
        requireFirstHistoryRevision(result, cursor);
        return result;
    }

    @Override
    public Result readRecentReviewDecisions(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        String safeReportId = canonicalHistoryUuid(reportId);
        Result result = executeProtected(
            requireSession(session),
            "GET",
            "/reports/" + safeReportId + "/review-decisions/history",
            recentHistoryQuery(),
            null,
            REVIEW_READ_PURPOSE,
            null,
            HistoryType.REVIEW,
            null
        );
        requireRecentHistoryWindow(result);
        return result;
    }

    @Override
    public Result lookupReviewDecision(SessionContext session, String reportId, String decisionId)
        throws IOException, GeneralSecurityException {
        String safeReportId = canonicalHistoryUuid(reportId);
        String safeDecisionId = AdminReportDecision.canonicalUuid(decisionId, "decision_id");
        return executeProtected(
            requireSession(session),
            "GET",
            "/reports/" + safeReportId + "/review-decisions/" + safeDecisionId,
            "",
            null,
            REVIEW_READ_PURPOSE,
            null,
            HistoryType.DECISION_LOOKUP,
            safeDecisionId
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
            "",
            DELIVERY_ACTION,
            null,
            delivery.requestBody(),
            HistoryType.NONE,
            null
        );
    }

    @Override
    public Result readDeliveries(SessionContext session, String reportId, String cursor)
        throws IOException, GeneralSecurityException {
        String safeReportId = canonicalHistoryUuid(reportId);
        String query = historyQuery(cursor);
        Result result = executeProtected(
            requireSession(session),
            "GET",
            "/reports/" + safeReportId + "/deliveries/history",
            query,
            null,
            DELIVERY_READ_PURPOSE,
            null,
            HistoryType.DELIVERY,
            null
        );
        requireFirstHistoryRevision(result, cursor);
        return result;
    }

    @Override
    public Result readRecentDeliveries(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        String safeReportId = canonicalHistoryUuid(reportId);
        Result result = executeProtected(
            requireSession(session),
            "GET",
            "/reports/" + safeReportId + "/deliveries/history",
            recentHistoryQuery(),
            null,
            DELIVERY_READ_PURPOSE,
            null,
            HistoryType.DELIVERY,
            null
        );
        requireRecentHistoryWindow(result);
        return result;
    }

    /**
     * S18-40: records a manual delivery correction (HIGH: device proof + one reconfirmation).
     * This call only records that an earlier observation was wrong; nothing is sent to an
     * institution and the corrected event stays in the history.
     */
    @Override
    public Result recordDeliveryCorrection(
        SessionContext session,
        String reportId,
        AdminDeliveryCorrection correction,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        return recordDeliveryCorrection(session, reportId, correction, reconfirmationHeaders, false);
    }

    @Override
    public Result recordDeliveryCorrection(
        SessionContext session,
        String reportId,
        AdminDeliveryCorrection correction,
        Map<String, String> reconfirmationHeaders,
        boolean resendOfUncertainAttempt
    ) throws IOException, GeneralSecurityException {
        if (correction == null) throw new IllegalArgumentException("delivery correction is required");
        SessionContext safeSession = requireSession(session);
        String safeReportId = canonicalHistoryUuid(reportId);
        Map<String, String> safeReconfirmation = requireReconfirmation(reconfirmationHeaders);
        String correctionPath = "/reports/" + safeReportId + "/delivery-corrections";
        byte[] body = correction.requestBody();
        AdminProtectedRequestExecutor.Result<Response> executed = protectedRequests.execute(
            safeSession, "POST", correctionPath, "", DELIVERY_CORRECTION_ACTION, null,
            body, safeReconfirmation, "application/json", null);
        String correlationId = executed.correlationId;
        Response response = executed.response;
        if (response.statusCode == 409) {
            // Review F3: an ID reused for another intent is not a state conflict; only a new
            // ID can proceed, so the caller must be able to tell the two apart.
            if (DELIVERY_CORRECTION_ID_CONFLICT.equals(errorCode(response.body))) {
                throw new CorrectionIdConflictException();
            }
            throw new MutationConflictException();
        }
        requireExactStatus(response.statusCode, 201, "delivery correction request");
        return new Result(
            correlationId,
            response.statusCode,
            requireDeliveryCorrectionResponse(
                response.body, safeReportId, correction, safeSession, resendOfUncertainAttempt
            )
        );
    }

    /**
     * The stored correction must carry exactly the requested intent and administrator. The
     * session must be the current one for a first send; a resend of an uncertain attempt may
     * receive the earlier stored record (EXISTING) from an earlier session (review F3).
     */
    private static String requireDeliveryCorrectionResponse(
        String body,
        String reportId,
        AdminDeliveryCorrection correction,
        SessionContext session,
        boolean resendOfUncertainAttempt
    ) throws IOException {
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_HISTORY_RESPONSE_BYTES) {
            throw new IOException("delivery correction response is too large");
        }
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        long correctedRevision = requiredLong(root, "corrected_revision", 1L);
        DeliveryCorrection stored = parseDeliveryCorrection(
            root, reportId, correction.correctsEventId(), correctedRevision
        );
        if (!correction.correctionId().equals(stored.id())
            || stored.expectedRevision() != correction.expectedRevision()
            || stored.reason() != correction.reason()
            || !correction.memo().equals(stored.memo())
            || !session.adminId().equals(stored.adminId())) {
            throw new IOException("delivery correction response does not match the request");
        }
        String storedSessionId = requiredUuid(root, "session_id");
        if (!resendOfUncertainAttempt && !session.sessionId().equals(storedSessionId)) {
            throw new IOException("delivery correction response does not match the session");
        }
        return stored.id();
    }

    @Override
    public AdminOriginalEvidence loadOriginalEvidence(
        SessionContext session,
        String reportId,
        int expectedContentRevision,
        String reason,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        requireOriginalEvidenceRequestActive();
        SessionContext safeSession = requireSession(session);
        String safeReportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
        if (expectedContentRevision < 0) {
            throw new IllegalArgumentException("expected content revision is invalid");
        }
        String normalizedReason = normalizedGrantReason(reason);
        Map<String, String> safeReconfirmation = requireReconfirmation(reconfirmationHeaders);
        String grantPath = "/reports/" + safeReportId + "/original-access-grants";
        Map<String, Object> grantFields = new LinkedHashMap<>();
        grantFields.put("purpose", AdminOriginalEvidence.PURPOSE);
        grantFields.put("reason", normalizedReason);
        grantFields.put("expected_content_revision", expectedContentRevision);
        byte[] body = AdminCanonicalEncoding.canonicalJsonBytes(grantFields);
        AdminProtectedRequestExecutor.Result<Response> executed = protectedRequests.execute(
            safeSession, "POST", grantPath, "", ORIGINAL_GRANT_ACTION, null,
            body, safeReconfirmation, "application/json",
            AdminOperationsHttpClient::requireOriginalEvidenceRequestActive, null);
        Response grantResponse = executed.response;
        requireOriginalEvidenceRequestActive();
        requireExactStatus(grantResponse.statusCode, 201, "original evidence grant request");

        try (AdminOriginalEvidence.IssuedGrant grant = parseOriginalGrant(
            grantResponse.body,
            safeReportId,
            safeSession.sessionId(),
            safeSession.deviceId(),
            expectedContentRevision,
            clock.nowEpochMs()
        )) {
            char[] accessToken = grant.consumeAccessToken(clock.nowEpochMs());
            try {
                requireOriginalEvidenceRequestActive();
                Map<String, String> imageHeaders = new LinkedHashMap<>(
                    protectedHeaders(safeSession, UUID.randomUUID().toString(), null)
                );
                imageHeaders.put("Accept", grant.contentType());
                imageHeaders.put("Cache-Control", "no-store");
                imageHeaders.put("Pragma", "no-cache");
                imageHeaders.put(ORIGINAL_ACCESS_GRANT_HEADER, new String(accessToken));
                try (BinaryResponse imageResponse = transport.executeBinary(
                    safeSession, "GET",
                    origin + grant.resourcePath(),
                    AdminJava8Collections.copyMap(imageHeaders),
                    grant.byteCount()
                )) {
                    return AdminOriginalEvidence.verifyAndTake(
                        grant,
                        imageResponse.statusCode,
                        imageResponse.contentType,
                        imageResponse.declaredLength,
                        imageResponse.takeBody(),
                        clock.nowEpochMs()
                    );
                }
            } catch (IOException | RuntimeException error) {
                // S20-54: the token is spent from here on. A lost or rejected image response
                // never revives it; the caller must obtain a new purpose-limited grant.
                throw new AdminOperationsApi.OriginalEvidenceNotReceivedException(error);
            } finally {
                Arrays.fill(accessToken, '\0');
            }
        }
    }

    private Result executeProtected(
        SessionContext session,
        String method,
        String path,
        String canonicalQuery,
        String action,
        String readPurpose,
        byte[] body,
        HistoryType historyType,
        String expectedReviewDecisionId
    ) throws IOException, GeneralSecurityException {
        Map<String, String> additionalHeaders = new LinkedHashMap<>();
        if (historyType != HistoryType.NONE) {
            additionalHeaders.put("Cache-Control", "no-store");
            additionalHeaders.put("Pragma", "no-cache");
        }
        AdminProtectedRequestExecutor.Result<Response> executed = protectedRequests.execute(
            session, method, path, canonicalQuery, action, readPurpose, body,
            additionalHeaders, "application/json", null);
        String correlationId = executed.correlationId;
        Response operationResponse = executed.response;
        if (historyType == HistoryType.DECISION_LOOKUP && operationResponse.statusCode == 404) {
            // Only the explicit "no such decision" answer resolves the attempt as absent.
            if ("review_decision_not_found".equals(errorCode(operationResponse.body))) {
                return new Result(correlationId, 404, null);
            }
            throw new HistoryNotFoundException();
        }
        if (historyType != HistoryType.NONE && operationResponse.statusCode == 404) {
            throw new HistoryNotFoundException();
        }
        if (historyType != HistoryType.NONE && operationResponse.statusCode == 422) {
            throw new HistoryCursorException();
        }
        if (historyType == HistoryType.NONE && operationResponse.statusCode == 409) {
            throw new MutationConflictException(
                "delivery_institution_mismatch".equals(errorCode(operationResponse.body))
            );
        }
        requireExactStatus(
            operationResponse.statusCode,
            "POST".equals(method) ? 201 : 200,
            "administrator operation"
        );
        return switch (historyType) {
            case NONE -> new Result(
                correlationId,
                operationResponse.statusCode,
                expectedReviewDecisionId == null
                    ? null
                    : requireReviewDecisionResponseId(
                        operationResponse.body,
                        reportIdFromPath(path),
                        expectedReviewDecisionId,
                        session.adminId()
                    )
            );
            case REVIEW -> parseReviewHistory(
                operationResponse.body, reportIdFromPath(path),
                correlationId, operationResponse.statusCode
            );
            case DELIVERY -> parseDeliveryHistory(
                operationResponse.body, reportIdFromPath(path),
                correlationId, operationResponse.statusCode
            );
            case DECISION_LOOKUP -> new Result(
                correlationId,
                operationResponse.statusCode,
                requireReviewDecisionResponseId(
                    operationResponse.body,
                    reportIdFromPath(path),
                    expectedReviewDecisionId,
                    session.adminId()
                )
            );
        };
    }

    private static String errorCode(String body) {
        try {
            Map<String, Object> root = AdminStrictJson.parseObject(body);
            Object detail = root.get("detail");
            if (!(detail instanceof Map<?, ?> detailMap)) return null;
            Object code = detailMap.get("code");
            return code instanceof String text ? text : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String historyQuery(String cursor) {
        List<AdminCanonicalEncoding.QueryParameter> parameters = new ArrayList<>();
        parameters.add(new AdminCanonicalEncoding.QueryParameter(
            "limit", Integer.toString(HISTORY_PAGE_SIZE)
        ));
        if (cursor != null) {
            if (!cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
                throw new IllegalArgumentException("administrator history cursor is invalid");
            }
            parameters.add(new AdminCanonicalEncoding.QueryParameter("cursor", cursor));
        }
        return AdminCanonicalEncoding.canonicalQuery(parameters);
    }

    private static String recentHistoryQuery() {
        return AdminCanonicalEncoding.canonicalQuery(AdminJava8Collections.list(
            new AdminCanonicalEncoding.QueryParameter("limit", Integer.toString(HISTORY_PAGE_SIZE)),
            new AdminCanonicalEncoding.QueryParameter("position", "last")
        ));
    }

    private static void requireRecentHistoryWindow(Result result) throws IOException {
        if (result.nextCursor() != null) {
            throw new IOException("recent history must have no next cursor");
        }
        long total = result.totalCount();
        long count = result.returnedItemCount();
        if (count > HISTORY_PAGE_SIZE || (total == 0L) != (count == 0L)
            || (total > 0L && count > total)) {
            throw new IOException("recent history size is inconsistent");
        }
        if (count == 0L) return;
        long first = result.kind() == ResultKind.REVIEW_HISTORY
            ? result.reviewHistory().get(0).revision()
            : result.deliveryHistory().get(0).revision();
        if (first != result.snapshotRevision() - count + 1L) {
            throw new IOException("recent history does not start at latest suffix");
        }
    }

    private static AdminOriginalEvidence.IssuedGrant parseOriginalGrant(
        String body,
        String expectedReportId,
        String sessionId,
        String deviceId,
        int expectedContentRevision,
        long nowEpochMs
    ) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        requireExactKeys(root, AdminJava8Collections.set(
            "schema_version", "grant_id", "content_revision", "expires_at",
            "exact_location", "image"
        ));
        if (!AdminOriginalEvidence.SCHEMA_VERSION.equals(requiredText(root, "schema_version", 64))) {
            throw new IOException("original evidence grant schema is unsupported");
        }
        String grantId = requiredUuid(root, "grant_id");
        int contentRevision = requiredInt(root, "content_revision", 0);
        if (contentRevision != expectedContentRevision) {
            throw new IOException("original evidence grant content revision is mismatched");
        }
        String expiresAt = requiredText(root, "expires_at", 64);
        Map<String, Object> exactLocation = requiredObject(root, "exact_location");
        requireExactKeys(exactLocation, AdminJava8Collections.set("lat", "lon", "accuracy"));
        double latitude = requiredFiniteNumber(exactLocation, "lat", -90d, 90d);
        double longitude = requiredFiniteNumber(exactLocation, "lon", -180d, 180d);
        Double accuracy = nullableFiniteNumber(exactLocation, "accuracy", 0d, Double.MAX_VALUE);
        Map<String, Object> image = requiredObject(root, "image");
        requireExactKeys(image, AdminJava8Collections.set(
            "resource_path", "content_type", "sha256", "byte_count", "access_token"
        ));
        return new AdminOriginalEvidence.IssuedGrant(
            expectedReportId,
            sessionId,
            deviceId,
            grantId,
            contentRevision,
            expiresAt,
            latitude,
            longitude,
            accuracy,
            requiredText(image, "resource_path", 255),
            requiredText(image, "content_type", 32),
            requiredText(image, "sha256", 64),
            requiredInt(image, "byte_count", 1),
            requiredText(image, "access_token", 43),
            nowEpochMs
        );
    }

    private static Map<String, String> requireReconfirmation(Map<String, String> headers) {
        if (headers == null
            || headers.size() != 1
            || !headers.containsKey(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER)) {
            throw new IllegalArgumentException("exact reconfirmation header is required");
        }
        String nonce = headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER);
        if (!AdminHighRiskActionGate.isCanonicalNonce(nonce)) {
            throw new IllegalArgumentException("reconfirmation nonce is invalid");
        }
        return AdminJava8Collections.map(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER, nonce);
    }

    /** S18-18: not synchronized; it only signals the transport, which never waits. */
    @Override
    public void cancelInFlightRequests() {
        transport.cancelInFlight();
    }

    private static void requireOriginalEvidenceRequestActive() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("original evidence request was cancelled");
        }
    }

    static byte[] readOriginalEvidenceBody(InputStream input, int expectedByteCount) throws IOException {
        return readOriginalEvidenceBody(input, expectedByteCount, null);
    }

    /** {@code deadline} is the request's overall deadline (S18-19), checked around every read. */
    private static byte[] readOriginalEvidenceBody(
        InputStream input,
        int expectedByteCount,
        AdminHttpRequestDeadline.Owner deadline
    ) throws IOException {
        if (input == null
            || expectedByteCount < 1
            || expectedByteCount > AdminOriginalEvidence.MAX_IMAGE_BYTES) {
            throw new IOException("original evidence response length is invalid");
        }
        byte[] content = new byte[expectedByteCount];
        byte[] buffer = new byte[Math.min(4_096, expectedByteCount)];
        boolean succeeded = false;
        try {
            try (input) {
                int offset = 0;
                int zeroReads = 0;
                while (offset < content.length) {
                    requireOriginalEvidenceRequestActive();
                    if (deadline != null) deadline.check();
                    int read = input.read(buffer, 0, Math.min(buffer.length, content.length - offset));
                    requireOriginalEvidenceRequestActive();
                    if (deadline != null) deadline.check();
                    if (read < 0) throw new IOException("original evidence response ended early");
                    if (read == 0) {
                        if (++zeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                            throw new IOException("original evidence response made no progress");
                        }
                        continue;
                    }
                    zeroReads = 0;
                    System.arraycopy(buffer, 0, content, offset, read);
                    offset += read;
                }
                requireOriginalEvidenceRequestActive();
                if (deadline != null) deadline.check();
                int trailing = input.read();
                requireOriginalEvidenceRequestActive();
                if (deadline != null) deadline.check();
                if (trailing != -1) {
                    throw new IOException("original evidence response is longer than declared");
                }
            }
            requireOriginalEvidenceRequestActive();
            if (deadline != null) deadline.check();
            succeeded = true;
            return content;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            if (!succeeded) Arrays.fill(content, (byte) 0);
        }
    }

    static String readBoundedResponse(InputStream input) throws IOException {
        return readBoundedResponse(input, null);
    }

    /** {@code deadline} is the request's overall deadline (S18-19), checked around every read. */
    private static String readBoundedResponse(
        InputStream input,
        AdminHttpRequestDeadline.Owner deadline
    ) throws IOException {
        byte[] buffer = new byte[4_096];
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            int zeroReads = 0;
            try {
                while (true) {
                    requireResponseReadActive();
                    if (deadline != null) deadline.check();
                    int read = input.read(buffer);
                    requireResponseReadActive();
                    if (deadline != null) deadline.check();
                    if (read < 0) break;
                    if (read == 0) {
                        if (++zeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                            throw new IOException("administrator API response made no progress");
                        }
                        continue;
                    }
                    zeroReads = 0;
                    if (output.size() + read > 64 * 1_024) {
                        throw new IOException("administrator API response is too large");
                    }
                    output.write(buffer, 0, read);
                }
                return AdminStrictJson.decodeUtf8(output.toByteArray());
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        }
    }

    private static void requireResponseReadActive() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("administrator API response read was cancelled");
        }
    }

    private static String normalizedGrantReason(String value) {
        if (value == null) throw new IllegalArgumentException("original evidence reason is required");
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 8 || normalized.length() > 500
            || normalized.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("original evidence reason is invalid");
        }
        return normalized;
    }

    private static Map<String, Object> requiredObject(Map<String, Object> value, String key)
        throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Map<?, ?> object)) {
            throw new IOException("original evidence object is invalid: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String text)) {
                throw new IOException("original evidence object key is invalid: " + key);
            }
            result.put(text, entry.getValue());
        }
        return AdminJava8Collections.copyMap(result);
    }

    private static double requiredFiniteNumber(
        Map<String, Object> value,
        String key,
        double minimum,
        double maximum
    ) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) {
            throw new IOException("original evidence number is invalid: " + key);
        }
        double parsed = number.doubleValue();
        if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum) {
            throw new IOException("original evidence number is invalid: " + key);
        }
        return parsed;
    }

    private static Double nullableFiniteNumber(
        Map<String, Object> value,
        String key,
        double minimum,
        double maximum
    ) throws IOException {
        if (value.get(key) == null) return null;
        return requiredFiniteNumber(value, key, minimum, maximum);
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

    private static String requireReviewDecisionResponseId(
        String body,
        String expectedReportId,
        String expectedDecisionId,
        String expectedAdminId
    ) throws IOException {
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_HISTORY_RESPONSE_BYTES) {
            throw new IOException("administrator review response is too large");
        }
        Map<String, Object> value = AdminStrictJson.parseObject(body);
        requireExactKeys(value, AdminJava8Collections.set(
            "id", "report_id", "revision", "content_revision", "decision", "reason",
            "user_visible_reason", "duplicate_of_report_id", "location_reviewed",
            "photo_reviewed", "privacy_reviewed", "admin_id", "session_id", "device_id",
            "correlation_id", "decided_at", "created_at"
        ));
        String decisionId = requiredUuid(value, "id");
        if (!expectedDecisionId.equals(decisionId)
            || !expectedReportId.equals(requiredUuid(value, "report_id"))
            || !expectedAdminId.equals(requiredAdminId(value, "admin_id"))) {
            throw new IOException("administrator review response binding is invalid");
        }
        requiredLong(value, "revision", 1L);
        requiredLong(value, "content_revision", 0L);
        reviewDecision(requiredText(value, "decision", 16));
        requiredText(value, "reason", 500);
        nullableText(value, "user_visible_reason", 500);
        nullableUuid(value, "duplicate_of_report_id");
        requiredBoolean(value, "location_reviewed");
        requiredBoolean(value, "photo_reviewed");
        requiredBoolean(value, "privacy_reviewed");
        requiredUuid(value, "session_id");
        requiredDeviceId(value, "device_id");
        requiredUuid(value, "correlation_id");
        requiredInstant(value, "decided_at", false);
        requiredInstant(value, "created_at", false);
        return decisionId;
    }

    private static Result parseReviewHistory(
        String body,
        String expectedReportId,
        String correlationId,
        int statusCode
    ) throws IOException {
        HistoryEnvelope envelope = historyEnvelope(
            body, expectedReportId, "walksafe.report-review-decision-page.v1"
        );
        List<Map<String, Object>> array = envelope.items;
        List<ReviewHistoryItem> result = new ArrayList<>(array.size());
        long previousRevision = 0L;
        for (int index = 0; index < array.size(); index++) {
            Map<String, Object> item = array.get(index);
            requireExactKeys(item, AdminJava8Collections.set(
                "id", "report_id", "revision", "content_revision", "decision", "reason", "user_visible_reason",
                "duplicate_of_report_id",
                "location_reviewed", "photo_reviewed", "privacy_reviewed", "admin_id",
                "session_id", "device_id", "correlation_id", "decided_at", "created_at"
            ));
            requiredUuid(item, "id");
            String reportId = requiredUuid(item, "report_id");
            if (!expectedReportId.equals(reportId)) throw new IOException("review history report binding is invalid");
            long revision = requiredLong(item, "revision", 1L);
            if (index > 0 && revision != previousRevision + 1L) {
                throw new IOException("review history revisions are not contiguous");
            }
            previousRevision = revision;
            long contentRevision = requiredLong(item, "content_revision", 0L);
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
                contentRevision,
                decision,
                reason,
                userVisibleReason,
                duplicateId,
                decidedAt
            ));
        }
        validateHistoryTail(envelope, previousRevision);
        return Result.reviewHistory(
            correlationId, statusCode, envelope.reportId, envelope.snapshotRevision,
            envelope.totalCount, envelope.nextCursor, result
        );
    }

    private static Result parseDeliveryHistory(
        String body,
        String expectedReportId,
        String correlationId,
        int statusCode
    ) throws IOException {
        HistoryEnvelope envelope = historyEnvelope(
            body, expectedReportId, "walksafe.report-delivery-event-page.v1"
        );
        List<Map<String, Object>> array = envelope.items;
        List<DeliveryHistoryItem> result = new ArrayList<>(array.size());
        // S18-40: transitions are checked along the effective chain (uncorrected events).
        AdminInstitutionDelivery.Status previousStatus = null;
        String previousPackageId = null;
        Long previousPackageRevision = null;
        boolean previousEffectiveInPage = false;
        long previousRevision = 0L;
        for (int index = 0; index < array.size(); index++) {
            Map<String, Object> item = array.get(index);
            requireExactKeys(item, AdminJava8Collections.set(
                "id", "report_id", "review_decision_id", "package_id", "package_revision",
                "revision", "institution", "channel",
                "recipient", "status", "external_receipt_id", "reason", "evidence_sha256",
                "observed_at", "expected_revision", "idempotency_key", "admin_id", "session_id",
                "device_id", "correlation_id", "recorded_at", "correction"
            ));
            String eventId = requiredUuid(item, "id");
            String reportId = requiredUuid(item, "report_id");
            if (!expectedReportId.equals(reportId)) throw new IOException("delivery history report binding is invalid");
            requiredUuid(item, "review_decision_id");
            String packageId = nullableUuid(item, "package_id");
            Long packageRevision = nullableLong(item, "package_revision", 1L);
            if ((packageId == null) != (packageRevision == null)) {
                throw new IOException("delivery history package binding is invalid");
            }
            long revision = requiredLong(item, "revision", 1L);
            if (index > 0 && revision != previousRevision + 1L) {
                throw new IOException("delivery history revisions are not contiguous");
            }
            String institution = requiredText(item, "institution", 160);
            requiredText(item, "channel", 32);
            requiredText(item, "recipient", 255);
            AdminInstitutionDelivery.Status status = deliveryStatus(requiredText(item, "status", 16));
            DeliveryCorrection correction = parseDeliveryCorrection(
                item.get("correction"), expectedReportId, eventId, revision
            );
            if (correction == null) {
                if (previousEffectiveInPage) {
                    AdminInstitutionDelivery.Status transitionBase = sameDeliveryPackage(
                        previousPackageId, previousPackageRevision, packageId, packageRevision
                    ) ? previousStatus : null;
                    if (!validDeliveryTransition(transitionBase, status)) {
                        throw new IOException("delivery history transition is invalid");
                    }
                } else if (revision - index == 1L && !validDeliveryTransition(null, status)) {
                    // First effective event of a history page that starts at revision 1.
                    throw new IOException("delivery history initial status is invalid");
                }
                previousStatus = status;
                previousPackageId = packageId;
                previousPackageRevision = packageRevision;
                previousEffectiveInPage = true;
            }
            previousRevision = revision;
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
            long expectedRevision = requiredLong(item, "expected_revision", 0L);
            if (expectedRevision != revision - 1L) {
                throw new IOException("delivery history expected revision is invalid");
            }
            requiredUuid(item, "idempotency_key");
            requiredAdminId(item, "admin_id");
            requiredUuid(item, "session_id");
            requiredDeviceId(item, "device_id");
            requiredUuid(item, "correlation_id");
            String recordedAt = requiredInstant(item, "recorded_at", false);
            result.add(new DeliveryHistoryItem(
                eventId,
                revision,
                packageId,
                packageRevision,
                status,
                receipt,
                institution,
                observedAt,
                recordedAt,
                correction
            ));
        }
        requireCorrectionsStepBack(result);
        validateHistoryTail(envelope, previousRevision);
        return Result.deliveryHistory(
            correlationId, statusCode, envelope.reportId, envelope.snapshotRevision,
            envelope.totalCount, envelope.nextCursor, result
        );
    }

    /**
     * S18-40: the nullable correction object of one delivery item. It must name exactly this
     * event and revision, and it was recorded when the delivery history was at
     * expected_revision (never before the corrected event).
     */
    private static DeliveryCorrection parseDeliveryCorrection(
        Object raw,
        String expectedReportId,
        String eventId,
        long revision
    ) throws IOException {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IOException("delivery correction is invalid");
        }
        Map<String, Object> value = stringObject(map);
        requireExactKeys(value, AdminJava8Collections.set(
            "schema_version", "id", "report_id", "corrects_event_id", "corrected_revision",
            "expected_revision", "reason_code", "memo", "admin_id", "session_id", "device_id",
            "correlation_id", "created_at"
        ));
        if (!AdminDeliveryCorrection.SCHEMA_VERSION.equals(requiredText(value, "schema_version", 64))) {
            throw new IOException("delivery correction schema is unsupported");
        }
        String id = requiredUuid(value, "id");
        if (!expectedReportId.equals(requiredUuid(value, "report_id"))
            || !eventId.equals(requiredUuid(value, "corrects_event_id"))
            || requiredLong(value, "corrected_revision", 1L) != revision) {
            throw new IOException("delivery correction binding is invalid");
        }
        long expectedRevision = requiredLong(value, "expected_revision", revision);
        AdminDeliveryCorrection.Reason reason;
        try {
            reason = AdminDeliveryCorrection.reasonFromWire(requiredText(value, "reason_code", 32));
        } catch (IllegalArgumentException error) {
            throw new IOException("delivery correction reason is invalid", error);
        }
        String memo = requiredText(value, "memo", AdminDeliveryCorrection.MAX_MEMO_LENGTH);
        String adminId = requiredAdminId(value, "admin_id");
        requiredUuid(value, "session_id");
        requiredDeviceId(value, "device_id");
        requiredUuid(value, "correlation_id");
        String createdAt = requiredInstant(value, "created_at", false);
        return new DeliveryCorrection(id, revision, expectedRevision, reason, memo, adminId, createdAt);
    }

    /**
     * S18-40: only the latest effective event can be corrected, so every event between a
     * corrected event and the history revision at correction time must be corrected too.
     */
    public static void requireCorrectionsStepBack(List<DeliveryHistoryItem> items) throws IOException {
        for (DeliveryHistoryItem corrected : items) {
            if (corrected.correction() == null) continue;
            long until = corrected.correction().expectedRevision();
            for (DeliveryHistoryItem later : items) {
                if (later.revision() > corrected.revision()
                    && later.revision() <= until
                    && later.correction() == null) {
                    throw new IOException("delivery correction skipped a later effective event");
                }
            }
        }
    }

    private static HistoryEnvelope historyEnvelope(
        String body,
        String expectedReportId,
        String expectedSchema
    ) throws IOException {
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_HISTORY_RESPONSE_BYTES) {
            throw new IOException("administrator history response is too large");
        }
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        requireExactKeys(root, AdminJava8Collections.set(
            "schema_version", "report_id", "snapshot_revision", "total_count",
            "items", "next_cursor"
        ));
        if (!expectedSchema.equals(requiredText(root, "schema_version", 64))) {
            throw new IOException("administrator history schema is unsupported");
        }
        String reportId = requiredUuid(root, "report_id");
        if (!expectedReportId.equals(reportId)) {
            throw new IOException("administrator history report binding is invalid");
        }
        long snapshotRevision = requiredLong(root, "snapshot_revision", 0L);
        long totalCount = requiredLong(root, "total_count", 0L);
        if (snapshotRevision != totalCount) {
            throw new IOException("administrator history snapshot is inconsistent");
        }
        Object rawItems = root.get("items");
        if (!(rawItems instanceof List<?> values) || values.size() > HISTORY_PAGE_SIZE) {
            throw new IOException("administrator history page items are invalid");
        }
        List<Map<String, Object>> items = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object)) {
                throw new IOException("administrator history item is invalid");
            }
            items.add(stringObject(object));
        }
        String nextCursor = nullableText(root, "next_cursor", 1024);
        if (nextCursor != null && !nextCursor.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new IOException("administrator history cursor is invalid");
        }
        if ((totalCount == 0L) != items.isEmpty() || (totalCount == 0L && nextCursor != null)) {
            throw new IOException("administrator history empty page is inconsistent");
        }
        return new HistoryEnvelope(
            reportId, snapshotRevision, totalCount, nextCursor,
            AdminJava8Collections.copyList(items)
        );
    }

    private static void validateHistoryTail(HistoryEnvelope envelope, long lastRevision)
        throws IOException {
        if (envelope.totalCount == 0L) return;
        if (lastRevision > envelope.snapshotRevision
            || (envelope.nextCursor == null && lastRevision != envelope.snapshotRevision)
            || (envelope.nextCursor != null && lastRevision >= envelope.snapshotRevision)) {
            throw new IOException("administrator history page boundary is inconsistent");
        }
    }

    private static String canonicalHistoryUuid(String value) {
        String canonical = AdminReportDecision.canonicalUuid(value, "report_id");
        UUID parsed = UUID.fromString(canonical);
        if (parsed.variant() != 2 || parsed.version() < 1 || parsed.version() > 5) {
            throw new IllegalArgumentException("report_id is not a supported canonical UUID");
        }
        return canonical;
    }

    private static Map<String, Object> stringObject(Map<?, ?> value) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("administrator history item key is invalid");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static final class HistoryEnvelope {
        final String reportId;
        final long snapshotRevision;
        final long totalCount;
        final String nextCursor;
        final List<Map<String, Object>> items;

        HistoryEnvelope(
            String reportId,
            long snapshotRevision,
            long totalCount,
            String nextCursor,
            List<Map<String, Object>> items
        ) {
            this.reportId = reportId;
            this.snapshotRevision = snapshotRevision;
            this.totalCount = totalCount;
            this.nextCursor = nextCursor;
            this.items = items;
        }
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
            || AdminReportDecision.hasDisallowedTextControl(text)) {
            throw new IOException("administrator history text is invalid: " + key);
        }
        return text;
    }

    private static String nullableText(Map<String, Object> value, String key, int maxLength) throws IOException {
        Object raw = value.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String text) || text.trim().isEmpty() || text.length() > maxLength
            || AdminReportDecision.hasDisallowedTextControl(text)) {
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

    private static long requiredLong(Map<String, Object> value, String key, long minimum)
        throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) {
            throw new IOException("administrator history integer is invalid: " + key);
        }
        long parsed = ((Number) raw).longValue();
        if (parsed < minimum) {
            throw new IOException("administrator history integer is invalid: " + key);
        }
        return parsed;
    }

    private static Long nullableLong(Map<String, Object> value, String key, long minimum)
        throws IOException {
        return value.get(key) == null ? null : requiredLong(value, key, minimum);
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

    private static boolean sameDeliveryPackage(
        String leftId,
        Long leftRevision,
        String rightId,
        Long rightRevision
    ) {
        return java.util.Objects.equals(leftId, rightId)
            && java.util.Objects.equals(leftRevision, rightRevision);
    }

    private static void requireFirstHistoryRevision(Result result, String cursor)
        throws IOException {
        if (cursor != null || result.totalCount() == 0L) return;
        long firstRevision = result.kind() == ResultKind.REVIEW_HISTORY
            ? result.reviewHistory().get(0).revision()
            : result.deliveryHistory().get(0).revision();
        if (firstRevision != 1L) {
            throw new IOException("administrator history first page is not contiguous");
        }
    }

    /**
     * S18-19: every request, including the one-time original image GET, has the overall
     * deadline of {@link AdminHttpRequestDeadline} on top of the connect/read phase limits
     * (20 s; the original image GET has the R14 size-based budget).
     * A timeout fails as "server outcome unknown" and is never re-sent automatically.
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
        public Response execute(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
            return execute(null, method, url, headers, body);
        }

        @Override
        public Response execute(AdminOperationsApi.SessionContext session, String method, String url,
            Map<String, String> headers, byte[] body) throws IOException {
            AdminHttpRequestDeadline.Owner owner = deadlines.open(session, url);
            try {
                HttpURLConnection connection = owner.connection();
                connection.setRequestMethod(method);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
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
                    ? connection.getInputStream()
                    : connection.getErrorStream();
                owner.check();
                return new Response(status, stream == null ? "" : readBoundedResponse(stream, owner));
            } finally {
                owner.close();
            }
        }

        @Override
        public BinaryResponse executeBinary(
            String method,
            String url,
            Map<String, String> headers,
            int expectedByteCount
        ) throws IOException {
            return executeBinary(null, method, url, headers, expectedByteCount);
        }

        @Override
        public BinaryResponse executeBinary(SessionContext session, String method, String url,
            Map<String, String> headers, int expectedByteCount) throws IOException {
            requireOriginalEvidenceRequestActive();
            if (!"GET".equals(method)
                || expectedByteCount < 1
                || expectedByteCount > AdminOriginalEvidence.MAX_IMAGE_BYTES) {
                throw new IOException("original evidence request is invalid");
            }
            // R14 (adm-F1): 20 s + expected size at 64 KiB/s (at most 150 s) for this GET only.
            AdminHttpRequestDeadline.Owner owner = deadlines.open(
                session, url,
                AdminHttpRequestDeadline.originalEvidenceBudgetNanos(expectedByteCount)
            );
            byte[] content = null;
            BinaryResponse response = null;
            try {
                HttpURLConnection connection = owner.connection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setDoInput(true);
                connection.setDoOutput(false);
                headers.forEach(connection::setRequestProperty);
                owner.check();
                int status = connection.getResponseCode();
                requireOriginalEvidenceRequestActive();
                owner.check();
                String contentType = connection.getHeaderField("Content-Type");
                long declaredLength = connection.getContentLengthLong();
                owner.check();
                if (status != 200 || declaredLength != expectedByteCount) {
                    response = new BinaryResponse(status, contentType, declaredLength, new byte[0]);
                } else {
                    content = readOriginalEvidenceBody(connection.getInputStream(), expectedByteCount, owner);
                    response = new BinaryResponse(status, contentType, declaredLength, content);
                    content = null;
                }
                owner.close();
                BinaryResponse completed = response;
                response = null;
                return completed;
            } finally {
                if (content != null) Arrays.fill(content, (byte) 0);
                // The deadline or a cancellation won after the body arrived: zeroize it.
                if (response != null) response.close();
                owner.close();
            }
        }

    }
}
