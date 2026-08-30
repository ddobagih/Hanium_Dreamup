package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

public interface AdminSecurityApi {
    enum RecoveryMaterialKind {
        RECOVERY_CODE,
        SECURITY_KEY
    }

    enum RecoveryStorageLocation {
        OFF_PHONE
    }

    final class RecoveryCustodyAttestation {
        private final String custodyReference;
        private final RecoveryMaterialKind materialKind;
        private final RecoveryStorageLocation storageLocation;
        private final boolean separateEncryptedBackupConfirmed;

        public RecoveryCustodyAttestation(
            String custodyReference,
            RecoveryMaterialKind materialKind,
            RecoveryStorageLocation storageLocation,
            boolean separateEncryptedBackupConfirmed
        ) {
            if (!isCanonicalCustodyReference(custodyReference)) {
                throw new IllegalArgumentException("invalid recovery custody reference");
            }
            if (materialKind == null || storageLocation != RecoveryStorageLocation.OFF_PHONE
                || !separateEncryptedBackupConfirmed) {
                throw new IllegalArgumentException("invalid recovery custody attestation");
            }
            this.custodyReference = custodyReference;
            this.materialKind = materialKind;
            this.storageLocation = storageLocation;
            this.separateEncryptedBackupConfirmed = true;
        }

        public String custodyReference() { return custodyReference; }
        public RecoveryMaterialKind materialKind() { return materialKind; }
        public RecoveryStorageLocation storageLocation() { return storageLocation; }
        public boolean isSeparateEncryptedBackupConfirmed() {
            return separateEncryptedBackupConfirmed;
        }

        private static boolean isCanonicalCustodyReference(String value) {
            if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return false;
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(value);
                return decoded.length == 32
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
    }

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
        private final AdminRecoveryCustodyState recoveryCustodyState;
        private final String recoveryCustodyAttestedAt;

        public StateSnapshot(
            AdminSecurityState securityState,
            String stateVersion,
            String observedAt,
            AdminRecoveryCustodyState recoveryCustodyState,
            String recoveryCustodyAttestedAt
        ) {
            this.securityState = securityState;
            this.stateVersion = stateVersion;
            this.observedAt = observedAt;
            this.recoveryCustodyState = recoveryCustodyState;
            this.recoveryCustodyAttestedAt = recoveryCustodyAttestedAt;
        }

        public AdminSecurityState securityState() { return securityState; }
        public String stateVersion() { return stateVersion; }
        public String observedAt() { return observedAt; }
        public AdminRecoveryCustodyState recoveryCustodyState() { return recoveryCustodyState; }
        public String recoveryCustodyAttestedAt() { return recoveryCustodyAttestedAt; }
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

    final class DeviceInfo {
        private final String deviceId;
        private final boolean current;

        public DeviceInfo(String deviceId, boolean current) {
            this.deviceId = deviceId;
            this.current = current;
        }

        public String deviceId() { return deviceId; }
        public boolean isCurrent() { return current; }
    }

    final class DeviceInventory {
        private final List<SessionInfo> sessions;
        private final List<DeviceInfo> devices;

        public DeviceInventory(List<SessionInfo> sessions, List<DeviceInfo> devices) {
            this.sessions = AdminJava8Collections.copyList(sessions);
            this.devices = AdminJava8Collections.copyList(devices);
        }

        public List<SessionInfo> sessions() { return sessions; }
        public List<DeviceInfo> devices() { return devices; }
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

    DeviceInventory getDeviceInventory(String accessToken) throws IOException;

    StateSnapshot revokeSession(String accessToken, String sessionId) throws IOException;

    StateSnapshot attestRecoveryCustody(
        String accessToken,
        RecoveryCustodyAttestation attestation
    ) throws IOException;

    StateSnapshot reportLostDevice(String accessToken, String deviceId) throws IOException;

    void clearLocalBinding();

    ReauthenticationResult reauthenticate(
        String accessToken,
        String password,
        String totpCode,
        String action,
        String method,
        String path,
        String nonce
    ) throws IOException;

    default ReauthenticationResult reauthenticate(
        String accessToken,
        char[] password,
        char[] totpCode,
        String action,
        String method,
        String path,
        String nonce
    ) throws IOException {
        throw new IOException("mutable credential reauthentication is unavailable");
    }

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
