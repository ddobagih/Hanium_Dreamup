package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminApi26CompatibilityStaticTest {
    @Test
    public void adminRuntimePathAvoidsJavaNineAndElevenCollectionAndStringApis() throws Exception {
        Path root = Paths.get("src/main/java");
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = read(path);
                assertFalse(path + " uses List.of", source.contains("List.of("));
                assertFalse(path + " uses List.copyOf", source.contains("List.copyOf("));
                assertFalse(path + " uses Set.of", source.contains("Set.of("));
                assertFalse(path + " uses Set.copyOf", source.contains("Set.copyOf("));
                assertFalse(path + " uses Map.of", source.contains("Map.of("));
                assertFalse(path + " uses Map.copyOf", source.contains("Map.copyOf("));
                assertFalse(path + " uses String.isBlank", source.contains(".isBlank("));
                assertFalse(path + " uses String.strip", source.contains(".strip("));
            });
        }
    }

    @Test
    public void reportRequestCredentialsStayMutableThroughTransportAndAreZeroized() {
        String controller = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminReportRequestController.java"
        ));
        String securityController = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java"
        ));
        String client = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java"
        ));
        String panel = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportRequestPanel.java"
        ));
        assertTrue(controller.contains("char[] password"));
        assertFalse(controller.contains("new String(request.password)"));
        assertFalse(panel.contains("highRiskPassword.getText().toString()"));
        assertTrue(panel.contains("editableChars(highRiskPassword)"));
        assertTrue(securityController.contains("char[] password"));
        assertTrue(client.contains("SensitiveByteBuffer"));
        assertTrue(client.contains("Arrays.fill(body, (byte) 0)"));
        assertTrue(client.contains("Arrays.fill(buf, (byte) 0)"));
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
