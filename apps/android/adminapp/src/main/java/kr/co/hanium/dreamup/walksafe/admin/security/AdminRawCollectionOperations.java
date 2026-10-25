package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Domain operations borrow a lease for each call; only the authority retains credentials. */
public final class AdminRawCollectionOperations {
    private final AdminOperationAuthority authority;
    private final AdminRawCollectionRepository rawCollectionRepository;

    AdminRawCollectionOperations(AdminOperationAuthority authority, AdminRawCollectionRepository rawCollectionRepository) {
        this.authority = authority;
        this.rawCollectionRepository = rawCollectionRepository;
    }

    private AdminRawCollectionRepository requireRawCollectionRepository() {
        if (rawCollectionRepository == null) throw new IllegalStateException("administrator raw collection review is unavailable");
        return rawCollectionRepository;
    }

    void cancelInFlightRequests() {
        if (rawCollectionRepository != null) rawCollectionRepository.cancelInFlightRequests();
    }

    public AdminRawCollectionModels.Page listAdminRawQuarantine(
        char[] password,
        char[] totpCode,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.bindBody(
                AdminHighRiskActionGate.rawCollectionList(), new byte[0]
            );
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireRawCollectionRepository().listQuarantine(
                lease.session(operationalWorkflowsEnabled),
                decision.requestHeaders()
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminRawCollectionModels.PurposeDecisionReceipt
        decideAdminRawCollectionPurpose(
            AdminRawCollectionModels.Summary source,
            AdminRawCollectionModels.PurposeDecisionRequest request,
            char[] password,
            char[] totpCode,
            long nowEpochMs,
            boolean operationalWorkflowsEnabled
        ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            if (source == null) throw new IllegalArgumentException("decision source is required");
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.bindBody(
                    AdminHighRiskActionGate.rawCollectionPurposeDecision(source.collectionId()),
                    AdminRawCollectionHttpClient.purposeBody(request)
                );
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireRawCollectionRepository().decidePurpose(
                lease.session(operationalWorkflowsEnabled),
                source,
                request,
                decision.requestHeaders()
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }

    public AdminRawCollectionModels.LegalHoldReceipt
        recordAdminRawCollectionLegalHold(
            String collectionId,
            AdminRawCollectionModels.LegalHoldRequest request,
            char[] password,
            char[] totpCode,
            long nowEpochMs,
            boolean operationalWorkflowsEnabled
        ) throws IOException, GeneralSecurityException {
        try (AdminOperationAuthority.Lease lease = authority.open()) {
            AdminHighRiskActionGate.Operation operation =
                AdminHighRiskActionGate.bindBody(
                    AdminHighRiskActionGate.rawCollectionLegalHold(collectionId),
                    AdminRawCollectionHttpClient.legalHoldBody(request)
                );
            lease.reauthenticate(password, totpCode, operation, nowEpochMs);
            AdminHighRiskActionGate.Decision decision = lease.consume(
                operation,
                nowEpochMs,
                operationalWorkflowsEnabled
            );
            if (!decision.isAllowed()) throw new IllegalStateException(decision.reason());
            return requireRawCollectionRepository().recordLegalHold(
                lease.session(operationalWorkflowsEnabled),
                collectionId,
                request,
                decision.requestHeaders()
            );
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totpCode != null) Arrays.fill(totpCode, '\0');
        }
    }
}
