package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public final class AdminSecurityBoundaryStaticTest {
    private final String build = read("build.gradle.kts");
    private final String manifest = read("src/main/AndroidManifest.xml");
    private final String debugManifest = read("src/debug/AndroidManifest.xml");
    private final String debugNetwork = read("src/debug/res/xml/admin_debug_network_security_config.xml");
    private final String activity = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java");
    private final String controller = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java"
    );
    private final String deviceKeyStore = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyStore.java"
    );
    private final String operationsClient = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClient.java"
    );
    private final String securityClient = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java"
    );
    private final String readme = read("README.md");

    @Test
    public void onlyInternetPermissionIsDeclaredAndBackupsAreClosed() {
        Matcher permissions = Pattern.compile("<uses-permission[^>]+android:name=\"([^\"]+)\"").matcher(manifest);
        int count = 0;
        while (permissions.find()) {
            count += 1;
            assertEquals("android.permission.INTERNET", permissions.group(1));
        }
        assertEquals(1, count);
        assertTrue(manifest.contains("android:allowBackup=\"false\""));
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/admin_data_extraction_rules\""));
    }

    @Test
    public void releaseAndOperationalBuildBoundariesRemainFailClosed() {
        assertTrue(build.contains("ADMIN_AUTHENTICATION_MODE\", \"\\\"PASSWORD_TOTP\\\""));
        assertTrue(build.contains("ADMIN_SECURITY_WORKFLOWS_ENABLED\", \"true\""));
        assertTrue(build.contains("ADMIN_OPERATIONAL_WORKFLOWS_ENABLED\", \"false\""));
        assertTrue(build.contains("WALKSAFE_ADMIN_OPERATIONAL_DEBUG"));
        assertTrue(build.contains("operationalDebugEnabled.toString()"));
        assertTrue(build.contains("Release builds require WALKSAFE_ADMIN_API_ORIGIN"));
        assertTrue(build.contains("http://127.0.0.1:8000"));
        assertFalse(build.contains("ADMIN_WORKFLOWS_ENABLED"));
    }

    @Test
    public void debugCleartextIsPlatformRestrictedToLoopback() {
        assertTrue(debugManifest.contains("@xml/admin_debug_network_security_config"));
        assertFalse(debugManifest.contains("usesCleartextTraffic=\"true\""));
        assertTrue(debugNetwork.contains("<base-config cleartextTrafficPermitted=\"false\""));
        assertTrue(debugNetwork.contains(">127.0.0.1</domain>"));
        assertTrue(debugNetwork.contains(">localhost</domain>"));
    }

    @Test
    public void secretsAreScreenProtectedUnsavedAndHeldOnlyByTheController() {
        assertTrue(activity.contains("WindowManager.LayoutParams.FLAG_SECURE"));
        assertTrue(activity.contains("view.setSaveEnabled(false)"));
        assertTrue(activity.contains("clearSensitiveInputs()"));
        assertFalse(activity.contains("onSaveInstanceState"));
        assertFalse(activity.contains("android.util.Log"));
        assertFalse(activity.contains("FileOutputStream"));
        assertTrue(controller.contains("private String accessToken"));
        assertTrue(controller.contains("private String recoveryToken"));
        assertFalse(controller.contains("SharedPreferences"));
        assertFalse(controller.contains("FileOutputStream"));
        assertFalse(controller.contains("private String custodyReference"));
    }

    @Test
    public void recoveryCustodyAndLostDeviceControlsAreExplicitAndKeepNoRecoveryMaterial() {
        assertTrue(activity.contains("custodyConfirmationInput.isChecked()"));
        assertTrue(activity.contains("복구자료 외부 보관 확인 기록"));
        assertTrue(activity.contains("서버 키·앱 서명 키·관리자 복구자료"));
        assertTrue(activity.contains("같은 저장공간이나 계정에 키를 함께 두지 않았음"));
        assertTrue(activity.contains("앱은 복구자료 원문을 저장하거나 전송하지 않습니다"));
        assertTrue(controller.contains("new byte[32]"));
        assertTrue(controller.contains("RecoveryStorageLocation.OFF_PHONE"));
        assertTrue(securityClient.contains("/admin/security/recovery-custody/attest"));
        assertTrue(securityClient.contains("/report-lost"));
        assertTrue(securityClient.contains("current device cannot be reported lost"));
        assertTrue(securityClient.contains("Set.of(\"sessions\", \"devices\")"));
        assertTrue(activity.contains("활성 장치 키만 남은 기기"));
        assertFalse(activity.contains("putString(\"recovery"));
        assertTrue(readme.contains("서버 키, 앱 서명 키, 관리자 복구자료"));
        assertTrue(readme.contains("separate_encrypted_backup_confirmed=true"));
        assertTrue(readme.contains("실제 운영 custody 보관"));
        assertTrue(readme.contains("분실 복구 훈련 증거가 아니다"));
        assertTrue(readme.contains("`NOT_RUN`"));
        assertTrue(readme.contains("외부 증거 없이 완료됐다고 주장하지 않는다"));
    }

    @Test
    public void deviceProofKeyIsNonexportableP256WithPersistedMarkerAndVersion() {
        assertTrue(deviceKeyStore.contains("AndroidKeyStore"));
        assertTrue(deviceKeyStore.contains("secp256r1"));
        assertTrue(deviceKeyStore.contains("KeyProperties.PURPOSE_SIGN"));
        assertTrue(deviceKeyStore.contains("KeyProperties.DIGEST_SHA256"));
        assertTrue(deviceKeyStore.contains("SHA256withECDSA"));
        assertTrue(deviceKeyStore.contains("ACTIVE_VERSION"));
        assertTrue(deviceKeyStore.contains("ACTIVE_MARKER"));
        assertTrue(deviceKeyStore.contains("public_key_spki_base64url"));
        assertTrue(activity.contains("setTextIsSelectable(true)"));
        assertTrue(activity.contains("registrationDescriptor(deviceId)"));
        assertTrue(readme.contains("--expected-key-marker '<descriptor key_marker>'"));
        assertTrue(readme.contains("DB write 전에 검증"));
        assertFalse(deviceKeyStore.contains("getPrivate().getEncoded"));
        assertFalse(deviceKeyStore.contains("PrivateKey.getEncoded"));
    }

    @Test
    public void operationsUseOnlyInternalBackendRecordsAndNoExternalDispatchPrimitive() {
        assertTrue(operationsClient.contains("/admin/security/device-proof/challenges"));
        assertTrue(operationsClient.contains("/review-decisions"));
        assertTrue(operationsClient.contains("/deliveries"));
        assertTrue(operationsClient.contains("records manual delivery facts"));
        assertFalse(operationsClient.contains("ACTION_SEND"));
        assertFalse(operationsClient.contains("mailto:"));
        assertFalse(operationsClient.contains("sms:"));
        assertFalse(operationsClient.contains("institutionUrl"));
        assertFalse(activity.contains("ACTION_SEND"));
        assertTrue(operationsClient.contains("\"recorded_at\""));
        assertTrue(activity.contains("item.recordedAt()"));
        assertTrue(activity.contains("ACKNOWLEDGED/RESOLVED 필수"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError("failed to read " + path, error);
        }
    }
}
