package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminIncidentStatusAttemptTest {
    private static final String INCIDENT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String ACTOR_ID = "admin-001";
    private static final String REASON = "실제 사고 확인 완료";
    private static final String OBSERVATION = "민감정보 없는 상태 관찰";
    private static final String EVIDENCE = "b".repeat(64);

    @Test
    public void ambiguousAttemptKeepsKeyUntilExactReplayReceivesAResponse() throws Exception {
        AdminIncidentStatusAttempt attempt = new AdminIncidentStatusAttempt();
        AdminIncidentModels.StatusRequest first = attempt.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 1, REASON, OBSERVATION, EVIDENCE
        );

        assertEquals(
            AdminIncidentStatusAttempt.Reconciliation.UNCONFIRMED,
            attempt.reconcile(detail(false, true), events(false, true))
        );
        AdminIncidentModels.StatusRequest retry = attempt.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 1, REASON, OBSERVATION, EVIDENCE
        );
        assertEquals(first.idempotencyKey(), retry.idempotencyKey());
        assertTrue(attempt.isPending());

        assertEquals(
            AdminIncidentStatusAttempt.Reconciliation.UNCONFIRMED,
            attempt.reconcile(detail(true, true), events(true, true))
        );
        assertTrue(attempt.isPending());
        assertTrue(attempt.canRetry(ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED"));
        AdminIncidentModels.StatusRequest committedReplay = attempt.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 2, REASON, OBSERVATION, EVIDENCE
        );
        assertEquals(first.idempotencyKey(), committedReplay.idempotencyKey());
        assertEquals(1, committedReplay.expectedVersion());
    }

    @Test
    public void recoverySnapshotRestoresOnlyDigestsAndReusesTheSavedKey() {
        AdminIncidentStatusAttempt original = new AdminIncidentStatusAttempt();
        AdminIncidentModels.StatusRequest first = original.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 1, REASON, OBSERVATION, EVIDENCE
        );
        AdminIncidentStatusAttempt.RecoverySnapshot snapshot = original.recoverySnapshot();
        assertFalse(snapshot.reasonDigest().contains(REASON));
        assertFalse(snapshot.observationDigest().contains(OBSERVATION));

        AdminIncidentStatusAttempt restored = new AdminIncidentStatusAttempt();
        restored.restore(
            snapshot.incidentId(),
            snapshot.actorId(),
            snapshot.nextState(),
            snapshot.expectedVersion(),
            snapshot.idempotencyKey(),
            snapshot.reasonDigest(),
            snapshot.observationDigest(),
            snapshot.evidenceDigest()
        );
        AdminIncidentModels.StatusRequest retry = restored.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 1, REASON, OBSERVATION, EVIDENCE
        );
        assertEquals(first.idempotencyKey(), retry.idempotencyKey());
        assertTrue(restored.belongsToActor(ACTOR_ID));
        assertFalse(restored.belongsToActor("admin-002"));
        assertThrows(IllegalStateException.class, () -> restored.prepare(
            ACTOR_ID,
            INCIDENT_ID,
            "ACKNOWLEDGED",
            1,
            REASON + " 변경",
            OBSERVATION,
            EVIDENCE
        ));
    }

    @Test
    public void sameNextStateFromAnotherIntentIsNotAcceptedAsThisAttempt() throws Exception {
        AdminIncidentStatusAttempt attempt = new AdminIncidentStatusAttempt();
        attempt.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 1, REASON, OBSERVATION, EVIDENCE
        );

        assertEquals(
            AdminIncidentStatusAttempt.Reconciliation.CHANGED,
            attempt.reconcile(detail(true, false), events(true, false))
        );
        assertFalse(attempt.isPending());
    }

    @Test
    public void exactTupleFromAnotherActorIsChangedAndCannotClaimThePendingKey()
        throws Exception {
        AdminIncidentStatusAttempt attempt = new AdminIncidentStatusAttempt();
        attempt.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 1, REASON, OBSERVATION, EVIDENCE
        );
        AdminIncidentModels.HistoryPage otherActor = AdminIncidentModels.parseHistoryPage(
            historyJson(true, true).replace(
                "\"actor_id\":\"admin-001\"",
                "\"actor_id\":\"admin-002\""
            ),
            INCIDENT_ID
        );

        assertEquals(
            AdminIncidentStatusAttempt.Reconciliation.CHANGED,
            attempt.reconcile(
                AdminIncidentModels.detailFromHistory(otherActor),
                otherActor.items()
            )
        );
        assertFalse(attempt.isPending());
    }

    @Test
    public void unresolvedAttemptRejectsAChangedIntentInsteadOfMintingAnotherKey() {
        AdminIncidentStatusAttempt attempt = new AdminIncidentStatusAttempt();
        attempt.prepare(
            ACTOR_ID, INCIDENT_ID, "ACKNOWLEDGED", 1, REASON, OBSERVATION, EVIDENCE
        );
        String originalKey = attempt.idempotencyKey();

        assertThrows(IllegalStateException.class, () -> attempt.prepare(
            ACTOR_ID,
            INCIDENT_ID,
            "ACKNOWLEDGED",
            1,
            REASON + " 변경",
            OBSERVATION,
            EVIDENCE
        ));
        assertEquals(originalKey, attempt.idempotencyKey());
    }

    private static AdminIncidentModels.Detail detail(boolean committed, boolean matching)
        throws Exception {
        return AdminIncidentModels.detailFromHistory(history(committed, matching));
    }

    private static java.util.List<AdminIncidentModels.Event> events(
        boolean committed,
        boolean matching
    ) throws Exception {
        return history(committed, matching).items();
    }

    private static AdminIncidentModels.HistoryPage history(boolean committed, boolean matching)
        throws Exception {
        return AdminIncidentModels.parseHistoryPage(
            historyJson(committed, matching),
            INCIDENT_ID
        );
    }

    private static String historyJson(boolean committed, boolean matching) {
        String committedReason = matching ? REASON : "다른 관리자가 확인 완료";
        String secondEvent = committed
            ? ",{"
                + "\"event_id\":\"44444444-4444-4444-8444-444444444444\","
                + "\"revision\":2,\"event_type\":\"ACKNOWLEDGED\","
                + "\"previous_state\":\"OPEN\",\"next_state\":\"ACKNOWLEDGED\","
                + "\"reason\":\"" + committedReason + "\",\"observation\":\"" + OBSERVATION + "\","
                + "\"evidence_sha256\":\"" + EVIDENCE + "\","
                + "\"observed_at\":\"2026-08-29T00:02:00Z\","
                + "\"recorded_at\":\"2026-08-29T00:02:01Z\",\"actor_id\":\"admin-001\"}"
            : "";
        int version = committed ? 2 : 1;
        String status = committed ? "ACKNOWLEDGED" : "OPEN";
        String allowed = committed ? "[\"RESOLVED\"]" : "[\"ACKNOWLEDGED\"]";
        String body = "{\"schema_version\":\"walksafe.admin-incident-history-page.v1\","
            + "\"incident\":{"
            + "\"incident_id\":\"" + INCIDENT_ID + "\",\"severity\":\"CRITICAL\","
            + "\"status\":\"" + status + "\",\"status_version\":" + version + ","
            + "\"reason_code\":\"USER_SAFETY_RISK\",\"summary\":\"안전 안내 기능 중단\","
            + "\"started_at\":\"2026-08-29T00:00:00Z\","
            + "\"detected_at\":\"2026-08-29T00:01:00Z\","
            + "\"updated_at\":\"2026-08-29T00:02:01Z\"},"
            + "\"allowed_next_states\":" + allowed + ","
            + "\"snapshot_revision\":" + version + ",\"total_count\":" + version + ","
            + "\"items\":[{"
            + "\"event_id\":\"33333333-3333-4333-8333-333333333333\","
            + "\"revision\":1,\"event_type\":\"OPENED\",\"previous_state\":null,"
            + "\"next_state\":\"OPEN\",\"reason\":\"USER_SAFETY_RISK\","
            + "\"observation\":\"안전 안내 기능 중단\",\"evidence_sha256\":\""
            + "a".repeat(64) + "\",\"observed_at\":\"2026-08-29T00:01:00Z\","
            + "\"recorded_at\":\"2026-08-29T00:01:01Z\",\"actor_id\":null}"
            + secondEvent + "],\"next_cursor\":null}";
        return body;
    }
}
