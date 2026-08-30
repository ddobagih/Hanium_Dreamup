package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict allowlisted administrator audit projection. */
public final class AdminAuditModels {
    public static final String SCHEMA = "walksafe.admin-audit-list.v1";
    public static final int PAGE_SIZE = 25;
    private static final Set<String> EVENT_TYPES = AdminJava8Collections.set(
        "SECURITY", "READ", "STATUS", "REVIEW", "EXPORT", "DELIVERY"
    );
    private static final Set<String> OUTCOMES = AdminJava8Collections.set("SUCCEEDED", "DENIED", "ERROR");

    private AdminAuditModels() {}

    public static final class Filters {
        private final String eventType;
        private final String actorId;

        public Filters(String eventType, String actorId) {
            String type = normalize(eventType);
            if (type != null && !EVENT_TYPES.contains(type)) {
                throw new IllegalArgumentException("audit event_type is invalid");
            }
            String actor = normalize(actorId);
            if (actor != null && !actor.matches("[A-Za-z0-9][A-Za-z0-9._@-]{2,63}")) {
                throw new IllegalArgumentException("audit actor_id is invalid");
            }
            this.eventType = type;
            this.actorId = actor;
        }

        public String eventType() { return eventType; }
        public String actorId() { return actorId; }
    }

    public static final class Event {
        private final String eventId;
        private final String eventType;
        private final String action;
        private final String outcome;
        private final String actorId;
        private final String resourceType;
        private final String resourceId;
        private final String occurredAt;
        private final String correlationId;

        private Event(Map<String, Object> value) throws IOException {
            exact(value, AdminJava8Collections.set(
                "event_id", "event_type", "action", "outcome", "actor_id", "resource_type",
                "resource_id", "occurred_at", "correlation_id"
            ));
            eventId = text(value, "event_id", 160);
            eventType = member(value, "event_type", EVENT_TYPES);
            action = text(value, "action", 64);
            outcome = member(value, "outcome", OUTCOMES);
            actorId = nullableText(value, "actor_id", 64);
            if (actorId != null && !actorId.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
                throw new IOException("audit actor id is invalid");
            }
            resourceType = text(value, "resource_type", 32);
            resourceId = text(value, "resource_id", 160);
            occurredAt = instant(value, "occurred_at");
            correlationId = nullableUuid(value, "correlation_id");
        }

        public String eventId() { return eventId; }
        public String eventType() { return eventType; }
        public String action() { return action; }
        public String outcome() { return outcome; }
        public String actorId() { return actorId; }
        public String resourceType() { return resourceType; }
        public String resourceId() { return resourceId; }
        public String occurredAt() { return occurredAt; }
        public String correlationId() { return correlationId; }
    }

    public static final class Page {
        private final List<Event> items;
        private final String nextCursor;

        private Page(List<Event> items, String nextCursor) {
            this.items = AdminJava8Collections.copyList(items);
            this.nextCursor = nextCursor;
        }

        public List<Event> items() { return items; }
        public String nextCursor() { return nextCursor; }
    }

    public static Page parsePage(String body) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        exact(root, AdminJava8Collections.set("schema_version", "items", "next_cursor"));
        if (!SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("unsupported administrator audit schema");
        }
        Object raw = root.get("items");
        if (!(raw instanceof List<?> list) || list.size() > 100) {
            throw new IOException("administrator audit items are invalid");
        }
        List<Event> events = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> object)) throw new IOException("audit event must be an object");
            events.add(new Event(stringObject(object)));
        }
        String cursor = nullableText(root, "next_cursor", 1024);
        if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,1024}")) {
            throw new IOException("administrator audit cursor is invalid");
        }
        return new Page(events, cursor);
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String result = value.trim();
        if (result.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IllegalArgumentException("audit filter has control characters");
        }
        return result;
    }

    private static void exact(Map<String, Object> value, Set<String> keys) throws IOException {
        if (!value.keySet().equals(keys)) throw new IOException("administrator audit fields do not match contract");
    }

    private static String text(Map<String, Object> value, String key, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.trim().isEmpty() || text.length() > max
            || text.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw new IOException("administrator audit text is invalid: " + key);
        }
        return text;
    }

    private static String nullableText(Map<String, Object> value, String key, int max) throws IOException {
        return value.get(key) == null ? null : text(value, key, max);
    }

    private static String member(Map<String, Object> value, String key, Set<String> allowed) throws IOException {
        String result = text(value, key, 64);
        if (!allowed.contains(result)) throw new IOException("administrator audit enum is invalid: " + key);
        return result;
    }

    private static String instant(Map<String, Object> value, String key) throws IOException {
        String result = text(value, key, 64);
        try {
            OffsetDateTime.parse(result);
            return result;
        } catch (DateTimeException error) {
            throw new IOException("administrator audit timestamp is invalid", error);
        }
    }

    private static String nullableUuid(Map<String, Object> value, String key) throws IOException {
        String result = nullableText(value, key, 36);
        if (result == null) return null;
        try {
            return AdminReportModels.canonicalUuid(result, key);
        } catch (IllegalArgumentException error) {
            throw new IOException("administrator audit UUID is invalid: " + key, error);
        }
    }

    private static Map<String, Object> stringObject(Map<?, ?> value) throws IOException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("audit key is invalid");
            result.put(key, entry.getValue());
        }
        return result;
    }
}
