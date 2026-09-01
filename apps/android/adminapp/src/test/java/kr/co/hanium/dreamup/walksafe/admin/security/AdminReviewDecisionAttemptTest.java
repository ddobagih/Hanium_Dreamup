package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdminReviewDecisionAttemptTest {
    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String GRANT_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    public void exactUserRetryReusesDecisionIdAndApprovedGrantAfterResponseLoss() {
        AdminReviewDecisionAttempt attempt = new AdminReviewDecisionAttempt();
        AdminReportDecision first = attempt.prepare(
            REPORT_ID,
            AdminReportDecision.Decision.APPROVED,
            "현장 정보와 사진을 확인함",
            null,
            null,
            true,
            true,
            true,
            3,
            GRANT_ID
        );

        AdminReportDecision retry = attempt.prepare(
            REPORT_ID,
            AdminReportDecision.Decision.APPROVED,
            "  현장 정보와 사진을 확인함  ",
            null,
            null,
            true,
            true,
            true,
            3,
            null
        );

        assertSame(first, retry);
        assertEquals(first.decisionId(), retry.decisionId());
        assertEquals(GRANT_ID, retry.evidenceGrantId());
        assertTrue(attempt.isPending());
    }

    @Test
    public void exactUserRetryKeepsOriginalContentRevisionAfterDetailAdvances() {
        AdminReviewDecisionAttempt attempt = new AdminReviewDecisionAttempt();
        AdminReportDecision first = rejected(attempt, "내부 검토 사유");

        AdminReportDecision retry = attempt.prepare(
            REPORT_ID,
            AdminReportDecision.Decision.REJECTED,
            "내부 검토 사유",
            "사용자에게 공개할 처리 사유",
            null,
            true,
            false,
            true,
            4,
            null
        );

        assertSame(first, retry);
        assertEquals(3, retry.contentRevision());
    }

    @Test
    public void changedIntentCannotMintAnotherIdUntilExplicitOutcomeClearsAttempt() {
        AdminReviewDecisionAttempt attempt = new AdminReviewDecisionAttempt();
        AdminReportDecision first = rejected(attempt, "내부 검토 사유");

        assertThrows(IllegalStateException.class, () -> rejected(attempt, "다른 내부 검토 사유"));
        assertEquals(first.decisionId(), attempt.decisionId());

        attempt.clear();
        AdminReportDecision next = rejected(attempt, "다른 내부 검토 사유");
        assertNotEquals(first.decisionId(), next.decisionId());
    }

    private static AdminReportDecision rejected(
        AdminReviewDecisionAttempt attempt,
        String reason
    ) {
        return attempt.prepare(
            REPORT_ID,
            AdminReportDecision.Decision.REJECTED,
            reason,
            "사용자에게 공개할 처리 사유",
            null,
            true,
            false,
            true,
            3,
            null
        );
    }
}
