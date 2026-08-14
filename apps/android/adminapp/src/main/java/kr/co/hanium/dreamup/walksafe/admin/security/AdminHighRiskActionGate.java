package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Base64;
import java.util.Map;

public final class AdminHighRiskActionGate {
    public static final String RECONFIRMATION_NONCE_HEADER = "X-WalkSafe-Reconfirm-Nonce";

    public enum Action {
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
        private final Action action;
        private final String nonce;
        private final long expiresAtEpochMs;

        public Binding(Action action, String nonce, long expiresAtEpochMs) {
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
        Action action,
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
        Action action,
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
        if (binding.action != action) {
            return denied("reconfirmation_binding_mismatch");
        }
        return new Decision(
            true,
            "allowed",
            Map.of(RECONFIRMATION_NONCE_HEADER, binding.nonce)
        );
    }

    private static Decision denied(String reason) {
        return new Decision(false, reason, Map.of());
    }
}
