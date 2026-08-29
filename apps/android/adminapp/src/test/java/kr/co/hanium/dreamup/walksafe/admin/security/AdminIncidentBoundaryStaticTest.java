package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminIncidentBoundaryStaticTest {
    @Test
    public void activityKeepsIncidentClientInsideOperationalDebugBoundary() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
        )), StandardCharsets.UTF_8);
        int gate = source.indexOf("if (BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED)");
        int client = source.indexOf("incidentRepository = new AdminIncidentHttpClient");
        int controller = source.indexOf("controller = new AdminSecurityController", client);

        assertTrue(gate >= 0 && client > gate && controller > client);
        assertTrue(source.contains("AdminIncidentRepository.StatusConflictException"));
        assertTrue(source.contains("자동 재제출하지 않고 최신 기록을 조회합니다"));
        assertTrue(source.contains("loadIncidentDetail(detail.summary().incidentId())"));
        assertTrue(source.contains("Arrays.fill(password, '\\0')"));
        assertTrue(source.contains("Arrays.fill(totp, '\\0')"));
    }

    @Test
    public void adminAppHasNoIncidentProducerOrAutomaticControlPath() throws Exception {
        String client = new String(Files.readAllBytes(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminIncidentHttpClient.java"
        )), StandardCharsets.UTF_8);
        assertFalse(client.contains("POST /internal"));
        assertFalse(client.contains("/internal/critical-incidents"));
        assertFalse(client.contains("restart"));
        assertFalse(client.contains("recover("));
        assertTrue(client.contains("GET\", LIST_PATH"));
        assertTrue(client.contains("\"PATCH\", LIST_PATH + \"/\" + safeId + \"/status\""));
    }
}
