package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminReportRequestAccessibilityStaticTest {
    private final String activity = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
    );
    private final String panel = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportRequestPanel.java"
    );
    private final String reportPanel = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportPanel.java"
    );

    @Test
    public void userRequestPanelReflowsAndUsesLabeledFortyEightDpControls() {
        assertTrue(panel.contains("setOrientation(VERTICAL)"));
        assertTrue(panel.contains("setMinHeight(dp(48))"));
        assertTrue(panel.contains("setMinWidth(dp(48))"));
        assertTrue(panel.contains("setContentDescription"));
        assertTrue(panel.contains("setAccessibilityLiveRegion"));
        assertTrue(panel.contains("statusIcon(item.status()) + \" \" + statusLabel(item.status())"));
        assertTrue(panel.contains("사용자에게 보이는 공개 답변"));
        assertTrue(panel.contains("관리자만 보는 내부 메모"));
        assertTrue(panel.contains("사용자에게 공개되지 않는 관리자 내부 메모"));
        assertFalse(panel.contains("publicResponse.setContentDescription"));
        assertFalse(panel.contains("internalNote.setContentDescription"));
    }

    @Test
    public void rotationAndLifecycleStoreOnlyAllowlistedNonSensitiveState() {
        assertTrue(activity.contains("REQUEST_FILTER_REPORT_STATE"));
        assertTrue(activity.contains("REQUEST_FILTER_TYPE_STATE"));
        assertTrue(activity.contains("REQUEST_FILTER_STATUS_STATE"));
        assertTrue(activity.contains("REQUEST_SELECTED_ID_STATE"));
        assertTrue(activity.contains("reportRequestController.invalidate()"));
        assertTrue(activity.contains("reportRequestPanel.clearSensitiveInputs()"));
        assertFalse(activity.contains("putString(\"password"));
        assertFalse(activity.contains("putString(\"totp"));
        assertFalse(activity.contains("putString(\"public_response"));
        assertFalse(panel.contains("ACTION_SEND"));
        assertFalse(panel.contains("mailto:"));
        assertFalse(panel.contains("sms:"));
        assertTrue(activity.indexOf(
            "render();\n        restorePendingMutationRecovery(savedInstanceState);"
        ) > 0);
        assertTrue(activity.indexOf(
            "restorePendingMutationRecovery(savedInstanceState);\n        restoreReportPanelState(savedInstanceState);"
        ) > 0);
    }

    @Test
    public void currentSessionActionIsPresentedAsLogoutWithoutChangingServerRevoke() {
        assertTrue(activity.contains("revokeCurrentButton = button(\"로그아웃\")"));
        assertTrue(activity.contains("session.isCurrent() ? \"로그아웃\""));
        assertTrue(activity.contains("controller.revokeSession(sessionId)"));
        assertTrue(activity.contains("currentSession ? \"로그아웃했습니다.\""));
        assertFalse(activity.contains("button(\"현재 기기 세션 폐기\")"));
    }

    @Test
    public void reviewUiSeparatesPublicAndInternalReasons() {
        assertTrue(activity.contains("내부 검토 사유 (사용자 비공개)"));
        assertTrue(activity.contains("사용자에게 보여줄 사유 (REJECTED/DUPLICATE 필수)"));
        assertTrue(activity.contains("사용자 공개 사유"));
        assertTrue(reportPanel.contains("사용자 공개 사유"));
    }

    private static String read(String path) {
        try {
            return new String(
                Files.readAllBytes(Paths.get(path)),
                StandardCharsets.UTF_8
            );
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
