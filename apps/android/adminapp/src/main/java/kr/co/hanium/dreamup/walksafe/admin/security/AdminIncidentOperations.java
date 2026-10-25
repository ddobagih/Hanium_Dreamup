package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Domain operations borrow a lease for each call; only the authority retains credentials. */
public final class AdminIncidentOperations {
    private final AdminOperationAuthority authority;
    private final AdminIncidentRepository incidentRepository;

    AdminIncidentOperations(AdminOperationAuthority authority, AdminIncidentRepository incidentRepository) {
        this.authority = authority;
        this.incidentRepository = incidentRepository;
    }

    private AdminIncidentRepository requireIncidentRepository() {
        if (incidentRepository == null) throw new IllegalStateException("administrator incident operations are unavailable");
        return incidentRepository;
    }

    public AdminIncidentModels.Page listAdminIncidents(
        AdminIncidentModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireIncidentRepository().list(
                lease.session(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminIncidentModels.Detail getAdminIncidentDetail(
        String incidentId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireIncidentRepository().detail(
                lease.session(operationalWorkflowsEnabled),
                incidentId
            );
        }
    }

    public AdminIncidentModels.HistoryPage getAdminIncidentHistory(
        String incidentId,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireIncidentRepository().history(
                lease.session(operationalWorkflowsEnabled),
                incidentId,
                cursor
            );
        }
    }

    public AdminIncidentModels.StatusSnapshot updateAdminIncidentStatus(
        String incidentId,
        AdminIncidentModels.StatusRequest request,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.bindBody(
                    AdminHighRiskActionGate.incidentStatus(incidentId),
                    AdminIncidentHttpClient.statusBody(request)
                );
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireIncidentRepository().updateStatus(
                lease.session(operationalWorkflowsEnabled),
                incidentId,
                request,
                decision.requestHeaders()
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }
    void cancelInFlightRequests() {
        if (incidentRepository != null) incidentRepository.cancelInFlightRequests();
    }
}
