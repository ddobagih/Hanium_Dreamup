package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact ten-field, append-only administrator review decision request. */
public final class AdminReportDecision {
    public enum Decision {
        APPROVED,
        REJECTED,
        DUPLICATE
    }

    private static final Set<String> EXACT_KEYS = AdminJava8Collections.set(
        "decision",
        "reason",
        "user_visible_reason",
        "duplicate_of_report_id",
        "location_reviewed",
        "photo_reviewed",
        "privacy_reviewed",
        "content_revision",
        "evidence_grant_id",
        "decision_id"
    );

    private final Decision decision;
    private final String reason;
    private final String userVisibleReason;
    private final String duplicateOfReportId;
    private final boolean locationReviewed;
    private final boolean photoReviewed;
    private final boolean privacyReviewed;
    private final int contentRevision;
    private final String evidenceGrantId;
    private final String decisionId;

    public AdminReportDecision(
        Decision decision,
        String reason,
        String userVisibleReason,
        String duplicateOfReportId,
        boolean locationReviewed,
        boolean photoReviewed,
        boolean privacyReviewed,
        int contentRevision,
        String evidenceGrantId,
        String decisionId
    ) {
        if (decision == null) throw new IllegalArgumentException("decision is required");
        this.decision = decision;
        this.reason = requiredText(reason, "reason", 500);
        this.userVisibleReason = userVisibleReason == null
            ? null
            : requiredText(userVisibleReason, "user_visible_reason", 500);
        if ((decision == Decision.APPROVED) != (this.userVisibleReason == null)) {
            throw new IllegalArgumentException(
                "user_visible_reason is null only for APPROVED"
            );
        }
        this.duplicateOfReportId = duplicateOfReportId == null
            ? null
            : canonicalUuid(duplicateOfReportId, "duplicate_of_report_id");
        if ((decision == Decision.DUPLICATE) != (this.duplicateOfReportId != null)) {
            throw new IllegalArgumentException("duplicate_of_report_id is required only for DUPLICATE");
        }
        if (decision == Decision.APPROVED
            && (!locationReviewed || !photoReviewed || !privacyReviewed)) {
            throw new IllegalArgumentException("APPROVED requires location, photo, and privacy review");
        }
        this.locationReviewed = locationReviewed;
        this.photoReviewed = photoReviewed;
        this.privacyReviewed = privacyReviewed;
        if (contentRevision < 0) throw new IllegalArgumentException("content_revision is invalid");
        this.contentRevision = contentRevision;
        this.evidenceGrantId = evidenceGrantId == null
            ? null
            : canonicalUuid(evidenceGrantId, "evidence_grant_id");
        if ((decision == Decision.APPROVED) != (this.evidenceGrantId != null)) {
            throw new IllegalArgumentException("evidence_grant_id is required only for APPROVED");
        }
        this.decisionId = canonicalUuid(decisionId, "decision_id");
    }

    public Decision decision() { return decision; }
    public String reason() { return reason; }
    public String userVisibleReason() { return userVisibleReason; }
    public String duplicateOfReportId() { return duplicateOfReportId; }
    public int contentRevision() { return contentRevision; }
    public String evidenceGrantId() { return evidenceGrantId; }
    public String decisionId() { return decisionId; }

    public byte[] requestBody(String reportId) {
        String canonicalReportId = canonicalUuid(reportId, "report_id");
        if (canonicalReportId.equals(duplicateOfReportId)) {
            throw new IllegalArgumentException("a report cannot be marked as a duplicate of itself");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("decision", decision.name());
        fields.put("reason", reason);
        fields.put("user_visible_reason", userVisibleReason);
        fields.put("duplicate_of_report_id", duplicateOfReportId);
        fields.put("location_reviewed", locationReviewed);
        fields.put("photo_reviewed", photoReviewed);
        fields.put("privacy_reviewed", privacyReviewed);
        fields.put("content_revision", contentRevision);
        fields.put("evidence_grant_id", evidenceGrantId);
        fields.put("decision_id", decisionId);
        if (!fields.keySet().equals(EXACT_KEYS)) {
            throw new IllegalStateException("invalid exact10 review shape");
        }
        return AdminCanonicalEncoding.canonicalJsonBytes(fields);
    }

    static String canonicalUuid(String value, String label) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException(label + " must be canonical UUID");
            return value;
        } catch (NullPointerException | IllegalArgumentException error) {
            throw new IllegalArgumentException(label + " must be canonical UUID", error);
        }
    }

    static String requiredText(String value, String label, int maxLength) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        if (hasDisallowedTextControl(value)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    static boolean hasDisallowedTextControl(String value) {
        return value.chars().anyMatch(character ->
            (character < 0x20 && character != '\t' && character != '\n' && character != '\r')
                || character == 0x7f
        );
    }
}
