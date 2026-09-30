package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * w22 review follow-up (S18-1-F1, S18-1-F2, S18-1-F3): Activity wiring guards only. The
 * decisions themselves are fixed by pure JVM tests in AdminDeviceKeySweepW22Test; real
 * AndroidKeyStore and device credential screens still need a device.
 */
public final class AdminDeviceKeyFixW22StaticTest {
    private final String activity = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
    );
    private final String keyStore = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyStore.java"
    );
    private final String authorization = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeviceKeyUseAuthorization.java"
    );
    private final String readme = read("README.md");

    @Test
    public void s18_1_f2_credentialOperationsAreNeverReplayedAndPreflightBeforeSecretsLeave() {
        String credential = between(
            activity,
            "private void runCredentialOperation(",
            "private interface CredentialOperation"
        );
        // One-time secrets are consumed by the first attempt: the retry slot stays empty.
        assertTrue(credential.contains("}, true);"));
        int preflight = credential.indexOf("preflightDeviceKeySignature();");
        int dispatch = credential.indexOf("credentials.takeForDispatch()");
        assertTrue(preflight >= 0 && preflight < dispatch);
        String security = between(
            activity,
            "private void runSecurityOperation(",
            "private void render()"
        );
        assertTrue(security.contains(
            "credentialBound ? null : () ->"
        ));
        String lost = between(activity, "private void reportLostDevice(", "private void requestLocalSessionResume()");
        assertTrue(lost.indexOf("preflightDeviceKeySignature();")
            < lost.indexOf("controller.reportLostDevice("));
        assertTrue(lost.contains("Arrays.fill(password, '\\0')"));
        assertTrue(lost.contains("true\n        );"));
        String result = between(activity, "protected void onActivityResult(", "if (requestCode == CONFIRM_LOCAL_SESSION)");
        assertTrue(result.contains("Decision.REENTER_CREDENTIALS"));
        assertTrue(activity.contains("기기 인증을 마쳤습니다. 비밀번호와 새 인증번호를 다시 입력해 주세요."));
        assertFalse(activity.contains("private Runnable pendingDeviceKeyUseRetry;"));
    }

    @Test
    public void s18_1_f1_replacementKeyIsReachableWithoutASessionAndPromotesOnlyAfterServerLogin() {
        int login = activity.indexOf("content.addView(loginGroup, matchWrap())");
        int replacement = activity.indexOf("content.addView(deviceKeyReplacementGroup, matchWrap())");
        assertTrue(login > 0 && replacement > login);
        assertTrue(activity.contains("교체용 새 기기키 준비"));
        assertTrue(activity.contains("--replace-active"));
        String render = between(activity, "private void render()", "private void hideInteractiveGroups()");
        // Visible while signed out, including when the client could not be built at all.
        assertTrue(render.contains("renderDeviceKeyReplacement(true)"));
        assertTrue(render.contains("renderDeviceKeyReplacement(!accessActive)"));
        String init = between(
            activity,
            "private void initializeDeviceKeyInBackground(",
            "private void completeSecurityInitialization("
        );
        assertTrue(init.contains("AdminDeviceKeyRotation.ActiveKeyUnavailableException"));
        assertTrue(init.contains("requiresLockScreen(error)"));
        String loginFlow = between(activity, "private void login()", "private void refresh()");
        assertTrue(loginFlow.indexOf("controller.login(")
            < loginFlow.indexOf("promoteReplacementKeyAfterServerLogin()"));
        String complete = between(activity, "private void completeRecovery()", "private AdminMutableCredentials takeSecurityCredentials(");
        assertTrue(complete.indexOf("controller.completeRecovery(")
            < complete.indexOf("promoteReplacementKeyAfterServerLogin()"));
        assertTrue(activity.contains("promoteAfterReplacementLogin("));
        assertTrue(activity.contains("signPinned("));
    }

    @Test
    public void s18_1_f3_invalidationIsAttributedToLockScreenRemovalNotBiometricEnrollment() {
        assertFalse(activity.contains("생체 정보가 바뀌어"));
        assertTrue(activity.contains("화면 잠금(PIN·패턴·비밀번호)이 제거되거나"));
        assertFalse(authorization.contains("or biometrics changed"));
        assertTrue(keyStore.contains("no effect on this time-bound key"));
        assertTrue(readme.contains("KeyPermanentlyInvalidatedException"));
        assertFalse(readme.contains("생체 정보 변경으로"));
        assertEquals(-1, readme.indexOf("생체 등록 변경 후"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) throw new AssertionError("missing source boundary: " + start);
        return source.substring(startIndex, endIndex);
    }
}
