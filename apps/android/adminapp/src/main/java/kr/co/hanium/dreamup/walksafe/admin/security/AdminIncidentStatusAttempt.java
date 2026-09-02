package kr.co.hanium.dreamup.walksafe.admin.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Keeps one incident mutation identity stable without retaining raw operator-entered text. */
public final class AdminIncidentStatusAttempt {
    public enum Reconciliation { NONE, UNCONFIRMED, CHANGED }

    public static final class RecoverySnapshot {
        private final String incidentId;
        private final String actorId;
        private final String nextState;
        private final int expectedVersion;
        private final String idempotencyKey;
        private final String reasonDigest;
        private final String observationDigest;
        private final String evidenceDigest;

        private RecoverySnapshot(
            String incidentId,
            String actorId,
            String nextState,
            int expectedVersion,
            String idempotencyKey,
            String reasonDigest,
            String observationDigest,
            String evidenceDigest
        ) {
            this.incidentId = incidentId;
            this.actorId = actorId;
            this.nextState = nextState;
            this.expectedVersion = expectedVersion;
            this.idempotencyKey = idempotencyKey;
            this.reasonDigest = reasonDigest;
            this.observationDigest = observationDigest;
            this.evidenceDigest = evidenceDigest;
        }

        public String incidentId() { return incidentId; }
        public String actorId() { return actorId; }
        public String nextState() { return nextState; }
        public int expectedVersion() { return expectedVersion; }
        public String idempotencyKey() { return idempotencyKey; }
        public String reasonDigest() { return reasonDigest; }
        public String observationDigest() { return observationDigest; }
        public String evidenceDigest() { return evidenceDigest; }
    }

    private String incidentId;
    private String actorId;
    private String nextState;
    private int expectedVersion;
    private String idempotencyKey;
    private String reasonDigest;
    private String observationDigest;
    private String evidenceDigest;

    public synchronized AdminIncidentModels.StatusRequest prepare(
        String actorId,
        String incidentId,
        String nextState,
        int expectedVersion,
        String reason,
        String observation,
        String evidenceSha256
    ) {
        String safeActorId = requireActorId(actorId);
        String safeId = AdminIncidentModels.canonicalUuid(incidentId, "incident_id");
        String requestKey = idempotencyKey == null
            ? UUID.randomUUID().toString()
            : idempotencyKey;
        int requestExpectedVersion = idempotencyKey == null
            ? expectedVersion
            : this.expectedVersion;
        AdminIncidentModels.StatusRequest candidate = new AdminIncidentModels.StatusRequest(
            nextState,
            requestExpectedVersion,
            requestKey,
            reason,
            observation,
            evidenceSha256
        );
        if (idempotencyKey == null) {
            retain(safeActorId, safeId, candidate);
            return candidate;
        }
        if (!safeActorId.equals(this.actorId) || !safeId.equals(this.incidentId)
            || !sameIntent(candidate)) {
            throw new IllegalStateException(
                "the previous incident status outcome must be reconciled before a new intent"
            );
        }
        return candidate;
    }

    public synchronized Reconciliation reconcile(
        AdminIncidentModels.Detail detail,
        List<AdminIncidentModels.Event> events
    ) {
        if (idempotencyKey == null || detail == null
            || !incidentId.equals(detail.summary().incidentId())) {
            return Reconciliation.NONE;
        }
        int version = detail.summary().statusVersion();
        if (version <= expectedVersion) return Reconciliation.UNCONFIRMED;
        if (version != expectedVersion + 1 || !nextState.equals(detail.summary().status())) {
            clear();
            return Reconciliation.CHANGED;
        }
        AdminIncidentModels.Event latest = latestEvent(events, version);
        if (latest == null) return Reconciliation.UNCONFIRMED;
        if (!matchesEvent(latest)) {
            clear();
            return Reconciliation.CHANGED;
        }
        // History does not expose the idempotency key, so an exact event tuple alone cannot
        // prove this key committed. Retain it until an exact replay receives a response.
        return Reconciliation.UNCONFIRMED;
    }

    public synchronized RecoverySnapshot recoverySnapshot() {
        if (idempotencyKey == null) return null;
        return new RecoverySnapshot(
            incidentId,
            actorId,
            nextState,
            expectedVersion,
            idempotencyKey,
            reasonDigest,
            observationDigest,
            evidenceDigest
        );
    }

    public synchronized void restore(
        String incidentId,
        String actorId,
        String nextState,
        int expectedVersion,
        String idempotencyKey,
        String reasonDigest,
        String observationDigest,
        String evidenceDigest
    ) {
        String safeId = AdminIncidentModels.canonicalUuid(incidentId, "incident_id");
        String safeActorId = requireActorId(actorId);
        String safeKey = AdminIncidentModels.canonicalUuid(idempotencyKey, "idempotency_key");
        if (!isMutationState(nextState) || expectedVersion < 1
            || !isDigest(reasonDigest) || !isDigest(observationDigest)
            || !isDigest(evidenceDigest)) {
            throw new IllegalArgumentException("incident status recovery snapshot is invalid");
        }
        this.incidentId = safeId;
        this.actorId = safeActorId;
        this.nextState = nextState;
        this.expectedVersion = expectedVersion;
        this.idempotencyKey = safeKey;
        this.reasonDigest = reasonDigest;
        this.observationDigest = observationDigest;
        this.evidenceDigest = evidenceDigest;
    }

    public synchronized boolean isPending() { return idempotencyKey != null; }

    public synchronized String incidentId() { return incidentId; }

    public synchronized String nextState() { return nextState; }

    public synchronized boolean canRetry(
        String candidateActorId,
        String candidateIncidentId,
        String candidateNextState
    ) {
        return idempotencyKey != null
            && actorId.equals(candidateActorId)
            && incidentId.equals(candidateIncidentId)
            && nextState.equals(candidateNextState);
    }

    public synchronized boolean belongsToActor(String candidateActorId) {
        return idempotencyKey != null && actorId.equals(candidateActorId);
    }

    public synchronized String idempotencyKey() { return idempotencyKey; }

    public synchronized void clear() {
        incidentId = null;
        actorId = null;
        nextState = null;
        expectedVersion = 0;
        idempotencyKey = null;
        reasonDigest = null;
        observationDigest = null;
        evidenceDigest = null;
    }

    private void retain(
        String safeActorId,
        String safeId,
        AdminIncidentModels.StatusRequest request
    ) {
        actorId = safeActorId;
        incidentId = safeId;
        nextState = request.nextState();
        expectedVersion = request.expectedVersion();
        idempotencyKey = request.idempotencyKey();
        reasonDigest = digest(request.reason());
        observationDigest = digest(request.observation());
        evidenceDigest = digest(request.evidenceSha256());
    }

    private boolean sameIntent(AdminIncidentModels.StatusRequest request) {
        return nextState.equals(request.nextState())
            && expectedVersion == request.expectedVersion()
            && reasonDigest.equals(digest(request.reason()))
            && observationDigest.equals(digest(request.observation()))
            && evidenceDigest.equals(digest(request.evidenceSha256()));
    }

    private boolean matchesEvent(AdminIncidentModels.Event event) {
        return nextState.equals(event.nextState())
            && actorId.equals(event.actorId())
            && reasonDigest.equals(digest(event.reason()))
            && observationDigest.equals(digest(event.observation()))
            && evidenceDigest.equals(digest(event.evidenceSha256()));
    }

    private static AdminIncidentModels.Event latestEvent(
        List<AdminIncidentModels.Event> events,
        int expectedRevision
    ) {
        if (events == null || events.isEmpty()) return null;
        AdminIncidentModels.Event latest = events.get(events.size() - 1);
        return latest.revision() == expectedRevision ? latest : null;
    }

    private static String digest(String value) {
        return AdminCanonicalEncoding.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isMutationState(String value) {
        return "ACKNOWLEDGED".equals(value)
            || "RESOLVED".equals(value)
            || "REOPENED".equals(value);
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String requireActorId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IllegalArgumentException("incident status actor is invalid");
        }
        return value;
    }
}
