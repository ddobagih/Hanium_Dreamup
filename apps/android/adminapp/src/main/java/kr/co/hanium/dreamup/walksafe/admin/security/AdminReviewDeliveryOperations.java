package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Domain operations borrow a lease for each call; only the authority retains credentials. */
public final class AdminReviewDeliveryOperations {
    private final AdminOperationAuthority authority;
    private final AdminOperationsApi operationsApi;

    AdminReviewDeliveryOperations(AdminOperationAuthority authority, AdminOperationsApi operationsApi) {
        this.authority = authority;
        this.operationsApi = operationsApi;
    }

    private AdminOperationsApi requireOperationsApi() {
        if (operationsApi == null) throw new IllegalStateException("administrator operations are unavailable");
        return operationsApi;
    }

    void cancelInFlightRequests() {
        if (operationsApi != null) operationsApi.cancelInFlightRequests();
    }

    public AdminOperationsApi.Result recordReviewDecision(
        String reportId,
        AdminReportDecision decision,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireOperationsApi().recordReviewDecision(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                decision
            );
        }
    }

    public AdminOriginalEvidence loadAdminOriginalEvidence(
        String reportId,
        int expectedContentRevision,
        String reason,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.originalEvidence(reportId);
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireOperationsApi().loadOriginalEvidence(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                expectedContentRevision,
                reason,
                decision.requestHeaders()
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminOperationsApi.Result lookupReviewDecision(
        String reportId,
        String decisionId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireOperationsApi().lookupReviewDecision(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                decisionId
            );
        }
    }

    public AdminOperationsApi.Result readReviewDecisions(
        String reportId,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireOperationsApi().readReviewDecisions(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                cursor
            );
        }
    }

    public AdminOperationsApi.Result readReviewDecisions(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return readReviewDecisions(reportId, null, operationalWorkflowsEnabled);
        }
    }

    public AdminOperationsApi.Result readRecentReviewDecisions(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireOperationsApi().readRecentReviewDecisions(
                lease.session(operationalWorkflowsEnabled), reportId
            );
        }
    }

    public AdminOperationsApi.Result recordDelivery(
        String reportId,
        AdminInstitutionDelivery delivery,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireOperationsApi().recordDelivery(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                delivery
            );
        }
    }

    public AdminOperationsApi.Result correctDelivery(
        String reportId,
        AdminDeliveryCorrection correction,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean resendOfUncertainAttempt,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            if (correction == null) {
                throw new IllegalArgumentException("delivery correction is required");
            }
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.deliveryCorrection(reportId);
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireOperationsApi().recordDeliveryCorrection(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                correction,
                decision.requestHeaders(),
                resendOfUncertainAttempt
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminOperationsApi.Result readDeliveries(
        String reportId,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireOperationsApi().readDeliveries(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                cursor
            );
        }
    }

    public AdminOperationsApi.Result readDeliveries(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return readDeliveries(reportId, null, operationalWorkflowsEnabled);
        }
    }

    public AdminOperationsApi.Result readRecentDeliveries(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireOperationsApi().readRecentDeliveries(
                lease.session(operationalWorkflowsEnabled), reportId
            );
        }
    }
}
