package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminReportModelsTest {
    @Test
    public void fixturesParseAsStrictMinimumListAndDetail() throws Exception {
        AdminReportModels.Page page = AdminReportModels.parsePage(wave5ListFixture());
        assertEquals(1, page.items().size());
        assertEquals("new", page.items().get(0).status());
        assertEquals(0.75d, page.items().get(0).confidence(), 0d);
        assertNull(page.nextCursor());

        String reportId = page.items().get(0).id();
        AdminReportModels.Detail detail = AdminReportModels.parseDetail(
            wave5DetailFixture(),
            reportId
        );
        assertEquals(0, detail.contentRevision());
        assertEquals(3, detail.latestDeliveryRevision());
        assertEquals("APPROVED", detail.review().decision());
        assertNull(detail.review().userVisibleReason());
        assertEquals("ACKNOWLEDGED", detail.currentContentDelivery().status());
        assertEquals(3, detail.currentContentDelivery().revision());
        assertEquals(1, detail.currentContentDelivery().packageRevision());
        assertEquals(0, detail.currentContentDelivery().packageContentRevision());
        assertEquals(
            "88888888-8888-4888-8888-888888888888",
            detail.currentContentDelivery().packageId()
        );
        assertEquals(4096, detail.currentContentDelivery().packageByteCount());
    }

    @Test
    public void v2KeepsLatestGlobalDeliveryRevisionWhenCurrentContentHasNoDelivery()
        throws Exception {
        String detailJson = wave5DetailFixture()
            .replace("\"latest_delivery_revision\": 3", "\"latest_delivery_revision\": 4");
        int deliveryStart = detailJson.indexOf("  \"current_delivery\": {");
        int capabilitiesStart = detailJson.indexOf("  \"capabilities\":", deliveryStart);
        String withoutCurrentDelivery = detailJson.substring(0, deliveryStart)
            + "  \"current_delivery\": null,\n"
            + detailJson.substring(capabilitiesStart);

        AdminReportModels.Detail detail = AdminReportModels.parseDetail(
            withoutCurrentDelivery,
            "11111111-1111-4111-8111-111111111111"
        );

        assertEquals(4, detail.latestDeliveryRevision());
        assertNull(detail.currentContentDelivery());
        assertNull(detail.delivery());
    }

    @Test
    public void submittedPackageCanReceiveLaterObservationAfterContentAndReviewChange()
        throws Exception {
        String reportId = "11111111-1111-4111-8111-111111111111";
        String packageId = "88888888-8888-4888-8888-888888888888";
        AdminReportModels.Detail changed = AdminReportModels.parseDetail(
            correctedDetailWithoutCurrentReview(3), reportId
        );
        AdminOperationsApi.DeliveryHistoryItem submitted = new AdminOperationsApi.DeliveryHistoryItem(
            "99999999-9999-4999-8999-999999999999", 3L, packageId, 1L,
            AdminInstitutionDelivery.Status.SUBMITTED, null, "기관", "2026-09-29T00:00:00Z",
            "2026-09-29T00:00:01Z", null
        );

        AdminReportModels.DeliveryFollowUp followUp =
            AdminReportModels.DeliveryFollowUp.fromLatest(changed, submitted);
        assertTrue(followUp.matchesDetail(changed));
        assertTrue(followUp.matchesPackage(reportId, packageId, 1L));
        assertTrue(followUp.allows(AdminInstitutionDelivery.Status.ACKNOWLEDGED));
        assertFalse(followUp.allows(AdminInstitutionDelivery.Status.SUBMITTED));
        assertFalse(followUp.matchesPackage(reportId, packageId, 2L));
        assertThrows(IllegalArgumentException.class,
            () -> AdminDeliveryPackage.Eligibility.fromDetail(changed));
    }

    @Test
    public void followUpBindingRejectsChangedDeliveryRevisionAndCompletedSubmission()
        throws Exception {
        String reportId = "11111111-1111-4111-8111-111111111111";
        String packageId = "88888888-8888-4888-8888-888888888888";
        AdminReportModels.Detail changed = AdminReportModels.parseDetail(
            correctedDetailWithoutCurrentReview(3), reportId
        );
        AdminOperationsApi.DeliveryHistoryItem acknowledged = new AdminOperationsApi.DeliveryHistoryItem(
            "99999999-9999-4999-8999-999999999999", 3L, packageId, 1L,
            AdminInstitutionDelivery.Status.ACKNOWLEDGED, "receipt", "기관",
            "2026-09-29T00:00:00Z", "2026-09-29T00:00:01Z", null
        );
        AdminReportModels.DeliveryFollowUp followUp =
            AdminReportModels.DeliveryFollowUp.fromLatest(changed, acknowledged);
        assertTrue(followUp.allows(AdminInstitutionDelivery.Status.RESOLVED));
        assertFalse(followUp.allows(AdminInstitutionDelivery.Status.ACKNOWLEDGED));
        assertFalse(followUp.matchesDetail(AdminReportModels.parseDetail(
            correctedDetailWithoutCurrentReview(3).replace(
                "\"latest_delivery_revision\": 3", "\"latest_delivery_revision\": 4"
            ), reportId
        )));
        AdminOperationsApi.DeliveryHistoryItem resolved = new AdminOperationsApi.DeliveryHistoryItem(
            "99999999-9999-4999-8999-999999999999", 3L, packageId, 1L,
            AdminInstitutionDelivery.Status.RESOLVED, "receipt", "기관",
            "2026-09-29T00:00:00Z", "2026-09-29T00:00:01Z", null
        );
        assertNull(AdminReportModels.DeliveryFollowUp.fromLatest(changed, resolved));
    }

    @Test
    public void reconnectSelectsOldSubmissionOrNewlyApprovedPackageByChosenZipRevision()
        throws Exception {
        String reportId = "11111111-1111-4111-8111-111111111111";
        AdminReportModels.Detail detail = AdminReportModels.parseDetail(
            wave5DetailFixture(), reportId
        );
        AdminDeliveryPackage.Eligibility current =
            AdminDeliveryPackage.Eligibility.fromDetail(detail);
        AdminOperationsApi.DeliveryHistoryItem submitted = new AdminOperationsApi.DeliveryHistoryItem(
            "99999999-9999-4999-8999-999999999999", 3L,
            "88888888-8888-4888-8888-888888888888", 1L,
            AdminInstitutionDelivery.Status.SUBMITTED, null, "기관",
            "2026-09-29T00:00:00Z", "2026-09-29T00:00:01Z", null
        );
        AdminReportModels.DeliveryFollowUp followUp =
            AdminReportModels.DeliveryFollowUp.fromLatest(detail, submitted);

        assertEquals(AdminReportModels.DeliveryPackageUse.FOLLOW_UP,
            AdminReportModels.deliveryPackageUse(current, followUp, reportId, 1L));
        assertEquals(AdminReportModels.DeliveryPackageUse.NEW_SUBMISSION,
            AdminReportModels.deliveryPackageUse(current, followUp, reportId, 2L));
        assertEquals(AdminReportModels.DeliveryPackageUse.FOLLOW_UP,
            AdminReportModels.deliveryPackageUse(null, followUp, reportId, 1L));
        assertThrows(IllegalArgumentException.class, () ->
            AdminReportModels.deliveryPackageUse(null, followUp, reportId, 2L));
        assertThrows(IllegalArgumentException.class, () ->
            AdminReportModels.deliveryPackageUse(current, followUp,
                "22222222-2222-4222-8222-222222222222", 2L));
    }

    @Test
    public void sensitiveUnknownDuplicateAndMismatchedCapabilityFieldsFailClosed() throws Exception {
        String list = wave5ListFixture();
        assertThrows(IOException.class, () -> AdminReportModels.parsePage(
            list.replace("\"status\": \"new\"", "\"status\": \"new\",\"image_path\":\"private\"")
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parsePage(
            list.replace("\"next_cursor\": null", "\"next_cursor\":null,\"next_cursor\":null")
        ));
        String detail = wave5DetailFixture();
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace(
                "walksafe.admin-report-detail.v2",
                "walksafe.admin-report-detail.v1"
            ),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("  \"content_revision\": 0,\n", ""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("  \"latest_delivery_revision\": 3,\n", ""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace(
                "\"latest_delivery_revision\": 3",
                "\"latest_delivery_revision\": 2"
            ),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("    \"user_visible_reason\": null,\n", ""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace(
                "\"user_visible_reason\": null",
                "\"user_visible_reason\": \"승인에 공개 거절 사유가 있으면 안 됨\""
            ),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("\"decision\": \"APPROVED\"", "\"decision\": \"REJECTED\""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("/review-decisions", "/raw-original"),
            "11111111-1111-4111-8111-111111111111"
        ));
    }

    @Test
    public void filtersRequireCanonicalIdEnumsAndOrderedAwareDates() {
        assertThrows(IllegalArgumentException.class, () -> new AdminReportModels.Filters(
            "11111111-1111-4111-8111-11111111111A", "new", "pothole", null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportModels.Filters(
            null, "NEW", null, null, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminReportModels.Filters(
            null, null, null, "2026-08-30T00:00:00Z", "2026-08-29T00:00:00Z"
        ));
    }

    @Test
    public void w20aR1LatestReviewRevisionIsExactRequiredAndCoversCurrentReview()
        throws Exception {
        String detail = wave5DetailFixture();
        AdminReportModels.Detail parsed = AdminReportModels.parseDetail(
            detail,
            "11111111-1111-4111-8111-111111111111"
        );
        assertEquals(2, parsed.latestReviewRevision());
        assertEquals(2, parsed.review().revision());
        assertEquals(
            5,
            AdminReportModels.parseDetail(
                detail.replace("\"latest_review_revision\": 2", "\"latest_review_revision\": 5"),
                "11111111-1111-4111-8111-111111111111"
            ).latestReviewRevision()
        );
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("  \"latest_review_revision\": 2,\n", ""),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("\"latest_review_revision\": 2", "\"latest_review_revision\": 1"),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("\"latest_review_revision\": 2", "\"latest_review_revision\": -1"),
            "11111111-1111-4111-8111-111111111111"
        ));
        assertThrows(IOException.class, () -> AdminReportModels.parseDetail(
            detail.replace("\"latest_review_revision\": 2", "\"latest_review_revision\": \"2\""),
            "11111111-1111-4111-8111-111111111111"
        ));
    }

    @Test
    public void w20aR1CorrectedReportReReviewBindsReportWideReviewRevision() throws Exception {
        String reportId = "11111111-1111-4111-8111-111111111111";
        String corrected = correctedDetailWithoutCurrentReview(1);
        AdminReportModels.Detail detail = AdminReportModels.parseDetail(corrected, reportId);
        assertNull(detail.review());
        assertEquals(1, detail.contentRevision());
        assertEquals(1, detail.latestReviewRevision());
        AdminReportDecision decision = new AdminReviewDecisionAttempt().prepare(
            reportId,
            AdminReportDecision.Decision.REJECTED,
            "정정된 설명을 다시 검토함",
            "검토 결과 이 신고를 처리할 수 없습니다.",
            null,
            true,
            true,
            true,
            detail.contentRevision(),
            null,
            detail.latestReviewRevision()
        );
        org.json.JSONObject body = new org.json.JSONObject(new String(
            decision.requestBody(reportId), StandardCharsets.UTF_8
        ));
        assertEquals(1L, body.getLong("expected_review_revision"));
        assertEquals(1L, body.getLong("content_revision"));
    }

    static String correctedDetailWithoutCurrentReview(int latestReviewRevision) throws Exception {
        String detailJson = wave5DetailFixture()
            .replace("\"content_revision\": 0", "\"content_revision\": 1")
            .replace(
                "\"latest_review_revision\": 2",
                "\"latest_review_revision\": " + latestReviewRevision
            );
        int reviewStart = detailJson.indexOf("  \"current_review\": {");
        int deliveryStart = detailJson.indexOf("  \"current_delivery\": {", reviewStart);
        int capabilitiesStart = detailJson.indexOf("  \"capabilities\":", deliveryStart);
        if (reviewStart < 0 || deliveryStart < 0 || capabilitiesStart < 0) {
            throw new AssertionError("detail fixture layout changed");
        }
        return detailJson.substring(0, reviewStart)
            + "  \"current_review\": null,\n"
            + "  \"current_delivery\": null,\n"
            + detailJson.substring(capabilitiesStart);
    }

    private static String fixture(String name) throws Exception {
        return new String(
            Files.readAllBytes(Paths.get("../../../contracts/fixtures/" + name)),
            StandardCharsets.UTF_8
        );
    }

    static String wave5ListFixture() throws Exception {
        return fixture("admin-report-list-v1.json").replace(
            "\"status\": \"new\",",
            "\"status\": \"new\",\n      \"status_version\": 1,"
        );
    }

    static String wave5DetailFixture() throws Exception {
        return fixture("admin-report-detail-v2.json");
    }
}
