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

    enum ResultKind {
        MUTATION,
        REVIEW_HISTORY,
        DELIVERY_HISTORY
    }

    final class SessionContext {
        private final String accessToken;
        private final String adminId;
        private final String sessionId;
        private final String deviceId;

        public SessionContext(String accessToken, String adminId, String sessionId, String deviceId) {
            this.accessToken = accessToken;
            this.adminId = adminId;
            this.sessionId = sessionId;
            this.deviceId = deviceId;
        }

        public String accessToken() { return accessToken; }
        public String adminId() { return adminId; }
        public String sessionId() { return sessionId; }
        public String deviceId() { return deviceId; }
    }

    final class Result {
        private final String correlationId;
        private final int statusCode;
        private final ResultKind kind;
        private final String reportId;
        private final long snapshotRevision;
        private final long totalCount;
        private final String nextCursor;
        private final List<ReviewHistoryItem> reviewHistory;
        private final List<DeliveryHistoryItem> deliveryHistory;

        public Result(String correlationId, int statusCode) {
            this(
                correlationId, statusCode, ResultKind.MUTATION, null, 0L, 0L, null,
                AdminJava8Collections.list(), AdminJava8Collections.list()
            );
        }

        private Result(
            String correlationId,
            int statusCode,
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
                correlationId, statusCode, ResultKind.REVIEW_HISTORY, reportId,
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
                correlationId, statusCode, ResultKind.DELIVERY_HISTORY, reportId,
                snapshotRevision, totalCount, nextCursor,
                AdminJava8Collections.list(), history
            );
        }
    }

    final class ReviewHistoryItem {
        private final long revision;
        private final AdminReportDecision.Decision decision;
        private final String reason;
        private final String userVisibleReason;
        private final String duplicateOfReportId;
        private final String decidedAt;

        ReviewHistoryItem(
            long revision,
            AdminReportDecision.Decision decision,
            String reason,
            String userVisibleReason,
            String duplicateOfReportId,
            String decidedAt
        ) {
            this.revision = revision;
            this.decision = decision;
            this.reason = reason;
            this.userVisibleReason = userVisibleReason;
            this.duplicateOfReportId = duplicateOfReportId;
            this.decidedAt = decidedAt;
        }

        public long revision() { return revision; }
        public AdminReportDecision.Decision decision() { return decision; }
        public String reason() { return reason; }
        public String userVisibleReason() { return userVisibleReason; }
        public String duplicateOfReportId() { return duplicateOfReportId; }
        public String decidedAt() { return decidedAt; }
    }

    final class DeliveryHistoryItem {
        private final long revision;
        private final String packageId;
        private final Long packageRevision;
        private final AdminInstitutionDelivery.Status status;
        private final String externalReceiptId;
        private final String institution;
        private final String observedAt;
        private final String recordedAt;

        DeliveryHistoryItem(
            long revision,
            String packageId,
            Long packageRevision,
            AdminInstitutionDelivery.Status status,
            String externalReceiptId,
            String institution,
            String observedAt,
            String recordedAt
        ) {
            this.revision = revision;
            this.packageId = packageId;
            this.packageRevision = packageRevision;
            this.status = status;
            this.externalReceiptId = externalReceiptId;
            this.institution = institution;
            this.observedAt = observedAt;
            this.recordedAt = recordedAt;
        }

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

    Result recordDelivery(SessionContext session, String reportId, AdminInstitutionDelivery delivery)
        throws IOException, GeneralSecurityException;

    Result readDeliveries(SessionContext session, String reportId, String cursor)
        throws IOException, GeneralSecurityException;

    default Result readDeliveries(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException {
        return readDeliveries(session, reportId, null);
    }

    AdminOriginalEvidence loadOriginalEvidence(
        SessionContext session,
        String reportId,
        int expectedContentRevision,
        String reason,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;
}
