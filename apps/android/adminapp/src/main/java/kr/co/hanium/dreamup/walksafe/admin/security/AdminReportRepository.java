package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

/** Actor-bound read boundary for minimum administrator report projections. */
public interface AdminReportRepository {
    final class NotFoundException extends IOException {
        public NotFoundException() {
            super("administrator report was not found");
        }
    }

    final class StatusConflictException extends IOException {
        private final AdminReportModels.StatusConflict latest;

        public StatusConflictException(AdminReportModels.StatusConflict latest) {
            super("administrator report status version conflicted");
            if (latest == null) throw new IllegalArgumentException("latest status is required");
            this.latest = latest;
        }

        public AdminReportModels.StatusConflict latest() { return latest; }
    }

    final class ReportRequestNotFoundException extends IOException {
        public ReportRequestNotFoundException() {
            super("administrator report request was not found");
        }
    }

    final class ReportRequestConflictException extends IOException {
        private final AdminReportRequestModels.StatusConflict latest;

        public ReportRequestConflictException(AdminReportRequestModels.StatusConflict latest) {
            super("administrator report request status version conflicted");
            if (latest == null) throw new IllegalArgumentException("latest request status is required");
            this.latest = latest;
        }

        public AdminReportRequestModels.StatusConflict latest() { return latest; }
    }

    final class ExternalCopyNotFoundException extends IOException {
        public ExternalCopyNotFoundException() {
            super("external-copy deletion record was not found");
        }
    }

    final class ExternalCopyConflictException extends IOException {
        private final AdminExternalCopyDeletionModels.Conflict latest;

        public ExternalCopyConflictException(AdminExternalCopyDeletionModels.Conflict latest) {
            super("external-copy deletion revision conflicted");
            if (latest == null) throw new IllegalArgumentException("latest external-copy state is required");
            this.latest = latest;
        }

        public AdminExternalCopyDeletionModels.Conflict latest() { return latest; }
    }

    AdminReportModels.Page list(
        AdminOperationsApi.SessionContext session,
        AdminReportModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException;

    AdminReportModels.Detail detail(
        AdminOperationsApi.SessionContext session,
        String reportId
    ) throws IOException, GeneralSecurityException;

    AdminReportModels.StatusSnapshot updateStatus(
        AdminOperationsApi.SessionContext session,
        String reportId,
        String nextStatus,
        int expectedVersion,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;

    default AdminReportModels.StatusSnapshot updateStatus(
        AdminOperationsApi.SessionContext session,
        String reportId,
        String nextStatus,
        int expectedVersion,
        Map<String, String> reconfirmationHeaders,
        AdminReportWorkflowController.StatusDispatch dispatch
    ) throws IOException, GeneralSecurityException {
        if (dispatch == null || !dispatch.markDispatched()) {
            throw new AdminReportWorkflowController.StatusDispatchCancelledException();
        }
        return updateStatus(
            session,
            reportId,
            nextStatus,
            expectedVersion,
            reconfirmationHeaders
        );
    }

    AdminDeliveryPackage createDeliveryPackage(
        AdminOperationsApi.SessionContext session,
        AdminDeliveryPackage.Eligibility eligibility,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;

    AdminDeliveryPackage.Proof deliveryPackageProof(
        AdminOperationsApi.SessionContext session,
        String reportId,
        int packageRevision
    ) throws IOException, GeneralSecurityException;

    AdminAuditModels.Page audits(
        AdminOperationsApi.SessionContext session,
        AdminAuditModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException;

    AdminReportRequestModels.Page listRequests(
        AdminOperationsApi.SessionContext session,
        AdminReportRequestModels.Filters filters,
        String cursor
    ) throws IOException, GeneralSecurityException;

    AdminReportRequestModels.Detail requestDetail(
        AdminOperationsApi.SessionContext session,
        String requestId
    ) throws IOException, GeneralSecurityException;

    AdminReportRequestModels.StatusSnapshot updateRequestStatus(
        AdminOperationsApi.SessionContext session,
        String requestId,
        String requestType,
        String nextStatus,
        int expectedVersion,
        String publicResponse,
        String internalNote,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;

    AdminExternalCopyDeletionModels.Page listExternalCopyDeletions(
        AdminOperationsApi.SessionContext session,
        AdminExternalCopyDeletionModels.Filter filter,
        String cursor
    ) throws IOException, GeneralSecurityException;

    AdminExternalCopyDeletionModels.Item recordExternalCopyDeletion(
        AdminOperationsApi.SessionContext session,
        AdminExternalCopyDeletionModels.EventCommand command
    ) throws IOException, GeneralSecurityException;
}
