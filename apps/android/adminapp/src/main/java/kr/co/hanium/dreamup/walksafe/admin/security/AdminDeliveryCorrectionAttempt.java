package kr.co.hanium.dreamup.walksafe.admin.security;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
/**
 * S18-40 review F3: lifecycle of the client-chosen correction ID (the server idempotency key).
 *
 * <p>The ID stays the same while the administrator resends the same intent after an uncertain
 * result, so the server can answer with the stored record instead of recording twice. It is
 * replaced after a confirmed success, when a loaded history already shows this ID (the uncertain
 * send was recorded), when the intent (target event, expected revision, reason or memo) changes
 * after a send, after a server ID conflict, and when the report changes.
 */
public final class AdminDeliveryCorrectionAttempt {
    private final Supplier<String> ids;
    private String id;
    private String sentIntent;

    public AdminDeliveryCorrectionAttempt() {
        this(() -> UUID.randomUUID().toString());
    }

    AdminDeliveryCorrectionAttempt(Supplier<String> ids) {
        if (ids == null) throw new IllegalArgumentException("correction id source is required");
        this.ids = ids;
        renew();
    }

    public String id() { return id; }

    /** True after a send whose result was not confirmed (a resend may meet the stored record). */
    public boolean uncertain() { return sentIntent != null; }

    /**
     * Builds the correction for this intent. A different intent than the one already sent under
     * the current ID gets a new ID, because the server would refuse it as an ID conflict.
     */
    public AdminDeliveryCorrection prepare(
        String correctsEventId,
        long expectedRevision,
        AdminDeliveryCorrection.Reason reason,
        String memo
    ) {
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            id, correctsEventId, expectedRevision, reason, memo
        );
        if (sentIntent != null && !sentIntent.equals(intent(correction))) {
            renew();
            correction = new AdminDeliveryCorrection(
                id, correctsEventId, expectedRevision, reason, memo
            );
        }
        return correction;
    }

    /**
     * Marks {@code correction} as sent and returns whether the same ID and intent had already
     * been sent without a confirmed result (a resend of an uncertain attempt).
     */
    public boolean markSent(AdminDeliveryCorrection correction) {
        if (correction == null || !id.equals(correction.correctionId())) {
            throw new IllegalArgumentException("correction does not belong to this attempt");
        }
        String current = intent(correction);
        boolean resend = current.equals(sentIntent);
        sentIntent = current;
        return resend;
    }

    /** The server confirmed this correction: the next correction needs a new ID. */
    public void succeeded() { renew(); }

    /** The server reported this ID as used for a different correction. */
    public void renewAfterIdConflict() { renew(); }

    /** A different report (or a cleared draft) never reuses an ID. */
    public void reset() { renew(); }

    /**
     * True when a loaded history already holds a correction with the current ID, i.e. an
     * earlier send whose result was lost was recorded. The ID is then replaced.
     */
    public boolean confirmedBy(List<AdminOperationsApi.DeliveryHistoryItem> items) {
        if (items == null) return false;
        for (AdminOperationsApi.DeliveryHistoryItem item : items) {
            if (item.correction() != null && id.equals(item.correction().id())) {
                renew();
                return true;
            }
        }
        return false;
    }

    private void renew() {
        String next = AdminReportDecision.canonicalUuid(ids.get(), "correction_id");
        if (next.equals(id)) throw new IllegalStateException("correction id was reused");
        id = next;
        sentIntent = null;
    }

    private static String intent(AdminDeliveryCorrection correction) {
        return correction.correctsEventId() + "\n" + correction.expectedRevision() + "\n"
            + correction.reason().name() + "\n" + correction.memo();
    }
}
