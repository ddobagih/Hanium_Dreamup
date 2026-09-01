package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminRawCollectionBoundaryStaticTest {
    @Test
    public void activityKeepsRawReviewInsideOperationalHighRiskBoundary() throws Exception {
        String source = read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
        );
        int gate = source.indexOf("if (BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED)");
        int client = source.indexOf("rawCollectionRepository = new AdminRawCollectionHttpClient");
        int controller = source.indexOf("controller = new AdminSecurityController", client);

        assertTrue(gate >= 0 && client > gate && controller > client);
        assertTrue(source.contains("AdminRawCollectionRepository.ConflictException"));
        assertTrue(source.contains("재제출하지 말고 새 재인증으로 최신 목록"));
        assertTrue(source.contains("controller.decideAdminRawCollectionPurpose"));
        assertTrue(source.contains("controller.recordAdminRawCollectionLegalHold"));
        assertTrue(source.contains("Arrays.fill(ownedPassword, '\\0')"));
        assertTrue(source.contains("Arrays.fill(ownedTotp, '\\0')"));
    }

    @Test
    public void clientAndPanelExposeOnlyBoundedMetadataReview() throws Exception {
        String client = read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminRawCollectionHttpClient.java"
        );
        String panel = read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminRawCollectionPanel.java"
        );

        assertTrue(client.contains("limit=100&state=QUARANTINED"));
        assertTrue(client.contains("MAX_RESPONSE_BYTES = 128 * 1024"));
        assertTrue(client.contains("requireNoStore(response)"));
        assertFalse(client.contains("/objects/"));
        assertFalse(client.contains("download"));
        assertTrue(panel.contains("최신 최대 100건만 표시합니다"));
        assertTrue(panel.contains("setMinHeight(dp(48))"));
        assertTrue(panel.contains("ACCESSIBILITY_LIVE_REGION_POLITE"));
        assertTrue(panel.contains("editableChars(decisionPasswordInput)"));
        assertFalse(panel.contains("decisionPasswordInput.getText().toString()"));
        assertFalse(panel.contains("holdPasswordInput.getText().toString()"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
