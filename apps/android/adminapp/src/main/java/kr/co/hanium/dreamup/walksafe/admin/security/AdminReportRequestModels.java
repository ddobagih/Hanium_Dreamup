package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict minimum projections for administrator handling of user report requests. */
public final class AdminReportRequestModels {
    public static final String LIST_SCHEMA = "walksafe.admin-report-request-list.v1";
    public static final String DETAIL_SCHEMA = "walksafe.admin-report-request-detail.v1";
    public static final String STATUS_SCHEMA = "walksafe.admin-report-request-status.v1";
    public static final int PAGE_SIZE = 25;
    private static final Set<String> REQUEST_TYPES = immutableSet("CORRECTION", "DELETE");
    private static final Set<String> STATUSES = immutableSet(
        "RECEIVED", "ACKNOWLEDGED", "RESOLVED", "REJECTED"
    );
    private static final Set<String> SUMMARY_KEYS = immutableSet(
        "request_id", "report_id", "request_type", "status", "status_version",
        "created_at", "updated_at"
    );

    private AdminReportRequestModels() {}

    public static final class Filters {
        private final String reportId;
        private final String requestType;
        private final String status;

        public Filters(String reportId, String requestType, String status) {
            this.reportId = optionalCanonicalUuid(reportId, "report_id");
            this.requestType = optionalMember(requestType, REQUEST_TYPES, "request_type");
            this.status = optionalMember(status, STATUSES, "status");
        }

        public String reportId() { return reportId; }
        public String requestType() { return requestType; }
        public String status() { return status; }
    }

    public static final class Summary {
        private final String requestId;
        private final String reportId;
        private final String requestType;
        private final String status;
        private final int statusVersion;
        private final String createdAt;
        private final String updatedAt;

        private Summary(Map<String, Object> value) throws IOException {
            exactKeys(value, SUMMARY_KEYS);
            requestId = uuid(value, "request_id");
            reportId = uuid(value, "report_id");
            requestType = member(value, "request_type", REQUEST_TYPES);
            status = member(value, "status", STATUSES);
            statusVersion = integer(value, "status_version", 1, Integer.MAX_VALUE);
            createdAt = instant(value, "created_at");
            updatedAt = instant(value, "updated_at");
        }

        public String requestId() { return requestId; }
        public String reportId() { return reportId; }
        public String requestType() { return requestType; }
        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public String createdAt() { return createdAt; }
        public String updatedAt() { return updatedAt; }
        public List<String> allowedNextStatuses() {
            return allowedNextStatusesFor(requestType, status);
        }
    }

    public static final class Page {
        private final List<Summary> items;
        private final String nextCursor;

        private Page(List<Summary> items, String nextCursor) {
            this.items = immutableList(items);
            this.nextCursor = nextCursor;
        }

        public List<Summary> items() { return items; }
        public String nextCursor() { return nextCursor; }
    }

    public static final class Detail {
        private final Summary summary;
        private final String requestText;
        private final String publicResponse;
        private final String internalNote;

        private Detail(
            Summary summary,
            String requestText,
            String publicResponse,
            String internalNote
        ) {
            this.summary = summary;
            this.requestText = requestText;
            this.publicResponse = publicResponse;
            this.internalNote = internalNote;
        }

        public Summary summary() { return summary; }
        public String requestText() { return requestText; }
        public String publicResponse() { return publicResponse; }
        public String internalNote() { return internalNote; }
    }

    public static final class StatusSnapshot {
        private final String requestId;
        private final String reportId;
        private final String status;
        private final int statusVersion;
        private final List<String> allowedNextStatuses;
        private final String publicResponse;
        private final String updatedAt;

        private StatusSnapshot(
            String requestId,
            String reportId,
            String status,
            int statusVersion,
            List<String> allowedNextStatuses,
            String publicResponse,
            String updatedAt
        ) {
            this.requestId = requestId;
            this.reportId = reportId;
            this.status = status;
            this.statusVersion = statusVersion;
            this.allowedNextStatuses = immutableList(allowedNextStatuses);
            this.publicResponse = publicResponse;
            this.updatedAt = updatedAt;
        }

        public String requestId() { return requestId; }
        public String reportId() { return reportId; }
        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public List<String> allowedNextStatuses() { return allowedNextStatuses; }
        public String publicResponse() { return publicResponse; }
        public String updatedAt() { return updatedAt; }
    }

    public static final class StatusConflict {
        private final StatusSnapshot latest;

        private StatusConflict(StatusSnapshot latest) {
            this.latest = latest;
        }

        public StatusSnapshot latest() { return latest; }
    }

    public static Page parsePage(String body) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, immutableSet("schema_version", "items", "next_cursor"));
        if (!LIST_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported administrator report request list schema");
        }
        Object rawItems = root.get("items");
        if (!(rawItems instanceof List<?> values) || values.size() > 100) {
            throw new IOException("administrator report request list items are invalid");
        }
        List<Summary> items = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object)) {
                throw new IOException("administrator report request summary must be an object");
            }
            items.add(new Summary(stringObject(object)));
        }
        String cursor = nullableText(root, "next_cursor", 1024);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new IOException("administrator report request cursor is invalid");
        }
        return new Page(items, cursor);
    }

    public static Detail parseDetail(String body, String expectedRequestId) throws IOException {
        String safeId = canonicalUuid(expectedRequestId, "request_id");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, immutableSet(
            "schema_version", "request_id", "report_id", "request_type", "status",
            "status_version", "created_at", "updated_at", "request_text",
            "public_response", "internal_note"
        ));
        if (!DETAIL_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported administrator report request detail schema");
        }
        Map<String, Object> summaryFields = new java.util.LinkedHashMap<>();
        for (String key : SUMMARY_KEYS) summaryFields.put(key, root.get(key));
        Summary summary = new Summary(summaryFields);
        if (!safeId.equals(summary.requestId())) {
            throw new IOException("administrator report request detail id is mismatched");
        }
        return new Detail(
            summary,
            contentText(root, "request_text", 500),
            nullableContentText(root, "public_response", 500),
            nullableContentText(root, "internal_note", 500)
        );
    }

    public static StatusSnapshot parseStatus(
        String body,
        String expectedRequestId,
        String expectedRequestType
    ) throws IOException {
        String safeId = canonicalUuid(expectedRequestId, "request_id");
        String safeType = requestType(expectedRequestType);
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, immutableSet(
            "schema_version", "request_id", "report_id", "status", "status_version",
            "allowed_next_statuses", "public_response", "updated_at"
        ));
        if (!STATUS_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported administrator report request status schema");
        }
        return status(root, safeId, safeType);
    }

    public static StatusConflict parseStatusConflict(
        String body,
        String expectedRequestId,
        String expectedRequestType
    )
        throws IOException {
        String safeId = canonicalUuid(expectedRequestId, "request_id");
        String safeType = requestType(expectedRequestType);
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, immutableSet("detail"));
        Map<String, Object> detail = requiredObject(root, "detail");
        exactKeys(detail, immutableSet("code", "message", "latest"));
        if (!"report_request_version_conflict".equals(text(detail, "code", 64))) {
            throw new IOException("administrator report request conflict code is invalid");
        }
        text(detail, "message", 500);
        Map<String, Object> latest = requiredObject(detail, "latest");
        exactKeys(latest, immutableSet(
            "request_id", "report_id", "status", "status_version",
            "allowed_next_statuses", "public_response", "updated_at"
        ));
        return new StatusConflict(status(latest, safeId, safeType));
    }

    public static String canonicalUuid(String value, String label) {
        return AdminReportModels.canonicalUuid(value, label);
    }

    static String optionalResponse(String value, String label) {
        String normalized = emptyToNull(value);
        if (normalized != null && normalized.length() > 500) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return normalized;
    }

    static String requestType(String value) {
        String normalized = emptyToNull(value);
        if (normalized == null || !REQUEST_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("request_type is invalid");
        }
        return normalized;
    }

    static List<String> allowedNextStatusesFor(String requestType, String status) {
        String safeType = requestType(requestType);
        return switch (status) {
            case "RECEIVED" -> Collections.singletonList("ACKNOWLEDGED");
            case "ACKNOWLEDGED" -> "DELETE".equals(safeType)
                ? Collections.singletonList("REJECTED")
                : immutableList(Arrays.asList("RESOLVED", "REJECTED"));
            case "RESOLVED", "REJECTED" -> Collections.emptyList();
            default -> throw new IllegalArgumentException("request status is invalid");
        };
    }

    private static StatusSnapshot status(
        Map<String, Object> value,
        String expectedRequestId,
        String expectedRequestType
    ) throws IOException {
        String requestId = uuid(value, "request_id");
        if (!expectedRequestId.equals(requestId)) {
            throw new IOException("administrator report request status id is mismatched");
        }
        String reportId = uuid(value, "report_id");
        String status = member(value, "status", STATUSES);
        List<String> allowed = statusList(value, "allowed_next_statuses", status);
        if (!allowed.equals(allowedNextStatusesFor(expectedRequestType, status))) {
            throw new IOException("administrator report request status transition projection is invalid");
        }
        return new StatusSnapshot(
            requestId,
            reportId,
            status,
            integer(value, "status_version", 1, Integer.MAX_VALUE),
            allowed,
            nullableContentText(value, "public_response", 500),
            instant(value, "updated_at")
        );
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

    private static String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (normalized.chars().anyMatch(AdminReportRequestModels::isForbiddenContentControl)) {
            throw new IllegalArgumentException("administrator report request text contains control characters");
        }
        return normalized;
    }

    private static void exactKeys(Map<String, Object> value, Set<String> expected) throws IOException {
        if (!value.keySet().equals(expected)) {
            throw new IOException("administrator report request fields do not match contract");
        }
    }

    private static String text(Map<String, Object> value, String key, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String parsed) || parsed.trim().isEmpty() || parsed.length() > max
            || !parsed.equals(parsed.trim())
            || parsed.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IOException("administrator report request text is invalid: " + key);
        }
        return parsed;
    }

    private static String nullableText(Map<String, Object> value, String key, int max) throws IOException {
        if (value.get(key) == null) return null;
        return text(value, key, max);
    }

    private static String contentText(Map<String, Object> value, String key, int max)
        throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String parsed) || parsed.trim().isEmpty() || parsed.length() > max
            || !parsed.equals(parsed.trim())
            || parsed.chars().anyMatch(AdminReportRequestModels::isForbiddenContentControl)) {
            throw new IOException("administrator report request content is invalid: " + key);
        }
        return parsed;
    }

    private static String nullableContentText(
        Map<String, Object> value,
        String key,
        int max
    ) throws IOException {
        if (value.get(key) == null) return null;
        return contentText(value, key, max);
    }

    private static boolean isForbiddenContentControl(int character) {
        return character < 0x20 && character != '\n' && character != '\r' && character != '\t'
            || character == 0x7f;
    }

    private static String uuid(Map<String, Object> value, String key) throws IOException {
        try {
            return canonicalUuid(text(value, key, 36), key);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator report request UUID is invalid: " + key, error);
        }
    }

    private static String member(Map<String, Object> value, String key, Set<String> allowed)
        throws IOException {
        String parsed = text(value, key, 64);
        if (!allowed.contains(parsed)) {
            throw new IOException("administrator report request enum is invalid: " + key);
        }
        return parsed;
    }

    private static int integer(Map<String, Object> value, String key, int min, int max)
        throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Long parsed) || parsed < min || parsed > max) {
            throw new IOException("administrator report request integer is invalid: " + key);
        }
        return parsed.intValue();
    }

    private static String instant(Map<String, Object> value, String key) throws IOException {
        String parsed = text(value, key, 64);
        try {
            OffsetDateTime.parse(parsed);
            return parsed;
        } catch (DateTimeException error) {
            throw new IOException("administrator report request instant is invalid: " + key, error);
        }
    }

    private static List<String> statusList(
        Map<String, Object> value,
        String key,
        String currentStatus
    ) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> list) || list.size() > 2) {
            throw new IOException("administrator report request allowed statuses are invalid");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String status) || !STATUSES.contains(status)
                || status.equals(currentStatus) || result.contains(status)) {
                throw new IOException("administrator report request allowed statuses are invalid");
            }
            result.add(status);
        }
        return immutableList(result);
    }

    private static Map<String, Object> requiredObject(Map<String, Object> value, String key)
        throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Map<?, ?> object)) {
            throw new IOException("administrator report request object is invalid: " + key);
        }
        return stringObject(object);
    }

    private static Map<String, Object> stringObject(Map<?, ?> raw) throws IOException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("administrator report request object key is invalid");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
