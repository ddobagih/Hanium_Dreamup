package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Base64;
import java.util.Map;

public final class AdminHighRiskActionGate {
    public static final String RECONFIRMATION_NONCE_HEADER = "X-WalkSafe-Reconfirm-Nonce";

    public interface Operation {
        String reauthenticationAction();
        String method();
        String path();

        /**
         * S18-7: SHA-256 of the exact request body the reconfirmation approves, or null when
         * this operation's body is not known before reauthentication.
         */
        default String intentSha256() { return null; }
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
        private final String action;
        private final String method;
        private final String path;
        private final String intentSha256;
        private final String nonce;
        private final long expiresAtEpochMs;

        public Binding(Operation action, String nonce, long expiresAtEpochMs) {
            if (action == null || !isCanonicalNonce(nonce)) {
                throw new IllegalArgumentException("invalid reauthentication binding");
            }
            this.action = action.reauthenticationAction();
            this.method = action.method();
            this.path = action.path();
            this.intentSha256 = action.intentSha256();
            this.nonce = nonce;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        public long expiresAtEpochMs() {
            return expiresAtEpochMs;
        }
    }

    private AdminHighRiskActionGate() {}

    /** Freeze the exact serialized body before password/TOTP reauthentication. */
    public static Operation bindBody(Operation operation, byte[] body) {
        if (operation == null || body == null) throw new IllegalArgumentException("operation and body are required");
        String action = operation.reauthenticationAction();
        String method = operation.method();
        String path = operation.path();
        String digest = AdminCanonicalEncoding.sha256Hex(body);
        return new Operation() {
            @Override public String reauthenticationAction() { return action; }
            @Override public String method() { return method; }
            @Override public String path() { return path; }
            @Override public String intentSha256() { return digest; }
        };
    }

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

    /** S18-40: append-only correction of the latest effective delivery event. */
    public static Operation deliveryCorrection(String reportId) {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        return operation(
            "report.delivery.correct",
            "POST",
            "/reports/" + safeId + "/delivery-corrections"
        );
    }

    public static Operation originalEvidence(String reportId) {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        return operation(
            "report.original.grant",
            "POST",
            "/reports/" + safeId + "/original-access-grants"
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

    public static Operation rawCollectionPurposeDecision(String collectionId) {
        String safeId = AdminRawCollectionModels.canonicalUuid(
            collectionId, "collection_id"
        );
        return operation(
            "admin.raw_collection.purpose_decide",
            "POST",
            "/admin/raw-collections/" + safeId + "/decisions"
        );
    }

    public static Operation rawCollectionList() {
        return operation(
            "admin.raw_collection.list",
            "GET",
            "/admin/raw-collections/quarantine"
        );
    }

    public static Operation rawCollectionLegalHold(String collectionId) {
        String safeId = AdminRawCollectionModels.canonicalUuid(
            collectionId, "collection_id"
        );
        return operation(
            "admin.raw_collection.legal_hold",
            "POST",
            "/admin/raw-collections/" + safeId + "/legal-holds"
        );
    }

    /**
     * S18-29: reporting another device lost revokes its keys and sessions, so it needs a
     * fresh password + TOTP reconfirmation bound to that exact device path.
     */
    public static Operation deviceReportLost(String deviceId) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IllegalArgumentException("device_id is invalid");
        }
        String path = "/admin/security/devices/" + deviceId + "/report-lost";
        // The request body is always "{}", so the reconfirmation can bind to it (S18-7).
        String intent = AdminCanonicalEncoding.sha256Hex(
            REPORT_LOST_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        return new Operation() {
            @Override public String reauthenticationAction() { return "device.report_lost"; }
            @Override public String method() { return "POST"; }
            @Override public String path() { return path; }
            @Override public String intentSha256() { return intent; }
        };
    }

    /** Exact JSON body of POST /admin/security/devices/{device_id}/report-lost. */
    public static final String REPORT_LOST_BODY = "{}";

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
        if (!binding.action.equals(action.reauthenticationAction())
            || !binding.method.equals(action.method())
            || !binding.path.equals(action.path())
            || !java.util.Objects.equals(binding.intentSha256, action.intentSha256())) {
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
