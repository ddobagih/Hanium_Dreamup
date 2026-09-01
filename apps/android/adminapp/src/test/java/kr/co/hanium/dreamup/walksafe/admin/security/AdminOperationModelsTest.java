package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public final class AdminOperationModelsTest {
    @Test
    public void reviewBodyIsExactNineWithContentRevisionAndEvidenceGrant() throws Exception {
        AdminReportDecision approved = new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED,
            "현장 정보와 사진을 확인함",
            null,
            null,
            true,
            true,
            true,
            3,
            EVIDENCE_GRANT_ID
        );
        JSONObject body = new JSONObject(new String(approved.requestBody(REPORT_ID), StandardCharsets.UTF_8));

        assertEquals(Set.of(
            "decision", "reason", "user_visible_reason", "duplicate_of_report_id",
            "location_reviewed", "photo_reviewed", "privacy_reviewed", "content_revision",
            "evidence_grant_id"
        ), keys(body));
        assertTrue(body.has("user_visible_reason"));
        assertTrue(body.isNull("user_visible_reason"));
        assertTrue(body.has("duplicate_of_report_id"));
        assertTrue(body.isNull("duplicate_of_report_id"));
        assertEquals("APPROVED", body.getString("decision"));
        assertEquals(3, body.getInt("content_revision"));
        assertEquals(EVIDENCE_GRANT_ID, body.getString("evidence_grant_id"));

        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED, "reason", null, null, true, false, true, 0,
            EVIDENCE_GRANT_ID
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED, "reason", null, OTHER_REPORT_ID, true, true, true, 0,
            EVIDENCE_GRANT_ID
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED,
            "reason",
            "승인에는 공개 거절 사유가 없어야 함",
            null,
            true,
            true,
            true,
            0,
            EVIDENCE_GRANT_ID
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.REJECTED, "reason", "공개 사유", null, true, true, true, -1, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED, "reason", null, null, true, true, true, 0, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.REJECTED, "reason", "공개 사유", null, true, true, true, 0,
            EVIDENCE_GRANT_ID
        ));
    }

    @Test
    public void rejectedAndDuplicateKeepExactFlagsWithoutRequiringApprovedCompleteness() throws Exception {
        AdminReportDecision rejected = new AdminReportDecision(
            AdminReportDecision.Decision.REJECTED,
            "내부 사진 확인 불가",
            "사진을 확인할 수 없어 요청을 처리하지 못했습니다.",
            null,
            true,
            false,
            true,
            0,
            null
        );
        JSONObject rejectedBody = new JSONObject(new String(
            rejected.requestBody(REPORT_ID), StandardCharsets.UTF_8
        ));
        assertTrue(!rejectedBody.getBoolean("photo_reviewed"));

        AdminReportDecision duplicate = new AdminReportDecision(
            AdminReportDecision.Decision.DUPLICATE,
            "내부 위치 중복",
            "같은 위치의 기존 신고와 중복됩니다.",
            OTHER_REPORT_ID,
            false,
            true,
            false,
            0,
            null
        );
        JSONObject duplicateBody = new JSONObject(new String(
            duplicate.requestBody(REPORT_ID), StandardCharsets.UTF_8
        ));
        assertEquals(OTHER_REPORT_ID, duplicateBody.getString("duplicate_of_report_id"));
    }

    @Test
    public void duplicateRequiresAnotherCanonicalReportUuid() {
        AdminReportDecision duplicate = new AdminReportDecision(
            AdminReportDecision.Decision.DUPLICATE,
            "동일 위치와 사진",
            "기존 신고와 중복됩니다.",
            OTHER_REPORT_ID,
            true,
            true,
            true,
            0,
            null
        );
        assertThrows(IllegalArgumentException.class, () -> duplicate.requestBody(OTHER_REPORT_ID));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.DUPLICATE, "reason", "공개 사유", null, true, true, true, 0, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.REJECTED, "reason", " ", null, true, true, true, 0, null
        ));
    }

    @Test
    public void manualDeliveryBodyIsExactTenAndNullableEvidenceRemainsPhysical() throws Exception {
        AdminInstitutionDelivery delivery = new AdminInstitutionDelivery(
            "서울시 도로관리과",
            "전화",
            "당직 담당자",
            AdminInstitutionDelivery.Status.SUBMITTED,
            null,
            "운영자가 전화 접수 후 기록",
            null,
            "2026-08-09T01:02:03Z",
            1L,
            0L,
            IDEMPOTENCY_KEY
        );
        JSONObject body = new JSONObject(new String(delivery.requestBody(), StandardCharsets.UTF_8));

        assertEquals(Set.of(
            "institution", "channel", "recipient", "status", "external_receipt_id",
            "reason", "evidence_sha256", "observed_at", "package_revision", "expected_revision", "idempotency_key"
        ), keys(body));
        assertTrue(body.has("external_receipt_id"));
        assertTrue(body.isNull("external_receipt_id"));
        assertTrue(body.has("evidence_sha256"));
        assertTrue(body.isNull("evidence_sha256"));
        assertEquals("SUBMITTED", body.getString("status"));
        assertEquals(1L, body.getLong("package_revision"));
        assertEquals(0L, body.getLong("expected_revision"));
    }

    @Test
    public void manualDeliveryRejectsBlankOptionalsInvalidHashesTimeRevisionAndUuid() {
        assertThrows(IllegalArgumentException.class, () -> delivery(" ", null, "2026-08-09T01:02:03Z", 0, IDEMPOTENCY_KEY));
        assertThrows(IllegalArgumentException.class, () -> delivery(null, "A".repeat(64), "2026-08-09T01:02:03Z", 0, IDEMPOTENCY_KEY));
        assertThrows(IllegalArgumentException.class, () -> delivery(null, null, "2026-08-09T10:02:03+09:00", 0, IDEMPOTENCY_KEY));
        assertThrows(IllegalArgumentException.class, () -> delivery(null, null, "2026-08-09T01:02:03Z", -1, IDEMPOTENCY_KEY));
        assertThrows(IllegalArgumentException.class, () -> delivery(null, null, "2026-08-09T01:02:03Z", 0, "not-uuid"));
        assertThrows(IllegalArgumentException.class, () -> new AdminInstitutionDelivery(
            "institution", "phone", "recipient", AdminInstitutionDelivery.Status.ACKNOWLEDGED,
            null, "reason", null, "2026-08-09T01:02:03Z", 1, 1, IDEMPOTENCY_KEY
        ));
    }

    private static AdminInstitutionDelivery delivery(
        String externalReceipt,
        String evidence,
        String observedAt,
        long revision,
        String idempotencyKey
    ) {
        return new AdminInstitutionDelivery(
            "institution", "phone", "recipient", AdminInstitutionDelivery.Status.FAILED,
            externalReceipt, "reason", evidence, observedAt, 1, revision, idempotencyKey
        );
    }

    private static Set<String> keys(JSONObject value) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        value.keys().forEachRemaining(keys::add);
        return keys;
    }

    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_REPORT_ID = "22222222-2222-4222-8222-222222222222";
    private static final String EVIDENCE_GRANT_ID = "44444444-4444-4444-8444-444444444444";
    private static final String IDEMPOTENCY_KEY = "33333333-3333-4333-8333-333333333333";
}
