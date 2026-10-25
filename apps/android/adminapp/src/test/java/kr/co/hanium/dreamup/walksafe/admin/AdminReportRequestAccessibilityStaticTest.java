package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminPendingRecoveryState;
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
        String initialization = between(
            activity,
            "private void completeSecurityInitialization(",
            "protected void onSaveInstanceState("
        );
        int backgroundLock = initialization.indexOf("controller.suspendForBackground(false)");
        int initialRender = initialization.indexOf("render();", backgroundLock);
        int restoreRecovery = initialization.indexOf(
            "pendingMutationRecovery.restore(this::restorePendingMutationRecovery)"
        );
        int restorePanels = initialization.indexOf("restoreReportPanelState(savedInstanceState)");
        assertTrue(backgroundLock >= 0 && backgroundLock < initialRender
            && initialRender < restoreRecovery && restoreRecovery < restorePanels);

        String capture = between(
            activity,
            "protected void onCreate(",
            "private void initializeDeviceKeyInBackground("
        );
        int captureRecovery = capture.indexOf(
            "pendingMutationRecovery = new AdminPendingRecoveryState<>("
        );
        assertTrue(captureRecovery >= 0
            && captureRecovery < capture.indexOf("initializeDeviceKeyInBackground(savedInstanceState)"));
        String recoveryCopy = between(
            activity,
            "private static Bundle copyMutationRecoveryState(",
            "private void restorePendingMutationRecovery("
        );
        assertTrue(recoveryCopy.contains("new Bundle(source)"));
        assertTrue(recoveryCopy.contains("new ArrayList<>(copy.keySet())"));
        assertTrue(recoveryCopy.contains(
            "if (!AdminPendingRecoveryState.isRecoveryKey(key)) copy.remove(key)"
        ));
        assertFalse(recoveryCopy.contains("getString("));
        assertFalse(recoveryCopy.contains("getBoolean("));
        assertFalse(recoveryCopy.contains("getInt("));

        String save = between(
            activity, "protected void onSaveInstanceState(", "protected void onStop("
        );
        assertTrue(save.contains("Bundle unappliedRecovery = pendingMutationRecovery.snapshot()"));
        assertTrue(save.contains("if (unappliedRecovery != null) outState.putAll(unappliedRecovery)"));
        assertTrue(save.contains("reportWorkflowController != null && unappliedRecovery == null"));
        assertTrue(save.contains("incidentRecovery != null && unappliedRecovery == null"));

        Set<String> expectedRecoveryKeys = new HashSet<>(Arrays.asList(
            "admin_report_status_recovery_id",
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
            "admin_incident_recovery_evidence_digest"
        ));
        String recoveryHandoff = read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminPendingRecoveryState.java"
        );
        String keyPolicy = between(recoveryHandoff, "return switch (key) {", "};");
        Set<String> configuredRecoveryKeys = new HashSet<>();
        Matcher key = Pattern.compile("\"([^\"]+)\"").matcher(keyPolicy);
        while (key.find()) configuredRecoveryKeys.add(key.group(1));
        assertEquals(expectedRecoveryKeys, configuredRecoveryKeys);
        assertTrue(keyPolicy.contains("default -> false"));
        assertTrue(recoveryHandoff.contains("if (key == null) return false"));
        for (String allowed : expectedRecoveryKeys) {
            assertTrue(AdminPendingRecoveryState.isRecoveryKey(allowed));
        }
        for (String excluded : Arrays.asList(
            "password", "totp", "public_response", "internal_note", "original_image",
            "accessToken", "recoveryToken", "admin_report_status_recovery_unknown",
            "admin_incident_recovery_unknown", "admin_report_filter_id"
        )) {
            assertFalse(AdminPendingRecoveryState.isRecoveryKey(excluded));
        }
    }

    @Test
    public void currentSessionActionIsPresentedAsLogoutWithoutChangingServerRevoke() {
        assertTrue(activity.contains("revokeCurrentButton = button(\"로그아웃\")"));
        assertTrue(activity.contains("session.isCurrent() ? \"로그아웃\""));
        assertTrue(activity.contains("controller.revokeSession(sessionId)"));
        assertTrue(activity.contains("controller.beginCurrentSessionRevocation(sessionId)"));
        assertFalse(activity.contains("button(\"현재 기기 세션 폐기\")"));
    }

    @Test
    public void reviewUiSeparatesPublicAndInternalReasons() {
        assertTrue(activity.contains("내부 검토 사유 (사용자 비공개)"));
        assertTrue(activity.contains("사용자에게 보여줄 사유 (REJECTED/DUPLICATE 필수)"));
        assertTrue(activity.contains("사용자 공개 사유"));
        assertTrue(reportPanel.contains("사용자 공개 사유"));
    }

    private static String between(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        assertTrue("missing source boundary: " + start, first >= 0 && last > first);
        return source.substring(first, last);
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
