package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

/** Backend-only administrator review and manual-delivery recording boundary. */
public interface AdminOperationsApi {
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
        private final List<ReviewHistoryItem> reviewHistory;
        private final List<DeliveryHistoryItem> deliveryHistory;

        public Result(String correlationId, int statusCode) {
            this(correlationId, statusCode, ResultKind.MUTATION, AdminJava8Collections.list(), AdminJava8Collections.list());
        }

        private Result(
            String correlationId,
            int statusCode,
            ResultKind kind,
            List<ReviewHistoryItem> reviewHistory,
            List<DeliveryHistoryItem> deliveryHistory
        ) {
            this.correlationId = correlationId;
            this.statusCode = statusCode;
            this.kind = kind;
            this.reviewHistory = AdminJava8Collections.copyList(reviewHistory);
            this.deliveryHistory = AdminJava8Collections.copyList(deliveryHistory);
        }

        public String correlationId() { return correlationId; }
        public int statusCode() { return statusCode; }
        public ResultKind kind() { return kind; }
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
            List<ReviewHistoryItem> history
        ) {
            return new Result(correlationId, statusCode, ResultKind.REVIEW_HISTORY, history, AdminJava8Collections.list());
        }

        static Result deliveryHistory(
            String correlationId,
            int statusCode,
            List<DeliveryHistoryItem> history
        ) {
            return new Result(correlationId, statusCode, ResultKind.DELIVERY_HISTORY, AdminJava8Collections.list(), history);
        }
    }

    final class ReviewHistoryItem {
        private final int revision;
        private final AdminReportDecision.Decision decision;
        private final String reason;
        private final String userVisibleReason;
        private final String duplicateOfReportId;
        private final String decidedAt;

        ReviewHistoryItem(
            int revision,
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

        public int revision() { return revision; }
        public AdminReportDecision.Decision decision() { return decision; }
        public String reason() { return reason; }
        public String userVisibleReason() { return userVisibleReason; }
        public String duplicateOfReportId() { return duplicateOfReportId; }
        public String decidedAt() { return decidedAt; }
    }

    final class DeliveryHistoryItem {
        private final int revision;
        private final int packageRevision;
        private final AdminInstitutionDelivery.Status status;
        private final String externalReceiptId;
        private final String institution;
        private final String observedAt;
        private final String recordedAt;

        DeliveryHistoryItem(
            int revision,
            int packageRevision,
            AdminInstitutionDelivery.Status status,
            String externalReceiptId,
            String institution,
            String observedAt,
            String recordedAt
        ) {
            this.revision = revision;
            this.packageRevision = packageRevision;
            this.status = status;
            this.externalReceiptId = externalReceiptId;
            this.institution = institution;
            this.observedAt = observedAt;
            this.recordedAt = recordedAt;
        }

        public int revision() { return revision; }
        public int packageRevision() { return packageRevision; }
        public AdminInstitutionDelivery.Status status() { return status; }
        public String externalReceiptId() { return externalReceiptId; }
        public String institution() { return institution; }
        public String observedAt() { return observedAt; }
        public String recordedAt() { return recordedAt; }
    }

    Result recordReviewDecision(SessionContext session, String reportId, AdminReportDecision decision)
        throws IOException, GeneralSecurityException;

    Result readReviewDecisions(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException;

    Result recordDelivery(SessionContext session, String reportId, AdminInstitutionDelivery delivery)
        throws IOException, GeneralSecurityException;

    Result readDeliveries(SessionContext session, String reportId)
        throws IOException, GeneralSecurityException;

    AdminOriginalEvidence loadOriginalEvidence(
        SessionContext session,
        String reportId,
        int expectedContentRevision,
        String reason,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;
}
