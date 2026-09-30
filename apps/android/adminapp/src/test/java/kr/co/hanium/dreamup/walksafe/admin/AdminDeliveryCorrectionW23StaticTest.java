package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** S18-40: the correction action is HIGH-risk, memory-only for credentials, and never auto-retried. */
public final class AdminDeliveryCorrectionW23StaticTest {
    private static final String ACTIVITY = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
    );
    private static final String CONTROLLER = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java"
    );

    @Test
    public void s18_40_correctionFlowReauthenticatesWithDedicatedCredentialsAndClearsThem() {
        String flow = between(ACTIVITY, "private void correctLatestDelivery()", "private void runReportHistoryOperation(");
        assertTrue(flow.contains("requireConnectedOperationsReportId()"));
        assertTrue(flow.contains("latestEffectiveDeliveryForCorrection()"));
        assertTrue(flow.contains("takeMutableInput(deliveryCorrectionPasswordInput)"));
        assertTrue(flow.contains("takeMutableInput(deliveryCorrectionTotpInput)"));
        assertTrue(flow.contains("controller.correctDelivery("));
        assertTrue(flow.contains("BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED"));
        assertTrue(flow.contains("connectedOperationsLatestDeliveryRevision"));
        assertTrue(flow.contains("Arrays.fill(requestPassword, '\\0')"));
        assertTrue(flow.contains("Arrays.fill(requestTotp, '\\0')"));
        assertTrue(flow.contains("refreshConnectedReportDetail()"));
        assertEquals(1, occurrences(flow, "controller.correctDelivery("));
        assertEquals(1, occurrences(flow, "setInteractiveEnabled(contentRoot, true)"));
        // No automatic replay of a credential-bearing request (S18-1-F2).
        assertFalse(flow.contains("handleDeviceKeyUseFailure("));
        assertFalse(flow.contains("runOperationalOperation("));
        assertFalse(flow.contains("getText().toString()"));
        assertFalse(flow.contains("startActivity"));
        // A new correction id only after a confirmed success; an ambiguous failure keeps it
        // (review F3: the id also follows the intent and an id conflict renews it).
        assertEquals(1, occurrences(flow, "deliveryCorrectionAttempt.succeeded()"));
        assertTrue(flow.indexOf("if (succeeded)") < flow.indexOf("deliveryCorrectionAttempt.succeeded()"));
        assertFalse(ACTIVITY.contains("deliveryCorrectionAttemptId"));
    }
    @Test
    public void s18_40_f3_attemptIdFollowsTheIntentAndIsConfirmedFromTheLoadedHistory() {
        String flow = between(ACTIVITY, "private void correctLatestDelivery()", "private void runReportHistoryOperation(");
        assertTrue(flow.contains("deliveryCorrectionAttempt.prepare("));
        assertTrue(flow.contains("deliveryCorrectionAttempt.markSent(correction)"));
        assertTrue(flow.indexOf("deliveryCorrectionAttempt.prepare(")
            < flow.indexOf("deliveryCorrectionAttempt.markSent(correction)"));
        assertTrue(flow.contains("catch (AdminOperationsApi.CorrectionIdConflictException"));
        assertTrue(flow.contains("deliveryCorrectionAttempt.renewAfterIdConflict()"));
        assertTrue(flow.indexOf("catch (AdminOperationsApi.CorrectionIdConflictException")
            < flow.indexOf("catch (AdminOperationsApi.MutationConflictException"));
        String history = between(ACTIVITY, "private void runReportHistoryOperation(", "private void applyReportHistoryPage(");
        assertTrue(history.contains("deliveryCorrectionAttempt.confirmedBy(deliveryHistoryItems)"));
        String method = between(
            CONTROLLER,
            "public AdminOperationsApi.Result correctDelivery(",
            "public AdminOperationsApi.Result readDeliveries("
        );
        assertTrue(method.contains("resendOfUncertainAttempt"));
    }
    @Test
    public void s18_40_f6_successAlwaysInvalidatesTheLoadedDeliveryHistory() {
        String flow = between(ACTIVITY, "private void correctLatestDelivery()", "private void runReportHistoryOperation(");
        String success = flow.substring(flow.indexOf("if (succeeded)"));
        assertTrue(success.contains("deliveryHistoryNeedsFirstPage = true;"));
        assertTrue(success.indexOf("deliveryHistoryNeedsFirstPage = true;")
            < success.indexOf("refreshConnectedReportDetail()"));
    }
    @Test
    public void s18_40_f8_historyReadsTheKoreanReasonAndTheScreenWarnsAboutReopening() {
        String text = between(ACTIVITY, "private String deliveryHistoryText()", "private static boolean validDeliveryHistoryTransition(");
        assertTrue(text.contains("deliveryCorrectionReasonLabel(item.correction().reason())"));
        assertFalse(text.contains(".append(item.correction().reason())"));
        String label = between(
            ACTIVITY,
            "private static String deliveryCorrectionReasonLabel(",
            "private static boolean validDeliveryHistoryTransition("
        );
        assertTrue(label.contains("DELIVERY_CORRECTION_REASON_LABELS[index]"));
        // w24 S18-40 / owner decision 6: no REOPEN event; a later new damage is a new report.
        assertTrue(ACTIVITY.contains("실제로 해결된 뒤 다시 손상된 경우는 정정 대상이 아니며 새 신고로 접수됩니다."));
        assertFalse(ACTIVITY.contains("정책 결정 대기"));
        assertFalse(ACTIVITY.contains("다시 처리(재개)"));
    }

    @Test
    public void s18_40_onlyTheLatestEffectiveEventOfAFullyLoadedCurrentHistoryIsCorrectable() {
        String target = between(
            ACTIVITY,
            "private AdminOperationsApi.DeliveryHistoryItem latestEffectiveDeliveryForCorrection()",
            "private void correctLatestDelivery()"
        );
        assertTrue(target.contains("deliveryHistoryNeedsFirstPage"));
        assertTrue(target.contains("deliveryHistoryNextCursor != null"));
        assertTrue(target.contains("deliveryHistorySnapshotRevision != connectedOperationsLatestDeliveryRevision"));
        assertTrue(target.contains("deliveryHistoryItems.size() != deliveryHistoryTotalCount"));
        assertTrue(target.contains("if (!item.corrected()) return item;"));
    }

    @Test
    public void s18_40_historyMergeUsesTheEffectiveChainAndShowsCorrections() {
        String merge = between(ACTIVITY, "private void applyReportHistoryPage(", "private static void requireLoadedCount(");
        assertTrue(merge.contains("(!item.corrected() && !validDeliveryHistoryTransition(previous, item))"));
        assertTrue(merge.contains("if (!item.corrected()) previous = item;"));
        assertTrue(merge.contains("AdminOperationsHttpClient.requireCorrectionsStepBack(combined)"));
        String text = between(ACTIVITY, "private String deliveryHistoryText()", "private static boolean validDeliveryHistoryTransition(");
        assertTrue(text.contains("정정됨, 현재 상태에서 제외"));
        assertTrue(text.contains("item.correction().memo()"));
        assertTrue(ACTIVITY.contains("잘못 기록한 전달 상태 정정"));
        assertTrue(ACTIVITY.contains("markAccessibilityHeading(deliveryCorrectionHeading)"));
        assertTrue(ACTIVITY.contains(
            "deliveryCorrectionTargetText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)"
        ));
        assertTrue(ACTIVITY.contains("기존 기록은 지우거나 고치지 않습니다."));
    }

    @Test
    public void s18_40_reportChangeClearsCorrectionDraftAndCredentials() {
        String fullReset = between(
            ACTIVITY,
            "private void resetOperationsInputsForDifferentReport()",
            "private void resetPackageBoundDeliveryDraft()"
        );
        assertTrue(fullReset.contains("clear(deliveryCorrectionMemoInput)"));
        assertTrue(fullReset.contains("clear(deliveryCorrectionPasswordInput)"));
        assertTrue(fullReset.contains("clear(deliveryCorrectionTotpInput)"));
        assertTrue(fullReset.contains("deliveryCorrectionReasonInput.setSelection(0)"));
        assertTrue(fullReset.contains("deliveryCorrectionAttempt.reset()"));
    }

    @Test
    public void s18_40_controllerConsumesTheBindingOnceAndZeroesCredentials() {
        String method = between(
            CONTROLLER,
            "public AdminOperationsApi.Result correctDelivery(",
            "public AdminOperationsApi.Result readDeliveries("
        );
        assertTrue(method.contains("AdminHighRiskActionGate.deliveryCorrection(reportId)"));
        assertTrue(method.contains("reauthenticate(password, totpCode, operation, nowEpochMs)"));
        assertTrue(method.contains("consumeHighRiskAuthorization("));
        assertTrue(method.contains("recordDeliveryCorrection("));
        assertTrue(method.contains("decision.requestHeaders()"));
        assertTrue(method.contains("finally"));
        assertTrue(method.contains("Arrays.fill(password, '\\0')"));
        assertTrue(method.contains("Arrays.fill(totpCode, '\\0')"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) throw new AssertionError("missing source boundary: " + start);
        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count += 1;
            offset += needle.length();
        }
        return count;
    }
}
