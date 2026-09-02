package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.UUID;

/** Retains one review intent and decision UUID until its explicit outcome is known. */
public final class AdminReviewDecisionAttempt {
    private String reportId;
    private String requestDigest;
    private AdminReportDecision decision;

    public synchronized AdminReportDecision prepare(
        String reportId,
        AdminReportDecision.Decision selectedDecision,
        String reason,
        String userVisibleReason,
        String duplicateOfReportId,
        boolean locationReviewed,
        boolean photoReviewed,
        boolean privacyReviewed,
        int contentRevision,
        String evidenceGrantId
    ) {
        String safeReportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
        String candidateId = decision == null
            ? UUID.randomUUID().toString()
            : decision.decisionId();
        String candidateEvidenceGrantId = decision == null
            ? evidenceGrantId
            : decision.evidenceGrantId();
        int candidateContentRevision = decision == null
            ? contentRevision
            : decision.contentRevision();
        AdminReportDecision candidate = new AdminReportDecision(
            selectedDecision,
            reason,
            userVisibleReason,
            duplicateOfReportId,
            locationReviewed,
            photoReviewed,
            privacyReviewed,
            candidateContentRevision,
            candidateEvidenceGrantId,
            candidateId
        );
        String candidateRequestDigest = AdminCanonicalEncoding.sha256Hex(
            candidate.requestBody(safeReportId)
        );
        if (decision != null) {
            if (!safeReportId.equals(this.reportId)
                || !candidateRequestDigest.equals(requestDigest)) {
                throw new IllegalStateException(
                    "the previous review decision outcome must be resolved before a new intent"
                );
            }
            return decision;
        }
        this.reportId = safeReportId;
        requestDigest = candidateRequestDigest;
        decision = candidate;
        return decision;
    }

    public synchronized boolean isPending() { return decision != null; }

    public synchronized String decisionId() {
        return decision == null ? null : decision.decisionId();
    }

    public synchronized void clear() {
        reportId = null;
        requestDigest = null;
        decision = null;
    }
}
