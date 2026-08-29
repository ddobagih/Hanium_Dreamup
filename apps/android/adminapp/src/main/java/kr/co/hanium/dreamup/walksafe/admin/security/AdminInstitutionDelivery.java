package kr.co.hanium.dreamup.walksafe.admin.security;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Exact package-bound record of a delivery performed manually outside WalkSafe. */
public final class AdminInstitutionDelivery {
    public enum Status {
        SUBMITTED,
        ACKNOWLEDGED,
        RESOLVED,
        FAILED
    }

    private static final Set<String> EXACT_KEYS = AdminJava8Collections.set(
        "institution",
        "channel",
        "recipient",
        "status",
        "external_receipt_id",
        "reason",
        "evidence_sha256",
        "observed_at",
        "package_revision",
        "expected_revision",
        "idempotency_key"
    );

    private final String institution;
    private final String channel;
    private final String recipient;
    private final Status status;
    private final String externalReceiptId;
    private final String reason;
    private final String evidenceSha256;
    private final String observedAt;
    private final long packageRevision;
    private final long expectedRevision;
    private final String idempotencyKey;

    public AdminInstitutionDelivery(
        String institution,
        String channel,
        String recipient,
        Status status,
        String externalReceiptId,
        String reason,
        String evidenceSha256,
        String observedAt,
        long packageRevision,
        long expectedRevision,
        String idempotencyKey
    ) {
        this.institution = AdminReportDecision.requiredText(institution, "institution", 160);
        this.channel = AdminReportDecision.requiredText(channel, "channel", 32);
        this.recipient = AdminReportDecision.requiredText(recipient, "recipient", 255);
        if (status == null) throw new IllegalArgumentException("status is required");
        this.status = status;
        this.externalReceiptId = optionalText(externalReceiptId, "external_receipt_id", 160);
        if ((status == Status.ACKNOWLEDGED || status == Status.RESOLVED)
            && this.externalReceiptId == null) {
            throw new IllegalArgumentException("ACKNOWLEDGED and RESOLVED require external_receipt_id");
        }
        this.reason = AdminReportDecision.requiredText(reason, "reason", 500);
        if (evidenceSha256 != null && !evidenceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evidence_sha256 must be null or lowercase SHA-256 hex");
        }
        this.evidenceSha256 = evidenceSha256;
        this.observedAt = utcInstant(observedAt);
        if (packageRevision < 1L) throw new IllegalArgumentException("package_revision must be positive");
        this.packageRevision = packageRevision;
        if (expectedRevision < 0L) throw new IllegalArgumentException("expected_revision must be non-negative");
        this.expectedRevision = expectedRevision;
        this.idempotencyKey = AdminReportDecision.canonicalUuid(idempotencyKey, "idempotency_key");
    }

    public Status status() { return status; }
    public long packageRevision() { return packageRevision; }
    public long expectedRevision() { return expectedRevision; }
    public String idempotencyKey() { return idempotencyKey; }

    public byte[] requestBody() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("institution", institution);
        fields.put("channel", channel);
        fields.put("recipient", recipient);
        fields.put("status", status.name());
        fields.put("external_receipt_id", externalReceiptId);
        fields.put("reason", reason);
        fields.put("evidence_sha256", evidenceSha256);
        fields.put("observed_at", observedAt);
        fields.put("package_revision", packageRevision);
        fields.put("expected_revision", expectedRevision);
        fields.put("idempotency_key", idempotencyKey);
        if (!fields.keySet().equals(EXACT_KEYS)) throw new IllegalStateException("invalid exact10 delivery shape");
        return AdminCanonicalEncoding.canonicalJsonBytes(fields);
    }

    private static String optionalText(String value, String label, int maxLength) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
            || normalized.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException(label + " must be null or non-blank text");
        }
        return normalized;
    }

    private static String utcInstant(String value) {
        if (value == null || !value.endsWith("Z")) {
            throw new IllegalArgumentException("observed_at must be RFC3339 UTC");
        }
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("observed_at must be RFC3339 UTC", error);
        }
    }
}
