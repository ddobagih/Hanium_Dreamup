package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

/** Administrator-only boundary for incident reads and append-only status recording. */
public interface AdminIncidentRepository {
    final class NotFoundException extends IOException {
        public NotFoundException() { super("administrator incident was not found"); }
    }

    final class HistoryCursorException extends IOException {
        public HistoryCursorException() { super("administrator incident history cursor is invalid"); }
    }

    final class StatusConflictException extends IOException {
        private final AdminIncidentModels.StatusConflict latest;

        public StatusConflictException(AdminIncidentModels.StatusConflict latest) {
            super("administrator incident status version conflicted");
            if (latest == null) throw new IllegalArgumentException("latest incident status is required");
            this.latest = latest;
        }

        public AdminIncidentModels.StatusConflict latest() { return latest; }
    }

    AdminIncidentModels.Page list(
        AdminOperationsApi.SessionContext session,
        AdminIncidentModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException;

    AdminIncidentModels.Detail detail(
        AdminOperationsApi.SessionContext session,
        String incidentId
    ) throws IOException, GeneralSecurityException;

    AdminIncidentModels.HistoryPage history(
        AdminOperationsApi.SessionContext session,
        String incidentId,
        String cursor
    ) throws IOException, GeneralSecurityException;

    AdminIncidentModels.StatusSnapshot updateStatus(
        AdminOperationsApi.SessionContext session,
        String incidentId,
        AdminIncidentModels.StatusRequest request,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;
}
