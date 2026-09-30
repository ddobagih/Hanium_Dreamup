package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.UUID;

/**
 * Retains one review intent and decision UUID until its explicit outcome is known.
 *
 * <p>S18-36: the non-secret identity of an unresolved attempt (decision_id, report_id, intent
 * digest, content/review revisions and evidence grant id — never the reason text, image or
 * credentials) is kept in a {@link Store}. After a process restart or a session change the
 * attempt is <em>restored</em>: the app must look the decision_id up first. A recorded
 * decision resolves the attempt; only a confirmed absence lets a different intent use a new
 * decision_id, and the same intent always reuses the original one.
 */
public final class AdminReviewDecisionAttempt {
    /** Public, non-secret identity of an attempt whose outcome is still unknown. */
    public static final class Snapshot {
        private final String reportId;
        private final String decisionId;
        private final String requestDigest;
        private final int contentRevision;
        private final long expectedReviewRevision;
        private final String evidenceGrantId;

        public Snapshot(
            String reportId,
            String decisionId,
            String requestDigest,
            int contentRevision,
            long expectedReviewRevision,
            String evidenceGrantId
        ) {
            this.reportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
            this.decisionId = AdminReportDecision.canonicalUuid(decisionId, "decision_id");
            if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("request digest is invalid");
            }
            if (contentRevision < 0 || expectedReviewRevision < 0L) {
                throw new IllegalArgumentException("attempt revisions are invalid");
            }
            this.requestDigest = requestDigest;
            this.contentRevision = contentRevision;
            this.expectedReviewRevision = expectedReviewRevision;
            this.evidenceGrantId = evidenceGrantId == null
                ? null
                : AdminReportDecision.canonicalUuid(evidenceGrantId, "evidence_grant_id");
        }

        public String reportId() { return reportId; }
        public String decisionId() { return decisionId; }
        public String requestDigest() { return requestDigest; }
        public int contentRevision() { return contentRevision; }
        public long expectedReviewRevision() { return expectedReviewRevision; }
        public String evidenceGrantId() { return evidenceGrantId; }
    }

    public interface Store {
        Snapshot load();

        void save(Snapshot snapshot);

        void clear();
    }

    public enum LookupOutcome {
        /** The server has the decision: the earlier attempt succeeded. */
        RECORDED,
        /** The server confirmed it has no decision with this id. */
        NOT_RECORDED
    }

    private static final Store NO_STORE = new Store() {
        @Override public Snapshot load() { return null; }
        @Override public void save(Snapshot snapshot) {}
        @Override public void clear() {}
    };

    private final Store store;
    private String reportId;
    private String requestDigest;
    private AdminReportDecision decision;
    private Snapshot restored;
    private boolean restoredConfirmedAbsent;

    public AdminReviewDecisionAttempt() {
        this(NO_STORE);
    }

    public AdminReviewDecisionAttempt(Store store) {
        if (store == null) throw new IllegalArgumentException("attempt store is required");
        this.store = store;
    }

    /** Loads an unresolved attempt left by an earlier process; it must be looked up first. */
    public synchronized void restore() {
        if (decision != null || restored != null) return;
        Snapshot snapshot;
        try {
            snapshot = store.load();
        } catch (RuntimeException invalid) {
            store.clear();
            return;
        }
        if (snapshot != null) {
            restored = snapshot;
            restoredConfirmedAbsent = false;
        }
    }

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
        return prepare(
            reportId, selectedDecision, reason, userVisibleReason, duplicateOfReportId,
            locationReviewed, photoReviewed, privacyReviewed, contentRevision, evidenceGrantId, 0L
        );
    }

    /**
     * A pending attempt keeps its decision_id, evidence grant, content revision and the review
     * revision it was prepared against, so a retry replays the exact original intent.
     */
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
        String evidenceGrantId,
        long expectedReviewRevision
    ) {
        String safeReportId = AdminReportDecision.canonicalUuid(reportId, "report_id");
        if (restored != null) {
            if (!restoredConfirmedAbsent) {
                throw new IllegalStateException(
                    "the previous review decision outcome must be looked up before a new intent"
                );
            }
            AdminReportDecision replay = new AdminReportDecision(
                selectedDecision, reason, userVisibleReason, duplicateOfReportId,
                locationReviewed, photoReviewed, privacyReviewed,
                restored.contentRevision(), restored.evidenceGrantId(), restored.decisionId(),
                restored.expectedReviewRevision()
            );
            String replayDigest = AdminCanonicalEncoding.sha256Hex(replay.requestBody(safeReportId));
            if (safeReportId.equals(restored.reportId()) && replayDigest.equals(restored.requestDigest())) {
                this.reportId = safeReportId;
                requestDigest = replayDigest;
                decision = replay;
                restored = null;
                return decision;
            }
            // The earlier decision was confirmed absent, so a different intent may start over.
            restored = null;
            restoredConfirmedAbsent = false;
            store.clear();
        }
        String candidateId = decision == null
            ? UUID.randomUUID().toString()
            : decision.decisionId();
        String candidateEvidenceGrantId = decision == null
            ? evidenceGrantId
            : decision.evidenceGrantId();
        int candidateContentRevision = decision == null
            ? contentRevision
            : decision.contentRevision();
        long candidateExpectedReviewRevision = decision == null
            ? expectedReviewRevision
            : decision.expectedReviewRevision();
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
            candidateId,
            candidateExpectedReviewRevision
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
        // Persist before dispatch so a process death after sending cannot lose the id.
        store.save(new Snapshot(
            safeReportId,
            candidate.decisionId(),
            candidateRequestDigest,
            candidateContentRevision,
            candidateExpectedReviewRevision,
            candidateEvidenceGrantId
        ));
        return decision;
    }

    public synchronized boolean isPending() { return decision != null || restored != null; }

    /** True while a restored attempt's outcome has not been looked up on the server. */
    public synchronized boolean needsLookup() {
        return restored != null && !restoredConfirmedAbsent;
    }

    public synchronized String restoredReportId() {
        return restored == null ? null : restored.reportId();
    }

    public synchronized String restoredDecisionId() {
        return restored == null ? null : restored.decisionId();
    }

    /** Applies the lookup result for the restored decision_id. */
    public synchronized void applyLookup(String decisionId, LookupOutcome outcome) {
        if (restored == null || outcome == null || !restored.decisionId().equals(decisionId)) {
            return;
        }
        if (outcome == LookupOutcome.RECORDED) {
            clear();
        } else {
            restoredConfirmedAbsent = true;
        }
    }

    /**
     * A session change keeps an unresolved attempt for lookup after the next login instead of
     * forgetting its decision_id; an attempt without an outstanding request is simply dropped.
     */
    public synchronized void suspendForSessionChange() {
        if (decision != null) {
            restored = new Snapshot(
                reportId,
                decision.decisionId(),
                requestDigest,
                decision.contentRevision(),
                decision.expectedReviewRevision(),
                decision.evidenceGrantId()
            );
            restoredConfirmedAbsent = false;
            reportId = null;
            requestDigest = null;
            decision = null;
        }
    }

    public synchronized String decisionId() {
        if (decision != null) return decision.decisionId();
        return restored == null ? null : restored.decisionId();
    }

    public synchronized void clear() {
        reportId = null;
        requestDigest = null;
        decision = null;
        restored = null;
        restoredConfirmedAbsent = false;
        store.clear();
    }
}
