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

    @Test
    public void deliveryPackageCredentialsStayMutableThroughEveryLayerAndAreZeroized() {
        String panel = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportPanel.java"
        ));
        String workflow = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminReportWorkflowController.java"
        ));
        String boundary = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
        ));
        String securityController = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java"
        ));
        String client = read(Paths.get(
            "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java"
        ));

        assertFalse(panel.contains("highRiskPassword.getText().toString()"));
        assertFalse(panel.contains("highRiskTotp.getText().toString()"));
        assertTrue(panel.contains("editableChars(highRiskPassword)"));
        assertTrue(panel.contains("editableChars(highRiskTotp)"));
        assertTrue(panel.contains("input.getText().getChars("));
        assertTrue(panel.contains("Arrays.fill(password, '\\0')"));
        assertTrue(panel.contains("Arrays.fill(totp, '\\0')"));

        assertTrue(workflow.contains("AdminDeliveryPackage createPackage("));
        assertFalse(workflow.contains("String password"));
        assertFalse(workflow.contains("String totp"));
        assertFalse(workflow.contains("passwordString()"));
        assertFalse(workflow.contains("totpString()"));
        assertFalse(workflow.contains("new String(password)"));
        assertTrue(workflow.contains("password.clone()"));
        assertTrue(workflow.contains("totp.clone()"));
        assertTrue(workflow.contains("Arrays.fill(password, '\\0')"));
        assertTrue(workflow.contains("Arrays.fill(totp, '\\0')"));

        String boundaryPackageLoader = section(
            boundary,
            "public AdminDeliveryPackage createPackage(",
            "public AdminReportModels.Detail refreshDetail"
        );
        assertTrue(boundaryPackageLoader.contains("char[] password"));
        assertTrue(boundaryPackageLoader.contains("char[] totp"));
        assertFalse(boundaryPackageLoader.contains("String password"));
        assertFalse(boundaryPackageLoader.contains("String totp"));

        String securityPackageMethod = section(
            securityController,
            "public synchronized AdminDeliveryPackage createAdminDeliveryPackage(",
            "public synchronized AdminDeliveryPackage.Proof getAdminDeliveryPackageProof("
        );
        assertTrue(securityPackageMethod.contains("char[] password"));
        assertTrue(securityPackageMethod.contains("char[] totpCode"));
        assertFalse(securityPackageMethod.contains("String password"));
        assertFalse(securityPackageMethod.contains("String totp"));
        assertTrue(securityPackageMethod.contains("finally"));
        assertTrue(securityPackageMethod.contains("Arrays.fill(password, '\\0')"));
        assertTrue(securityPackageMethod.contains("Arrays.fill(totpCode, '\\0')"));

        String mutableTransport = section(
            client,
            "public synchronized ReauthenticationResult reauthenticate(\n"
                + "        String accessToken,\n        char[] password,",
            "private static ReauthenticationResult parseReauthentication("
        );
        assertTrue(mutableTransport.contains("char[] totpCode"));
        assertFalse(mutableTransport.contains("String password"));
        assertFalse(mutableTransport.contains("String totp"));
        assertTrue(mutableTransport.contains("SensitiveByteBuffer"));
        assertTrue(mutableTransport.contains("Arrays.fill(body, (byte) 0)"));
        assertTrue(mutableTransport.contains("encoded.destroy()"));
    }

    private static String section(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        if (start < 0 || end < 0 || end <= start) {
            throw new AssertionError("expected source section was not found");
        }
        return source.substring(start, end);
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
