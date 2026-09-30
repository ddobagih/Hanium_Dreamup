package kr.co.hanium.dreamup.walksafe.admin.security;

import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyPreparationException.Code;

/**
 * S18-3: the administrator device key lifecycle without AndroidKeyStore dependencies.
 *
 * <p>Rotation order: create the next key locally as PENDING → the operator registers its
 * descriptor with the trusted CLI (server PENDING) → the PENDING key signs the rotation
 * payload inside a session proven by the ACTIVE key → the server revokes the old key and
 * activates the new one in one transaction → only then does the app switch its active version
 * and delete the old key. Every failure before the server confirmation keeps the ACTIVE key.
 * App reinstall or data deletion is never part of this procedure.
 */
public final class AdminDeviceKeyRotation {
    public static final String ROTATION_SCHEMA_VERSION = "walksafe.admin-device-key-rotation.v1";

    public interface KeyBackend {
        boolean contains(int version) throws GeneralSecurityException;

        /** Creates a new signing key for {@code version}; the key must not exist yet. */
        void generate(int version) throws GeneralSecurityException;

        byte[] publicKeySpki(int version) throws GeneralSecurityException;

        String securityLevel(int version) throws GeneralSecurityException;

        byte[] sign(int version, byte[] payload) throws GeneralSecurityException;

        void delete(int version) throws GeneralSecurityException;
    }

    public interface MetadataStore {
        State load() throws GeneralSecurityException;

        boolean commit(State state);
    }

    /** Persisted public metadata only: versions and SPKI SHA-256 markers. */
    public static final class State {
        public static final State EMPTY = new State(0, null, 0, null, 0);

        final int activeVersion;
        final String activeMarker;
        final int pendingVersion;
        final String pendingMarker;
        final int highestVersion;

        public State(
            int activeVersion,
            String activeMarker,
            int pendingVersion,
            String pendingMarker,
            int highestVersion
        ) {
            this.activeVersion = activeVersion;
            this.activeMarker = activeMarker;
            this.pendingVersion = pendingVersion;
            this.pendingMarker = pendingMarker;
            this.highestVersion = highestVersion;
        }

        public int activeVersion() { return activeVersion; }
        public String activeMarker() { return activeMarker; }
        public int pendingVersion() { return pendingVersion; }
        public String pendingMarker() { return pendingMarker; }
        public int highestVersion() { return highestVersion; }
    }

    public enum ReconcileOutcome {
        NO_PENDING,
        /** The server still names the active key; the pending key awaits confirmation. */
        UNCHANGED,
        /** The server already activated the pending key; the app switched to it. */
        PROMOTED,
        /** The server names neither local key; nothing was changed. */
        UNKNOWN
    }

    /** adm-F4: waits before the single re-check of a missing ACTIVE key entry. */
    interface Pause {
        void pause(long millis) throws InterruptedException;
    }

    /** adm-F4: delay before re-checking a registered key the key store reported absent. */
    static final long ACTIVE_KEY_RECHECK_DELAY_MS = 500L;

    private final KeyBackend backend;
    private final MetadataStore store;
    private final Pause pause;

    public AdminDeviceKeyRotation(KeyBackend backend, MetadataStore store) {
        this(backend, store, Thread::sleep);
    }

    AdminDeviceKeyRotation(KeyBackend backend, MetadataStore store, Pause pause) {
        if (backend == null || store == null) throw new IllegalArgumentException("key backend and store are required");
        if (pause == null) throw new IllegalArgumentException("re-check pause is required");
        this.backend = backend;
        this.store = store;
        this.pause = pause;
    }

    /**
     * S18-1-F1: the metadata names an ACTIVE key whose AndroidKeyStore entry is gone. The app
     * keeps its device identity and offers only the replacement path (new PENDING key →
     * operator {@code --replace-active} → login with the replacement key).
     */
    public static final class ActiveKeyUnavailableException extends GeneralSecurityException {
        public ActiveKeyUnavailableException() {
            super("registered administrator device key is unavailable");
        }
    }

    public synchronized AdminDeviceKeyStore.Descriptor ensureActive() throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.activeVersion == 0) {
            if (!backend.contains(1)) generateAccepted(1);
            AdminDeviceKeyStore.Descriptor descriptor = describe(1);
            if (!store.commit(new State(1, descriptor.marker(), 0, null, Math.max(1, state.highestVersion)))) {
                throw failure(Code.METADATA_WRITE_FAILED, "administrator device key metadata could not be committed");
            }
            return descriptor;
        }
        if (!backend.contains(state.activeVersion) && !containsAfterPause(state.activeVersion)) {
            throw new ActiveKeyUnavailableException();
        }
        AdminDeviceKeyStore.Descriptor descriptor = describe(state.activeVersion);
        if (!descriptor.marker().equals(state.activeMarker)) {
            throw failure(Code.MARKER_MISMATCH, "administrator device key marker does not match stored metadata");
        }
        return descriptor;
    }

    /**
     * adm-F4: an alias lookup may answer "absent" for a transient key store error, and
     * "absent" leads the operator to the replacement path. Look exactly once more after a
     * short pause; an interrupted pause is a key store problem, never "key missing".
     */
    private boolean containsAfterPause(int version) throws GeneralSecurityException {
        try {
            pause.pause(ACTIVE_KEY_RECHECK_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdminDeviceKeyPreparationException(
                Code.KEYSTORE_UNAVAILABLE, "administrator device key re-check was interrupted", interrupted);
        }
        return backend.contains(version);
    }

    /** Returns the pending rotation key, creating the next version when none exists. */
    public synchronized AdminDeviceKeyStore.Descriptor preparePending() throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.activeVersion == 0) throw new GeneralSecurityException("no active administrator device key");
        if (state.pendingVersion != 0) {
            if (backend.contains(state.pendingVersion)) {
                AdminDeviceKeyStore.Descriptor pending = describe(state.pendingVersion);
                if (!pending.marker().equals(state.pendingMarker)) {
                    throw failure(Code.MARKER_MISMATCH, "pending device key marker does not match stored metadata");
                }
                return pending;
            }
            // The pending key vanished locally; it was never usable, so forget it.
            state = commitOrThrow(new State(
                state.activeVersion, state.activeMarker, 0, null, state.highestVersion
            ));
        }
        int next = Math.max(state.activeVersion, state.highestVersion) + 1;
        if (backend.contains(next)) backend.delete(next);
        generateAccepted(next);
        AdminDeviceKeyStore.Descriptor pending;
        try {
            pending = describe(next);
        } catch (GeneralSecurityException | RuntimeException error) {
            deleteQuietly(next);
            throw error;
        }
        if (!store.commit(new State(state.activeVersion, state.activeMarker, next, pending.marker(), next))) {
            deleteQuietly(next);
            throw new GeneralSecurityException("pending device key metadata could not be committed");
        }
        return pending;
    }

    public synchronized AdminDeviceKeyStore.Descriptor pendingOrNull() throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.pendingVersion == 0 || !backend.contains(state.pendingVersion)) return null;
        AdminDeviceKeyStore.Descriptor pending = describe(state.pendingVersion);
        return pending.marker().equals(state.pendingMarker) ? pending : null;
    }

    public synchronized byte[] signActive(byte[] payload) throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.activeVersion == 0) throw new GeneralSecurityException("no active administrator device key");
        return backend.sign(state.activeVersion, payload);
    }

    /** Signs the canonical rotation payload with the PENDING key (proof of possession). */
    public synchronized byte[] signPending(byte[] payload) throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.pendingVersion == 0) throw new GeneralSecurityException("no pending administrator device key");
        return backend.sign(state.pendingVersion, payload);
    }

    /**
     * S18-1-F1: signs with exactly {@code version} while the local metadata still names it as
     * the ACTIVE or the PENDING key with the same marker. Clients built for a replacement login
     * use this so they never fall back to another key version.
     */
    public synchronized byte[] signPinned(int version, String marker, byte[] payload)
        throws GeneralSecurityException {
        if (payload == null || payload.length == 0) {
            throw new GeneralSecurityException("device proof payload is required");
        }
        State state = validated(store.load());
        boolean named = marker != null && version > 0
            && ((version == state.activeVersion && marker.equals(state.activeMarker))
                || (version == state.pendingVersion && marker.equals(state.pendingMarker)));
        if (!named) throw new GeneralSecurityException("device key version is not bound locally");
        return backend.sign(version, payload);
    }

    /**
     * S18-1-F1: after the server accepted a LOGIN or RECOVERY_COMPLETE proof signed by the
     * local PENDING key (the server issues and verifies those proofs only for its ACTIVE key),
     * make that key the local ACTIVE key. {@code serverKeyVersion}/{@code serverMarkerPrefix}
     * are the server's current-device view when known (0/null = not reported); a view naming
     * another key refuses the switch.
     *
     * @return true when {@code version} is the local ACTIVE key afterwards
     */
    public synchronized boolean promoteAfterReplacementLogin(
        int version,
        String marker,
        int serverKeyVersion,
        String serverMarkerPrefix
    ) throws GeneralSecurityException {
        State state = validated(store.load());
        if (marker == null) return false;
        if (state.pendingVersion == 0 || state.pendingVersion != version
            || !marker.equals(state.pendingMarker)) {
            return version == state.activeVersion && marker.equals(state.activeMarker);
        }
        if (serverKeyVersion != 0 && serverKeyVersion != version) return false;
        if (serverMarkerPrefix != null
            && (!serverMarkerPrefix.matches("[0-9a-f]{8}") || !marker.startsWith(serverMarkerPrefix))) {
            return false;
        }
        promotePending();
        return true;
    }

    /** Call only after the server confirmed the rotation (or reconcile saw it active). */
    public synchronized AdminDeviceKeyStore.Descriptor promotePending() throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.pendingVersion == 0) throw new GeneralSecurityException("no pending administrator device key");
        AdminDeviceKeyStore.Descriptor pending = describe(state.pendingVersion);
        if (!pending.marker().equals(state.pendingMarker)) {
            throw failure(Code.MARKER_MISMATCH, "pending device key marker does not match stored metadata");
        }
        int previous = state.activeVersion;
        commitOrThrow(new State(state.pendingVersion, state.pendingMarker, 0, null, state.highestVersion));
        // The server already revoked the previous key; an undeleted alias is inert.
        deleteQuietly(previous);
        return pending;
    }

    /** Drops a pending key that was never confirmed; the active key is untouched. */
    public synchronized void abandonPending() throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.pendingVersion == 0) return;
        int pending = state.pendingVersion;
        commitOrThrow(new State(state.activeVersion, state.activeMarker, 0, null, state.highestVersion));
        deleteQuietly(pending);
    }

    /**
     * Resolves an unknown confirmation outcome from the server's view of this device
     * (GET /admin/security/sessions: current device key_version and key_marker_prefix).
     */
    public synchronized ReconcileOutcome reconcile(int serverKeyVersion, String serverMarkerPrefix)
        throws GeneralSecurityException {
        State state = validated(store.load());
        if (state.pendingVersion == 0) return ReconcileOutcome.NO_PENDING;
        if (serverMarkerPrefix == null || !serverMarkerPrefix.matches("[0-9a-f]{8}")) {
            return ReconcileOutcome.UNKNOWN;
        }
        if (serverKeyVersion == state.pendingVersion && state.pendingMarker.startsWith(serverMarkerPrefix)) {
            promotePending();
            return ReconcileOutcome.PROMOTED;
        }
        if (serverKeyVersion == state.activeVersion && state.activeMarker.startsWith(serverMarkerPrefix)) {
            return ReconcileOutcome.UNCHANGED;
        }
        return ReconcileOutcome.UNKNOWN;
    }

    /** The exact canonical JSON the backend verifies (admin_device_key_rotation_payload). */
    public static String rotationPayload(
        String adminId,
        String deviceId,
        String sessionId,
        String correlationId,
        int previousKeyVersion,
        String previousKeyMarker,
        int keyVersion,
        String keyMarker
    ) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("admin_id", adminId);
        fields.put("correlation_id", correlationId);
        fields.put("device_id", deviceId);
        fields.put("key_marker", keyMarker);
        fields.put("key_version", keyVersion);
        fields.put("previous_key_marker", previousKeyMarker);
        fields.put("previous_key_version", previousKeyVersion);
        fields.put("schema_version", ROTATION_SCHEMA_VERSION);
        fields.put("session_id", sessionId);
        return AdminCanonicalEncoding.canonicalJson(fields);
    }

    private void generateAccepted(int version) throws GeneralSecurityException {
        backend.generate(version);
        String level;
        try {
            level = backend.securityLevel(version);
        } catch (GeneralSecurityException | RuntimeException error) {
            deleteQuietly(version);
            throw error;
        }
        if (!AdminDeviceKeySecurityLevel.isAccepted(level)) {
            deleteQuietly(version);
            throw failure(Code.HARDWARE_KEY_UNSUPPORTED, "administrator device key is not hardware-backed");
        }
    }

    private AdminDeviceKeyStore.Descriptor describe(int version) throws GeneralSecurityException {
        byte[] spki = backend.publicKeySpki(version);
        if (spki == null || spki.length == 0) {
            throw failure(Code.KEYSTORE_UNAVAILABLE, "device public key cannot be encoded");
        }
        String level;
        try {
            level = backend.securityLevel(version);
        } catch (GeneralSecurityException | RuntimeException unavailable) {
            // Only recorded here; a new key must pass generateAccepted() first.
            level = AdminDeviceKeySecurityLevel.UNKNOWN;
        }
        return new AdminDeviceKeyStore.Descriptor(
            AdminCanonicalEncoding.sha256Hex(spki),
            version,
            Base64.getUrlEncoder().withoutPadding().encodeToString(spki),
            level
        );
    }

    private State commitOrThrow(State next) throws GeneralSecurityException {
        if (!store.commit(next)) {
            throw failure(Code.METADATA_WRITE_FAILED, "administrator device key metadata could not be committed");
        }
        return next;
    }

    private void deleteQuietly(int version) {
        try {
            backend.delete(version);
        } catch (GeneralSecurityException | RuntimeException ignored) {
            // A leftover key that no metadata names can never sign a request.
        }
    }

    /** S18-23: preparation failures carry a secret-free code for the key setup screen. */
    private static AdminDeviceKeyPreparationException failure(Code code, String message) {
        return new AdminDeviceKeyPreparationException(code, message);
    }

    private static State validated(State state) throws GeneralSecurityException {
        if (state == null) throw failure(Code.METADATA_INVALID, "administrator device key metadata is invalid");
        if ((state.activeVersion == 0) != (state.activeMarker == null)
            || (state.pendingVersion == 0) != (state.pendingMarker == null)) {
            throw failure(Code.METADATA_INVALID, "administrator device key metadata is incomplete");
        }
        if (state.activeVersion < 0 || state.pendingVersion < 0 || state.highestVersion < 0) {
            throw failure(Code.VERSION_UNSUPPORTED, "administrator device key version is unsupported");
        }
        if (state.activeMarker != null && !state.activeMarker.matches("[0-9a-f]{64}")) {
            throw failure(Code.METADATA_INVALID, "administrator device key marker is invalid");
        }
        if (state.pendingVersion != 0) {
            if (state.activeVersion == 0
                || state.pendingVersion <= state.activeVersion
                || !state.pendingMarker.matches("[0-9a-f]{64}")
                || state.pendingMarker.equals(state.activeMarker)) {
                throw failure(Code.METADATA_INVALID, "pending administrator device key metadata is invalid");
            }
        }
        return state;
    }
}
