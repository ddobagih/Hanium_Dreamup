package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportModels;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Calls the text formatters used by the detail card, without constructing Android views. */
public final class AdminReportStatusMeaningTest {
    @Test
    public void approvedNewDoesNotClaimInternalCompletionOrInstitutionReceipt() throws Exception {
        AdminReportModels.Detail detail = detail("new", "APPROVED", null);
        String review = reviewText(detail);

        assertEquals("내부 처리: 새 신고", statusLabel(detail));
        assertTrue(review, review.contains("검수 결과: 승인"));
        assertTrue(review, review.contains("내부 처리 상태와 기관 전달·접수는 별도"));
        assertTrue(review, review.contains("새 신고 상태에서도 검수 승인을 기록할 수 있습니다"));
        assertTrue(deliveryText(detail).contains("전달·접수 여부 미확인"));
    }

    @Test
    public void internallyResolvedWithoutDeliveryCannotBeReadAsInstitutionCompletion() throws Exception {
        AdminReportModels.Detail detail = detail("resolved", "APPROVED", null);

        assertEquals("내부 처리: 완료", statusLabel(detail));
        assertTrue(deliveryText(detail).contains("기관 전달 상태: 현재 내용의 기록 없음"));
        assertTrue(deliveryText(detail).contains("전달·접수 여부 미확인"));
        assertFalse(deliveryText(detail).contains("기관 접수 확인 기록됨"));
    }

    @Test
    public void rejectedWithoutDeliveryShowsRejectionAndTheRequiredNextStep() throws Exception {
        AdminReportModels.Detail detail = detail("resolved", "REJECTED", null);
        String review = reviewText(detail);

        assertTrue(review, review.contains("검수 결과: 기각"));
        assertTrue(review, review.contains("기각 사유"));
        assertTrue(review, review.contains("새 기관 수동 전달을 진행할 수 없습니다"));
        assertTrue(deliveryText(detail).contains("전달·접수 여부 미확인"));
        assertFalse(canCreatePackage(detail));
    }

    @Test
    public void submittedRecordExplicitlyWaitsForInstitutionReceipt() throws Exception {
        AdminReportModels.Detail detail = detail("new", "APPROVED", "SUBMITTED");
        String delivery = deliveryText(detail);

        assertTrue(delivery, delivery.contains("기관 전달 상태: 수동 제출 기록됨"));
        assertTrue(delivery, delivery.contains("기관 접수 확인 전"));
        assertTrue(delivery, delivery.contains("앱 밖에서 수행한 수동 전달 사실의 기록"));
        assertFalse(delivery.contains("기관 접수 확인 기록됨"));
        assertEquals("내부 처리: 새 신고", statusLabel(detail));
    }

    @Test
    public void acknowledgedRecordIsReceiptRatherThanInternalCompletion() throws Exception {
        AdminReportModels.Detail detail = detail("new", "APPROVED", "ACKNOWLEDGED");
        String delivery = deliveryText(detail);

        assertTrue(delivery, delivery.contains("기관 전달 상태: 기관 접수 확인 기록됨"));
        assertTrue(delivery, delivery.contains("기관 처리 결과는 별도"));
        assertTrue(delivery, delivery.contains("외부 접수번호 있음"));
        assertTrue(delivery, delivery.contains("관리자가 수동 기록한 전달 상태"));
        assertTrue(delivery, delivery.contains("기관의 응답이나 증거를 앱이 검증한 결과가 아닙니다"));
        assertEquals("내부 처리: 새 신고", statusLabel(detail));
    }

    @Test
    public void failedRecordDoesNotClaimSuccessfulSubmissionOrReceipt() throws Exception {
        AdminReportModels.Detail detail = detail("resolved", "APPROVED", "FAILED");
        String delivery = deliveryText(detail);

        assertTrue(delivery, delivery.contains("기관 전달 상태: 전달 실패 기록됨"));
        assertTrue(delivery, delivery.contains("이번 실패 기록은 제출 성공·기관 접수 확인이 아닙니다"));
        assertFalse(delivery.contains("기관 접수 확인 기록됨"));
        assertFalse(delivery.contains("수동 제출 기록됨"));
        assertEquals("내부 처리: 완료", statusLabel(detail));
    }

    @Test
    public void institutionResolvedIsAnExternalResultRecord() throws Exception {
        AdminReportModels.Detail detail = detail("new", "APPROVED", "RESOLVED");
        String delivery = deliveryText(detail);

        assertTrue(delivery, delivery.contains("기관 전달 상태: 기관 처리 완료 기록됨"));
        assertTrue(delivery, delivery.contains("내부 처리 상태와 별도"));
        assertEquals("내부 처리: 새 신고", statusLabel(detail));
    }

    @Test
    public void absentAndDuplicateReviewCannotAuthorizeNewManualDelivery() throws Exception {
        AdminReportModels.Detail unreviewed = detail("new", null, null);
        AdminReportModels.Detail duplicate = detail("reviewed", "DUPLICATE", null);

        assertTrue(reviewText(unreviewed).contains("검수 결과: 현재 내용의 기록 없음"));
        assertTrue(reviewText(unreviewed).contains("최신 검수 승인과 위치·사진·개인정보 확인"));
        assertTrue(reviewText(duplicate).contains("검수 결과: 중복"));
        assertTrue(reviewText(duplicate).contains("새 기관 수동 전달을 진행할 수 없습니다"));
        assertFalse(canCreatePackage(unreviewed));
        assertFalse(canCreatePackage(duplicate));
    }

    @Test
    public void incompleteApprovalDoesNotDescribeNewDeliveryAsReady() throws Exception {
        JSONObject payload = payload("new", "APPROVED", null);
        payload.getJSONObject("current_review").put("privacy_reviewed", false);
        AdminReportModels.Detail detail = parse(payload);
        String review = reviewText(detail);

        assertTrue(review, review.contains("확인 항목이 남아 있어 새 기관 수동 전달을 진행할 수 없습니다"));
        assertTrue(review, review.contains("개인정보 미확인"));
        assertFalse(canCreatePackage(detail));
        assertTrue(canCreatePackage(detail("new", "APPROVED", null)));
    }

    @Test
    public void currentContentWithoutDeliveryDoesNotEraseEarlierHistory() throws Exception {
        JSONObject payload = payload("resolved", null, null);
        payload.put("latest_delivery_revision", 4);
        AdminReportModels.Detail detail = parse(payload);

        assertTrue(deliveryText(detail).contains("현재 내용의 기록 없음"));
        assertTrue(deliveryText(detail).contains("이전 내용의 전달 이력은 별도 확인"));
        assertFalse(deliveryText(detail).contains("전달한 적 없음"));
    }

    @Test
    public void approvalProofAndOptionalDeliveryEvidenceAreDistinctAndRecordsStayIdentifiable()
        throws Exception {
        AdminReportModels.Detail detail = detail("new", "APPROVED", "ACKNOWLEDGED");
        String review = reviewText(detail);
        String delivery = deliveryText(detail);

        assertTrue(review, review.contains("승인에 필요한 원본 확인"));
        assertTrue(review, review.contains("전달 증빙은 선택 항목"));
        assertTrue(review, review.contains("이미 제출한 자료의 후속 접수·처리 기록은 별도"));
        assertTrue(review, review.contains("검수 기록 버전: 2"));
        assertTrue(delivery, delivery.contains("전달 기록 버전: 3"));
        assertTrue(delivery, delivery.contains("제출본 버전: 1"));
        JSONObject payload = payload("new", "APPROVED", "SUBMITTED");
        payload.getJSONObject("current_delivery").put("evidence_present", false);
        String withoutEvidence = deliveryText(parse(payload));
        assertTrue(withoutEvidence, withoutEvidence.contains("수동 제출 기록됨"));
        assertTrue(withoutEvidence, withoutEvidence.contains("증빙 없음"));
    }

    private static AdminReportModels.Detail detail(String status, String decision, String delivery)
        throws Exception {
        return parse(payload(status, decision, delivery));
    }

    private static JSONObject payload(String status, String decision, String delivery) throws Exception {
        String fixture = new String(Files.readAllBytes(Paths.get(
            "../../../contracts/fixtures/admin-report-detail-v2.json"
        )), StandardCharsets.UTF_8);
        JSONObject payload = new JSONObject(fixture);
        payload.put("status", status);
        JSONArray next = new JSONArray();
        for (String candidate : new String[]{"new", "reviewed", "resolved"}) {
            if (!status.equals(candidate)) next.put(candidate);
        }
        payload.put("allowed_next_statuses", next);
        if (decision == null) {
            payload.put("current_review", JSONObject.NULL);
        } else {
            JSONObject review = payload.getJSONObject("current_review");
            review.put("decision", decision);
            review.put("user_visible_reason", "APPROVED".equals(decision)
                ? JSONObject.NULL : "기각 사유 또는 중복 근거 확인");
            review.put("duplicate_of_report_id", "DUPLICATE".equals(decision)
                ? "22222222-2222-4222-8222-222222222222" : JSONObject.NULL);
        }
        if (delivery == null) {
            payload.put("current_delivery", JSONObject.NULL);
            payload.put("latest_delivery_revision", 0);
        } else {
            JSONObject record = payload.getJSONObject("current_delivery");
            record.put("status", delivery);
            record.put("external_receipt_present", !"SUBMITTED".equals(delivery) && !"FAILED".equals(delivery));
        }
        return payload;
    }

    private static AdminReportModels.Detail parse(JSONObject payload) throws Exception {
        return AdminReportModels.parseDetail(payload.toString(), payload.getString("id"));
    }

    private static String statusLabel(AdminReportModels.Detail detail) throws Exception {
        return (String) invoke("statusLabel", String.class, detail.summary().status());
    }

    private static String reviewText(AdminReportModels.Detail detail) throws Exception {
        return (String) invoke("reviewText", AdminReportModels.ReviewSummary.class, detail.review());
    }

    private static String deliveryText(AdminReportModels.Detail detail) throws Exception {
        return (String) invoke("deliveryText", AdminReportModels.DeliverySummary.class, detail.delivery());
    }

    private static boolean canCreatePackage(AdminReportModels.Detail detail) throws Exception {
        return (Boolean) invoke("canCreatePackage", AdminReportModels.ReviewSummary.class, detail.review());
    }

    private static Object invoke(String name, Class<?> parameter, Object value) throws Exception {
        Method method = AdminReportPanel.class.getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        return method.invoke(null, value);
    }
}
