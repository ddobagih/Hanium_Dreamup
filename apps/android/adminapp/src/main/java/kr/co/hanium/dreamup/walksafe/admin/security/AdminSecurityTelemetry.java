package kr.co.hanium.dreamup.walksafe.admin.security;

import android.util.Log;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

public final class AdminSecurityTelemetry {
    public static final String SCHEMA_VERSION = "walksafe.admin-security-telemetry.v1";
    public static final String AUTH_SUCCEEDED = "walksafe.admin.auth.succeeded";
    public static final String AUTH_FAILED = "walksafe.admin.auth.failed";
    public static final String RECOVERY_START_SUCCEEDED = "walksafe.admin.recovery.start.succeeded";
    public static final String RECOVERY_START_FAILED = "walksafe.admin.recovery.start.failed";
    public static final String RECOVERY_COMPLETE_SUCCEEDED = "walksafe.admin.recovery.complete.succeeded";
    public static final String RECOVERY_COMPLETE_FAILED = "walksafe.admin.recovery.complete.failed";

    public enum Severity {
        INFO,
        WARN
    }

    public interface Recorder {
        void record(Event event);
    }

    public static final class Event {
        private final String eventName;
        private final Severity severity;
        private final String correlationId;
        private final String outcome;
        private final String failureCode;

        Event(
            String eventName,
            Severity severity,
            String correlationId,
            String outcome,
            String failureCode
        ) {
            this.eventName = eventName;
            this.severity = severity;
            this.correlationId = correlationId;
            this.outcome = outcome;
            this.failureCode = failureCode;
        }

        public String schemaVersion() { return SCHEMA_VERSION; }
        public String eventName() { return eventName; }
        public Severity severity() { return severity; }
        public String correlationId() { return correlationId; }
        public String outcome() { return outcome; }
        public String failureCode() { return failureCode; }

        public String toJson() {
            return "{"
                + "\"schema_version\":\"" + SCHEMA_VERSION + "\","
                + "\"event_name\":\"" + eventName + "\","
                + "\"severity\":\"" + severity + "\","
                + "\"correlation_id\":\"" + correlationId + "\","
                + "\"outcome\":\"" + outcome + "\","
                + "\"failure_code\":" + (failureCode == null ? "null" : "\"" + failureCode + "\"")
                + "}";
        }
    }

    private AdminSecurityTelemetry() {}

    static Recorder androidLogRecorder() {
        return event -> Log.println(
            event.severity() == Severity.INFO ? Log.INFO : Log.WARN,
            "WalkSafeAdminSecurity",
            event.toJson()
        );
    }

    static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    static String failureCode(Throwable error) {
        if (error instanceof AdminSecurityApiException apiError) {
            return "api_" + apiError.code().name().toLowerCase(Locale.ROOT);
        }
        if (error instanceof IOException) return "io_failure";
        if (error instanceof IllegalArgumentException) return "validation_failure";
        return "runtime_failure";
    }
}
