package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;

/** A thread-bound lease of the controller's existing lifecycle/work lock authority. */
interface AdminOperationAuthority {
    Lease open();
    interface Lease extends AutoCloseable {
        AdminOperationsApi.SessionContext session(boolean operationalWorkflowsEnabled);
        void reauthenticate(char[] password, char[] totp,
            AdminHighRiskActionGate.Operation operation, long nowEpochMs) throws IOException;
        AdminHighRiskActionGate.Decision consume(AdminHighRiskActionGate.Operation operation,
            long nowEpochMs, boolean operationalWorkflowsEnabled);
        @Override void close();
    }
}
