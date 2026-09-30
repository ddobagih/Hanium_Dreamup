package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Activity-owner-thread handoff for unapplied, non-sensitive mutation recovery.
 * The copier selects recovery entries and makes a detached copy without defaulting values.
 */
public final class AdminPendingRecoveryState<T> {
    private final UnaryOperator<T> recoveryCopy;
    private T pending;

    public AdminPendingRecoveryState(T savedState, UnaryOperator<T> recoveryCopy) {
        this.recoveryCopy = Objects.requireNonNull(recoveryCopy, "recovery copier");
        pending = savedState == null ? null : recoveryCopy.apply(savedState);
    }

    /** Does not consume state: initialization may still fail or the Activity may stop. */
    public T snapshot() {
        return pending == null ? null : recoveryCopy.apply(pending);
    }

    /** Failed or partially applied restoration must leave the original handoff available. */
    public void restore(Consumer<T> restorer) {
        Objects.requireNonNull(restorer, "recovery restorer");
        T attempt = snapshot();
        if (attempt == null) return;
        restorer.accept(attempt);
        pending = null;
    }

    /** Exact existing saved-state keys only; no prefix-based admission of future fields. */
    public static boolean isRecoveryKey(String key) {
        if (key == null) return false;
        return switch (key) {
            case "admin_report_status_recovery_id",
                 "admin_report_status_recovery_confirmed",
                 "admin_report_status_recovery_target",
                 "admin_report_status_recovery_version",
                 "admin_incident_recovery_id",
                 "admin_incident_recovery_actor",
                 "admin_incident_recovery_next",
                 "admin_incident_recovery_version",
                 "admin_incident_recovery_key",
                 "admin_incident_recovery_reason_digest",
                 "admin_incident_recovery_observation_digest",
                 "admin_incident_recovery_evidence_digest" -> true;
            default -> false;
        };
    }
}
