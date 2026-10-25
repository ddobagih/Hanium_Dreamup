package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

/** Backend-only administrator review and manual-delivery recording boundary. */
public interface AdminOperationsApi {
    final class HistoryCursorException extends IOException {
        public HistoryCursorException() { super("administrator history cursor is invalid"); }
    }

    final class HistoryNotFoundException extends IOException {
        public HistoryNotFoundException() { super("administrator history resource was not found"); }
    }

    /**
     * S18-40 review F3: the correction ID was already used for a different correction
     * (server 409 delivery_correction_idempotency_conflict). Only a new ID can proceed.
     */
    final class CorrectionIdConflictException extends IOException {
        public CorrectionIdConflictException() {
            super("administrator delivery correction id was used for a different correction");
        }
    }

    final class MutationConflictException extends IOException {
        private final boolean institutionMismatch;

        public MutationConflictException() { this(false); }

        public MutationConflictException(boolean institutionMismatch) {
            super("administrator mutation conflicted with current server state");
            this.institutionMismatch = institutionMismatch;
        }

        public boolean institutionMismatch() { return institutionMismatch; }
    }

    /**
     * S20-54: the image was not received and verified after the one-time original access
     * token had already been taken from its grant. The server may have consumed the grant and
     * audited "ACCESS_GRANTED" (server approval and return preparation, not client receipt).
     * The token is never reused or reactivated; viewing again needs a new purpose-limited grant.
     * w24 misc-F7: AdminBoundaryActivity also uses it when verified bytes cannot be decoded for
     * display, since that grant is spent as well.
     */
    final class OriginalEvidenceNotReceivedException extends IOException {
        public OriginalEvidenceNotReceivedException(Throwable cause) {
            super("original evidence was not received after its one-time grant was used", cause);
        }
    }

    enum ResultKind {
        MUTATION,
        REVIEW_HISTORY,
        DELIVERY_HISTORY
    }

    final class SessionContext implements AdminRequestAdmission {

        private final String accessToken;
        private final String adminId;
        private final String sessionId;
        private final String deviceId;
        private final Runnable currentOperationCheck;
        private final AdminRequestAdmission requestAdmission;

        public SessionContext(String accessToken, String adminId, String sessionId, String deviceId) {
            this(accessToken, adminId, sessionId, deviceId, () -> {});
        }

        SessionContext(String accessToken, String adminId, String sessionId, String deviceId,
            Runnable currentOperationCheck) {
            this(accessToken, adminId, sessionId, deviceId, currentOperationCheck,
                (cancel, enqueue) -> { currentOperationCheck.run(); enqueue.run(); return () -> {}; });
        }

        SessionContext(String accessToken, String adminId, String sessionId, String deviceId,
            Runnable currentOperationCheck, AdminRequestAdmission requestAdmission) {
            this.currentOperationCheck = currentOperationCheck;
            this.requestAdmission = requestAdmission;
            this.accessToken = accessToken;
            this.adminId = adminId;
            this.sessionId = sessionId;
            this.deviceId = deviceId;
        }

        void requireCurrentOperation() { currentOperationCheck.run(); }

        @Override public Registration dispatch(Runnable cancel, Runnable enqueue) {
            return requestAdmission.dispatch(cancel, enqueue);
        }

        public String accessToken() { return accessToken; }
        public String adminId() { return adminId; }
        public String sessionId() { return sessionId; }
        public String deviceId() { return deviceId; }
    }

    final class Result {
        private final String correlationId;
        private final int statusCode;
        private final String mutationId;
        private final ResultKind kind;
        private final String reportId;
        private final long snapshotRevision;
        private final long totalCount;
        private final String nextCursor;
        private final List<ReviewHistoryItem> reviewHistory;
        private final List<DeliveryHistoryItem> deliveryHistory;

        public Result(String correlationId, int statusCode) {
            this(correlationId, statusCode, null);
        }

        public Result(String correlationId, int statusCode, String mutationId) {
            this(
                correlationId, statusCode, mutationId, ResultKind.MUTATION, null, 0L, 0L, null,
                AdminJava8Collections.list(), AdminJava8Collections.list()
            );
        }

        private Result(
            String correlationId,
            int statusCode,
            String mutationId,
            ResultKind kind,
            String reportId,
            long snapshotRevision,
            long totalCount,
            String nextCursor,
            List<ReviewHistoryItem> reviewHistory,
            List<DeliveryHistoryItem> deliveryHistory
        ) {
            this.correlationId = correlationId;
            this.statusCode = statusCode;
            this.mutationId = mutationId;
            this.kind = kind;
            this.reportId = reportId;
            this.snapshotRevision = snapshotRevision;
            this.totalCount = totalCount;
            this.nextCursor = nextCursor;
            this.reviewHistory = AdminJava8Collections.copyList(reviewHistory);
            this.deliveryHistory = AdminJava8Collections.copyList(deliveryHistory);
        }

        public String correlationId() { return correlationId; }
        public int statusCode() { return statusCode; }
        public String mutationId() { return mutationId; }
        public ResultKind kind() { return kind; }
        public String reportId() { return reportId; }
        public long snapshotRevision() { return snapshotRevision; }
        public long totalCount() { return totalCount; }
        public String nextCursor() { return nextCursor; }
        public List<ReviewHistoryItem> reviewHistory() { return reviewHistory; }
        public List<DeliveryHistoryItem> deliveryHistory() { return deliveryHistory; }
        public Integer returnedItemCount() {
            return switch (kind) {
                case MUTATION -> null;
                case REVIEW_HISTORY -> reviewHistory.size();
                case DELIVERY_HISTORY -> deliveryHistory.size();
            };
        }

        static Result reviewHistory(
            String correlationId,
            int statusCode,
            String reportId,
            long snapshotRevision,
            long totalCount,
            String nextCursor,
            List<ReviewHistoryItem> history
        ) {
            return new Result(
                correlationId, statusCode, null, ResultKind.REVIEW_HISTORY, reportId,
                snapshotRevision, totalCount, nextCursor,
                history, AdminJava8Collections.list()
            );
        }

        static Result deliveryHistory(
            String correlationId,
            int statusCode,
            String reportId,
            long snapshotRevision,
            long totalCount,
            String nextCursor,
            List<DeliveryHistoryItem> history
        ) {
            return new Result(
                correlationId, statusCode, null, ResultKind.DELIVERY_HISTORY, reportId,
                snapshotRevision, totalCount, nextCursor,
                AdminJava8Collections.list(), history
            );
        }
    }

    final class ReviewHistoryItem {
        private final long revision;
        private final long contentRevision;
        private final AdminReportDecision.Decision decision;
        private final String reason;
        private final String userVisibleReason;
        private final String duplicateOfReportId;
        private final String decidedAt;

        ReviewHistoryItem(
            long revision,
            long contentRevision,
            AdminReportDecision.Decision decision,
            String reason,
            String userVisibleReason,
            String duplicateOfReportId,
            String decidedAt
        ) {
            this.revision = revision;
            this.contentRevision = contentRevision;
            this.decision = decision;
            this.reason = reason;
            this.userVisibleReason = userVisibleReason;
            this.duplicateOfReportId = duplicateOfReportId;
            this.decidedAt = decidedAt;
        }

        public long revision() { return revision; }
        public long contentRevision() { return contentRevision; }
        public AdminReportDecision.Decision decision() { return decision; }
        public String reason() { return reason; }
        public String userVisibleReason() { return userVisibleReason; }
        public String duplicateOfReportId() { return duplicateOfReportId; }
        public String decidedAt() { return decidedAt; }
    }

    /** S18-40: the append-only correction that names one delivery event. */
    final class DeliveryCorrection {
        private final String id;
        private final long correctedRevision;
        private final long expectedRevision;
        private final AdminDeliveryCorrection.Reason reason;
        private final String memo;
        private final String adminId;
        private final String createdAt;

        DeliveryCorrection(
            String id,
            long correctedRevision,
            long expectedRevision,
            AdminDeliveryCorrection.Reason reason,
            String memo,
            String adminId,
            String createdAt
        ) {
            this.id = id;
            this.correctedRevision = correctedRevision;
            this.expectedRevision = expectedRevision;
            this.reason = reason;
            this.memo = memo;
            this.adminId = adminId;
            this.createdAt = createdAt;
        }

        public String id() { return id; }
        public long correctedRevision() { return correctedRevision; }
        /** Highest delivery revision when the correction was recorded. */
        public long expectedRevision() { return expectedRevision; }
        public AdminDeliveryCorrection.Reason reason() { return reason; }
        public String memo() { return memo; }
        public String adminId() { return adminId; }
        public String createdAt() { return createdAt; }
    }

    final class DeliveryHistoryItem {
        private final String id;
        private final long revision;
        private final String packageId;
        private final Long packageRevision;
        private final AdminInstitutionDelivery.Status status;
        private final String externalReceiptId;
        private final String institution;
        private final String observedAt;
        private final String recordedAt;
        private final DeliveryCorrection correction;

        DeliveryHistoryItem(
            String id,
            long revision,
            String packageId,
            Long packageRevision,
            AdminInstitutionDelivery.Status status,
            String externalReceiptId,
            String institution,
            String observedAt,
            String recordedAt,
            DeliveryCorrection correction
        ) {
            this.id = id;
            this.revision = revision;
            this.packageId = packageId;
            this.packageRevision = packageRevision;
            this.status = status;
            this.externalReceiptId = externalReceiptId;
            this.institution = institution;
            this.observedAt = observedAt;
            this.recordedAt = recordedAt;
            this.correction = correction;
        }

        public String id() { return id; }
        /** S18-40: null while the event is effective; corrected events carry no state. */
        public DeliveryCorrection correction() { return correction; }
        public boolean corrected() { return correction != null; }
        public long revision() { return revision; }
        public String packageId() { return packageId; }
        public Long packageRevision() { return packageRevision; }
        public AdminInstitutionDelivery.Status status() { return status; }
        public String externalReceiptId() { return externalReceiptId; }
        public String institution() { return institution; }
        public String observedAt() { return observedAt; }
        public String recordedAt() { return recordedAt; }
    }

    Result recordReviewDecision(SessionContext session, String reportId, AdminReportDecision decision)
        throws IOException, GeneralSecurityException;

    Result readReviewDecisions(SessionContext session, String reportId, String cursor)
        throws IOException, GeneralSecurityException;

    default Result readReviewDecisions(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        return readReviewDecisions(session, reportId, null);
    }

    /** Latest verified suffix for display only; never a completed full-history traversal. */
    default Result readRecentReviewDecisions(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        throw new IOException("recent review history is unavailable");
    }

    /**
     * S18-36: GET /reports/{report_id}/review-decisions/{decision_id}. The result's mutationId is
     * the decision id when it was recorded and null when the server has no such decision.
     */
    default Result lookupReviewDecision(SessionContext session, String reportId, String decisionId)
        throws IOException, GeneralSecurityException {
        throw new IOException("administrator review decision lookup is unavailable");
    }
    Result recordDelivery(SessionContext session, String reportId, AdminInstitutionDelivery delivery)
        throws IOException, GeneralSecurityException;

    Result readDeliveries(SessionContext session, String reportId, String cursor)
        throws IOException, GeneralSecurityException;

    default Result readDeliveries(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        return readDeliveries(session, reportId, null);
    }

    /** Latest verified suffix for display only; never a completed full-history traversal. */
    default Result readRecentDeliveries(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        throw new IOException("recent delivery history is unavailable");
    }

    /**
     * S18-40: HIGH-risk POST /reports/{report_id}/delivery-corrections. The result's mutationId
     * is the stored correction id. This records a correction only; it sends nothing outside.
     */
    default Result recordDeliveryCorrection(
        SessionContext session,
        String reportId,
        AdminDeliveryCorrection correction,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException {
        throw new IOException("administrator delivery correction is unavailable");
    }

    /**
     * Review F3: {@code resendOfUncertainAttempt} is true when this exact correction (same ID
     * and intent) was sent before without a confirmed result. The server may then answer with
     * the stored earlier record (EXISTING), which can carry an earlier session of the same
     * administrator; a first send must match the current session.
     */
    default Result recordDeliveryCorrection(
        SessionContext session,
        String reportId,
        AdminDeliveryCorrection correction,
        Map<String, String> reconfirmationHeaders,
        boolean resendOfUncertainAttempt
    ) throws IOException, GeneralSecurityException {
        return recordDeliveryCorrection(session, reportId, correction, reconfirmationHeaders);
    }

    AdminOriginalEvidence loadOriginalEvidence(
        SessionContext session,
        String reportId,
        int expectedContentRevision,
        String reason,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;

    /** S18-18: explicit, non-blocking cancellation of requests still in flight (no re-send). */
    default void cancelInFlightRequests() {}
}
