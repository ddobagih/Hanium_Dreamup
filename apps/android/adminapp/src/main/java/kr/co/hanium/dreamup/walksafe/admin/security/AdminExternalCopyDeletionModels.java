package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict record-only projections for institution-held report-copy deletion facts. */
public final class AdminExternalCopyDeletionModels {
    public static final int PAGE_SIZE = 25;
    public static final String LIST_SCHEMA =
        "walksafe.admin-report-deletion-external-copy-list.v1";
    public static final String EVENT_SCHEMA =
        "walksafe.admin-report-deletion-external-copy-event.v1";
    private static final Set<String> STATES = immutableSet(
        "NOT_REQUESTED", "REQUEST_SENT", "REPLY_ACKNOWLEDGED",
        "REPLY_DELETION_CONFIRMED", "REPLY_DECLINED"
    );
    private static final Set<String> RECORDED_STATES = immutableSet(
        "REQUEST_SENT", "REPLY_ACKNOWLEDGED", "REPLY_DELETION_CONFIRMED", "REPLY_DECLINED"
    );
    private static final Set<String> DELIVERY_STATUSES = immutableSet(
        "SUBMITTED", "ACKNOWLEDGED", "RESOLVED", "FAILED"
    );
    private static final Set<String> ITEM_KEYS = immutableSet(
        "request_id", "copy_id", "institution", "delivery_status_at_local_deletion",
        "state", "revision", "allowed_next_states", "status_observed_at",
        "status_recorded_at"
    );
    private static final Set<String> CONFLICT_CODES = immutableSet(
        "report_external_copy_revision_conflict",
        "report_external_copy_idempotency_conflict"
    );

    private AdminExternalCopyDeletionModels() {}

    public static final class Filter {
        private final String requestId;

        public Filter(String requestId) {
            this.requestId = optionalUuid(requestId, "request_id");
        }

        public String requestId() { return requestId; }
    }

    public static final class Item {
        private final String requestId;
        private final String copyId;
        private final String institution;
        private final String deliveryStatusAtLocalDeletion;
        private final String state;
        private final int revision;
        private final List<String> allowedNextStates;
        private final String statusObservedAt;
        private final String statusRecordedAt;

        private Item(Map<String, Object> value) throws IOException {
            exactKeys(value, ITEM_KEYS);
            requestId = uuid(value, "request_id");
            copyId = uuid(value, "copy_id");
            institution = content(value, "institution", 160);
            deliveryStatusAtLocalDeletion = member(
                value, "delivery_status_at_local_deletion", DELIVERY_STATUSES
            );
            state = member(value, "state", STATES);
            revision = integer(value, "revision", 0, Integer.MAX_VALUE);
            allowedNextStates = stateList(value, "allowed_next_states", state);
            if (!allowedNextStates.equals(allowedNextStatesFor(state))) {
                throw new IOException("external-copy transition projection is invalid");
            }
            statusObservedAt = nullableInstant(value, "status_observed_at");
            statusRecordedAt = nullableInstant(value, "status_recorded_at");
            if ((revision == 0) != "NOT_REQUESTED".equals(state)
                || (revision == 0) != (statusObservedAt == null)
                || (revision == 0) != (statusRecordedAt == null)) {
                throw new IOException("external-copy revision projection is invalid");
            }
        }

        public String requestId() { return requestId; }
        public String copyId() { return copyId; }
        public String institution() { return institution; }
        public String deliveryStatusAtLocalDeletion() { return deliveryStatusAtLocalDeletion; }
        public String state() { return state; }
        public int revision() { return revision; }
        public List<String> allowedNextStates() { return allowedNextStates; }
        public String statusObservedAt() { return statusObservedAt; }
        public String statusRecordedAt() { return statusRecordedAt; }
    }

    public static final class Page {
        private final List<Item> items;
        private final String nextCursor;

        private Page(List<Item> items, String nextCursor) {
            this.items = immutableList(items);
            this.nextCursor = nextCursor;
        }

        public List<Item> items() { return items; }
        public String nextCursor() { return nextCursor; }
    }

    public static final class EventCommand {
        private final String requestId;
        private final String copyId;
        private final String state;
        private final int expectedRevision;
        private final String idempotencyKey;
        private final String observedAt;
        private final String institutionReference;
        private final String evidenceSha256;

        public EventCommand(
            String requestId,
            String copyId,
            String state,
            int expectedRevision,
            String idempotencyKey,
            String observedAt,
            String institutionReference,
            String evidenceSha256
        ) {
            this.requestId = canonicalUuid(requestId, "request_id");
            this.copyId = canonicalUuid(copyId, "copy_id");
            if (!RECORDED_STATES.contains(state)) {
                throw new IllegalArgumentException("external-copy state is invalid");
            }
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expected_revision must be non-negative");
            }
            this.state = state;
            this.expectedRevision = expectedRevision;
            this.idempotencyKey = canonicalUuid(idempotencyKey, "idempotency_key");
            this.observedAt = utcInstant(observedAt, "observed_at");
            this.institutionReference = optionalReference(institutionReference);
            this.evidenceSha256 = optionalDigest(evidenceSha256);
            if (state.startsWith("REPLY_")
                && this.institutionReference == null && this.evidenceSha256 == null) {
                throw new IllegalArgumentException(
                    "institution replies require a reference or evidence digest"
                );
            }
            if ("REPLY_DELETION_CONFIRMED".equals(state) && this.evidenceSha256 == null) {
                throw new IllegalArgumentException("deletion confirmation requires evidence digest");
            }
        }

        public String requestId() { return requestId; }
        public String copyId() { return copyId; }
        public String state() { return state; }
        public int expectedRevision() { return expectedRevision; }
        public String idempotencyKey() { return idempotencyKey; }
        public String observedAt() { return observedAt; }
        public String institutionReference() { return institutionReference; }
        public String evidenceSha256() { return evidenceSha256; }

        Map<String, Object> bodyFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("evidence_sha256", evidenceSha256);
            fields.put("expected_revision", expectedRevision);
            fields.put("idempotency_key", idempotencyKey);
            fields.put("institution_reference", institutionReference);
            fields.put("observed_at", observedAt);
            fields.put("state", state);
            return fields;
        }
    }

    public static final class Conflict {
        private final Item latest;

        private Conflict(Item latest) { this.latest = latest; }

        public Item latest() { return latest; }
    }

    public static Page parsePage(String body) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, immutableSet("schema_version", "items", "next_cursor"));
        if (!LIST_SCHEMA.equals(text(root, "schema_version", 96))) {
            throw new IOException("unsupported external-copy list schema");
        }
        Object rawItems = root.get("items");
        if (!(rawItems instanceof List<?> values) || values.size() > 100) {
            throw new IOException("external-copy list items are invalid");
        }
        List<Item> items = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object)) {
                throw new IOException("external-copy item must be an object");
            }
            items.add(new Item(stringObject(object)));
        }
        String cursor = nullableText(root, "next_cursor", 1024);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new IOException("external-copy cursor is invalid");
        }
        return new Page(items, cursor);
    }

    public static Item parseEvent(String body, EventCommand expected) throws IOException {
        if (expected == null) throw new IllegalArgumentException("external-copy command is required");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        Set<String> keys = new HashSet<>(ITEM_KEYS);
        keys.add("schema_version");
        exactKeys(root, Collections.unmodifiableSet(keys));
        if (!EVENT_SCHEMA.equals(text(root, "schema_version", 96))) {
            throw new IOException("unsupported external-copy event schema");
        }
        Map<String, Object> fields = new LinkedHashMap<>(root);
        fields.remove("schema_version");
        Item item = new Item(fields);
        if (!expected.requestId().equals(item.requestId())
            || !expected.copyId().equals(item.copyId())
            || !expected.state().equals(item.state())
            || item.revision() != expected.expectedRevision() + 1
            || !sameInstant(expected.observedAt(), item.statusObservedAt())) {
            throw new IOException("external-copy event result is mismatched");
        }
        return item;
    }

    public static Conflict parseConflict(
        String body,
        String expectedRequestId,
        String expectedCopyId
    ) throws IOException {
        String safeRequestId = canonicalUuid(expectedRequestId, "request_id");
        String safeCopyId = canonicalUuid(expectedCopyId, "copy_id");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exactKeys(root, immutableSet("detail"));
        Map<String, Object> detail = requiredObject(root, "detail");
        exactKeys(detail, immutableSet("code", "message", "latest"));
        if (!CONFLICT_CODES.contains(text(detail, "code", 96))) {
            throw new IOException("external-copy conflict code is invalid");
        }
        text(detail, "message", 500);
        Item latest = new Item(requiredObject(detail, "latest"));
        if (!safeRequestId.equals(latest.requestId()) || !safeCopyId.equals(latest.copyId())) {
            throw new IOException("external-copy conflict latest is mismatched");
        }
        return new Conflict(latest);
    }

    public static List<String> allowedNextStatesFor(String state) {
        return switch (state) {
            case "NOT_REQUESTED" -> Collections.singletonList("REQUEST_SENT");
            case "REQUEST_SENT" -> immutableList(Arrays.asList(
                "REPLY_ACKNOWLEDGED", "REPLY_DELETION_CONFIRMED", "REPLY_DECLINED"
            ));
            case "REPLY_ACKNOWLEDGED" -> immutableList(Arrays.asList(
                "REPLY_DELETION_CONFIRMED", "REPLY_DECLINED"
            ));
            case "REPLY_DECLINED" -> Collections.singletonList("REQUEST_SENT");
            case "REPLY_DELETION_CONFIRMED" -> Collections.emptyList();
            default -> throw new IllegalArgumentException("external-copy state is invalid");
        };
    }

    public static String canonicalUuid(String value, String label) {
        return AdminReportModels.canonicalUuid(value, label);
    }

    private static String optionalUuid(String value, String label) {
        String normalized = emptyToNull(value);
        return normalized == null ? null : canonicalUuid(normalized, label);
    }

    private static String optionalReference(String value) {
        String normalized = emptyToNull(value);
        if (normalized != null
            && !normalized.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")) {
            throw new IllegalArgumentException("institution_reference is invalid");
        }
        return normalized;
    }

    private static String optionalDigest(String value) {
        String normalized = emptyToNull(value);
        if (normalized != null && !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("evidence_sha256 is invalid");
        }
        return normalized;
    }

    private static String utcInstant(String value, String label) {
        String normalized = emptyToNull(value);
        if (normalized == null || !normalized.matches(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?(?:Z|\\+00:00)"
        )) {
            throw new IllegalArgumentException(label + " must be RFC3339 UTC text");
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(normalized);
            if (!ZoneOffset.UTC.equals(parsed.getOffset())) throw new DateTimeException("not UTC");
            return parsed.toInstant().toString();
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(label + " must be RFC3339 UTC text", error);
        }
    }

    private static String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (!normalized.equals(value)
            || normalized.chars().anyMatch(AdminExternalCopyDeletionModels::forbiddenControl)) {
            throw new IllegalArgumentException("external-copy input is invalid");
        }
        return normalized;
    }

    private static int integer(Map<String, Object> value, String key, int min, int max)
        throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Long parsed) || parsed < min || parsed > max) {
            throw new IOException("external-copy integer is invalid: " + key);
        }
        return parsed.intValue();
    }

    private static String uuid(Map<String, Object> value, String key) throws IOException {
        try {
            return canonicalUuid(text(value, key, 36), key);
        } catch (IllegalArgumentException error) {
            throw new IOException("external-copy UUID is invalid: " + key, error);
        }
    }

    private static String content(Map<String, Object> value, String key, int max)
        throws IOException {
        String parsed = text(value, key, max);
        if (parsed.chars().anyMatch(AdminExternalCopyDeletionModels::forbiddenControl)) {
            throw new IOException("external-copy content is invalid: " + key);
        }
        return parsed;
    }

    private static String member(Map<String, Object> value, String key, Set<String> allowed)
        throws IOException {
        String parsed = text(value, key, 64);
        if (!allowed.contains(parsed)) throw new IOException("external-copy enum is invalid: " + key);
        return parsed;
    }

    private static List<String> stateList(
        Map<String, Object> value,
        String key,
        String current
    ) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> list) || list.size() > 3) {
            throw new IOException("external-copy allowed states are invalid");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String state) || !RECORDED_STATES.contains(state)
                || current.equals(state) || result.contains(state)) {
                throw new IOException("external-copy allowed states are invalid");
            }
            result.add(state);
        }
        return immutableList(result);
    }

    private static String nullableInstant(Map<String, Object> value, String key) throws IOException {
        if (value.get(key) == null) return null;
        String parsed = text(value, key, 64);
        try {
            OffsetDateTime.parse(parsed);
            return parsed;
        } catch (DateTimeException error) {
            throw new IOException("external-copy instant is invalid: " + key, error);
        }
    }

    private static boolean sameInstant(String first, String second) {
        if (first == null || second == null) return first == null && second == null;
        try {
            return OffsetDateTime.parse(first).toInstant().equals(
                OffsetDateTime.parse(second).toInstant()
            );
        } catch (DateTimeException error) {
            return false;
        }
    }

    private static String text(Map<String, Object> value, String key, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String parsed) || parsed.isEmpty() || parsed.length() > max
            || !parsed.equals(parsed.trim())
            || parsed.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IOException("external-copy text is invalid: " + key);
        }
        return parsed;
    }

    private static String nullableText(Map<String, Object> value, String key, int max)
        throws IOException {
        return value.get(key) == null ? null : text(value, key, max);
    }

    private static Map<String, Object> requiredObject(Map<String, Object> value, String key)
        throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Map<?, ?> object)) {
            throw new IOException("external-copy object is invalid: " + key);
        }
        return stringObject(object);
    }

    private static Map<String, Object> stringObject(Map<?, ?> raw) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("external-copy object key is invalid");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void exactKeys(Map<String, Object> value, Set<String> expected)
        throws IOException {
        if (!value.keySet().equals(expected)) {
            throw new IOException("external-copy fields do not match contract");
        }
    }

    private static boolean forbiddenControl(int character) {
        return character < 0x20 || character == 0x7f;
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
