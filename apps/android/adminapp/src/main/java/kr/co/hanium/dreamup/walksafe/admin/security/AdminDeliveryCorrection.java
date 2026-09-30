package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * S18-40: an append-only correction of the latest effective manual delivery event.
 *
 * <p>The corrected event is never edited or deleted. The server records which event was wrong,
 * a reason code and a memo, and every delivery state skips corrected events. Only the latest
 * effective (uncorrected) event can be corrected; older mistakes are corrected step by step.
 */
public final class AdminDeliveryCorrection {
    public static final String SCHEMA_VERSION = "walksafe.report-delivery-correction.v1";
    public static final int MAX_MEMO_LENGTH = 500;
    static final Set<String> EXACT_KEYS = AdminJava8Collections.set(
        "correction_id", "corrects_event_id", "expected_revision", "reason_code", "memo"
    );

    public enum Reason {
        WRONG_STATUS,
        WRONG_REPORT,
        DUPLICATE_ENTRY,
        OTHER
    }

    private final String correctionId;
    private final String correctsEventId;
    private final long expectedRevision;
    private final Reason reason;
    private final String memo;

    public AdminDeliveryCorrection(
        String correctionId,
        String correctsEventId,
        long expectedRevision,
        Reason reason,
        String memo
    ) {
        this.correctionId = AdminReportDecision.canonicalUuid(correctionId, "correction_id");
        this.correctsEventId = AdminReportDecision.canonicalUuid(
            correctsEventId, "corrects_event_id"
        );
        if (expectedRevision < 1L) {
            throw new IllegalArgumentException("a correction needs an existing delivery revision");
        }
        if (reason == null) throw new IllegalArgumentException("correction reason is required");
        this.expectedRevision = expectedRevision;
        this.reason = reason;
        this.memo = normalizedMemo(memo);
    }

    /**
     * Review F4: the server stores the memo stripped like Python's no-argument {@code str.strip}
     * (schema strip_whitespace and PG walksafe_python_strip), which removes Unicode whitespace
     * such as NBSP and U+3000 that {@link String#trim()} keeps. The app sends exactly the value
     * the server will store.
     */
    static String normalizedMemo(String value) {
        if (value == null) throw new IllegalArgumentException("correction memo is required");
        if (AdminReportDecision.hasDisallowedTextControl(value)) {
            throw new IllegalArgumentException("correction memo is invalid");
        }
        int start = 0;
        int end = value.length();
        while (start < end && isPythonWhitespace(value.charAt(start))) start++;
        while (end > start && isPythonWhitespace(value.charAt(end - 1))) end--;
        String normalized = value.substring(start, end);
        if (normalized.isEmpty() || normalized.length() > MAX_MEMO_LENGTH) {
            throw new IllegalArgumentException("correction memo is invalid");
        }
        return normalized;
    }

    /** Exactly the code points for which Python {@code str.isspace()} is true (all in the BMP). */
    static boolean isPythonWhitespace(char character) {
        return (character >= 0x09 && character <= 0x0d)
            || (character >= 0x1c && character <= 0x20)
            || character == 0x85
            || character == 0xa0
            || character == 0x1680
            || (character >= 0x2000 && character <= 0x200a)
            || character == 0x2028
            || character == 0x2029
            || character == 0x202f
            || character == 0x205f
            || character == 0x3000;
    }

    public String correctionId() { return correctionId; }
    public String correctsEventId() { return correctsEventId; }
    public long expectedRevision() { return expectedRevision; }
    public Reason reason() { return reason; }
    public String memo() { return memo; }

    public byte[] requestBody() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("correction_id", correctionId);
        fields.put("corrects_event_id", correctsEventId);
        fields.put("expected_revision", expectedRevision);
        fields.put("reason_code", reason.name());
        fields.put("memo", memo);
        if (!fields.keySet().equals(EXACT_KEYS)) {
            throw new IllegalStateException("invalid exact5 correction shape");
        }
        return AdminCanonicalEncoding.canonicalJsonBytes(fields);
    }

    static Reason reasonFromWire(String value) {
        if (value == null) throw new IllegalArgumentException("correction reason is required");
        for (Reason candidate : Reason.values()) {
            if (candidate.name().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("correction reason is unsupported");
    }
}
