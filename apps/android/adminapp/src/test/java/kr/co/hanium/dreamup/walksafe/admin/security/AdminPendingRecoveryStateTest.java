package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Executes the state handoff protocol with detached maps, not Android lifecycle callbacks. */
public final class AdminPendingRecoveryStateTest {
    @Test
    public void confirmedReportAndIncidentSurviveTwoRecreationsWithExactTypes() {
        Map<String, Object> original = fullRecovery();
        AdminPendingRecoveryState<Map<String, Object>> first = pending(original);
        AdminPendingRecoveryState<Map<String, Object>> second = pending(first.snapshot());
        AdminPendingRecoveryState<Map<String, Object>> third = pending(second.snapshot());

        Map<String, Object> restored = third.snapshot();
        assertEquals(original, restored);
        assertEquals(12, restored.size());
        assertEquals(Boolean.class, restored.get(REPORT_CONFIRMED).getClass());
        assertEquals(Integer.class, restored.get(REPORT_VERSION).getClass());
        assertEquals(Integer.class, restored.get(INCIDENT_VERSION).getClass());
        assertEquals(String.class, restored.get(INCIDENT_ACTOR).getClass());
        assertTrue(restored.containsKey(INCIDENT_REASON));
        assertNull(restored.get(INCIDENT_REASON));
    }

    @Test
    public void unconfirmedAndLegacyAbsentFlagsDoNotAcquireTargetOrVersionDefaults() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put(REPORT_ID, "report-pending");
        original.put(REPORT_CONFIRMED, false);
        Map<String, Object> carried = pending(pending(original).snapshot()).snapshot();

        assertEquals(original, carried);
        assertEquals(Boolean.FALSE, carried.get(REPORT_CONFIRMED));
        assertFalse(carried.containsKey(REPORT_TARGET));
        assertFalse(carried.containsKey(REPORT_VERSION));

        original.remove(REPORT_CONFIRMED);
        carried = pending(pending(original).snapshot()).snapshot();
        assertEquals(original, carried);
        assertFalse(carried.containsKey(REPORT_CONFIRMED));
        assertFalse(carried.containsKey(REPORT_TARGET));
        assertFalse(carried.containsKey(REPORT_VERSION));
    }

    @Test
    public void onlyExactRecoveryKeysAreCarriedAndCredentialsAreNotAdded() {
        Map<String, Object> original = fullRecovery();
        Map<String, Object> expected = new LinkedHashMap<>(original);
        original.put("password", "test-only-password");
        original.put("totp", "000000");
        original.put("original_image", new byte[] {1, 2, 3});
        original.put("accessToken", "test-only-token");
        original.put("admin_report_status_recovery_unknown", "not-allowlisted");
        original.put("admin_incident_recovery_unknown", "not-allowlisted");
        original.put("admin_report_filter_id", "filter-not-recovery");

        assertEquals(expected, pending(original).snapshot());
        assertFalse(AdminPendingRecoveryState.isRecoveryKey(null));
    }

    @Test
    public void sourceAndSaveSnapshotsCannotMutateTheRetainedHandoff() {
        Map<String, Object> original = fullRecovery();
        Map<String, Object> expected = new LinkedHashMap<>(original);
        AdminPendingRecoveryState<Map<String, Object>> state = pending(original);
        original.clear();
        Map<String, Object> firstSave = state.snapshot();
        firstSave.put(INCIDENT_ACTOR, "different-actor");
        firstSave.remove(REPORT_CONFIRMED);

        assertEquals(expected, state.snapshot());
        assertEquals(expected, pending(state.snapshot()).snapshot());
    }

    @Test
    public void partialRestoreFailureKeepsOriginalStateForAnotherSaveAndRetry() {
        Map<String, Object> original = fullRecovery();
        AdminPendingRecoveryState<Map<String, Object>> state = pending(original);
        assertThrows(IllegalStateException.class, () -> state.restore(attempt -> {
            attempt.put(REPORT_VERSION, 999);
            attempt.remove(INCIDENT_ACTOR);
            throw new IllegalStateException("simulated restoration failure");
        }));

        assertEquals(original, state.snapshot());
        AdminPendingRecoveryState<Map<String, Object>> next = pending(state.snapshot());
        AtomicInteger applied = new AtomicInteger();
        next.restore(attempt -> {
            assertEquals(original, attempt);
            applied.incrementAndGet();
        });
        assertEquals(1, applied.get());
        assertNull(next.snapshot());
    }

    @Test
    public void successfulRestoreConsumesStateAndCannotResurrectOnLaterSave() {
        Map<String, Object> original = fullRecovery();
        AdminPendingRecoveryState<Map<String, Object>> state = pending(original);
        AtomicInteger applied = new AtomicInteger();
        state.restore(attempt -> {
            assertEquals(original, attempt);
            applied.incrementAndGet();
        });
        assertNull(state.snapshot());
        state.restore(attempt -> applied.incrementAndGet());
        pending(state.snapshot()).restore(attempt -> applied.incrementAndGet());
        assertEquals(1, applied.get());
    }

    @Test
    public void missingOrNonRecoveryStateDoesNotInvokeRestoration() {
        AtomicInteger applied = new AtomicInteger();
        pending(null).restore(attempt -> applied.incrementAndGet());
        pending(new LinkedHashMap<>()).restore(attempt -> applied.incrementAndGet());
        Map<String, Object> ordinaryState = new LinkedHashMap<>();
        ordinaryState.put("password", "test-only-password");
        ordinaryState.put("admin_report_filter_id", "report-filter");
        pending(ordinaryState).restore(attempt -> applied.incrementAndGet());
        assertEquals(0, applied.get());
        assertNull(pending(ordinaryState).snapshot());
    }

    private static AdminPendingRecoveryState<Map<String, Object>> pending(Map<String, Object> values) {
        return new AdminPendingRecoveryState<>(values, AdminPendingRecoveryStateTest::copyRecovery);
    }

    private static Map<String, Object> copyRecovery(Map<String, Object> values) {
        if (values == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (AdminPendingRecoveryState.isRecoveryKey(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy.isEmpty() ? null : copy;
    }

    private static Map<String, Object> fullRecovery() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(REPORT_ID, "report-confirmed");
        values.put(REPORT_CONFIRMED, true);
        values.put(REPORT_TARGET, "RESOLVED");
        values.put(REPORT_VERSION, 7);
        values.put(INCIDENT_ID, "incident-pending");
        values.put(INCIDENT_ACTOR, "admin-01");
        values.put(INCIDENT_NEXT, "ACKNOWLEDGED");
        values.put(INCIDENT_VERSION, 4);
        values.put(INCIDENT_KEY, "retained-idempotency-key");
        values.put(INCIDENT_REASON, null);
        values.put(INCIDENT_OBSERVATION, "observation-digest");
        values.put(INCIDENT_EVIDENCE, "evidence-digest");
        return values;
    }

    private static final String REPORT_ID = "admin_report_status_recovery_id";
    private static final String REPORT_CONFIRMED = "admin_report_status_recovery_confirmed";
    private static final String REPORT_TARGET = "admin_report_status_recovery_target";
    private static final String REPORT_VERSION = "admin_report_status_recovery_version";
    private static final String INCIDENT_ID = "admin_incident_recovery_id";
    private static final String INCIDENT_ACTOR = "admin_incident_recovery_actor";
    private static final String INCIDENT_NEXT = "admin_incident_recovery_next";
    private static final String INCIDENT_VERSION = "admin_incident_recovery_version";
    private static final String INCIDENT_KEY = "admin_incident_recovery_key";
    private static final String INCIDENT_REASON = "admin_incident_recovery_reason_digest";
    private static final String INCIDENT_OBSERVATION = "admin_incident_recovery_observation_digest";
    private static final String INCIDENT_EVIDENCE = "admin_incident_recovery_evidence_digest";
}
