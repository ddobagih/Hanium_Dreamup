package kr.co.hanium.dreamup.walksafe.admin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsApi;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminInstitutionDelivery;

/** Owns the selected report/session and the atomic admission of its review/delivery histories. */
final class AdminReviewDeliveryState {
    enum Change { NONE, REPORT, CONTENT, REVIEW, DELIVERY }

    private final List<AdminOperationsApi.ReviewHistoryItem> reviewHistoryItems =
        new ArrayList<>();
    private String reviewHistoryNextCursor;
    private long reviewHistorySnapshotRevision = -1L;
    private long reviewHistoryTotalCount;
    private boolean reviewHistoryNeedsFirstPage;
    private AdminOperationsApi.Result reviewRecentPreview;
    private final List<AdminOperationsApi.DeliveryHistoryItem> deliveryHistoryItems =
        new ArrayList<>();
    private String deliveryHistoryNextCursor;
    private long deliveryHistorySnapshotRevision = -1L;
    private long deliveryHistoryTotalCount;
    private boolean deliveryHistoryNeedsFirstPage;
    private AdminOperationsApi.Result deliveryRecentPreview;
    private volatile String connectedOperationsReportId;
    private int connectedOperationsContentRevision = -1;
    private int connectedOperationsReviewRevision = -1;
    // W20A-R1: report-wide latest review revision (all content revisions); the review
    // decision CAS compares with this, not with the content-scoped current_review.
    private int connectedOperationsLatestReviewRevision = -1;
    private String connectedOperationsReviewDecision;
    private int connectedOperationsLatestDeliveryRevision = -1;
    private int connectedOperationsDeliveryRevision = -1;
    private Integer connectedOperationsPackageRevision;
    private String connectedOperationsDeliveryStatus;
    private volatile String boundOperationsSessionId;
    private volatile String boundOperationsAdminId;
    private boolean operationsAccessBindingInitialized;
    private volatile boolean boundOperationsAccessActive;
    private volatile long operationsSessionGeneration;
    private volatile long reportHistoryGeneration;

    static final class Binding {
        private final String reportId;
        private final String sessionId;
        private final long sessionGeneration;
        private final long historyGeneration;
        private Binding(AdminReviewDeliveryState state) {
            reportId = state.connectedOperationsReportId;
            sessionId = state.boundOperationsSessionId;
            sessionGeneration = state.operationsSessionGeneration;
            historyGeneration = state.reportHistoryGeneration;
        }
    }
    AdminOperationsApi.Result reviewRecentPreview() { return reviewRecentPreview; }
    AdminOperationsApi.Result deliveryRecentPreview() { return deliveryRecentPreview; }
    Binding binding() { return new Binding(this); }
    boolean isCurrent(Binding binding) {
        return binding != null && boundOperationsAccessActive && binding.reportId != null
            && Objects.equals(binding.reportId, connectedOperationsReportId)
            && Objects.equals(binding.sessionId, boundOperationsSessionId)
            && binding.sessionGeneration == operationsSessionGeneration
            && binding.historyGeneration == reportHistoryGeneration;
    }
    private void requireCurrent(Binding binding) throws IOException {
        if (binding == null || !boundOperationsAccessActive || binding.reportId == null
            || !Objects.equals(binding.reportId, connectedOperationsReportId)
            || !Objects.equals(binding.sessionId, boundOperationsSessionId)
            || binding.sessionGeneration != operationsSessionGeneration
            || binding.historyGeneration != reportHistoryGeneration) {
            throw new IOException("report history belongs to an expired report or session");
        }
    }
    boolean accessBindingChanged(boolean active, String session, String admin) {
        return !operationsAccessBindingInitialized || boundOperationsAccessActive != active
            || !Objects.equals(boundOperationsSessionId, session)
            || !Objects.equals(boundOperationsAdminId, admin);
    }
    boolean restoringAccess(boolean active) {
        return operationsAccessBindingInitialized && !boundOperationsAccessActive && active;
    }
    void bindAccess(boolean active, String session, String admin) {
        if (accessBindingChanged(active, session, admin)) resetSession();
        operationsAccessBindingInitialized = true;
        boundOperationsAccessActive = active;
        boundOperationsSessionId = session;
        boundOperationsAdminId = admin;
    }
    private void resetSession() {
        operationsSessionGeneration++;
        clearSelection();
    }
    void clearSelection() {
        reportHistoryGeneration++;
        connectedOperationsReportId = null;
        connectedOperationsContentRevision = -1;
        connectedOperationsReviewRevision = -1;
        connectedOperationsLatestReviewRevision = -1;
        connectedOperationsReviewDecision = null;
        connectedOperationsLatestDeliveryRevision = -1;
        connectedOperationsDeliveryRevision = -1;
        connectedOperationsPackageRevision = null;
        connectedOperationsDeliveryStatus = null;
        resetHistory();
    }
    void requireFirstPage(boolean review) {
        if (review) reviewHistoryNeedsFirstPage = true;
        else deliveryHistoryNeedsFirstPage = true;
    }

    Change connectReport(AdminReportModels.Detail detail) {
        String reportId = detail.summary().id();
        boolean reportChanged = connectedOperationsReportId != null
            && !reportId.equals(connectedOperationsReportId);
        boolean contentRevisionChanged = connectedOperationsContentRevision >= 0
            && connectedOperationsContentRevision != detail.contentRevision();
        int reviewRevision = detail.review() == null ? -1 : detail.review().revision();
        String reviewDecision = detail.review() == null ? null : detail.review().decision();
        boolean reviewChanged = connectedOperationsContentRevision >= 0
            && (connectedOperationsReviewRevision != reviewRevision
                || connectedOperationsLatestReviewRevision != detail.latestReviewRevision()
                || !java.util.Objects.equals(connectedOperationsReviewDecision, reviewDecision));
        int deliveryRevision = detail.delivery() == null ? -1 : detail.delivery().revision();
        Integer packageRevision = detail.delivery() == null
            ? null : detail.delivery().packageRevision();
        String deliveryStatus = detail.delivery() == null ? null : detail.delivery().status();
        boolean deliveryTupleChanged = !reportChanged && !contentRevisionChanged && !reviewChanged
            && connectedOperationsContentRevision >= 0
            && (connectedOperationsDeliveryRevision != deliveryRevision
                || !java.util.Objects.equals(connectedOperationsPackageRevision, packageRevision)
                || !java.util.Objects.equals(connectedOperationsDeliveryStatus, deliveryStatus)
                || connectedOperationsLatestDeliveryRevision
                    != detail.latestDeliveryRevision());
        Change change = reportChanged ? Change.REPORT : contentRevisionChanged ? Change.CONTENT
            : reviewChanged ? Change.REVIEW : deliveryTupleChanged ? Change.DELIVERY : Change.NONE;
        if (change != Change.NONE) {
            reportHistoryGeneration++;
            resetHistory();
        }
        connectedOperationsReportId = reportId;
        connectedOperationsContentRevision = detail.contentRevision();
        connectedOperationsReviewRevision = reviewRevision;
        connectedOperationsLatestReviewRevision = detail.latestReviewRevision();
        connectedOperationsReviewDecision = reviewDecision;
        connectedOperationsLatestDeliveryRevision = detail.latestDeliveryRevision();
        connectedOperationsDeliveryRevision = deliveryRevision;
        connectedOperationsPackageRevision = packageRevision;
        connectedOperationsDeliveryStatus = deliveryStatus;
        return change;
    }

    private void resetHistory() {
        reviewRecentPreview = null;
        deliveryRecentPreview = null;
        reviewHistoryItems.clear();
        reviewHistoryNextCursor = null;
        reviewHistorySnapshotRevision = -1L;
        reviewHistoryTotalCount = 0L;
        reviewHistoryNeedsFirstPage = false;
        deliveryHistoryItems.clear();
        deliveryHistoryNextCursor = null;
        deliveryHistorySnapshotRevision = -1L;
        deliveryHistoryTotalCount = 0L;
        deliveryHistoryNeedsFirstPage = false;
    }

    void applyRecentHistoryPreview(Binding binding, boolean review, AdminOperationsApi.Result result)
        throws IOException {
        requireCurrent(binding);
        if (result == null || !java.util.Objects.equals(result.reportId(), connectedOperationsReportId)
            || result.snapshotRevision() != result.totalCount() || result.nextCursor() != null
            || result.kind() != (review
                ? AdminOperationsApi.ResultKind.REVIEW_HISTORY
                : AdminOperationsApi.ResultKind.DELIVERY_HISTORY)) {
            throw new IOException("recent history report or snapshot is invalid");
        }
        long total = result.totalCount();
        long minimumKnownRevision = review
            ? connectedOperationsLatestReviewRevision : connectedOperationsLatestDeliveryRevision;
        if (total < minimumKnownRevision) {
            throw new IOException("recent history predates connected report detail");
        }
        int count = result.returnedItemCount();
        if (count > 25 || (total == 0L) != (count == 0)
            || (total > 0L && count > total)) {
            throw new IOException("recent history item count is invalid");
        }
        long expectedRevision = total - count + 1L;
        if (review) {
            for (AdminOperationsApi.ReviewHistoryItem item : result.reviewHistory()) {
                if (item.revision() != expectedRevision++) {
                    throw new IOException("recent review history is not a contiguous suffix");
                }
            }
            reviewRecentPreview = result;
        } else {
            for (AdminOperationsApi.DeliveryHistoryItem item : result.deliveryHistory()) {
                if (item.revision() != expectedRevision++) {
                    throw new IOException("recent delivery history is not a contiguous suffix");
                }
            }
            deliveryRecentPreview = result;
        }
    }

    void applyReportHistoryPage(
        Binding binding,
        boolean review,
        boolean firstPage,
        String requestedCursor,
        AdminOperationsApi.Result result
    ) throws IOException {
        requireCurrent(binding);
        String expectedCursor = review ? reviewHistoryNextCursor : deliveryHistoryNextCursor;
        if (!firstPage && (review ? reviewHistoryNeedsFirstPage : deliveryHistoryNeedsFirstPage)) {
            throw new IOException("report history requires a fresh first page");
        }
        if ((firstPage && requestedCursor != null)
            || (!firstPage && (requestedCursor == null || !Objects.equals(requestedCursor, expectedCursor)))) {
            throw new IOException("report history request cursor does not match the loaded page");
        }
        if (result == null || !java.util.Objects.equals(result.reportId(), connectedOperationsReportId)
            || result.snapshotRevision() != result.totalCount()
            || result.snapshotRevision() < (review
                ? connectedOperationsLatestReviewRevision : connectedOperationsLatestDeliveryRevision)
            || (requestedCursor != null && requestedCursor.equals(result.nextCursor()))) {
            throw new IOException("report history page binding is invalid");
        }
        if (review) {
            if (result.kind() != AdminOperationsApi.ResultKind.REVIEW_HISTORY
                || (!firstPage && (result.snapshotRevision() != reviewHistorySnapshotRevision
                    || result.totalCount() != reviewHistoryTotalCount))) {
                throw new IOException("review history snapshot is invalid");
            }
            List<AdminOperationsApi.ReviewHistoryItem> combined = new ArrayList<>();
            if (!firstPage) combined.addAll(reviewHistoryItems);
            long expectedRevision = combined.isEmpty()
                ? 1L : combined.get(combined.size() - 1).revision() + 1L;
            for (AdminOperationsApi.ReviewHistoryItem item : result.reviewHistory()) {
                if (item.revision() != expectedRevision++) {
                    throw new IOException("review history pages are not contiguous");
                }
                combined.add(item);
            }
            requireLoadedCount(combined.size(), result.totalCount(), result.nextCursor());
            reviewHistoryItems.clear();
            reviewHistoryItems.addAll(combined);
            reviewHistorySnapshotRevision = result.snapshotRevision();
            reviewHistoryTotalCount = result.totalCount();
            reviewHistoryNextCursor = result.nextCursor();
            reviewHistoryNeedsFirstPage = false;
            return;
        }
        if (result.kind() != AdminOperationsApi.ResultKind.DELIVERY_HISTORY
            || (!firstPage && (result.snapshotRevision() != deliveryHistorySnapshotRevision
                || result.totalCount() != deliveryHistoryTotalCount))) {
            throw new IOException("delivery history snapshot is invalid");
        }
        List<AdminOperationsApi.DeliveryHistoryItem> combined = new ArrayList<>();
        if (!firstPage) combined.addAll(deliveryHistoryItems);
        long expectedRevision = combined.isEmpty()
            ? 1L : combined.get(combined.size() - 1).revision() + 1L;
        // S18-40: transitions follow the effective chain; corrected events carry no state.
        AdminOperationsApi.DeliveryHistoryItem previous = null;
        for (int index = combined.size() - 1; index >= 0; index--) {
            if (!combined.get(index).corrected()) {
                previous = combined.get(index);
                break;
            }
        }
        for (AdminOperationsApi.DeliveryHistoryItem item : result.deliveryHistory()) {
            if (item.revision() != expectedRevision++
                || (!item.corrected() && !validDeliveryHistoryTransition(previous, item))) {
                throw new IOException("delivery history pages are not contiguous");
            }
            combined.add(item);
            if (!item.corrected()) previous = item;
        }
        AdminOperationsHttpClient.requireCorrectionsStepBack(combined);
        requireLoadedCount(combined.size(), result.totalCount(), result.nextCursor());
        deliveryHistoryItems.clear();
        deliveryHistoryItems.addAll(combined);
        deliveryHistorySnapshotRevision = result.snapshotRevision();
        deliveryHistoryTotalCount = result.totalCount();
        deliveryHistoryNextCursor = result.nextCursor();
        deliveryHistoryNeedsFirstPage = false;
    }

    private static void requireLoadedCount(int loaded, long total, String nextCursor)
        throws IOException {
        if (loaded > total || (nextCursor == null && loaded != total)
            || (nextCursor != null && loaded >= total)) {
            throw new IOException("report history loaded count is invalid");
        }
    }

    private static boolean validDeliveryHistoryTransition(
        AdminOperationsApi.DeliveryHistoryItem previous,
        AdminOperationsApi.DeliveryHistoryItem next
    ) {
        boolean samePackage = previous != null
            && java.util.Objects.equals(previous.packageId(), next.packageId())
            && java.util.Objects.equals(previous.packageRevision(), next.packageRevision());
        if (!samePackage) {
            return next.status() == AdminInstitutionDelivery.Status.SUBMITTED
                || next.status() == AdminInstitutionDelivery.Status.FAILED;
        }
        return switch (previous.status()) {
            case FAILED -> next.status() == AdminInstitutionDelivery.Status.FAILED
                || next.status() == AdminInstitutionDelivery.Status.SUBMITTED;
            case SUBMITTED -> next.status() == AdminInstitutionDelivery.Status.ACKNOWLEDGED
                || next.status() == AdminInstitutionDelivery.Status.FAILED;
            case ACKNOWLEDGED -> next.status() == AdminInstitutionDelivery.Status.RESOLVED;
            case RESOLVED -> false;
        };
    }

    AdminOperationsApi.DeliveryHistoryItem latestEffectiveDeliveryForCorrection() {
        if (deliveryHistoryNeedsFirstPage
            || deliveryHistoryNextCursor != null
            || deliveryHistorySnapshotRevision < 1L
            || deliveryHistorySnapshotRevision != connectedOperationsLatestDeliveryRevision
            || deliveryHistoryItems.size() != deliveryHistoryTotalCount) {
            return null;
        }
        for (int index = deliveryHistoryItems.size() - 1; index >= 0; index--) {
            AdminOperationsApi.DeliveryHistoryItem item = deliveryHistoryItems.get(index);
            if (!item.corrected()) return item;
        }
        return null;
    }

    static boolean operationalConflictSnapshotsMatch(
        String reportId,
        boolean deliveryMutation,
        AdminReportModels.Detail detail, AdminOperationsApi.Result history
    ) {
        if (detail == null || history == null
            || !reportId.equals(detail.summary().id())
            || !reportId.equals(history.reportId())) {
            return false;
        }
        if (deliveryMutation) {
            return history.kind() == AdminOperationsApi.ResultKind.DELIVERY_HISTORY
                && detail.latestDeliveryRevision() == history.snapshotRevision();
        }
        if (history.kind() != AdminOperationsApi.ResultKind.REVIEW_HISTORY) {
            return false;
        }
        if (detail.review() != null) {
            return detail.review().revision() == history.snapshotRevision();
        }
        if (history.snapshotRevision() == 0L) return true;
        List<AdminOperationsApi.ReviewHistoryItem> items = history.reviewHistory();
        if (history.nextCursor() != null || items.isEmpty()) return false;
        AdminOperationsApi.ReviewHistoryItem latest = items.get(items.size() - 1);
        return latest.revision() == history.snapshotRevision()
            && latest.contentRevision() < detail.contentRevision();
    }

    List<AdminOperationsApi.ReviewHistoryItem> reviewHistoryItems() { return Collections.unmodifiableList(reviewHistoryItems); }
    String reviewHistoryNextCursor() { return reviewHistoryNextCursor; }
    long reviewHistorySnapshotRevision() { return reviewHistorySnapshotRevision; }
    long reviewHistoryTotalCount() { return reviewHistoryTotalCount; }
    boolean reviewHistoryNeedsFirstPage() { return reviewHistoryNeedsFirstPage; }
    List<AdminOperationsApi.DeliveryHistoryItem> deliveryHistoryItems() { return Collections.unmodifiableList(deliveryHistoryItems); }
    String deliveryHistoryNextCursor() { return deliveryHistoryNextCursor; }
    long deliveryHistorySnapshotRevision() { return deliveryHistorySnapshotRevision; }
    long deliveryHistoryTotalCount() { return deliveryHistoryTotalCount; }
    boolean deliveryHistoryNeedsFirstPage() { return deliveryHistoryNeedsFirstPage; }
    String connectedOperationsReportId() { return connectedOperationsReportId; }
    int connectedOperationsContentRevision() { return connectedOperationsContentRevision; }
    int connectedOperationsReviewRevision() { return connectedOperationsReviewRevision; }
    int connectedOperationsLatestReviewRevision() { return connectedOperationsLatestReviewRevision; }
    String connectedOperationsReviewDecision() { return connectedOperationsReviewDecision; }
    int connectedOperationsLatestDeliveryRevision() { return connectedOperationsLatestDeliveryRevision; }
    int connectedOperationsDeliveryRevision() { return connectedOperationsDeliveryRevision; }
    Integer connectedOperationsPackageRevision() { return connectedOperationsPackageRevision; }
    String connectedOperationsDeliveryStatus() { return connectedOperationsDeliveryStatus; }
    String boundOperationsSessionId() { return boundOperationsSessionId; }
    String boundOperationsAdminId() { return boundOperationsAdminId; }
    boolean operationsAccessBindingInitialized() { return operationsAccessBindingInitialized; }
    boolean boundOperationsAccessActive() { return boundOperationsAccessActive; }
    long operationsSessionGeneration() { return operationsSessionGeneration; }
    long reportHistoryGeneration() { return reportHistoryGeneration; }
}
