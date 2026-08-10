package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact six-field, append-only administrator review decision request. */
public final class AdminReportDecision {
    public enum Decision {
        APPROVED,
        REJECTED,
        DUPLICATE
    }

    private static final Set<String> EXACT_KEYS = Set.of(
        "decision",
        "reason",
        "duplicate_of_report_id",
        "location_reviewed",
        "photo_reviewed",
        "privacy_reviewed"
    );

    private final Decision decision;
    private final String reason;
    private final String duplicateOfReportId;
    private final boolean locationReviewed;
    private final boolean photoReviewed;
    private final boolean privacyReviewed;

    public AdminReportDecision(
        Decision decision,
        String reason,
        String duplicateOfReportId,
        boolean locationReviewed,
        boolean photoReviewed,
        boolean privacyReviewed
    ) {
        if (decision == null) throw new IllegalArgumentException("decision is required");
        this.decision = decision;
        this.reason = requiredText(reason, "reason", 500);
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
    }

    public Decision decision() { return decision; }
    public String reason() { return reason; }
    public String duplicateOfReportId() { return duplicateOfReportId; }

    public byte[] requestBody(String reportId) {
        String canonicalReportId = canonicalUuid(reportId, "report_id");
        if (canonicalReportId.equals(duplicateOfReportId)) {
            throw new IllegalArgumentException("a report cannot be marked as a duplicate of itself");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("decision", decision.name());
        fields.put("reason", reason);
        fields.put("duplicate_of_report_id", duplicateOfReportId);
        fields.put("location_reviewed", locationReviewed);
        fields.put("photo_reviewed", photoReviewed);
        fields.put("privacy_reviewed", privacyReviewed);
        if (!fields.keySet().equals(EXACT_KEYS)) throw new IllegalStateException("invalid exact6 review shape");
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
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
            || normalized.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }
}
