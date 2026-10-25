package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Domain operations borrow a lease for each call; only the authority retains credentials. */
public final class AdminReportOperations {
    private final AdminOperationAuthority authority;
    private final AdminReportRepository reportRepository;

    AdminReportOperations(AdminOperationAuthority authority, AdminReportRepository reportRepository) {
        this.authority = authority;
        this.reportRepository = reportRepository;
    }

    private AdminReportRepository requireReportRepository() {
        if (reportRepository == null) throw new IllegalStateException("administrator report reads are unavailable");
        return reportRepository;
    }

    public AdminReportModels.Page listAdminReports(
        AdminReportModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().list(
                lease.session(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminReportModels.Detail getAdminReportDetail(
        String reportId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().detail(
                lease.session(operationalWorkflowsEnabled),
                reportId
            );
        }
    }

    public AdminReportModels.StatusSnapshot updateAdminReportStatus(
        String reportId,
        String nextStatus,
        int expectedVersion,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            try {
                return updateAdminReportStatus(
                    reportId,
                    nextStatus,
                    expectedVersion,
                    password,
                    totpCode,
                    nowEpochMs,
                    operationalWorkflowsEnabled,
                    () -> true
                );
            } catch (AdminReportWorkflowController.StatusDispatchCancelledException impossible) {
                throw new IllegalStateException("unconditional report status dispatch was cancelled", impossible);
            }
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminReportModels.StatusSnapshot updateAdminReportStatus(
        String reportId,
        String nextStatus,
        int expectedVersion,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled,
        AdminReportWorkflowController.StatusDispatch dispatch
    ) throws IOException, GeneralSecurityException,
        AdminReportWorkflowController.StatusDispatchCancelledException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            if (dispatch == null) throw new IllegalArgumentException("status dispatch is required");
            AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.bindBody(
                AdminHighRiskActionGate.reportStatus(reportId),
                AdminReportHttpClient.statusBody(nextStatus, expectedVersion)
            );
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireReportRepository().updateStatus(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                nextStatus,
                expectedVersion,
                decision.requestHeaders(),
                dispatch
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminDeliveryPackage createAdminDeliveryPackage(
        AdminDeliveryPackage.Eligibility eligibility,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            if (eligibility == null) {
                throw new IllegalArgumentException("package eligibility is required");
            }
            String reportId = eligibility.reportId();
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.deliveryPackage(reportId);
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireReportRepository().createDeliveryPackage(
                lease.session(operationalWorkflowsEnabled),
                eligibility,
                decision.requestHeaders()
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminDeliveryPackage.Proof getAdminDeliveryPackageProof(
        String reportId,
        int packageRevision,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().deliveryPackageProof(
                lease.session(operationalWorkflowsEnabled),
                reportId,
                packageRevision
            );
        }
    }

    public AdminAuditModels.Page listAdminAudits(
        AdminAuditModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().audits(
                lease.session(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminReportRequestModels.Page listAdminReportRequests(
        AdminReportRequestModels.Filters filters,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().listRequests(
                lease.session(operationalWorkflowsEnabled),
                filters,
                cursor
            );
        }
    }

    public AdminReportRequestModels.Detail getAdminReportRequestDetail(
        String requestId,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().requestDetail(
                lease.session(operationalWorkflowsEnabled),
                requestId
            );
        }
    }

    public AdminReportRequestModels.StatusSnapshot updateAdminReportRequestStatus(
        String requestId,
        String requestType,
        String nextStatus,
        int expectedVersion,
        String publicResponse,
        String internalNote,
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.bindBody(
                    AdminHighRiskActionGate.reportRequestStatus(requestId),
                    AdminReportHttpClient.requestStatusBody(requestType, nextStatus, expectedVersion, publicResponse, internalNote)
                );
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireReportRepository().updateRequestStatus(
                lease.session(operationalWorkflowsEnabled),
                requestId,
                requestType,
                nextStatus,
                expectedVersion,
                publicResponse,
                internalNote,
                decision.requestHeaders()
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminExternalCopyDeletionModels.Page listAdminExternalCopyDeletions(
        AdminExternalCopyDeletionModels.Filter filter,
        String cursor,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().listExternalCopyDeletions(
                lease.session(operationalWorkflowsEnabled),
                filter,
                cursor
            );
        }
    }

    public AdminExternalCopyDeletionModels.Item recordAdminExternalCopyDeletion(
        AdminExternalCopyDeletionModels.EventCommand command,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            return requireReportRepository().recordExternalCopyDeletion(
                lease.session(operationalWorkflowsEnabled),
                command
            );
        }
    }
    void cancelInFlightRequests() {
        if (reportRepository != null) reportRepository.cancelInFlightRequests();
    }
}
