package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;

/** Non-secret, durable identity for one recovery-custody attestation attempt. */
public final class AdminRecoveryCustodyAttempt {
    public interface Store {
        Snapshot load();
        boolean save(Snapshot value);
        boolean clear();
    }

    public static final class Snapshot {
        private final String adminId;
        private final String deviceId;
        private final AdminSecurityApi.RecoveryMaterialKind materialKind;
        private final String custodyReference;
        private final String expectedStateVersion;
        private final String status;

        public Snapshot(
            String adminId,
            String deviceId,
            AdminSecurityApi.RecoveryMaterialKind materialKind,
            String custodyReference,
            String expectedStateVersion,
            String status
        ) {
            if (adminId == null || adminId.trim().isEmpty()
                || deviceId == null || deviceId.trim().isEmpty()
                || materialKind == null || expectedStateVersion == null
                || !expectedStateVersion.matches("0|[1-9][0-9]{0,18}")
                || !"PENDING".equals(status)) {
                throw new IllegalArgumentException("invalid recovery custody attempt binding");
            }
            // Uses the API's canonical 32-byte Base64url reference validation.
            new AdminSecurityApi.RecoveryCustodyAttestation(
                custodyReference, materialKind,
                AdminSecurityApi.RecoveryStorageLocation.OFF_PHONE, true,
                expectedStateVersion
            );
            this.adminId = adminId;
            this.deviceId = deviceId;
            this.materialKind = materialKind;
            this.custodyReference = custodyReference;
            this.expectedStateVersion = expectedStateVersion;
            this.status = status;
        }

        public String adminId() { return adminId; }
        public String deviceId() { return deviceId; }
        public AdminSecurityApi.RecoveryMaterialKind materialKind() { return materialKind; }
        public String custodyReference() { return custodyReference; }
        public String expectedStateVersion() { return expectedStateVersion; }
        public String status() { return status; }

        private boolean sameBinding(String adminId, String deviceId) {
            return this.adminId.equals(adminId) && this.deviceId.equals(deviceId);
        }
    }

    public static final class MemoryStore implements Store {
        private Snapshot value;

        @Override public Snapshot load() { return value; }
        @Override public boolean save(Snapshot next) { value = next; return true; }
        @Override public boolean clear() { value = null; return true; }
    }

    private final Store store;

    public AdminRecoveryCustodyAttempt(Store store) {
        if (store == null) throw new IllegalArgumentException("custody attempt store is required");
        this.store = store;
    }

    public void discardIfDifferentBinding(String adminId, String deviceId) throws IOException {
        Snapshot pending = load();
        if (pending != null && !pending.sameBinding(adminId, deviceId)) clear();
    }

    public Snapshot prepare(
        String adminId,
        String deviceId,
        AdminSecurityApi.RecoveryMaterialKind materialKind,
        String expectedStateVersion,
        String freshReference
    ) throws IOException {
        Snapshot pending = load();
        if (pending != null && !pending.sameBinding(adminId, deviceId)) {
            clear();
            pending = null;
        }
        if (pending != null) return pending;
        Snapshot created;
        try {
            created = new Snapshot(
                adminId, deviceId, materialKind, freshReference, expectedStateVersion, "PENDING"
            );
        } catch (IllegalArgumentException invalid) {
            throw new IOException("recovery custody attempt is invalid", invalid);
        }
        if (!store.save(created)) {
            throw new IOException("recovery custody attempt was not committed");
        }
        return created;
    }

    public AdminSecurityApi.RecoveryMaterialKind pendingMaterialKindFor(
        String adminId,
        String deviceId
    ) {
        try {
            Snapshot pending = load();
            return pending != null && pending.sameBinding(adminId, deviceId)
                ? pending.materialKind() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    public void confirm(Snapshot submitted) throws IOException {
        clearSubmitted(submitted);
    }

    public void abandon(Snapshot rejected) throws IOException {
        clearSubmitted(rejected);
    }

    private void clearSubmitted(Snapshot submitted) throws IOException {
        Snapshot pending = load();
        if (submitted == null || pending == null
            || !pending.sameBinding(submitted.adminId(), submitted.deviceId())
            || !pending.custodyReference().equals(submitted.custodyReference())) {
            throw new IOException("recovery custody attempt changed before confirmation");
        }
        clear();
    }

    private Snapshot load() throws IOException {
        try {
            return store.load();
        } catch (RuntimeException invalid) {
            throw new IOException("recovery custody attempt could not be read", invalid);
        }
    }

    private void clear() throws IOException {
        if (!store.clear()) throw new IOException("recovery custody attempt was not cleared");
    }
}
