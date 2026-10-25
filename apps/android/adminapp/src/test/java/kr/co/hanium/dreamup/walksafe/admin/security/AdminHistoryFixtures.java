package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.List;

/** Test-only factories for history DTOs whose constructors remain outside the UI contract. */
public final class AdminHistoryFixtures {
    private AdminHistoryFixtures() {}
    public static AdminOperationsApi.Result reviews(String report, long total, String cursor, long... revisions) {
        java.util.ArrayList<AdminOperationsApi.ReviewHistoryItem> items = new java.util.ArrayList<>();
        for (long revision : revisions) items.add(new AdminOperationsApi.ReviewHistoryItem(
            revision, 2, AdminReportDecision.Decision.APPROVED, "검토 확인", null, null, "2026-08-27T00:00:00Z"));
        return AdminOperationsApi.Result.reviewHistory("test", 200, report, total, total, cursor, items);
    }
    public static AdminOperationsApi.Result deliveries(String report, long total, String cursor,
        AdminOperationsApi.DeliveryHistoryItem... items) {
        return AdminOperationsApi.Result.deliveryHistory("test", 200, report, total, total, cursor, List.of(items));
    }
    public static AdminOperationsApi.DeliveryHistoryItem delivery(long revision,
        AdminInstitutionDelivery.Status status, Long correctedAtRevision) {
        AdminOperationsApi.DeliveryCorrection correction = correctedAtRevision == null ? null
            : new AdminOperationsApi.DeliveryCorrection("correction-" + revision, revision,
                correctedAtRevision, AdminDeliveryCorrection.Reason.WRONG_STATUS, "잘못된 상태 기록", "admin", "2026-08-27T00:00:00Z");
        return new AdminOperationsApi.DeliveryHistoryItem("delivery-" + revision, revision, "package", 1L,
            status, "receipt", "기관", "2026-08-27T00:00:00Z", "2026-08-27T00:00:00Z", correction);
    }
}
