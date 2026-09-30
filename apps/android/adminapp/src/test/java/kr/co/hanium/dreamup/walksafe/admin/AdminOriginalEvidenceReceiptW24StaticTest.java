package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * S20-54 (originals/20.txt:457-460): token consumption is not receipt. A lost image after the
 * one-time token was used is shown as "grant already used -> new grant -> view again", and the
 * audit meaning is stated as server return preparation.
 */
public final class AdminOriginalEvidenceReceiptW24StaticTest {
    private static final String ACTIVITY = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
    );
    private static final String CLIENT = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClient.java"
    );
    private static final String README = read("README.md");

    @Test
    public void spentGrantFailureHasItsOwnNewGrantGuidance() {
        String load = between(ACTIVITY, "private void loadOriginalEvidence()", "private static Bitmap decodeOriginalEvidence(");
        int special = load.indexOf("error instanceof AdminOperationsApi.OriginalEvidenceNotReceivedException");
        int generic = load.indexOf("원본 증거를 불러오지 못했습니다. 서버 상태와 재인증 정보를 확인해 주세요.");
        assertTrue(special > 0);
        assertTrue("the spent-grant case is decided before the generic failure", special < generic);
        assertTrue(load.contains("clearOriginalEvidence(ORIGINAL_EVIDENCE_NOT_RECEIVED_STATUS)"));
        assertTrue(load.contains("resultText.setText(ORIGINAL_EVIDENCE_NEW_GRANT_NEXT_ACTION)"));
        // w24 misc-F7: the status does not claim the server consumed the grant (it may not have).
        assertTrue(ACTIVITY.contains("이 기기에서 원본을 받아 표시하지 못했습니다. 기존 원본 열람 권한은 더는 쓸 수 없습니다."));
        assertFalse(ACTIVITY.contains("이미 사용되었고"));
        assertTrue(ACTIVITY.contains("'정확한 위치·원본 사진 일회 열람'을 눌러 "));
        assertTrue(ACTIVITY.contains("새 열람 권한을 받으세요. 자동 재시도하지 않습니다."));
        // The status line is the assertive live region, so TalkBack reads the same sentence.
        assertTrue(ACTIVITY.contains(
            "originalEvidenceStatusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE)"
        ));
    }

    @Test
    public void decodingFailureAfterTheGrantTakesTheSameNewGrantPath() {
        // w24 misc-F7: verified bytes that cannot be decoded were paid for with the grant too.
        String load = between(ACTIVITY, "private void loadOriginalEvidence()", "private static Bitmap decodeOriginalEvidence(");
        int decode = load.indexOf("decoded = decodeOriginalEvidence(loaded)");
        int decodeFailure = load.indexOf("catch (RuntimeException error)", decode);
        int cleanup = load.indexOf("loaded.close();", decodeFailure);
        int wrap = load.indexOf("throw new AdminOperationsApi.OriginalEvidenceNotReceivedException(error);", cleanup);
        int dispatch = load.indexOf("runOnUiThread", wrap);
        int special = load.indexOf("error instanceof AdminOperationsApi.OriginalEvidenceNotReceivedException", dispatch);
        assertTrue(decode > 0 && decodeFailure > decode && cleanup > decodeFailure && wrap > cleanup);
        assertTrue("the wrapped decode failure reaches the spent-grant branch", dispatch > wrap && special > dispatch);
    }
    @Test
    public void loadButtonTalkBackNameStartsWithItsVisibleName() {
        // w24 misc-F8: the next-action notice names the visible button; TalkBack must hear it first.
        String visible = "정확한 위치·원본 사진 일회 열람";
        String create = between(ACTIVITY, "originalEvidenceLoadButton = button(", "originalEvidenceLoadButton.setOnClickListener(");
        assertTrue(create.startsWith("originalEvidenceLoadButton = button(\"" + visible + "\");"));
        String description = between(create, "originalEvidenceLoadButton.setContentDescription(", ");");
        String literal = description.substring(description.indexOf('"') + 1, description.lastIndexOf('"'));
        assertTrue(literal, literal.startsWith(visible + ". "));
        assertTrue(ACTIVITY.contains("'" + visible + "'을 눌러 "));
    }
    @Test
    public void auditMeaningIsServerReturnPreparationNotReceipt() {
        assertTrue(ACTIVITY.contains("'서버 반환 준비' 시점이며, "));
        assertTrue(ACTIVITY.contains("reportOperationsFormGroup.addView(text(ORIGINAL_EVIDENCE_AUDIT_MEANING, 15), matchWrap());"));
        assertTrue(README.contains("**서버 반환 준비**"));
        assertTrue(README.contains("`OriginalEvidenceNotReceivedException`"));
    }

    @Test
    public void onlyFailuresAfterTheTokenIsTakenAreSpentGrantFailures() {
        String load = between(CLIENT, "public AdminOriginalEvidence loadOriginalEvidence(", "private Result executeProtected(");
        int consume = load.indexOf("grant.consumeAccessToken(");
        int wrap = load.indexOf("throw new AdminOperationsApi.OriginalEvidenceNotReceivedException(error);");
        int grantRequest = load.indexOf("\"original evidence grant request\"");
        assertTrue(consume > 0 && wrap > consume && grantRequest > 0 && grantRequest < consume);
        assertEquals(1, occurrences(load, "OriginalEvidenceNotReceivedException"));
        assertEquals(1, occurrences(load, "grant.consumeAccessToken("));
        assertFalse(load.contains("reactivate"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) throw new AssertionError("missing source boundary: " + start);
        return source.substring(startIndex, endIndex);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0; index = source.indexOf(needle, index + 1)) {
            count += 1;
        }
        return count;
    }
}
