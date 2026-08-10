package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Base64;
import java.util.Map;

public final class AdminHighRiskActionGate {
    public static final String RECONFIRMATION_NONCE_HEADER = "X-WalkSafe-Reconfirm-Nonce";

    public enum Action {
        RELEASE_APPROVAL,
        PRIVILEGE_CHANGE,
        DATA_DELETE
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
        private final String nonce;
        private final long expiresAtEpochMs;

        public Binding(String action, String method, String path, String nonce, long expiresAtEpochMs) {
            if (action == null || method == null || path == null
                || !isCanonicalNonce(nonce)) {
                throw new IllegalArgumentException("invalid reauthentication binding");
            }
            this.action = action;
            this.method = method;
            this.path = path;
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
        long reauthenticatedUntilEpochMs,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        if (action == null || securityState == null) return denied("invalid_security_context");
        if (securityState != AdminSecurityState.NORMAL) return denied("administrator_access_not_normal");
        if (!operationalWorkflowsEnabled) return denied("operational_workflows_locked");
        if (reauthenticatedUntilEpochMs <= nowEpochMs) return denied("recent_reauthentication_required");
        return denied("reconfirmation_binding_required");
    }

    public static Decision evaluate(
        String action,
        String method,
        String path,
        AdminSecurityState securityState,
        Binding binding,
        long nowEpochMs,
        boolean operationalWorkflowsEnabled
    ) {
        if (action == null || method == null || path == null || securityState == null) {
            return denied("invalid_security_context");
        }
        if (securityState != AdminSecurityState.NORMAL) return denied("administrator_access_not_normal");
        if (!operationalWorkflowsEnabled) return denied("operational_workflows_locked");
        if (binding == null) return denied("reconfirmation_binding_required");
        if (binding.expiresAtEpochMs <= nowEpochMs) return denied("recent_reauthentication_required");
        if (!binding.action.equals(action)
            || !binding.method.equals(method)
            || !binding.path.equals(path)) {
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
