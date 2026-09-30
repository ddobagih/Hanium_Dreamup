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
        private final String expectedStateVersion;

        public RecoveryCustodyAttestation(
            String custodyReference,
            RecoveryMaterialKind materialKind,
            RecoveryStorageLocation storageLocation,
            boolean separateEncryptedBackupConfirmed
        ) {
            this(custodyReference, materialKind, storageLocation,
                separateEncryptedBackupConfirmed, null);
        }

        public RecoveryCustodyAttestation(
            String custodyReference,
            RecoveryMaterialKind materialKind,
            RecoveryStorageLocation storageLocation,
            boolean separateEncryptedBackupConfirmed,
            String expectedStateVersion
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
            if (expectedStateVersion != null
                && !expectedStateVersion.matches("0|[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException("invalid expected recovery custody state version");
            }
            this.expectedStateVersion = expectedStateVersion;
        }

        public String custodyReference() { return custodyReference; }
        public RecoveryMaterialKind materialKind() { return materialKind; }
        public RecoveryStorageLocation storageLocation() { return storageLocation; }
        public boolean isSeparateEncryptedBackupConfirmed() {
            return separateEncryptedBackupConfirmed;
        }
        public String expectedStateVersion() { return expectedStateVersion; }

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
        private final String deviceLabel;
        private final String lastSeenAt;
        private final int keyVersion;
        private final String keyMarkerPrefix;

        public DeviceInfo(String deviceId, boolean current) {
            this(deviceId, current, null, null, 0, null);
        }

        /**
         * Identification fields for picking a lost device: label and last activity of its
         * latest session (null for a key-only device), key version and the key marker's
         * first 8 hex characters. keyVersion 0 / null prefix means the server did not say.
         */
        public DeviceInfo(
            String deviceId,
            boolean current,
            String deviceLabel,
            String lastSeenAt,
            int keyVersion,
            String keyMarkerPrefix
        ) {
            this.deviceId = deviceId;
            this.current = current;
            this.deviceLabel = deviceLabel;
            this.lastSeenAt = lastSeenAt;
            this.keyVersion = keyVersion;
            this.keyMarkerPrefix = keyMarkerPrefix;
        }

        public String deviceId() { return deviceId; }
        public boolean isCurrent() { return current; }
        public String deviceLabel() { return deviceLabel; }
        public String lastSeenAt() { return lastSeenAt; }
        public int keyVersion() { return keyVersion; }
        public String keyMarkerPrefix() { return keyMarkerPrefix; }
    }

    final class DeviceInventory {
        private final List<SessionInfo> sessions;
        private final List<DeviceInfo> devices;
        private final int deviceTotal;
        private final boolean devicesComplete;

        public DeviceInventory(List<SessionInfo> sessions, List<DeviceInfo> devices) {
            this(sessions, devices, devices == null ? 0 : devices.size(), true);
        }

        public DeviceInventory(
            List<SessionInfo> sessions,
            List<DeviceInfo> devices,
            int deviceTotal,
            boolean devicesComplete
        ) {
            this.sessions = AdminJava8Collections.copyList(sessions);
            this.devices = AdminJava8Collections.copyList(devices);
            this.deviceTotal = deviceTotal;
            this.devicesComplete = devicesComplete;
        }

        public List<SessionInfo> sessions() { return sessions; }
        public List<DeviceInfo> devices() { return devices; }
        /**
         * All active trusted devices on the server, including ones not listed; -1 when the
         * server did not report a total (legacy sessions-only response).
         */
        public int deviceTotal() { return deviceTotal; }
        /** False when the capped list omits some active devices or the server did not say. */
        public boolean isDevicesComplete() { return devicesComplete; }
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

    /** Signs the canonical rotation payload with the PENDING device key (S18-3). */
    @FunctionalInterface
    interface RotationSigner {
        byte[] sign(byte[] payload) throws java.security.GeneralSecurityException;
    }

    /**
     * S18-3: confirm this device's PENDING key. The request is device-proofed with the ACTIVE
     * key; the PENDING key's signature over the canonical rotation payload proves possession.
     */
    final class DeviceKeyRotationRequest {
        private final int previousKeyVersion;
        private final String previousKeyMarker;
        private final int keyVersion;
        private final String keyMarker;
        private final RotationSigner pendingKeySigner;

        public DeviceKeyRotationRequest(
            int previousKeyVersion,
            String previousKeyMarker,
            int keyVersion,
            String keyMarker,
            RotationSigner pendingKeySigner
        ) {
            if (previousKeyVersion < 1 || keyVersion <= previousKeyVersion
                || previousKeyMarker == null || !previousKeyMarker.matches("[0-9a-f]{64}")
                || keyMarker == null || !keyMarker.matches("[0-9a-f]{64}")
                || keyMarker.equals(previousKeyMarker) || pendingKeySigner == null) {
                throw new IllegalArgumentException("invalid device key rotation request");
            }
            this.previousKeyVersion = previousKeyVersion;
            this.previousKeyMarker = previousKeyMarker;
            this.keyVersion = keyVersion;
            this.keyMarker = keyMarker;
            this.pendingKeySigner = pendingKeySigner;
        }

        public int previousKeyVersion() { return previousKeyVersion; }
        public String previousKeyMarker() { return previousKeyMarker; }
        public int keyVersion() { return keyVersion; }
        public String keyMarker() { return keyMarker; }
        public RotationSigner pendingKeySigner() { return pendingKeySigner; }
    }

    final class DeviceKeyRotationResult {
        private final int keyVersion;
        private final String keyMarker;

        public DeviceKeyRotationResult(int keyVersion, String keyMarker) {
            this.keyVersion = keyVersion;
            this.keyMarker = keyMarker;
        }

        public int keyVersion() { return keyVersion; }
        public String keyMarker() { return keyMarker; }
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

    /**
     * S18-20: login from caller-owned mutable buffers. The HTTP client encodes them straight
     * into a zeroed-after-use UTF-8 body and never builds a String of the secret; the caller
     * zeroes its buffers. An implementation without mutable support refuses rather than
     * converting to String.
     */
    default LoginResult login(
        String adminId,
        char[] password,
        char[] totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        throw new IOException("mutable credential login is unavailable");
    }

    StateSnapshot getState(String accessToken) throws IOException;

    DeviceInventory getDeviceInventory(String accessToken) throws IOException;

    default DeviceInventory getCompleteDeviceInventory(
        String accessToken,
        Runnable ensureCurrent
    ) throws IOException {
        if (ensureCurrent == null) throw new IllegalArgumentException("current operation check is required");
        ensureCurrent.run();
        return getDeviceInventory(accessToken);
    }

    StateSnapshot revokeSession(String accessToken, String sessionId) throws IOException;

    StateSnapshot attestRecoveryCustody(
        String accessToken,
        RecoveryCustodyAttestation attestation
    ) throws IOException;

    /**
     * Revokes another device's keys and sessions. This is HIGH risk (S18-29): the headers
     * must be the single-use reconfirmation nonce bound to the exact report-lost path.
     */
    StateSnapshot reportLostDevice(
        String accessToken,
        String deviceId,
        java.util.Map<String, String> reconfirmationHeaders
    ) throws IOException;

    void clearLocalBinding();

    /**
     * S18-18: explicit cancellation of this client's HTTP requests still in flight. It never
     * waits for them and never re-sends anything; a cancelled request's outcome is unknown.
     * It must not take the client's request lock, which the blocked request may hold.
     */
    default void cancelInFlightRequests() {}

    default DeviceKeyRotationResult confirmDeviceKeyRotation(
        String accessToken,
        DeviceKeyRotationRequest request
    ) throws IOException {
        throw new IOException("administrator device key rotation is unavailable");
    }

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

    /**
     * S18-7: reauthentication bound to the SHA-256 of the exact request body, when known.
     * The HTTP client sends it; an implementation without binding support stays unbound.
     */
    default ReauthenticationResult reauthenticate(
        String accessToken,
        char[] password,
        char[] totpCode,
        String action,
        String method,
        String path,
        String nonce,
        String intentSha256
    ) throws IOException {
        return reauthenticate(accessToken, password, totpCode, action, method, path, nonce);
    }

    default ReauthenticationResult reauthenticate(
        String accessToken,
        String password,
        String totpCode,
        String action,
        String method,
        String path,
        String nonce,
        String intentSha256
    ) throws IOException {
        return reauthenticate(accessToken, password, totpCode, action, method, path, nonce);
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

    /** S18-20: recovery start from a caller-owned mutable recovery code (see login). */
    default RecoveryStartResult startRecovery(
        String adminId,
        char[] recoveryCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        throw new IOException("mutable credential recovery is unavailable");
    }

    /** S18-20: recovery completion from caller-owned mutable secrets (see login). */
    default LoginResult completeRecovery(
        String recoveryToken,
        char[] newPassword,
        char[] totpCode,
        String deviceId,
        String deviceLabel
    ) throws IOException {
        throw new IOException("mutable credential recovery is unavailable");
    }
}
