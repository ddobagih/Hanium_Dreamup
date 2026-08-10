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
    public void reviewBodyIsExactSixWithPhysicalNullAndAllReviewsRequired() throws Exception {
        AdminReportDecision approved = new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED,
            "현장 정보와 사진을 확인함",
            null,
            true,
            true,
            true
        );
        JSONObject body = new JSONObject(new String(approved.requestBody(REPORT_ID), StandardCharsets.UTF_8));

        assertEquals(Set.of(
            "decision", "reason", "duplicate_of_report_id",
            "location_reviewed", "photo_reviewed", "privacy_reviewed"
        ), keys(body));
        assertTrue(body.has("duplicate_of_report_id"));
        assertTrue(body.isNull("duplicate_of_report_id"));
        assertEquals("APPROVED", body.getString("decision"));

        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED, "reason", null, true, false, true
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.APPROVED, "reason", OTHER_REPORT_ID, true, true, true
        ));
    }

    @Test
    public void rejectedAndDuplicateKeepExactFlagsWithoutRequiringApprovedCompleteness() throws Exception {
        AdminReportDecision rejected = new AdminReportDecision(
            AdminReportDecision.Decision.REJECTED, "사진 확인 불가", null, true, false, true
        );
        JSONObject rejectedBody = new JSONObject(new String(
            rejected.requestBody(REPORT_ID), StandardCharsets.UTF_8
        ));
        assertTrue(!rejectedBody.getBoolean("photo_reviewed"));

        AdminReportDecision duplicate = new AdminReportDecision(
            AdminReportDecision.Decision.DUPLICATE, "위치 중복", OTHER_REPORT_ID, false, true, false
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
            OTHER_REPORT_ID,
            true,
            true,
            true
        );
        assertThrows(IllegalArgumentException.class, () -> duplicate.requestBody(OTHER_REPORT_ID));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportDecision(
            AdminReportDecision.Decision.DUPLICATE, "reason", null, true, true, true
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
            0L,
            IDEMPOTENCY_KEY
        );
        JSONObject body = new JSONObject(new String(delivery.requestBody(), StandardCharsets.UTF_8));

        assertEquals(Set.of(
            "institution", "channel", "recipient", "status", "external_receipt_id",
            "reason", "evidence_sha256", "observed_at", "expected_revision", "idempotency_key"
        ), keys(body));
        assertTrue(body.has("external_receipt_id"));
        assertTrue(body.isNull("external_receipt_id"));
        assertTrue(body.has("evidence_sha256"));
        assertTrue(body.isNull("evidence_sha256"));
        assertEquals("SUBMITTED", body.getString("status"));
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
            null, "reason", null, "2026-08-09T01:02:03Z", 1, IDEMPOTENCY_KEY
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
            externalReceipt, "reason", evidence, observedAt, revision, idempotencyKey
        );
    }

    private static Set<String> keys(JSONObject value) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        value.keys().forEachRemaining(keys::add);
        return keys;
    }

    private static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_REPORT_ID = "22222222-2222-4222-8222-222222222222";
    private static final String IDEMPOTENCY_KEY = "33333333-3333-4333-8333-333333333333";
}
