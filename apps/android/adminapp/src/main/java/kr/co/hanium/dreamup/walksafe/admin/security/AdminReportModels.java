package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict, non-sensitive projections returned by the administrator report APIs. */
public final class AdminReportModels {
    public static final String LIST_SCHEMA = "walksafe.admin-report-list.v1";
    public static final String DETAIL_SCHEMA = "walksafe.admin-report-detail.v1";
    public static final String STATUS_SCHEMA = "walksafe.admin-report-status.v1";
    public static final int PAGE_SIZE = 25;
    private static final Set<String> STATUSES = AdminJava8Collections.set("new", "reviewed", "resolved");
    private static final Set<String> CLASSES = AdminJava8Collections.set(
        "damaged_tactile_block",
        "parked_kickboard_bicycle",
        "construction_obstacle",
        "pothole"
    );
    private static final Set<String> LOCATION_QUALITIES = AdminJava8Collections.set("missing", "low", "medium", "high");

    private AdminReportModels() {}

    public static final class Filters {
        private final String reportId;
        private final String status;
        private final String className;
        private final String createdFrom;
        private final String createdTo;

        public Filters(
            String reportId,
            String status,
            String className,
            String createdFrom,
            String createdTo
        ) {
            this.reportId = optionalCanonicalUuid(reportId, "report_id");
            this.status = optionalMember(status, STATUSES, "status");
            this.className = optionalMember(className, CLASSES, "class_name");
            this.createdFrom = optionalInstant(createdFrom, "created_from");
            this.createdTo = optionalInstant(createdTo, "created_to");
            if (this.createdFrom != null && this.createdTo != null
                && OffsetDateTime.parse(this.createdFrom).isAfter(OffsetDateTime.parse(this.createdTo))) {
                throw new IllegalArgumentException("created_from must not be after created_to");
            }
        }

        public String reportId() { return reportId; }
        public String status() { return status; }
        public String className() { return className; }
        public String createdFrom() { return createdFrom; }
        public String createdTo() { return createdTo; }
    }

    public static final class Summary {
        private final String id;
        private final String status;
        private final int statusVersion;
        private final String className;
        private final double confidence;
        private final String locationQuality;
        private final int duplicateCount;
        private final String capturedAt;
        private final String createdAt;
        private final String updatedAt;

        private Summary(Map<String, Object> value) throws IOException {
            exactKeys(value, AdminJava8Collections.set(
                "id", "status", "status_version", "class_name", "confidence", "location_quality",
                "duplicate_count", "captured_at", "created_at", "updated_at"
            ));
            id = uuid(value, "id");
            status = member(value, "status", STATUSES);
            statusVersion = integer(value, "status_version", 1, Integer.MAX_VALUE);
            className = member(value, "class_name", CLASSES);
            confidence = decimal(value, "confidence", 0d, 1d);
            locationQuality = member(value, "location_quality", LOCATION_QUALITIES);
            duplicateCount = integer(value, "duplicate_count", 0, Integer.MAX_VALUE);
            capturedAt = instant(value, "captured_at");
            createdAt = instant(value, "created_at");
            updatedAt = instant(value, "updated_at");
        }

        public String id() { return id; }
        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public String className() { return className; }
        public double confidence() { return confidence; }
        public String locationQuality() { return locationQuality; }
        public int duplicateCount() { return duplicateCount; }
        public String capturedAt() { return capturedAt; }
        public String createdAt() { return createdAt; }
        public String updatedAt() { return updatedAt; }
    }

    public static final class Page {
        private final List<Summary> items;
        private final String nextCursor;

        private Page(List<Summary> items, String nextCursor) {
            this.items = AdminJava8Collections.copyList(items);
            this.nextCursor = nextCursor;
        }

        public List<Summary> items() { return items; }
        public String nextCursor() { return nextCursor; }
    }

    public static final class ReviewSummary {
        private final int revision;
        private final String decision;
        private final String userVisibleReason;
        private final String duplicateOfReportId;
        private final boolean locationReviewed;
        private final boolean photoReviewed;
        private final boolean privacyReviewed;
        private final String decidedAt;

        private ReviewSummary(Map<String, Object> value) throws IOException {
            exactKeys(value, AdminJava8Collections.set(
                "revision", "decision", "user_visible_reason", "duplicate_of_report_id", "location_reviewed",
                "photo_reviewed", "privacy_reviewed", "decided_at"
            ));
            revision = integer(value, "revision", 1, Integer.MAX_VALUE);
            decision = member(value, "decision", AdminJava8Collections.set("APPROVED", "REJECTED", "DUPLICATE"));
            userVisibleReason = nullableText(value, "user_visible_reason", 500);
            duplicateOfReportId = nullableUuid(value, "duplicate_of_report_id");
            locationReviewed = bool(value, "location_reviewed");
            photoReviewed = bool(value, "photo_reviewed");
            privacyReviewed = bool(value, "privacy_reviewed");
            decidedAt = instant(value, "decided_at");
            if (("DUPLICATE".equals(decision)) != (duplicateOfReportId != null)) {
                throw new IOException("review duplicate binding is invalid");
            }
            if (("APPROVED".equals(decision)) != (userVisibleReason == null)) {
                throw new IOException("review user-visible reason binding is invalid");
            }
        }

        public int revision() { return revision; }
        public String decision() { return decision; }
        public String userVisibleReason() { return userVisibleReason; }
        public String duplicateOfReportId() { return duplicateOfReportId; }
        public boolean locationReviewed() { return locationReviewed; }
        public boolean photoReviewed() { return photoReviewed; }
        public boolean privacyReviewed() { return privacyReviewed; }
        public String decidedAt() { return decidedAt; }
    }

    public static final class DeliverySummary {
        private final int revision;
        private final Integer packageRevision;
        private final String status;
        private final boolean externalReceiptPresent;
        private final boolean evidencePresent;
        private final String observedAt;
        private final String recordedAt;

        private DeliverySummary(Map<String, Object> value) throws IOException {
            exactKeys(value, AdminJava8Collections.set(
                "revision", "package_revision", "status", "external_receipt_present", "evidence_present",
                "observed_at", "recorded_at"
            ));
            revision = integer(value, "revision", 1, Integer.MAX_VALUE);
            packageRevision = nullableInteger(value, "package_revision", 1, Integer.MAX_VALUE);
            status = member(value, "status", AdminJava8Collections.set("SUBMITTED", "ACKNOWLEDGED", "RESOLVED", "FAILED"));
            externalReceiptPresent = bool(value, "external_receipt_present");
            evidencePresent = bool(value, "evidence_present");
            observedAt = instant(value, "observed_at");
            recordedAt = instant(value, "recorded_at");
        }

        public int revision() { return revision; }
        public Integer packageRevision() { return packageRevision; }
        public String status() { return status; }
        public boolean externalReceiptPresent() { return externalReceiptPresent; }
        public boolean evidencePresent() { return evidencePresent; }
        public String observedAt() { return observedAt; }
        public String recordedAt() { return recordedAt; }
    }

    public static final class Detail {
        private final Summary summary;
        private final ReviewSummary review;
        private final DeliverySummary delivery;
        private final List<String> allowedNextStatuses;
        private final String reviewPath;
        private final String deliveriesPath;
        private final String originalGrantsPath;
        private final String statusPath;
        private final String deliveryPackagesPath;

        private Detail(
            Summary summary,
            ReviewSummary review,
            DeliverySummary delivery,
            List<String> allowedNextStatuses,
            String reviewPath,
            String deliveriesPath,
            String originalGrantsPath,
            String statusPath,
            String deliveryPackagesPath
        ) {
            this.summary = summary;
            this.review = review;
            this.delivery = delivery;
            this.allowedNextStatuses = AdminJava8Collections.copyList(allowedNextStatuses);
            this.reviewPath = reviewPath;
            this.deliveriesPath = deliveriesPath;
            this.originalGrantsPath = originalGrantsPath;
            this.statusPath = statusPath;
            this.deliveryPackagesPath = deliveryPackagesPath;
        }

        public Summary summary() { return summary; }
        public ReviewSummary review() { return review; }
        public DeliverySummary delivery() { return delivery; }
        public List<String> allowedNextStatuses() { return allowedNextStatuses; }
        public String reviewPath() { return reviewPath; }
        public String deliveriesPath() { return deliveriesPath; }
        public String originalGrantsPath() { return originalGrantsPath; }
        public String statusPath() { return statusPath; }
        public String deliveryPackagesPath() { return deliveryPackagesPath; }
    }

    public static Page parsePage(String body) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, AdminJava8Collections.set("schema_version", "items", "next_cursor"));
        if (!LIST_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported administrator report list schema");
        }
        Object rawItems = root.get("items");
        if (!(rawItems instanceof List<?> values) || values.size() > 100) {
            throw new IOException("administrator report list items are invalid");
        }
        List<Summary> items = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object)) throw new IOException("report summary must be an object");
            items.add(new Summary(stringObject(object)));
        }
        String cursor = nullableText(root, "next_cursor", 1024);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new IOException("administrator report cursor is invalid");
        }
        return new Page(items, cursor);
    }

    public static Detail parseDetail(String body, String expectedReportId) throws IOException {
        String safeId = canonicalUuid(expectedReportId, "report_id");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, AdminJava8Collections.set(
            "schema_version", "id", "status", "status_version", "allowed_next_statuses",
            "class_name", "confidence", "location_quality",
            "captured_at", "created_at", "updated_at", "current_review", "current_delivery",
            "capabilities"
        ));
        if (!DETAIL_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported administrator report detail schema");
        }
        Map<String, Object> summaryFields = new java.util.LinkedHashMap<>();
        for (String key : AdminJava8Collections.set(
            "id", "status", "status_version", "class_name", "confidence", "location_quality",
            "captured_at", "created_at", "updated_at"
        )) summaryFields.put(key, root.get(key));
        summaryFields.put("duplicate_count", 0L);
        Summary summary = new Summary(summaryFields);
        if (!safeId.equals(summary.id())) throw new IOException("administrator report detail id is mismatched");
        List<String> allowedNextStatuses = statusList(root, "allowed_next_statuses", summary.status());
        ReviewSummary review = nullableObject(root, "current_review") == null
            ? null : new ReviewSummary(nullableObject(root, "current_review"));
        DeliverySummary delivery = nullableObject(root, "current_delivery") == null
            ? null : new DeliverySummary(nullableObject(root, "current_delivery"));
        Map<String, Object> capabilities = requiredObject(root, "capabilities");
        exactKeys(capabilities, AdminJava8Collections.set(
            "review_decisions_path", "deliveries_path", "original_access_grants_path",
            "status_path", "delivery_packages_path"
        ));
        String prefix = "/reports/" + safeId;
        String reviewPath = exactPath(capabilities, "review_decisions_path", prefix + "/review-decisions");
        String deliveriesPath = exactPath(capabilities, "deliveries_path", prefix + "/deliveries");
        String originalPath = exactPath(
            capabilities,
            "original_access_grants_path",
            prefix + "/original-access-grants"
        );
        String adminPrefix = "/admin/reports/" + safeId;
        String statusPath = exactPath(capabilities, "status_path", adminPrefix + "/status");
        String packagePath = exactPath(
            capabilities,
            "delivery_packages_path",
            adminPrefix + "/delivery-packages"
        );
        return new Detail(
            summary,
            review,
            delivery,
            allowedNextStatuses,
            reviewPath,
            deliveriesPath,
            originalPath,
            statusPath,
            packagePath
        );
    }

    public static final class StatusSnapshot {
        private final String id;
        private final String status;
        private final int statusVersion;
        private final List<String> allowedNextStatuses;
        private final String updatedAt;

        private StatusSnapshot(
            String id,
            String status,
            int statusVersion,
            List<String> allowedNextStatuses,
            String updatedAt
        ) {
            this.id = id;
            this.status = status;
            this.statusVersion = statusVersion;
            this.allowedNextStatuses = AdminJava8Collections.copyList(allowedNextStatuses);
            this.updatedAt = updatedAt;
        }

        public String id() { return id; }
        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public List<String> allowedNextStatuses() { return allowedNextStatuses; }
        public String updatedAt() { return updatedAt; }
    }

    public static final class StatusConflict {
        private final String status;
        private final int statusVersion;
        private final List<String> allowedNextStatuses;

        private StatusConflict(String status, int statusVersion, List<String> allowedNextStatuses) {
            this.status = status;
            this.statusVersion = statusVersion;
            this.allowedNextStatuses = AdminJava8Collections.copyList(allowedNextStatuses);
        }

        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public List<String> allowedNextStatuses() { return allowedNextStatuses; }
    }

    public static StatusSnapshot parseStatus(String body, String expectedReportId) throws IOException {
        String safeId = canonicalUuid(expectedReportId, "report_id");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, AdminJava8Collections.set(
            "schema_version", "id", "status", "status_version", "allowed_next_statuses", "updated_at"
        ));
        if (!STATUS_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported administrator report status schema");
        }
        String id = uuid(root, "id");
        if (!safeId.equals(id)) throw new IOException("administrator report status id is mismatched");
        String status = member(root, "status", STATUSES);
        return new StatusSnapshot(
            id,
            status,
            integer(root, "status_version", 1, Integer.MAX_VALUE),
            statusList(root, "allowed_next_statuses", status),
            instant(root, "updated_at")
        );
    }

    public static StatusConflict parseStatusConflict(String body) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, AdminJava8Collections.set("detail"));
        Map<String, Object> detail = requiredObject(root, "detail");
        exactKeys(detail, AdminJava8Collections.set("code", "message", "latest"));
        if (!"report_status_version_conflict".equals(text(detail, "code", 64))) {
            throw new IOException("administrator report conflict code is invalid");
        }
        text(detail, "message", 500);
        Map<String, Object> latest = requiredObject(detail, "latest");
        exactKeys(latest, AdminJava8Collections.set("status", "status_version", "allowed_next_statuses"));
        String status = member(latest, "status", STATUSES);
        return new StatusConflict(
            status,
            integer(latest, "status_version", 1, Integer.MAX_VALUE),
            statusList(latest, "allowed_next_statuses", status)
        );
    }

    public static String canonicalUuid(String value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException(label + " is not canonical");
            return value;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(label + " is not a canonical UUID", error);
        }
    }

    private static String optionalCanonicalUuid(String value, String label) {
        String normalized = emptyToNull(value);
        return normalized == null ? null : canonicalUuid(normalized, label);
    }

    private static String optionalMember(String value, Set<String> allowed, String label) {
        String normalized = emptyToNull(value);
        if (normalized != null && !allowed.contains(normalized)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private static String optionalInstant(String value, String label) {
        String normalized = emptyToNull(value);
        if (normalized == null) return null;
        try {
            OffsetDateTime.parse(normalized);
            return normalized;
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(label + " must be RFC3339 with an offset", error);
        }
    }

    private static String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (normalized.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("filter text contains control characters");
        }
        return normalized;
    }

    private static void exactKeys(Map<String, Object> value, Set<String> expected) throws IOException {
        if (!value.keySet().equals(expected)) throw new IOException("administrator report fields do not match contract");
    }

    private static String text(Map<String, Object> value, String key, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.trim().isEmpty() || text.length() > max
            || text.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IOException("administrator report text is invalid: " + key);
        }
        return text;
    }

    private static String nullableText(Map<String, Object> value, String key, int max) throws IOException {
        if (value.get(key) == null) return null;
        return text(value, key, max);
    }

    private static String uuid(Map<String, Object> value, String key) throws IOException {
        try {
            return canonicalUuid(text(value, key, 36), key);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator report UUID is invalid: " + key, error);
        }
    }

    private static String nullableUuid(Map<String, Object> value, String key) throws IOException {
        String raw = nullableText(value, key, 36);
        if (raw == null) return null;
        try {
            return canonicalUuid(raw, key);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator report UUID is invalid: " + key, error);
        }
    }

    private static String member(Map<String, Object> value, String key, Set<String> allowed) throws IOException {
        String parsed = text(value, key, 64);
        if (!allowed.contains(parsed)) throw new IOException("administrator report enum is invalid: " + key);
        return parsed;
    }

    private static boolean bool(Map<String, Object> value, String key) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Boolean parsed)) throw new IOException("administrator report flag is invalid: " + key);
        return parsed;
    }

    private static int integer(Map<String, Object> value, String key, int min, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Long parsed) || parsed < min || parsed > max) {
            throw new IOException("administrator report integer is invalid: " + key);
        }
        return parsed.intValue();
    }

    private static Integer nullableInteger(
        Map<String, Object> value,
        String key,
        int min,
        int max
    ) throws IOException {
        return value.get(key) == null ? null : integer(value, key, min, max);
    }

    private static List<String> statusList(
        Map<String, Object> value,
        String key,
        String currentStatus
    ) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> list) || list.size() > 2) {
            throw new IOException("administrator report allowed statuses are invalid");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String status) || !STATUSES.contains(status)
                || status.equals(currentStatus) || result.contains(status)) {
                throw new IOException("administrator report allowed statuses are invalid");
            }
            result.add(status);
        }
        return AdminJava8Collections.copyList(result);
    }

    private static double decimal(Map<String, Object> value, String key, double min, double max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) throw new IOException("administrator report decimal is invalid: " + key);
        double parsed = number instanceof BigDecimal decimal ? decimal.doubleValue() : number.doubleValue();
        if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
            throw new IOException("administrator report decimal is invalid: " + key);
        }
        return parsed;
    }

    private static String instant(Map<String, Object> value, String key) throws IOException {
        String parsed = text(value, key, 64);
        try {
            OffsetDateTime.parse(parsed);
            return parsed;
        } catch (DateTimeException error) {
            throw new IOException("administrator report timestamp is invalid: " + key, error);
        }
    }

    private static Map<String, Object> nullableObject(Map<String, Object> value, String key) throws IOException {
        Object raw = value.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> object)) throw new IOException("administrator report object is invalid: " + key);
        return stringObject(object);
    }

    private static Map<String, Object> requiredObject(Map<String, Object> value, String key) throws IOException {
        Map<String, Object> result = nullableObject(value, key);
        if (result == null) throw new IOException("administrator report object is required: " + key);
        return result;
    }

    private static Map<String, Object> stringObject(Map<?, ?> value) throws IOException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("administrator report object key is invalid");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String exactPath(Map<String, Object> value, String key, String expected) throws IOException {
        String actual = text(value, key, 512);
        if (!expected.equals(actual)) throw new IOException("administrator report capability path is invalid");
        return actual;
    }
}
