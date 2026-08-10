package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.util.List;

public interface AdminSecurityApi {
    final class LoginResult {
        private final String accessToken;
        private final AdminSecurityState securityState;
        private final String currentSessionId;

        public LoginResult(String accessToken, AdminSecurityState securityState, String currentSessionId) {
            this.accessToken = accessToken;
            this.securityState = securityState;
            this.currentSessionId = currentSessionId;
        }

        public String accessToken() { return accessToken; }
        public AdminSecurityState securityState() { return securityState; }
        public String currentSessionId() { return currentSessionId; }
    }

    final class StateSnapshot {
        private final AdminSecurityState securityState;
        private final String stateVersion;
        private final String observedAt;

        public StateSnapshot(AdminSecurityState securityState, String stateVersion, String observedAt) {
            this.securityState = securityState;
            this.stateVersion = stateVersion;
            this.observedAt = observedAt;
        }

        public AdminSecurityState securityState() { return securityState; }
        public String stateVersion() { return stateVersion; }
        public String observedAt() { return observedAt; }
    }

    final class SessionInfo {
        private final String sessionId;
        private final String deviceId;
        private final String deviceLabel;
        private final boolean current;
        private final boolean revoked;
        private final String lastSeenAt;

        public SessionInfo(
            String sessionId,
            String deviceId,
            String deviceLabel,
            boolean current,
            boolean revoked,
            String lastSeenAt
        ) {
            this.sessionId = sessionId;
            this.deviceId = deviceId;
            this.deviceLabel = deviceLabel;
            this.current = current;
            this.revoked = revoked;
            this.lastSeenAt = lastSeenAt;
        }

        public String sessionId() { return sessionId; }
        public String deviceId() { return deviceId; }
        public String deviceLabel() { return deviceLabel; }
        public boolean isCurrent() { return current; }
        public boolean isRevoked() { return revoked; }
        public String lastSeenAt() { return lastSeenAt; }
    }

    final class ReauthenticationResult {
        private final long reauthenticatedUntilEpochMs;
        private final String action;
        private final String method;
        private final String path;

        public ReauthenticationResult(
            long reauthenticatedUntilEpochMs,
            String action,
            String method,
            String path
        ) {
            this.reauthenticatedUntilEpochMs = reauthenticatedUntilEpochMs;
            this.action = action;
            this.method = method;
            this.path = path;
        }

        public long reauthenticatedUntilEpochMs() { return reauthenticatedUntilEpochMs; }
        public String action() { return action; }
        public String method() { return method; }
        public String path() { return path; }
    }

    final class RecoveryStartResult {
        private final String recoveryToken;
        private final AdminSecurityState securityState;

        public RecoveryStartResult(String recoveryToken, AdminSecurityState securityState) {
            this.recoveryToken = recoveryToken;
            this.securityState = securityState;
        }

        public String recoveryToken() { return recoveryToken; }
        public AdminSecurityState securityState() { return securityState; }
    }

    LoginResult login(
        String adminId,
        String password,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException;

    StateSnapshot getState(String accessToken) throws IOException;

    List<SessionInfo> getSessions(String accessToken) throws IOException;

    StateSnapshot revokeSession(String accessToken, String sessionId) throws IOException;

    ReauthenticationResult reauthenticate(
        String accessToken,
        String password,
        String totpCode,
        String action,
        String method,
        String path,
        String nonce
    ) throws IOException;

    RecoveryStartResult startRecovery(
        String adminId,
        String recoveryCode,
        String deviceId,
        String deviceLabel
    ) throws IOException;

    LoginResult completeRecovery(
        String recoveryToken,
        String newPassword,
        String totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException;
}
