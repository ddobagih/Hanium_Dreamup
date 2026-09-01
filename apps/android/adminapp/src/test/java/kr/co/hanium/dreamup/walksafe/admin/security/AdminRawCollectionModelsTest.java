package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public final class AdminRawCollectionModelsTest {
    static final String COLLECTION = "11111111-1111-4111-8111-111111111111";
    static final String IDEMPOTENCY = "22222222-2222-4222-8222-222222222222";
    static final String DATASET = "33333333-3333-4333-8333-333333333333";

    @Test
    public void listIsAnExactBoundedQuarantineProjectionWithCasRevisions() throws Exception {
        AdminRawCollectionModels.Page page = AdminRawCollectionModels.parsePage(listJson());
        AdminRawCollectionModels.Summary item = page.items().get(0);

        assertEquals(COLLECTION, item.collectionId());
        assertEquals("QUARANTINED", item.state());
        assertEquals(2, item.reportDecisionRevision());
        assertEquals(3, item.trainingDecisionRevision());
        assertEquals(4, item.legalHoldRevision());
        assertTrue(item.legalHoldActive());

        assertThrows(IOException.class, () -> AdminRawCollectionModels.parsePage(
            listJson().replace("\"items\":", "\"private_object_key\":\"secret\",\"items\":")
        ));
        assertThrows(IOException.class, () -> AdminRawCollectionModels.parsePage(
            listJson().replace("\"QUARANTINED\"", "\"COMMITTED\"")
        ));
        assertThrows(IOException.class, () -> AdminRawCollectionModels.parsePage(
            listJson().replace("\"report_decision_revision\":2", "\"report_decision_revision\":0")
        ));
    }

    @Test
    public void trainingApprovalRequiresEveryEvidenceAndExclusionBinding() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdminRawCollectionModels.PurposeDecisionRequest(
                "TRAINING", "APPROVED", 0, IDEMPOTENCY, "승인 근거",
                "a".repeat(64), "b".repeat(64), "c".repeat(64), DATASET,
                true, true, false
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AdminRawCollectionModels.PurposeDecisionRequest(
                "REPORT", "APPROVED", 0, IDEMPOTENCY, "승인 근거",
                "a".repeat(64), null, null, null, false, false, false
            )
        );

        AdminRawCollectionModels.PurposeDecisionRequest request =
            new AdminRawCollectionModels.PurposeDecisionRequest(
                "TRAINING", "APPROVED", 3, IDEMPOTENCY, "학습 승인 근거",
                "a".repeat(64), "b".repeat(64), "c".repeat(64), DATASET,
                true, true, true
            );
        assertEquals(3, request.expectedRevision());
        assertTrue(request.thirdPartyFacesExcluded());
    }

    @Test
    public void mutationReceiptsRejectExtraOrMismatchedServerFields() throws Exception {
        AdminRawCollectionModels.PurposeDecisionRequest decision = reportDecision();
        AdminRawCollectionModels.Summary source = summary();
        var receipt = AdminRawCollectionModels.parsePurposeDecisionReceipt(
            decisionReceiptJson(), source, decision, "admin-001"
        );
        assertEquals(3, receipt.revision());
        assertEquals("APPROVED", receipt.decision());

        assertThrows(IOException.class, () ->
            AdminRawCollectionModels.parsePurposeDecisionReceipt(
                decisionReceiptJson().replace(
                    "\"admin_id\":\"admin-001\"",
                    "\"private_actor_ip\":\"127.0.0.1\",\"admin_id\":\"admin-001\""
                ),
                source,
                decision,
                "admin-001"
            )
        );
        assertThrows(IOException.class, () ->
            AdminRawCollectionModels.parsePurposeDecisionReceipt(
                decisionReceiptJson().replace("\"revision\":3", "\"revision\":4"),
                source,
                decision,
                "admin-001"
            )
        );
        assertThrows(IOException.class, () ->
            AdminRawCollectionModels.parsePurposeDecisionReceipt(
                decisionReceiptJson().replace(
                    "\"source_manifest_sha256\":\"" + "a".repeat(64) + "\"",
                    "\"source_manifest_sha256\":\"" + "c".repeat(64) + "\""
                ),
                source,
                decision,
                "admin-001"
            )
        );
        assertThrows(IOException.class, () ->
            AdminRawCollectionModels.parsePurposeDecisionReceipt(
                decisionReceiptJson().replace(
                    "\"source_receipt_sha256\":\"" + "b".repeat(64) + "\"",
                    "\"source_receipt_sha256\":\"" + "c".repeat(64) + "\""
                ),
                source,
                decision,
                "admin-001"
            )
        );

        AdminRawCollectionModels.LegalHoldRequest hold = legalHold();
        var holdReceipt = AdminRawCollectionModels.parseLegalHoldReceipt(
            legalHoldReceiptJson().replace(
                "2027-01-01T00:00:00Z", "2027-01-01T09:00:00+09:00"
            ),
            COLLECTION,
            hold,
            "admin-001"
        );
        assertEquals(5, holdReceipt.revision());
        assertEquals("APPLY", holdReceipt.action());
    }

    @Test
    public void legalHoldApplyAndReleaseHaveDisjointFields() {
        assertThrows(IllegalArgumentException.class, () ->
            new AdminRawCollectionModels.LegalHoldRequest(
                "APPLY", 0, IDEMPOTENCY, "보존 근거", null, "AUTH-1",
                "privacy@example.invalid", "2027-01-01T00:00:00Z"
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new AdminRawCollectionModels.LegalHoldRequest(
                "RELEASE", 1, IDEMPOTENCY, "해제 근거", "법적 근거", null,
                null, null
            )
        );
        AdminRawCollectionModels.LegalHoldRequest release =
            new AdminRawCollectionModels.LegalHoldRequest(
                "RELEASE", 5, IDEMPOTENCY, "해제 근거", null, null, null, null
            );
        assertFalse("APPLY".equals(release.action()));
        assertEquals(null, release.expiresAt());
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
