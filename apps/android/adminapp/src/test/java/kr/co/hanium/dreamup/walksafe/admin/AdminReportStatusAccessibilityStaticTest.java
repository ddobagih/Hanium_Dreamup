package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/** Source wiring checks; these do not replace device TalkBack or layout verification. */
public final class AdminReportStatusAccessibilityStaticTest {
    private final String panel = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportPanel.java"
    );

    @Test
    public void shortAxisHeadingsAndSeparateBodiesHaveMatchingScreenReaderOrder() {
        String[] order = {
            "reviewHeading", "reviewStatus", "internalHeading", "internalStatus",
            "deliveryHeading", "deliveryStatus"
        };
        int previous = -1;
        for (String view : order) {
            int position = panel.indexOf("card.addView(" + view + ", matchWrap())");
            assertTrue(view, position > previous);
            assertTrue(panel.contains(view + ".setId(View.generateViewId())"));
            previous = position;
        }
        int actions = panel.indexOf("for (String nextStatus : detail.allowedNextStatuses())");
        assertTrue(actions > previous);
        assertTrue(panel.contains("text(\"1. 검수 결과\", 18)"));
        assertTrue(panel.contains("text(\"2. 내부 처리 상태\", 18)"));
        assertTrue(panel.contains("text(\"3. 기관 전달 상태\", 18)"));
        for (String heading : new String[]{"reviewHeading", "internalHeading", "deliveryHeading"}) {
            assertTrue(panel.contains("markAccessibilityHeading(" + heading + ")"));
        }
        for (String axis : new String[]{"reviewStatus", "internalStatus", "deliveryStatus"}) {
            assertFalse(panel.contains("markAccessibilityHeading(" + axis + ")"));
            assertFalse(panel.contains(axis + ".setContentDescription("));
        }
        for (int index = 1; index < order.length; index++) {
            assertTrue(panel.contains(order[index] + ".setAccessibilityTraversalAfter("
                + order[index - 1] + ".getId())"));
        }
        assertTrue(panel.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)"));
        assertTrue(panel.contains("AccessibilityNodeInfo.CollectionItemInfo.obtain("));
    }

    @Test
    public void listFilterSummaryAndActionsNameInternalStateWithoutExternalCompletionBadge() {
        assertTrue(panel.contains("신고 내부 처리 상태 필터"));
        assertTrue(panel.contains("내부 처리 기준: 새 신고"));
        assertTrue(panel.contains("내부 처리: 완료"));
        assertTrue(panel.contains("신고 생성:"));
        assertFalse(panel.contains("\"처리 완료\""));
        assertTrue(panel.contains("\"\\n내부 처리 기록 버전: \" + summary.statusVersion()"));
        assertTrue(panel.contains("\"\\n검수 기록 버전: \" + review.revision()"));
        assertTrue(panel.contains("\"\\n전달 기록 버전: \" + delivery.revision()"));
        assertTrue(panel.contains("\"\\n제출본 버전: \" + delivery.packageRevision()"));
        assertFalse(panel.contains("ACTION_SEND"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
