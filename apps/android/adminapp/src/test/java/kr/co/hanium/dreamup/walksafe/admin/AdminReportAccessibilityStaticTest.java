package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminReportAccessibilityStaticTest {
    private final String activity = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java");
    private final String panel = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportPanel.java");
    private final String auditPanel = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminAuditPanel.java");
    private final String reportClient = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminReportHttpClient.java"
    );
    private final String build = read("build.gradle.kts");

    @Test
    public void panelReflowsVerticallyInsideScreenScrollAndHasNonColorStatusLabels() {
        assertTrue(activity.contains("new ScrollView(this)"));
        assertTrue(panel.contains("setOrientation(VERTICAL)"));
        assertTrue(panel.contains("setMinHeight(dp(48))"));
        assertTrue(panel.contains("setMinWidth(dp(48))"));
        assertTrue(panel.contains("setAccessibilityLiveRegion"));
        assertTrue(panel.contains("statusIcon(item.status()) + \" \" + statusLabel(item.status())"));
        assertTrue(panel.contains("setContentDescription"));
        assertTrue(auditPanel.contains("setMinHeight(dp(48))"));
        assertTrue(auditPanel.contains("typeIcon(item.eventType())"));
        assertTrue(panel.contains("신고 생성 시작시각 필터"));
        assertTrue(panel.contains("신고 생성 종료시각 필터"));
        assertTrue(auditPanel.contains("감사 사건 유형 필터"));
        assertTrue(auditPanel.contains("감사 기록 관리자 ID 필터"));
    }

    @Test
    public void rotationStateIsAllowlistedAndExternalDispatchRemainsAbsent() {
        assertTrue(activity.contains("REPORT_FILTER_ID_STATE"));
        assertTrue(activity.contains("REPORT_SELECTED_ID_STATE"));
        assertFalse(activity.contains("putString(\"token"));
        assertFalse(panel.contains("ACTION_SEND"));
        assertFalse(panel.contains("mailto:"));
        assertFalse(panel.contains("sms:"));
        assertTrue(activity.contains("Intent.ACTION_CREATE_DOCUMENT"));
        assertTrue(activity.contains("openOutputStream(uri, \"wt\")"));
        assertTrue(activity.contains("DocumentsContract.deleteDocument"));
        assertFalse(activity.contains("FileOutputStream"));
        assertTrue(activity.contains("verifiedDeliveryPackage.matchesDelivery(reportId, parsedPackageRevision)"));
        assertTrue(reportClient.contains("connection.setUseCaches(false)"));
        assertTrue(panel.contains("highRiskConfirm.setEnabled(!loading)"));
        assertTrue(panel.contains("for (Button button : mutationButtons) button.setEnabled(!loading)"));
        assertTrue(build.contains("release {"));
        assertTrue(build.contains("ADMIN_OPERATIONAL_WORKFLOWS_ENABLED\", \"false\""));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
