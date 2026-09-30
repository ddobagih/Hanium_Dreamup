package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Source wiring guards only; not a substitute for Android lifecycle/device tests. */
public final class AdminUiThreadBoundaryStaticTest {
    private final String activity = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
    );

    @Test
    public void keyPreparationRunsOnWorkerAndOverlappingActivitiesSerializeCreation() {
        String create = section("protected void onCreate(", "private void initializeDeviceKeyInBackground(");
        assertFalse(create.contains("ensureKey()"));
        int recoveryCapture = create.indexOf("pendingMutationRecovery = new AdminPendingRecoveryState<>(");
        assertTrue(recoveryCapture >= 0
            && recoveryCapture < create.indexOf("initializeDeviceKeyInBackground(savedInstanceState)"));
        assertTrue(create.indexOf("setContentView(buildContent())")
            < create.indexOf("initializeDeviceKeyInBackground(savedInstanceState)"));
        String prepare = section("private void initializeDeviceKeyInBackground(",
            "private void completeSecurityInitialization(");
        assertTrue(prepare.indexOf("networkExecutor.execute")
            < prepare.indexOf("keyStore.ensureKey()"));
        assertTrue(prepare.indexOf("synchronized (AdminDeviceKeyStore.class)")
            < prepare.indexOf("keyStore.ensureKey()"));
        assertTrue(prepare.indexOf("keyStore.ensureKey()") < prepare.indexOf("runOnUiThread"));
    }

    @Test
    public void initializationRejectsDestroyedActivityAndKeepsStoppedActivityLocked() {
        String complete = section("private void completeSecurityInitialization(", "protected void onSaveInstanceState(");
        assertTrue(complete.indexOf("if (isDestroyed() || isFinishing()) return;")
            < complete.indexOf("deviceKeyDescriptor = descriptor"));
        assertTrue(complete.contains("if (!activityStarted && controller != null) controller.suspendForBackground(false)"));
        int recoveryRestore = complete.indexOf(
            "pendingMutationRecovery.restore(this::restorePendingMutationRecovery)"
        );
        assertTrue(recoveryRestore > complete.indexOf("controller.suspendForBackground(false)"));
        assertTrue(complete.contains("controller = null"));
        assertTrue(complete.contains("operationsClientConfigured = false"));
        String save = section("protected void onSaveInstanceState(", "protected void onStop(");
        assertTrue(save.contains("Bundle unappliedRecovery = pendingMutationRecovery.snapshot()"));
        assertTrue(save.contains("if (unappliedRecovery != null) outState.putAll(unappliedRecovery)"));
        assertTrue(save.contains("reportWorkflowController != null && unappliedRecovery == null"));
        assertTrue(save.contains("incidentRecovery != null && unappliedRecovery == null"));
        String copy = section("private static Bundle copyMutationRecoveryState(",
            "private void restorePendingMutationRecovery(");
        assertTrue(copy.contains("new Bundle(source)"));
        assertTrue(copy.contains("AdminPendingRecoveryState.isRecoveryKey(key)"));
        assertTrue(copy.contains("copy.remove(key)"));
        assertFalse(copy.contains("putString("));
        assertFalse(copy.contains("putBoolean("));
        assertFalse(copy.contains("putInt("));
    }

    @Test
    public void keyFailureAndClientInitializationFailureHaveSeparateSafeMessages() {
        assertTrue(activity.contains("기기 키 초기화 실패"));
        assertTrue(activity.contains("관리자 클라이언트·화면 상태 초기화에 실패했습니다."));
        assertFalse(activity.contains("deviceKeyFailure = \"키 사용 불가\""));
        String prepare = section("private void initializeDeviceKeyInBackground(",
            "protected void onSaveInstanceState(");
        assertFalse(prepare.contains("error.getMessage()"));
    }

    @Test
    public void imageDecodePrecedesUiDispatchAndRetainsBoundedMutablePixelCleanup() {
        String load = section("private void loadOriginalEvidence(", "private static Bitmap decodeOriginalEvidence(");
        int worker = load.indexOf("networkExecutor.submit");
        int decode = load.indexOf("decodeOriginalEvidence(loaded)");
        int dispatch = load.indexOf("runOnUiThread", worker);
        assertTrue(worker >= 0 && worker < decode && decode < dispatch);
        String decoder = section("private static Bitmap decodeOriginalEvidence(",
            "private void applyLoadedOriginalEvidence(");
        assertTrue(decoder.contains("bounds.inJustDecodeBounds = true"));
        assertTrue(decoder.contains("20_000_000L"));
        assertTrue(decoder.contains("options.inMutable = true"));
        assertTrue(decoder.contains("Thread.currentThread().isInterrupted()"));
        assertTrue(decoder.contains("finally {"));
        assertTrue(decoder.contains("Arrays.fill(displayBytes, (byte) 0)"));
        assertTrue(decoder.contains("if (decoded != null) destroyBitmap(decoded)"));
        assertTrue(load.contains("loaded.close();"));
    }

    @Test
    public void uiOnlyAdoptsCurrentForegroundEvidenceAndDestroysStalePixels() {
        String apply = section("private void applyLoadedOriginalEvidence(",
            "private void scheduleOriginalEvidenceExpiry(");
        assertFalse(apply.contains("BitmapFactory"));
        assertFalse(apply.contains("copyImageBytes"));
        assertTrue(apply.contains("!activityStarted"));
        assertTrue(apply.contains("controller.isLocallyLocked()"));
        assertTrue(apply.contains("sessionGeneration != operationsSessionGeneration"));
        assertTrue(apply.contains("!sessionId.equals(boundOperationsSessionId)"));
        assertTrue(apply.contains("contentRevision != connectedOperationsContentRevision"));
        assertTrue(apply.contains("!loaded.matches("));
        assertTrue(apply.indexOf("destroyBitmap(decoded)") < apply.indexOf("originalEvidence = loaded"));
        assertTrue(apply.indexOf("!loaded.matches(") < apply.indexOf("originalEvidence = loaded"));
        assertTrue(activity.contains("awaitingSafResult || awaitingSafReconnectResult || awaitingLocalUnlock"));
    }

    @Test
    public void totpReplayAndManualWorkflowStatesRemainExplicitWithoutClaimingProof() {
        assertTrue(activity.contains("로그인이나 직전 재인증에 사용한 TOTP는 다시 쓰지 말고"));
        assertTrue(activity.contains("검토 결정, 신고 처리 상태, 수동 기관 전달 상태는 별개입니다."));
        assertTrue(activity.contains("승인이 자동 전달이나 기관 접수를 뜻하지 않습니다."));
        assertTrue(activity.contains("실제 백업 복원 여부는 별도 확인이 필요합니다."));
    }

    private String section(String start, String end) {
        int first = activity.indexOf(start);
        int last = activity.indexOf(end, first + start.length());
        assertTrue("missing source section: " + start, first >= 0 && last > first);
        return activity.substring(first, last);
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError("failed to read source boundary", error);
        }
    }
}
