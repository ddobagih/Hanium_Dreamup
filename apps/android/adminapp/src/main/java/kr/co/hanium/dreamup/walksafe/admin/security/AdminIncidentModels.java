package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict, content-minimized administrator projection for real CRITICAL incidents. */
public final class AdminIncidentModels {
    public static final String LIST_SCHEMA = "walksafe.admin-incident-list.v1";
    public static final String DETAIL_SCHEMA = "walksafe.admin-incident-detail.v1";
    public static final String HISTORY_SCHEMA = "walksafe.admin-incident-history-page.v1";
    public static final String STATUS_SCHEMA = "walksafe.admin-incident-status.v1";
    public static final int PAGE_SIZE = 25;

    private static final Set<String> STATES = AdminJava8Collections.set(
        "OPEN", "ACKNOWLEDGED", "RESOLVED", "REOPENED"
    );
    private static final Set<String> EVENT_TYPES = AdminJava8Collections.set(
        "OPENED", "ACKNOWLEDGED", "RESOLVED", "REOPENED"
    );
    private static final Set<String> MUTATION_STATES = AdminJava8Collections.set(
        "ACKNOWLEDGED", "RESOLVED", "REOPENED"
    );
    private static final Set<String> REASON_CODES = AdminJava8Collections.set(
        "USER_SAFETY_RISK",
        "PERSONAL_DATA_BREACH",
        "DELETION_INTEGRITY_FAILURE",
        "CORE_SERVICE_TOTAL_OUTAGE",
        "IRREVERSIBLE_DATA_LOSS"
    );
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    private AdminIncidentModels() {}

    public static final class Filters {
        private final String status;

        public Filters(String status) {
            String normalized = normalizeOptional(status);
            if (normalized != null && !STATES.contains(normalized)) {
                throw new IllegalArgumentException("incident status filter is invalid");
            }
            this.status = normalized;
        }

        public String status() { return status; }
    }

    public static final class Summary {
        private final String incidentId;
        private final String severity;
        private final String status;
        private final int statusVersion;
        private final String reasonCode;
        private final String summary;
        private final String startedAt;
        private final String detectedAt;
        private final String updatedAt;

        private Summary(Map<String, Object> value) throws IOException {
            exact(value, AdminJava8Collections.set(
                "incident_id", "severity", "status", "status_version", "reason_code",
                "summary", "started_at", "detected_at", "updated_at"
            ));
            incidentId = uuid(value, "incident_id");
            severity = text(value, "severity", 16);
            if (!"CRITICAL".equals(severity)) {
                throw new IOException("only CRITICAL incidents may be displayed");
            }
            status = member(value, "status", STATES);
            statusVersion = integer(value, "status_version", 1, Integer.MAX_VALUE);
            reasonCode = member(value, "reason_code", REASON_CODES);
            summary = text(value, "summary", 200);
            startedAt = instant(value, "started_at");
            detectedAt = instant(value, "detected_at");
            updatedAt = instant(value, "updated_at");
            if (OffsetDateTime.parse(startedAt).isAfter(OffsetDateTime.parse(detectedAt))) {
                throw new IOException("incident started_at is after detected_at");
            }
        }

        public String incidentId() { return incidentId; }
        public String severity() { return severity; }
        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public String reasonCode() { return reasonCode; }
        public String summary() { return summary; }
        public String startedAt() { return startedAt; }
        public String detectedAt() { return detectedAt; }
        public String updatedAt() { return updatedAt; }
    }

    public static final class Event {
        private final String eventId;
        private final int revision;
        private final String eventType;
        private final String previousState;
        private final String nextState;
        private final String reason;
        private final String observation;
        private final String evidenceSha256;
        private final String observedAt;
        private final String recordedAt;
        private final String actorId;

        private Event(Map<String, Object> value) throws IOException {
            exact(value, AdminJava8Collections.set(
                "event_id", "revision", "event_type", "previous_state", "next_state",
                "reason", "observation", "evidence_sha256", "observed_at", "recorded_at",
                "actor_id"
            ));
            eventId = uuid(value, "event_id");
            revision = integer(value, "revision", 1, Integer.MAX_VALUE);
            eventType = member(value, "event_type", EVENT_TYPES);
            previousState = nullableMember(value, "previous_state", STATES);
            nextState = member(value, "next_state", STATES);
            reason = text(value, "reason", 500);
            observation = text(value, "observation", 500);
            evidenceSha256 = text(value, "evidence_sha256", 64);
            if (!evidenceSha256.matches(SHA256_PATTERN)) {
                throw new IOException("incident evidence digest is invalid");
            }
            observedAt = instant(value, "observed_at");
            recordedAt = instant(value, "recorded_at");
            actorId = nullableText(value, "actor_id", 64);
            validateEventBinding();
        }

        private void validateEventBinding() throws IOException {
            if (revision == 1) {
                if (!"OPENED".equals(eventType) || previousState != null
                    || !"OPEN".equals(nextState) || actorId != null) {
                    throw new IOException("incident opening event binding is invalid");
                }
                return;
            }
            if (actorId == null || !actorId.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
                throw new IOException("incident operator binding is invalid");
            }
            if (!nextState.equals(eventType) || !isAllowedTransition(previousState, nextState)) {
                throw new IOException("incident event transition is invalid");
            }
        }

        public String eventId() { return eventId; }
        public int revision() { return revision; }
        public String eventType() { return eventType; }
        public String previousState() { return previousState; }
        public String nextState() { return nextState; }
        public String reason() { return reason; }
        public String observation() { return observation; }
        public String evidenceSha256() { return evidenceSha256; }
        public String observedAt() { return observedAt; }
        public String recordedAt() { return recordedAt; }
        public String actorId() { return actorId; }
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

    public static final class HistoryPage {
        private final Summary incident;
        private final List<String> allowedNextStates;
        private final int snapshotRevision;
        private final int totalCount;
        private final List<Event> items;
        private final String nextCursor;

        private HistoryPage(
            Summary incident,
            List<String> allowedNextStates,
            int snapshotRevision,
            int totalCount,
            List<Event> items,
            String nextCursor
        ) {
            this.incident = incident;
            this.allowedNextStates = AdminJava8Collections.copyList(allowedNextStates);
            this.snapshotRevision = snapshotRevision;
            this.totalCount = totalCount;
            this.items = AdminJava8Collections.copyList(items);
            this.nextCursor = nextCursor;
        }

        public Summary incident() { return incident; }
        public List<String> allowedNextStates() { return allowedNextStates; }
        public int snapshotRevision() { return snapshotRevision; }
        public int totalCount() { return totalCount; }
        public List<Event> items() { return items; }
        public String nextCursor() { return nextCursor; }
    }

    public static final class Detail {
        private final Summary summary;
        private final List<String> allowedNextStates;
        private final List<Event> events;

        private Detail(Summary summary, List<String> allowedNextStates, List<Event> events) {
            this.summary = summary;
            this.allowedNextStates = AdminJava8Collections.copyList(allowedNextStates);
            this.events = AdminJava8Collections.copyList(events);
        }

        public Summary summary() { return summary; }
        public List<String> allowedNextStates() { return allowedNextStates; }
        public List<Event> events() { return events; }
    }

    public static final class StatusSnapshot {
        private final String incidentId;
        private final String status;
        private final int statusVersion;
        private final List<String> allowedNextStates;
        private final String updatedAt;

        private StatusSnapshot(
            String incidentId,
            String status,
            int statusVersion,
            List<String> allowedNextStates,
            String updatedAt
        ) {
            this.incidentId = incidentId;
            this.status = status;
            this.statusVersion = statusVersion;
            this.allowedNextStates = AdminJava8Collections.copyList(allowedNextStates);
            this.updatedAt = updatedAt;
        }

        public String incidentId() { return incidentId; }
        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public List<String> allowedNextStates() { return allowedNextStates; }
        public String updatedAt() { return updatedAt; }
    }

    public static final class StatusConflict {
        private final String status;
        private final int statusVersion;
        private final List<String> allowedNextStates;

        private StatusConflict(String status, int statusVersion, List<String> allowedNextStates) {
            this.status = status;
            this.statusVersion = statusVersion;
            this.allowedNextStates = AdminJava8Collections.copyList(allowedNextStates);
        }

        public String status() { return status; }
        public int statusVersion() { return statusVersion; }
        public List<String> allowedNextStates() { return allowedNextStates; }
    }

    public static final class StatusRequest {
        private final String nextState;
        private final int expectedVersion;
        private final String idempotencyKey;
        private final String reason;
        private final String observation;
        private final String evidenceSha256;

        public StatusRequest(
            String nextState,
            int expectedVersion,
            String idempotencyKey,
            String reason,
            String observation,
            String evidenceSha256
        ) {
            if (!MUTATION_STATES.contains(nextState) || expectedVersion < 1) {
                throw new IllegalArgumentException("incident status request is invalid");
            }
            this.nextState = nextState;
            this.expectedVersion = expectedVersion;
            this.idempotencyKey = canonicalUuid(idempotencyKey, "idempotency_key");
            this.reason = boundedInput(reason, "reason", 8, 500);
            this.observation = boundedInput(observation, "observation", 8, 500);
            this.evidenceSha256 = boundedInput(evidenceSha256, "evidence_sha256", 64, 64);
            if (!this.evidenceSha256.matches(SHA256_PATTERN)) {
                throw new IllegalArgumentException("evidence_sha256 must be lowercase SHA-256");
            }
        }

        public String nextState() { return nextState; }
        public int expectedVersion() { return expectedVersion; }
        public String idempotencyKey() { return idempotencyKey; }
        public String reason() { return reason; }
        public String observation() { return observation; }
        public String evidenceSha256() { return evidenceSha256; }
    }

    public static Page parsePage(String body) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exact(root, AdminJava8Collections.set("schema_version", "items", "next_cursor"));
        if (!LIST_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported incident list schema");
        }
        Object rawItems = root.get("items");
        if (!(rawItems instanceof List<?> values) || values.size() > 100) {
            throw new IOException("incident list items are invalid");
        }
        List<Summary> items = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> object)) throw new IOException("incident item is invalid");
            items.add(new Summary(stringObject(object)));
        }
        String cursor = nullableText(root, "next_cursor", 1024);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new IOException("incident cursor is invalid");
        }
        return new Page(items, cursor);
    }

    public static Detail parseDetail(String body, String expectedIncidentId) throws IOException {
        String safeId = canonicalUuid(expectedIncidentId, "incident_id");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exact(root, AdminJava8Collections.set(
            "schema_version", "incident_id", "severity", "status", "status_version",
            "reason_code", "summary", "started_at", "detected_at", "updated_at",
            "allowed_next_states", "events"
        ));
        if (!DETAIL_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported incident detail schema");
        }
        Map<String, Object> summaryFields = new java.util.LinkedHashMap<>();
        for (String key : AdminJava8Collections.set(
            "incident_id", "severity", "status", "status_version", "reason_code",
            "summary", "started_at", "detected_at", "updated_at"
        )) summaryFields.put(key, root.get(key));
        Summary summary = new Summary(summaryFields);
        if (!safeId.equals(summary.incidentId())) throw new IOException("incident detail id is mismatched");
        List<String> allowed = allowedStates(root, "allowed_next_states", summary.status());
        Object rawEvents = root.get("events");
        if (!(rawEvents instanceof List<?> values) || values.isEmpty() || values.size() > 256) {
            throw new IOException("incident events are invalid");
        }
        List<Event> events = new ArrayList<>(values.size());
        Set<String> eventIds = new HashSet<>();
        String previous = null;
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (!(value instanceof Map<?, ?> object)) throw new IOException("incident event is invalid");
            Event event = new Event(stringObject(object));
            if (!eventIds.add(event.eventId()) || event.revision() != index + 1
                || (index > 0 && !previous.equals(event.previousState()))) {
                throw new IOException("incident event history is not contiguous");
            }
            previous = event.nextState();
            events.add(event);
        }
        Event latest = events.get(events.size() - 1);
        if (summary.statusVersion() != latest.revision() || !summary.status().equals(latest.nextState())) {
            throw new IOException("incident current projection is not bound to its history");
        }
        return new Detail(summary, allowed, events);
    }

    public static HistoryPage parseHistoryPage(String body, String expectedIncidentId)
        throws IOException {
        String safeId = canonicalUuid(expectedIncidentId, "incident_id");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exact(root, AdminJava8Collections.set(
            "schema_version", "incident", "allowed_next_states", "snapshot_revision",
            "total_count", "items", "next_cursor"
        ));
        if (!HISTORY_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported incident history schema");
        }
        Summary incident = new Summary(object(root, "incident"));
        if (!safeId.equals(incident.incidentId())) {
            throw new IOException("incident history id is mismatched");
        }
        List<String> allowed = allowedStates(root, "allowed_next_states", incident.status());
        int snapshot = integer(root, "snapshot_revision", 1, 256);
        int total = integer(root, "total_count", 1, 256);
        if (snapshot != total || incident.statusVersion() != snapshot) {
            throw new IOException("incident history snapshot is inconsistent");
        }
        Object rawItems = root.get("items");
        if (!(rawItems instanceof List<?> values) || values.isEmpty()
            || values.size() > PAGE_SIZE) {
            throw new IOException("incident history page items are invalid");
        }
        List<Event> items = new ArrayList<>(values.size());
        Set<String> eventIds = new HashSet<>();
        Event previous = null;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> eventValue)) {
                throw new IOException("incident history event is invalid");
            }
            Event event = new Event(stringObject(eventValue));
            if (!eventIds.add(event.eventId())
                || (previous != null && (event.revision() != previous.revision() + 1
                    || !previous.nextState().equals(event.previousState())))) {
                throw new IOException("incident history page is not contiguous");
            }
            items.add(event);
            previous = event;
        }
        String cursor = nullableText(root, "next_cursor", 1024);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new IOException("incident history cursor is invalid");
        }
        Event last = items.get(items.size() - 1);
        if (last.revision() > snapshot
            || (cursor == null && (last.revision() != snapshot
                || !incident.status().equals(last.nextState())))
            || (cursor != null && last.revision() >= snapshot)) {
            throw new IOException("incident history page boundary is inconsistent");
        }
        return new HistoryPage(incident, allowed, snapshot, total, items, cursor);
    }

    public static Detail bindDetailToHistory(Detail detail, HistoryPage history)
        throws IOException {
        if (detail == null || history == null
            || !detail.summary().incidentId().equals(history.incident().incidentId())) {
            throw new IOException("incident detail and history are mismatched");
        }
        return new Detail(history.incident(), history.allowedNextStates(), detail.events());
    }

    public static Detail detailFromHistory(HistoryPage history) {
        if (history == null) throw new IllegalArgumentException("incident history is required");
        return new Detail(
            history.incident(), history.allowedNextStates(), AdminJava8Collections.list()
        );
    }

    public static StatusSnapshot parseStatus(String body, String expectedIncidentId) throws IOException {
        String safeId = canonicalUuid(expectedIncidentId, "incident_id");
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exact(root, AdminJava8Collections.set(
            "schema_version", "incident_id", "status", "status_version",
            "allowed_next_states", "updated_at"
        ));
        if (!STATUS_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported incident status schema");
        }
        String id = uuid(root, "incident_id");
        if (!safeId.equals(id)) throw new IOException("incident status id is mismatched");
        String status = member(root, "status", STATES);
        return new StatusSnapshot(
            id,
            status,
            integer(root, "status_version", 1, Integer.MAX_VALUE),
            allowedStates(root, "allowed_next_states", status),
            instant(root, "updated_at")
        );
    }

    public static StatusConflict parseStatusConflict(String body) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exact(root, AdminJava8Collections.set("detail"));
        Map<String, Object> detail = object(root, "detail");
        exact(detail, AdminJava8Collections.set("code", "message", "latest"));
        if (!"incident_status_version_conflict".equals(text(detail, "code", 64))) {
            throw new IOException("incident conflict code is invalid");
        }
        text(detail, "message", 500);
        Map<String, Object> latest = object(detail, "latest");
        exact(latest, AdminJava8Collections.set("status", "status_version", "allowed_next_states"));
        String status = member(latest, "status", STATES);
        return new StatusConflict(
            status,
            integer(latest, "status_version", 1, Integer.MAX_VALUE),
            allowedStates(latest, "allowed_next_states", status)
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

    public static boolean isAllowedTransition(String previous, String next) {
        return ("OPEN".equals(previous) && "ACKNOWLEDGED".equals(next))
            || ("ACKNOWLEDGED".equals(previous) && "RESOLVED".equals(next))
            || ("RESOLVED".equals(previous) && "REOPENED".equals(next))
            || ("REOPENED".equals(previous) && "ACKNOWLEDGED".equals(next));
    }

    public static String expectedNextState(String current) {
        return switch (current) {
            case "OPEN", "REOPENED" -> "ACKNOWLEDGED";
            case "ACKNOWLEDGED" -> "RESOLVED";
            case "RESOLVED" -> "REOPENED";
            default -> throw new IllegalArgumentException("incident state is invalid");
        };
    }

    private static List<String> allowedStates(
        Map<String, Object> value,
        String key,
        String current
    ) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof List<?> list) || list.size() != 1
            || !(list.get(0) instanceof String next)
            || !expectedNextState(current).equals(next)) {
            throw new IOException("incident allowed next states are invalid");
        }
        return AdminJava8Collections.list(next);
    }

    private static String boundedInput(String value, String label, int min, int max) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() < min || normalized.length() > max
            || hasControl(normalized)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (hasControl(normalized)) throw new IllegalArgumentException("filter has control characters");
        return normalized;
    }

    private static void exact(Map<String, Object> value, Set<String> keys) throws IOException {
        if (!value.keySet().equals(keys)) throw new IOException("incident fields do not match contract");
    }

    private static String text(Map<String, Object> value, String key, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.trim().isEmpty() || text.length() > max
            || hasControl(text)) {
            throw new IOException("incident text is invalid: " + key);
        }
        return text;
    }

    private static String nullableText(Map<String, Object> value, String key, int max) throws IOException {
        return value.get(key) == null ? null : text(value, key, max);
    }

    private static String member(Map<String, Object> value, String key, Set<String> allowed) throws IOException {
        String parsed = text(value, key, 64);
        if (!allowed.contains(parsed)) throw new IOException("incident enum is invalid: " + key);
        return parsed;
    }

    private static String nullableMember(
        Map<String, Object> value,
        String key,
        Set<String> allowed
    ) throws IOException {
        String parsed = nullableText(value, key, 64);
        if (parsed != null && !allowed.contains(parsed)) {
            throw new IOException("incident enum is invalid: " + key);
        }
        return parsed;
    }

    private static String uuid(Map<String, Object> value, String key) throws IOException {
        try {
            return canonicalUuid(text(value, key, 36), key);
        } catch (IllegalArgumentException error) {
            throw new IOException("incident UUID is invalid: " + key, error);
        }
    }

    private static int integer(Map<String, Object> value, String key, int min, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Long number) || number < min || number > max) {
            throw new IOException("incident integer is invalid: " + key);
        }
        return number.intValue();
    }

    private static String instant(Map<String, Object> value, String key) throws IOException {
        String parsed = text(value, key, 64);
        try {
            OffsetDateTime.parse(parsed);
            return parsed;
        } catch (DateTimeException error) {
            throw new IOException("incident timestamp is invalid: " + key, error);
        }
    }

    private static Map<String, Object> object(Map<String, Object> value, String key) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Map<?, ?> object)) throw new IOException("incident object is invalid: " + key);
        return stringObject(object);
    }

    private static Map<String, Object> stringObject(Map<?, ?> value) throws IOException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("incident object key is invalid");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }
}
