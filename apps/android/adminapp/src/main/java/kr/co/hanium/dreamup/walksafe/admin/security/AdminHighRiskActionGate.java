package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Base64;
import java.util.Map;

public final class AdminHighRiskActionGate {
    public static final String RECONFIRMATION_NONCE_HEADER = "X-WalkSafe-Reconfirm-Nonce";

    public interface Operation {
        String reauthenticationAction();
        String method();
        String path();
    }

    public enum Action implements Operation {
        RELEASE_APPROVAL(
            "release.approval",
            "POST",
            "/admin/operations/release-approvals"
        ),
        PRIVILEGE_CHANGE(
            "privilege.change",
            "POST",
            "/admin/operations/privilege-changes"
        ),
        DATA_DELETE(
            "data.delete",
            "POST",
            "/admin/operations/data-deletions"
        );

        private final String reauthenticationAction;
        private final String method;
        private final String path;

        Action(String reauthenticationAction, String method, String path) {
            this.reauthenticationAction = reauthenticationAction;
            this.method = method;
            this.path = path;
        }

        public String reauthenticationAction() {
            return reauthenticationAction;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public static Action fromExactTriple(String action, String method, String path) {
            for (Action candidate : values()) {
                if (candidate.name().equals(action)
                    && candidate.method.equals(method)
                    && candidate.path.equals(path)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unsupported high-risk action binding");
        }
    }

    public static final class Decision {
        private final boolean allowed;
        private final String reason;
        private final Map<String, String> requestHeaders;

        private Decision(boolean allowed, String reason, Map<String, String> requestHeaders) {
            this.allowed = allowed;
            this.reason = reason;
            this.requestHeaders = requestHeaders;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String reason() {
            return reason;
        }

        public Map<String, String> requestHeaders() {
            return requestHeaders;
        }
    }

    public static final class Binding {
        private final Operation action;
        private final String nonce;
        private final long expiresAtEpochMs;

        public Binding(Operation action, String nonce, long expiresAtEpochMs) {
            if (action == null || !isCanonicalNonce(nonce)) {
                throw new IllegalArgumentException("invalid reauthentication binding");
            }
            this.action = action;
            this.nonce = nonce;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        public long expiresAtEpochMs() {
            return expiresAtEpochMs;
        }
    }

    private AdminHighRiskActionGate() {}

    public static Operation reportStatus(String reportId) {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        return operation(
            "admin.report.status.update",
            "PATCH",
            "/admin/reports/" + safeId + "/status"
        );
    }

    public static Operation deliveryPackage(String reportId) {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        return operation(
            "admin.report.delivery_package.create",
            "POST",
            "/admin/reports/" + safeId + "/delivery-packages"
        );
    }

    public static Operation reportRequestStatus(String requestId) {
        String safeId = AdminReportRequestModels.canonicalUuid(requestId, "request_id");
        return operation(
            "admin.report_request.status.update",
            "PATCH",
            "/admin/report-requests/" + safeId + "/status"
        );
    }

    public static Operation incidentStatus(String incidentId) {
        String safeId = AdminIncidentModels.canonicalUuid(incidentId, "incident_id");
        return operation(
            "admin.incident.status.update",
            "PATCH",
            "/admin/incidents/" + safeId + "/status"
        );
    }

    private static Operation operation(String action, String method, String path) {
        return new Operation() {
            @Override public String reauthenticationAction() { return action; }
            @Override public String method() { return method; }
            @Override public String path() { return path; }
        };
    }

    static boolean isCanonicalNonce(String nonce) {
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{22}")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(nonce);
            return decoded.length == 16
                && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(nonce);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static Decision evaluate(
        Operation action,
        AdminSecurityState securityState,
        AdminRecoveryCustodyState recoveryCustodyState,
        long reauthenticatedUntilEpochMs,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        if (action == null || securityState == null) return denied("invalid_security_context");
        if (securityState != AdminSecurityState.NORMAL) return denied("administrator_access_not_normal");
        if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
            return denied("recovery_custody_not_attested");
        }
        if (!operationalWorkflowsEnabled) return denied("operational_workflows_locked");
        if (reauthenticatedUntilEpochMs <= nowEpochMs) return denied("recent_reauthentication_required");
        return denied("reconfirmation_binding_required");
    }

    public static Decision evaluate(
        Operation action,
        AdminSecurityState securityState,
        AdminRecoveryCustodyState recoveryCustodyState,
        Binding binding,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        if (action == null || securityState == null) {
            return denied("invalid_security_context");
        }
        if (securityState != AdminSecurityState.NORMAL) return denied("administrator_access_not_normal");
        if (recoveryCustodyState != AdminRecoveryCustodyState.ATTESTED) {
            return denied("recovery_custody_not_attested");
        }
        if (!operationalWorkflowsEnabled) return denied("operational_workflows_locked");
        if (binding == null) return denied("reconfirmation_binding_required");
        if (binding.expiresAtEpochMs <= nowEpochMs) return denied("recent_reauthentication_required");
        if (!binding.action.reauthenticationAction().equals(action.reauthenticationAction())
            || !binding.action.method().equals(action.method())
            || !binding.action.path().equals(action.path())) {
            return denied("reconfirmation_binding_mismatch");
        }
        return new Decision(
            true,
            "allowed",
            AdminJava8Collections.map(RECONFIRMATION_NONCE_HEADER, binding.nonce)
        );
    }

    private static Decision denied(String reason) {
        return new Decision(false, reason, AdminJava8Collections.map());
    }
}
