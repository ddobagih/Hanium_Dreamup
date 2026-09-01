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
    private final String httpTransport = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOkHttpTransport.java"
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
    public void rotationStateIsAllowlistedAndManualDeliveryRecoveryIsExplicitlyAnnounced() {
        assertTrue(activity.contains("REPORT_FILTER_ID_STATE"));
        assertTrue(activity.contains("REPORT_SELECTED_ID_STATE"));
        assertFalse(activity.contains("putString(\"token"));
        assertFalse(panel.contains("ACTION_SEND"));
        assertFalse(panel.contains("mailto:"));
        assertFalse(panel.contains("sms:"));
        assertTrue(activity.contains("Intent.ACTION_CREATE_DOCUMENT"));
        assertTrue(activity.contains("Intent.ACTION_OPEN_DOCUMENT"));
        assertTrue(activity.contains("기존 제출본 ZIP 다시 확인"));
        assertTrue(activity.contains(
            "현재 신고, 전달 버전, 콘텐츠 버전과 해시 다시 확인"
        ));
        assertTrue(activity.contains(
            "앱 재시작이나 재로그인 뒤 접수·처리 결과를 이어서 기록하려면"
        ));
        assertTrue(activity.contains(
            "앱은 ZIP을 내부에 복사하거나 기관으로 전송하지 않습니다."
        ));
        assertTrue(activity.contains(
            "이전에 저장한 v2 제출본 ZIP을 직접 선택하세요. 파일은 수정·삭제·공유하지 않고 서버 증명과 대조합니다."
        ));
        assertTrue(activity.contains(
            "선택한 ZIP의 서버 증명, 전체 해시, v2 매니페스트를 확인해 현재 신고에 연결했습니다. 기관 제출은 자동으로 수행하지 않습니다."
        ));
        assertTrue(activity.contains(
            "기존 제출본 연결과 입력, 선택한 원본 파일은 그대로 유지했습니다."
        ));
        assertTrue(activity.contains("openOutputStream(uri, \"wt\")"));
        assertTrue(activity.contains("DocumentsContract.deleteDocument"));
        assertFalse(activity.contains("FileOutputStream"));
        assertTrue(activity.contains(
            "parsedDeliveryRevision != connectedOperationsLatestDeliveryRevision"
        ));
        assertTrue(activity.contains("input(\"예상 최신 revision\""));
        assertTrue(activity.contains(
            "makeReadOnly(expectedRevisionInput, \"앱이 관리하는 현재 전달 기록 버전\")"
        ));
        assertFalse(activity.contains("Intent.ACTION_SEND"));
        assertTrue(reportClient.contains("new OkHttpTransport()"));
        assertTrue(httpTransport.contains(".cache(null)"));
        assertTrue(panel.contains("highRiskConfirm.setEnabled(!loading)"));
        assertTrue(panel.contains("for (Button button : mutationButtons) button.setEnabled(!loading)"));
        assertTrue(build.contains("release {"));
        assertTrue(build.contains("ADMIN_OPERATIONAL_WORKFLOWS_ENABLED\", \"false\""));
    }

    @Test
    public void sensitiveBoundaryInputsRestorePasswordMaskingAfterSingleLineMode() {
        int inputStart = activity.indexOf("private EditText input(String hint, int inputType");
        int inputEnd = activity.indexOf("private Button button(String label)", inputStart);
        assertTrue(inputStart >= 0);
        assertTrue(inputEnd > inputStart);
        String input = activity.substring(inputStart, inputEnd);

        assertTrue(input.indexOf("view.setSingleLine(true)") >= 0);
        assertTrue(
            input.indexOf("view.setSingleLine(true)")
                < input.indexOf("view.setInputType(inputType)")
        );
        assertTrue(activity.contains("InputType.TYPE_TEXT_VARIATION_PASSWORD"));
        assertTrue(activity.contains("InputType.TYPE_NUMBER_VARIATION_PASSWORD"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
