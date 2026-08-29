package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminIncidentPanelAccessibilityStaticTest {
    @Test
    public void panelUsesTextAndIconsWithAccessibleTargetsAndNoControlClaims() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminIncidentPanel.java"
        )), StandardCharsets.UTF_8);

        assertTrue(source.contains("CRITICAL · "));
        assertTrue(source.contains("statusIcon(item.status())"));
        assertTrue(source.contains("statusLabel(item.status())"));
        assertTrue(source.contains("setMinHeight(dp(48))"));
        assertTrue(source.contains("setContentDescription"));
        assertTrue(source.contains("ACCESSIBILITY_LIVE_REGION_POLITE"));
        assertTrue(source.contains("자동 복구나 자동 제어를 수행하지 않습니다"));
        assertTrue(source.contains("개인정보, 정확한 위치, 음성·영상 또는 원로그를 입력하지 마세요"));
        assertFalse(source.contains("자동 재제출"));
        assertFalse(source.contains("사고 생성"));
    }

    @Test
    public void passwordAndTotpAreClearedBeforeDispatch() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminIncidentPanel.java"
        )), StandardCharsets.UTF_8);
        int clear = source.indexOf("clearSensitiveInputs();", source.indexOf("private void submit"));
        int dispatch = source.indexOf("listener.onUpdateStatus", source.indexOf("private void submit"));
        assertTrue(clear > 0);
        assertTrue(dispatch > clear);
    }
}
