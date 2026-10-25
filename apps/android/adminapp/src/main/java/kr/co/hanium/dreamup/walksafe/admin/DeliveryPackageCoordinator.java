package kr.co.hanium.dreamup.walksafe.admin;

import java.util.Objects;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackage;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackageSaver;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportModels;

/** Owns SAF request identities and package resources; Android intents/URIs stay in the view. */
final class DeliveryPackageCoordinator {
    static final int FIRST_SAVE_REQUEST = 7_301;
    static final int LAST_SAVE_REQUEST = 65_534;
    static final class Save {
        final AdminDeliveryPackage packageValue;
        final long generation;
        final long workflowGeneration;
        final long sessionGeneration;
        final String sessionId;
        final int requestCode;
        Save(AdminDeliveryPackage value, long generation, long workflowGeneration,
            long sessionGeneration, String sessionId, int requestCode) {
            this.packageValue = value;
            this.generation = generation;
            this.workflowGeneration = workflowGeneration;
            this.sessionGeneration = sessionGeneration;
            this.sessionId = sessionId;
            this.requestCode = requestCode;
        }
    }
    static final class Reconnect {
        final long generation;
        final long sessionGeneration;
        final String sessionId;
        final AdminDeliveryPackage.Eligibility eligibility;
        final AdminReportModels.DeliveryFollowUp followUp;
        Reconnect(long generation, long sessionGeneration, String sessionId,
            AdminDeliveryPackage.Eligibility eligibility, AdminReportModels.DeliveryFollowUp followUp) {
            this.generation = generation;
            this.sessionGeneration = sessionGeneration;
            this.sessionId = sessionId;
            this.eligibility = eligibility;
            this.followUp = followUp;
        }
    }

    interface SessionAccess {
        boolean refresh(String sessionId) throws IOException;
        AdminReportModels.Detail detail(String reportId) throws IOException, GeneralSecurityException;
        AdminDeliveryPackage.Proof proof(String reportId, int revision)
            throws IOException, GeneralSecurityException;
    }
    interface Source { InputStream open() throws IOException; }
    interface SelectionGuard {
        boolean current(AdminDeliveryPackage.Eligibility eligibility,
            AdminReportModels.DeliveryFollowUp followUp);
    }
    static final class Verified {
        final AdminDeliveryPackageSaver.Saved saved;
        final AdminReportModels.Detail detail;
        final AdminDeliveryPackage.Eligibility eligibility;
        final AdminReportModels.DeliveryFollowUp followUp;
        Verified(AdminDeliveryPackageSaver.Saved saved, AdminReportModels.Detail detail,
            AdminDeliveryPackage.Eligibility eligibility, AdminReportModels.DeliveryFollowUp followUp) {
            this.saved = saved;
            this.detail = detail;
            this.eligibility = eligibility;
            this.followUp = followUp;
        }
    }

    Verified saveVerified(AdminDeliveryPackage value, AdminDeliveryPackageSaver.Destination destination,
        SessionAccess access) throws IOException, GeneralSecurityException {
        AdminDeliveryPackageSaver.Saved saved = AdminDeliveryPackageSaver.save(value, destination);
        AdminReportModels.Detail afterWrite = access.detail(saved.reportId());
        if (!value.matchesFreshEligibility(afterWrite) || !saved.matchesFreshDetail(afterWrite)) {
            throw new IOException("report package eligibility changed during SAF save");
        }
        return new Verified(saved, afterWrite, null, null);
    }

    Verified reconnect(Reconnect request, Source source, SessionAccess access, SelectionGuard guard)
        throws IOException, GeneralSecurityException {
        String sessionId = request.sessionId;
        AdminDeliveryPackage.Eligibility eligibility = request.eligibility;
        AdminReportModels.DeliveryFollowUp followUp = request.followUp;
        if (!access.refresh(sessionId)) {
            throw new IOException("administrator session is no longer current");
        }
        InputStream locatorInput = source.open();
        if (locatorInput == null) throw new IOException("SAF locator input unavailable");
        AdminDeliveryPackage.Reference reference =
            AdminDeliveryPackageSaver.inspectUntrusted(locatorInput);
        AdminReportModels.DeliveryPackageUse packageUse;
        try {
            packageUse = AdminReportModels.deliveryPackageUse(
                eligibility, followUp, reference.reportId(), reference.packageRevision()
            );
        } catch (IllegalArgumentException invalidReference) {
            throw new IOException("selected package belongs to another report or submission",
                invalidReference);
        }
        AdminDeliveryPackage.Eligibility selectedEligibility =
            packageUse == AdminReportModels.DeliveryPackageUse.NEW_SUBMISSION
                ? eligibility : null;
        AdminReportModels.DeliveryFollowUp selectedFollowUp =
            packageUse == AdminReportModels.DeliveryPackageUse.FOLLOW_UP
                ? followUp : null;
        if (!guard.current(selectedEligibility, selectedFollowUp)) throw new IOException("report or submitted package changed during selection");
        String reportId = reference.reportId();
        AdminReportModels.Detail beforeRead = access.detail(reportId);
        if (selectedFollowUp == null
            ? !selectedEligibility.matchesExact(beforeRead)
            : !selectedFollowUp.matchesDetail(beforeRead)) {
            throw new IOException("report eligibility changed before package verification");
        }
        AdminDeliveryPackage.Proof proof = access.proof(reference.reportId(), reference.packageRevision());
        if (!proof.matchesReference(reference)
            || (selectedFollowUp == null && !proof.matchesFreshDetail(beforeRead))) {
            throw new IOException("package proof does not match the fresh report detail");
        }
        InputStream input = source.open();
        if (input == null) throw new IOException("SAF input unavailable");
        AdminDeliveryPackageSaver.Saved saved =
            AdminDeliveryPackageSaver.verifyExisting(proof, input);
        AdminReportModels.Detail afterRead = access.detail(reportId);
        if (!saved.matchesProof(proof)
            || (selectedFollowUp == null
                ? !selectedEligibility.matchesExact(afterRead)
                    || !proof.matchesFreshDetail(afterRead)
                    || !saved.matchesFreshDetail(afterRead)
                : !selectedFollowUp.matchesDetail(afterRead)
                    || !selectedFollowUp.matchesSavedPackage(saved))) {
            throw new IOException("report state changed during package verification");
        }
        return new Verified(saved, afterRead, selectedEligibility, selectedFollowUp);
    }

    private long saveGeneration;
    private long reconnectGeneration;
    private int nextRequestCode = FIRST_SAVE_REQUEST;
    private Save pendingSave;
    private Reconnect pendingReconnect;
    private AdminDeliveryPackageSaver.Saved verified;

    synchronized Save beginSave(AdminDeliveryPackage value, long workflow, long session, String id) {
        discardPendingSave();
        int code = nextRequestCode;
        nextRequestCode = code == LAST_SAVE_REQUEST ? FIRST_SAVE_REQUEST : code + 1;
        pendingSave = new Save(value, ++saveGeneration, workflow, session, id, code);
        return pendingSave;
    }
    synchronized Save takeSave(int requestCode) {
        if (pendingSave == null || requestCode != pendingSave.requestCode) return null;
        Save owned = pendingSave;
        pendingSave = null;
        return owned;
    }
    synchronized boolean awaitingSave() { return pendingSave != null; }
    synchronized boolean awaitingReconnect() { return pendingReconnect != null; }
    synchronized boolean isCurrentSave(long generation, long workflow, long currentWorkflow,
        long session, long currentSession, String id, String currentId) {
        return generation >= 0L && generation == saveGeneration && workflow >= 0L
            && workflow == currentWorkflow && session == currentSession
            && Objects.equals(id, currentId);
    }
    synchronized Reconnect beginReconnect(long session, String id,
        AdminDeliveryPackage.Eligibility eligibility, AdminReportModels.DeliveryFollowUp followUp) {
        if (pendingReconnect != null) throw new IllegalStateException("package picker is already open");
        pendingReconnect = new Reconnect(++reconnectGeneration, session, id, eligibility, followUp);
        return pendingReconnect;
    }
    synchronized Reconnect takeReconnect() {
        Reconnect owned = pendingReconnect;
        pendingReconnect = null;
        return owned;
    }
    synchronized boolean isCurrentReconnect(long generation, long session, long currentSession,
        String id, String currentId) {
        return generation >= 0L && generation == reconnectGeneration
            && session == currentSession && Objects.equals(id, currentId);
    }
    synchronized AdminDeliveryPackageSaver.Saved verified() { return verified; }
    synchronized void setVerified(AdminDeliveryPackageSaver.Saved value) { verified = value; }
    synchronized void discardPendingSave() {
        if (pendingSave != null) pendingSave.packageValue.destroy();
        pendingSave = null;
    }
    synchronized void invalidate() {
        saveGeneration++;
        reconnectGeneration++;
        discardPendingSave();
        pendingReconnect = null;
    }
    synchronized void clearSession() {
        invalidate();
        verified = null;
    }
}
