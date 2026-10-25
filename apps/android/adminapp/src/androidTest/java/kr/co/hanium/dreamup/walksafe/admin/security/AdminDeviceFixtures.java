package kr.co.hanium.dreamup.walksafe.admin.security;
import java.io.IOException;
/** Synthetic response fixtures shared in meaning with the JVM transport/model contract tests. */
final class AdminDeviceFixtures {
    static final String REPORT_ID = "11111111-1111-4111-8111-111111111111";
    static final String REQUEST_ID = "88888888-8888-4888-8888-888888888888";
    static final String INCIDENT_ID = "99999999-9999-4999-8999-999999999999";
    static final String COLLECTION = REPORT_ID;
    static final String IDEMPOTENCY = "22222222-2222-4222-8222-222222222222";
    static String reportStatus() {
        return "{\"schema_version\":\"walksafe.admin-report-status.v1\","
            + "\"id\":\"" + REPORT_ID + "\",\"status\":\"resolved\","
            + "\"status_version\":3,\"allowed_next_statuses\":[\"reviewed\"],"
            + "\"updated_at\":\"2026-08-29T02:00:00Z\"}";
    }
    static String requestStatus() {
        return "{\"schema_version\":\"walksafe.admin-report-request-status.v1\","
            + "\"request_id\":\"" + REQUEST_ID + "\","
            + "\"report_id\":\"" + REPORT_ID + "\",\"status\":\"REJECTED\","
            + "\"status_version\":3,\"allowed_next_statuses\":[],"
            + "\"public_response\":\"요청 처리를 완료했습니다.\","
            + "\"updated_at\":\"2026-08-29T03:00:00Z\"}";
    }
    static String incidentStatus() {
        return "{\"schema_version\":\"walksafe.admin-incident-status.v1\","
            + "\"incident_id\":\"" + INCIDENT_ID + "\",\"status\":\"ACKNOWLEDGED\","
            + "\"status_version\":2,\"allowed_next_states\":[\"RESOLVED\"],"
            + "\"updated_at\":\"2026-08-29T00:02:00Z\"}";
    }
    static AdminRawCollectionModels.PurposeDecisionRequest reportDecision() {
        return new AdminRawCollectionModels.PurposeDecisionRequest(
            "REPORT", "APPROVED", 2, IDEMPOTENCY, "신고 사용 승인 근거",
            null, null, null, null, false, false, false
        );
    }
    static AdminRawCollectionModels.Summary summary() throws IOException {
        return AdminRawCollectionModels.parsePage(listJson()).items().get(0);
    }
    static AdminRawCollectionModels.LegalHoldRequest legalHold() {
        return new AdminRawCollectionModels.LegalHoldRequest(
            "APPLY", 4, IDEMPOTENCY, "법적 보존 필요", "수사기관 보존 요청",
            "AUTH-2026-001", "privacy@example.invalid", "2027-01-01T00:00:00Z"
        );
    }
    static String listJson() {
        return "{\"schema_version\":\"walksafe.admin-raw-collection-list.v1\",\"items\":[{"
            + "\"collection_id\":\"" + COLLECTION + "\","
            + "\"purpose\":\"GENERAL_RAW\",\"state\":\"QUARANTINED\","
            + "\"manifest_sha256\":\"" + "a".repeat(64) + "\","
            + "\"receipt_sha256\":\"" + "b".repeat(64) + "\","
            + "\"object_count\":2,\"total_bytes\":4096,"
            + "\"committed_at\":\"2026-08-29T00:00:00Z\","
            + "\"quarantine_expires_at\":\"2026-09-12T00:00:00Z\","
            + "\"report_decision\":\"APPROVED\",\"report_decision_revision\":2,"
            + "\"training_decision\":\"REJECTED\",\"training_decision_revision\":3,"
            + "\"legal_hold_active\":true,\"legal_hold_revision\":4}]}";
    }
    static String decisionReceiptJson() {
        return "{\"id\":\"44444444-4444-4444-8444-444444444444\","
            + "\"collection_id\":\"" + COLLECTION + "\",\"scope\":\"REPORT\","
            + "\"revision\":3,\"expected_revision\":2,"
            + "\"idempotency_key\":\"" + IDEMPOTENCY + "\","
            + "\"decision\":\"APPROVED\",\"reason\":\"신고 사용 승인 근거\","
            + "\"source_manifest_sha256\":\"" + "a".repeat(64) + "\","
            + "\"source_receipt_sha256\":\"" + "b".repeat(64) + "\","
            + "\"training_consent_receipt_sha256\":null,"
            + "\"deidentification_receipt_sha256\":null,"
            + "\"sanitized_manifest_sha256\":null,\"target_dataset_id\":null,"
            + "\"exact_location_excluded\":false,\"raw_audio_excluded\":false,"
            + "\"third_party_faces_excluded\":false,\"admin_id\":\"admin-001\","
            + "\"decided_at\":\"2026-08-29T00:02:00Z\"}";
    }
    static String legalHoldReceiptJson() {
        return "{\"id\":\"55555555-5555-4555-8555-555555555555\","
            + "\"collection_id\":\"" + COLLECTION + "\",\"revision\":5,"
            + "\"expected_revision\":4,\"idempotency_key\":\"" + IDEMPOTENCY + "\","
            + "\"action\":\"APPLY\",\"reason\":\"법적 보존 필요\","
            + "\"legal_basis\":\"수사기관 보존 요청\","
            + "\"authority_reference\":\"AUTH-2026-001\","
            + "\"contact\":\"privacy@example.invalid\","
            + "\"expires_at\":\"2027-01-01T00:00:00Z\","
            + "\"admin_id\":\"admin-001\",\"recorded_at\":\"2026-08-29T00:03:00Z\"}";
    }
}
