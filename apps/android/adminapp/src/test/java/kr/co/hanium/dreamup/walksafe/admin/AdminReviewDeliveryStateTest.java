package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.*;
import static kr.co.hanium.dreamup.walksafe.admin.security.AdminHistoryFixtures.*;
import static kr.co.hanium.dreamup.walksafe.admin.security.AdminInstitutionDelivery.Status.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import kr.co.hanium.dreamup.walksafe.admin.security.*;
import org.junit.Test;

public final class AdminReviewDeliveryStateTest {
    private static final String REPORT = "11111111-1111-4111-8111-111111111111";

    @Test public void queuedOperationPageCannotSurviveRevisionOrSessionReplacement() throws Exception {
        AdminReviewDeliveryState state = state();
        var first = state.binding();
        assertTrue(state.isCurrent(first));
        state.connectReport(detail(3));
        assertFalse(state.isCurrent(first));
        var latest = state.binding();
        assertTrue(state.isCurrent(latest));
        state.bindAccess(true, "session-2", "admin");
        assertFalse(state.isCurrent(latest));
        state.connectReport(detail(3));
        var replacement = state.binding();
        assertTrue(state.isCurrent(replacement));
        state.clearSelection();
        assertFalse(state.isCurrent(replacement));
    }

    @Test public void duplicateMissingOrWrongCursorPagesPreserveTheAdmittedSnapshot() throws Exception {
        AdminReviewDeliveryState state = state();
        var binding = state.binding();
        state.applyReportHistoryPage(binding, true, true, null, reviews(REPORT, 3, "page2", 1));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, true, false, "page2", reviews(REPORT, 3, "page3", 1)));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, true, false, "page2", reviews(REPORT, 3, null, 3)));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, true, false, "wrong", reviews(REPORT, 3, null, 2, 3)));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, true, false, "page2", reviews(REPORT, 4, null, 2, 3, 4)));
        assertEquals(1, state.reviewHistoryItems().size());
        assertEquals("page2", state.reviewHistoryNextCursor());
        state.applyReportHistoryPage(binding, true, false, "page2", reviews(REPORT, 3, null, 2, 3));
        assertEquals(3, state.reviewHistoryItems().size());
        assertNull(state.reviewHistoryNextCursor());
        assertThrows(UnsupportedOperationException.class, () -> state.reviewHistoryItems().clear());
    }

    @Test public void missingFinalItemsOrRepeatedNextCursorAreRejectedAtomically() throws Exception {
        AdminReviewDeliveryState state = state();
        var binding = state.binding();
        state.applyReportHistoryPage(binding, true, true, null, reviews(REPORT, 3, "page2", 1));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, true, false, "page2", reviews(REPORT, 3, null, 2)));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, true, false, "page2", reviews(REPORT, 3, "page2", 2)));
        assertEquals(1, state.reviewHistoryItems().size());
        assertEquals("page2", state.reviewHistoryNextCursor());
    }

    @Test public void firstPageCannotMoveBehindTheConnectedReportRevision() throws Exception {
        AdminReviewDeliveryState state = state();
        var binding = state.binding();
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, true, true, null,
            reviews(REPORT, 1, null, 1)));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, false, true, null,
            deliveries(REPORT, 1, null, delivery(1, SUBMITTED, null))));
        assertEquals(-1, state.reviewHistorySnapshotRevision());
        assertEquals(-1, state.deliveryHistorySnapshotRevision());
    }

    @Test public void correctionTargetRequiresCompleteCurrentHistoryAndFollowsEffectiveChain() throws Exception {
        AdminReviewDeliveryState state = state();
        state.connectReport(detail(3));
        var binding = state.binding();
        state.applyReportHistoryPage(binding, false, true, null, deliveries(REPORT, 3, "page2", delivery(1, SUBMITTED, null)));
        assertNull(state.latestEffectiveDeliveryForCorrection());
        state.applyReportHistoryPage(binding, false, false, "page2", deliveries(REPORT, 3, null,
            delivery(2, ACKNOWLEDGED, 2L), delivery(3, ACKNOWLEDGED, null)));
        assertEquals(3, state.latestEffectiveDeliveryForCorrection().revision());
        state.requireFirstPage(false);
        assertNull(state.latestEffectiveDeliveryForCorrection());
    }

    @Test public void correctionCannotSkipALaterEffectiveEventAcrossPageBoundary() throws Exception {
        AdminReviewDeliveryState state = state();
        var binding = state.binding();
        state.applyReportHistoryPage(binding, false, true, null, deliveries(REPORT, 2, "page2", delivery(1, SUBMITTED, 2L)));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, false, false, "page2",
            deliveries(REPORT, 2, null, delivery(2, SUBMITTED, null))));
        assertEquals(1, state.deliveryHistoryItems().size());
        assertEquals("page2", state.deliveryHistoryNextCursor());
    }

    @Test public void invalidDeliveryTransitionCannotReplacePreviouslyAdmittedHistory() throws Exception {
        AdminReviewDeliveryState state = state();
        var binding = state.binding();
        state.applyReportHistoryPage(binding, false, true, null, deliveries(REPORT, 2, "page2", delivery(1, SUBMITTED, null)));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(binding, false, false, "page2",
            deliveries(REPORT, 2, null, delivery(2, RESOLVED, null))));
        assertEquals(1, state.deliveryHistoryItems().size());
    }

    @Test public void sameReportAfterSessionChangeRejectsLatePagesAndPreviews() throws Exception {
        AdminReviewDeliveryState state = state();
        var old = state.binding();
        state.bindAccess(true, "session-2", "admin");
        state.connectReport(detail(2));
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(old, true, true, null, reviews(REPORT, 1, null, 1)));
        assertThrows(IOException.class, () -> state.applyRecentHistoryPreview(old, true, reviews(REPORT, 1, null, 1)));
        assertEquals(-1L, state.reviewHistorySnapshotRevision());
        assertNull(state.reviewRecentPreview());
    }

    @Test public void changedReportRevisionInvalidatesLatePagesAndClearsCorrectionState() throws Exception {
        AdminReviewDeliveryState state = state();
        var old = state.binding();
        state.applyReportHistoryPage(old, false, true, null, deliveries(REPORT, 2, null,
            delivery(1, SUBMITTED, null), delivery(2, ACKNOWLEDGED, null)));
        assertNotNull(state.latestEffectiveDeliveryForCorrection());
        assertEquals(AdminReviewDeliveryState.Change.DELIVERY, state.connectReport(detail(3)));
        assertNull(state.latestEffectiveDeliveryForCorrection());
        assertThrows(IOException.class, () -> state.applyReportHistoryPage(old, false, true, null,
            deliveries(REPORT, 2, null, delivery(1, SUBMITTED, null), delivery(2, ACKNOWLEDGED, null))));
        state.clearSelection();
        assertNull(state.connectedOperationsReportId());
    }

    @Test public void previewsRemainSeparateAndMustBeAContiguousSuffix() throws Exception {
        AdminReviewDeliveryState state = state();
        var binding = state.binding();
        state.applyRecentHistoryPreview(binding, true, reviews(REPORT, 5, null, 4, 5));
        assertEquals(2, state.reviewRecentPreview().reviewHistory().size());
        assertTrue(state.reviewHistoryItems().isEmpty());
        assertThrows(IOException.class, () -> state.applyRecentHistoryPreview(binding, true, reviews(REPORT, 5, null, 3, 5)));
        assertEquals(4, state.reviewRecentPreview().reviewHistory().get(0).revision());
    }

    private static AdminReviewDeliveryState state() throws Exception {
        AdminReviewDeliveryState state = new AdminReviewDeliveryState();
        state.bindAccess(true, "session-1", "admin");
        state.connectReport(detail(2));
        return state;
    }
    private static AdminReportModels.Detail detail(int latestDelivery) throws Exception {
        String json = new String(
            Files.readAllBytes(Paths.get("../../../contracts/fixtures/admin-report-detail-v2.json")),
            StandardCharsets.UTF_8
        );
        json = json.replace("\"latest_delivery_revision\": 3", "\"latest_delivery_revision\": " + latestDelivery)
            .replace("\"revision\": 3", "\"revision\": " + latestDelivery);
        return AdminReportModels.parseDetail(json, REPORT);
    }
}
