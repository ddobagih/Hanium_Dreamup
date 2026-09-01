package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminExternalCopyDeletionAccessibilityStaticTest {
    private final String activity = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
    );
    private final String panel = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminExternalCopyDeletionPanel.java"
    );

    @Test
    public void panelIsRecordOnlyAccessibleAndDistinguishesDeliveryFromDeletion() {
        assertTrue(panel.contains("앱 밖 삭제 요청 발송 사실 기록"));
        assertTrue(panel.contains("기관 회신 확인 사실 기록"));
        assertTrue(panel.contains("기관 삭제 확인 사실 기록"));
        assertTrue(panel.contains("기관 거절 사실 기록"));
        assertTrue(panel.contains("외부 보관본 삭제 확인 아님"));
        assertTrue(panel.contains("setMinHeight(dp(48))"));
        assertTrue(panel.contains("setMinWidth(dp(48))"));
        assertTrue(panel.contains("setContentDescription"));
        assertTrue(panel.contains("setAccessibilityLiveRegion"));
        assertTrue(panel.contains("View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE"));
        assertFalse(panel.contains("Intent.ACTION_SEND"));
        assertFalse(panel.contains("mailto:"));
        assertFalse(panel.contains("sms:"));
    }

    @Test
    public void rotationStoresOnlyFilterAndSelectionAndInvalidatesLateWork() {
        assertTrue(activity.contains("EXTERNAL_COPY_FILTER_REQUEST_STATE"));
        assertTrue(activity.contains("EXTERNAL_COPY_SELECTED_ID_STATE"));
        assertTrue(activity.contains("externalCopyDeletionController.invalidate()"));
        assertTrue(activity.contains("externalCopyDeletionPanel.clearTransientInputs()"));
        assertTrue(activity.contains("externalCopyDeletionController.clearSessionState()"));
        assertFalse(activity.contains("putString(\"observed_at"));
        assertFalse(activity.contains("putString(\"institution_reference"));
        assertFalse(activity.contains("putString(\"evidence_sha256"));
        assertFalse(activity.contains("putString(\"idempotency_key"));
    }

    @Test
    public void activityUsesDedicatedPanelWithoutHighRiskReauthentication() {
        assertTrue(activity.contains(
            "requiredParallelOperationsGroup.addView(externalCopyDeletionPanel, matchWrap())"
        ));
        String method = between(
            activity,
            "private void recordExternalCopyDeletionFact(",
            "private void executeExternalCopyDeletionRequest("
        );
        assertTrue(method.contains("externalCopyDeletionController.beginRecord("));
        assertFalse(method.contains("password"));
        assertFalse(method.contains("totp"));
    }

    private static String between(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        if (first < 0 || last < 0) throw new AssertionError("source anchors are missing");
        return source.substring(first, last);
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
