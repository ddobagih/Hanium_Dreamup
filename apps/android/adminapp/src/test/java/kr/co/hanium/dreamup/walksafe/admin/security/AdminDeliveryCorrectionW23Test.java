package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** S18-40 (originals/18.txt:276-280): append-only delivery event corrections in the admin app. */
public final class AdminDeliveryCorrectionW23Test {
    private static final String ORIGIN = "http://127.0.0.1:8000";
    private static final String REPORT_ID = "44444444-4444-4444-8444-444444444444";
    private static final String CORRECTION_ID = "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f";
    private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String PACKAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final AdminOperationsApi.SessionContext SESSION = new AdminOperationsApi.SessionContext(
        "opaque-access-token-for-tests-123456",
        "admin-001",
        AdminDeviceProofTest.SESSION_ID,
        AdminDeviceProofTest.DEVICE_ID
    );

    // --- history parser --------------------------------------------------------------

    @Test
    public void s18_40_correctedEventsAreKeptButSkippedByTheTransitionChain() throws Exception {
        FakeTransport transport = new FakeTransport();
        // SUBMITTED, ACKNOWLEDGED (corrected), ACKNOWLEDGED again: only valid on the effective chain.
        transport.history = page(
            item(1, "SUBMITTED", null),
            item(2, "ACKNOWLEDGED", correction(2, 2, "WRONG_STATUS")),
            item(3, "ACKNOWLEDGED", null)
        );
        AdminOperationsApi.Result result = client(transport).readDeliveries(SESSION, REPORT_ID, null);
        List<AdminOperationsApi.DeliveryHistoryItem> items = result.deliveryHistory();
        assertEquals(3, items.size());
        assertNull(items.get(0).correction());
        assertTrue(items.get(1).corrected());
        assertEquals(AdminDeliveryCorrection.Reason.WRONG_STATUS, items.get(1).correction().reason());
        assertEquals(2L, items.get(1).correction().correctedRevision());
        assertEquals(eventId(2), items.get(1).id());
        assertFalse(items.get(2).corrected());
    }

    @Test
    public void s18_40_aCorrectedResolvedWithoutSubmissionIsAcceptedAndCarriesNoState() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.history = page(item(1, "RESOLVED", correction(1, 1, "WRONG_REPORT")));
        AdminOperationsApi.Result result = client(transport).readDeliveries(SESSION, REPORT_ID, null);
        assertTrue(result.deliveryHistory().get(0).corrected());

        transport.history = page(item(1, "RESOLVED", null));
        assertThrows(IOException.class, () -> client(transport).readDeliveries(SESSION, REPORT_ID, null));
    }

    @Test
    public void s18_40_effectiveChainStillRejectsAnInvalidTransition() {
        FakeTransport transport = new FakeTransport();
        // With ACKNOWLEDGED corrected, RESOLVED would follow SUBMITTED directly.
        transport.history = page(
            item(1, "SUBMITTED", null),
            item(2, "ACKNOWLEDGED", correction(2, 2, "WRONG_STATUS")),
            item(3, "RESOLVED", null)
        );
        assertThrows(IOException.class, () -> client(transport).readDeliveries(SESSION, REPORT_ID, null));
    }

    @Test
    public void s18_40_correctionMustStepBackFromTheLatestEffectiveEvent() {
        FakeTransport transport = new FakeTransport();
        // Revision 1 was corrected at history revision 2 while revision 2 stayed effective.
        transport.history = page(
            item(1, "SUBMITTED", correction(1, 2, "WRONG_REPORT")),
            item(2, "FAILED", null)
        );
        assertThrows(IOException.class, () -> client(transport).readDeliveries(SESSION, REPORT_ID, null));
    }

    @Test
    public void s18_40_correctionObjectIsStrictlyBoundToItsEvent() {
        List<Map<String, Object>> variants = new ArrayList<>();
        Map<String, Object> wrongEvent = correction(2, 2, "WRONG_STATUS");
        wrongEvent.put("corrects_event_id", eventId(1));
        variants.add(wrongEvent);
        Map<String, Object> wrongRevision = correction(2, 2, "WRONG_STATUS");
        wrongRevision.put("corrected_revision", 1);
        variants.add(wrongRevision);
        Map<String, Object> earlyExpected = correction(2, 2, "WRONG_STATUS");
        earlyExpected.put("expected_revision", 1);
        variants.add(earlyExpected);
        variants.add(correction(2, 2, "TYPO"));
        Map<String, Object> blankMemo = correction(2, 2, "WRONG_STATUS");
        blankMemo.put("memo", "   ");
        variants.add(blankMemo);
        Map<String, Object> otherReport = correction(2, 2, "WRONG_STATUS");
        otherReport.put("report_id", "45454545-4545-4545-8545-454545454545");
        variants.add(otherReport);
        Map<String, Object> extra = correction(2, 2, "WRONG_STATUS");
        extra.put("deleted", true);
        variants.add(extra);
        Map<String, Object> schema = correction(2, 2, "WRONG_STATUS");
        schema.put("schema_version", "walksafe.report-delivery-correction.v0");
        variants.add(schema);
        for (Map<String, Object> variant : variants) {
            FakeTransport transport = new FakeTransport();
            transport.history = page(item(1, "SUBMITTED", null), item(2, "ACKNOWLEDGED", variant));
            assertThrows(
                variant.toString(),
                IOException.class,
                () -> client(transport).readDeliveries(SESSION, REPORT_ID, null)
            );
        }
        FakeTransport missingKey = new FakeTransport();
        Map<String, Object> legacyItem = item(1, "SUBMITTED", null);
        legacyItem.remove("correction");
        missingKey.history = page(legacyItem);
        assertThrows(IOException.class, () -> client(missingKey).readDeliveries(SESSION, REPORT_ID, null));
    }

    // --- request model, gate and POST --------------------------------------------------

    @Test
    public void s18_40_correctionRequestRequiresReasonAndMemoAndIsExact5() {
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.WRONG_REPORT, "  다른 건 회신  "
        );
        assertEquals("다른 건 회신", correction.memo());
        assertEquals(
            "{\"correction_id\":\"" + CORRECTION_ID + "\",\"corrects_event_id\":\"" + eventId(3)
                + "\",\"expected_revision\":3,\"memo\":\"다른 건 회신\",\"reason_code\":\"WRONG_REPORT\"}",
            new String(correction.requestBody(), StandardCharsets.UTF_8)
        );
        for (String memo : new String[] {null, "", "   ", "x".repeat(501), "bad\u0007memo"}) {
            assertThrows(IllegalArgumentException.class, () -> new AdminDeliveryCorrection(
                CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.OTHER, memo
            ));
        }
        assertThrows(IllegalArgumentException.class, () -> new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, null, "memo"
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminDeliveryCorrection(
            CORRECTION_ID.toUpperCase(), eventId(3), 3L, AdminDeliveryCorrection.Reason.OTHER, "memo"
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 0L, AdminDeliveryCorrection.Reason.OTHER, "memo"
        ));
    }

    @Test
    public void s18_40_gateBindsTheExactHighRiskTriple() {
        AdminHighRiskActionGate.Operation operation = AdminHighRiskActionGate.deliveryCorrection(REPORT_ID);
        assertEquals("report.delivery.correct", operation.reauthenticationAction());
        assertEquals("POST", operation.method());
        assertEquals("/reports/" + REPORT_ID + "/delivery-corrections", operation.path());
        assertNull(operation.intentSha256());
        assertThrows(IllegalArgumentException.class, () -> AdminHighRiskActionGate.deliveryCorrection(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa".toUpperCase()
        ));
    }

    @Test
    public void s18_40_postUsesDeviceProofAndTheExactReconfirmationHeader() throws Exception {
        FakeTransport transport = new FakeTransport();
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.WRONG_REPORT, "다른 건 회신"
        );
        transport.correctionResponse = new JSONObject(correctionResponse(correction)).toString();
        AdminOperationsApi.Result result = client(transport).recordDeliveryCorrection(
            SESSION, REPORT_ID, correction, reconfirmation()
        );
        assertEquals(CORRECTION_ID, result.mutationId());
        assertEquals(201, result.statusCode());
        assertEquals(2, transport.requests.size());
        FakeTransport.Request challenge = transport.requests.get(0);
        FakeTransport.Request post = transport.requests.get(1);
        JSONObject intent = new JSONObject(new String(challenge.body, StandardCharsets.UTF_8));
        assertEquals("report.delivery.correct", intent.getString("action"));
        assertEquals("/reports/" + REPORT_ID + "/delivery-corrections", intent.getString("path"));
        assertEquals("POST", post.method);
        assertEquals(ORIGIN + "/reports/" + REPORT_ID + "/delivery-corrections", post.url);
        assertEquals(NONCE, post.headers.get(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER));
        assertArrayEquals(correction.requestBody(), post.body);
        assertEquals(AdminCanonicalEncoding.sha256Hex(post.body), intent.getString("body_sha256"));
    }

    @Test
    public void s18_40_postRefusesConflictsMismatchesAndMissingReconfirmation() throws Exception {
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.WRONG_REPORT, "다른 건 회신"
        );
        FakeTransport conflict = new FakeTransport();
        conflict.correctionStatus = 409;
        assertThrows(AdminOperationsApi.MutationConflictException.class, () -> client(conflict)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation()));

        FakeTransport mismatched = new FakeTransport();
        Map<String, Object> response = correctionResponse(correction);
        response.put("memo", "서버가 바꾼 메모");
        mismatched.correctionResponse = new JSONObject(response).toString();
        assertThrows(IOException.class, () -> client(mismatched)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation()));

        FakeTransport noHeader = new FakeTransport();
        assertThrows(IllegalArgumentException.class, () -> client(noHeader)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, AdminJava8Collections.map()));
        assertTrue(noHeader.requests.isEmpty());
    }

    // --- review follow-up (w23 fix_admin_delivery) -------------------------------------

    @Test
    public void s18_40_f4_memoIsStrippedWithThePythonStrWhitespaceSet() {
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.OTHER,
            "\u3000 다른\u00a0건 회신\u00a0\u2003\u205f"
        );
        // Leading/trailing NBSP, ideographic and other Unicode spaces go; inner ones stay.
        assertEquals("다른\u00a0건 회신", correction.memo());
        assertTrue(new String(correction.requestBody(), StandardCharsets.UTF_8)
            .contains("\"memo\":\"다른\u00a0건 회신\""));
        assertEquals("메모", new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.OTHER,
            "\u0085\u1680\u2028\u2029\u202f메모\u3000\t\r\n"
        ).memo());
        for (String memo : new String[] {"\u3000", "\u00a0 \u2028", "\u205f\u3000\u1680", "\u2000\u200a"}) {
            assertThrows(memo, IllegalArgumentException.class, () -> new AdminDeliveryCorrection(
                CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.OTHER, memo
            ));
        }
        // Characters Python does not treat as whitespace are kept (zero-width space, BOM).
        assertEquals("\u200b메모\ufeff", new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.OTHER, "\u200b메모\ufeff"
        ).memo());
    }

    @Test
    public void s18_40_f4_serverStrippedUnicodeMemoMatchesTheRequest() throws Exception {
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.WRONG_REPORT,
            "\u3000다른 건 회신\u00a0"
        );
        FakeTransport transport = new FakeTransport();
        Map<String, Object> stored = correctionResponse(correction);
        stored.put("memo", "다른 건 회신"); // what Python str.strip() stores
        transport.correctionResponse = new JSONObject(stored).toString();
        AdminOperationsApi.Result result = client(transport).recordDeliveryCorrection(
            SESSION, REPORT_ID, correction, reconfirmation()
        );
        assertEquals(CORRECTION_ID, result.mutationId());
    }

    @Test
    public void s18_40_f1_auditParserAcceptsCorrectionRowsAndCorrectedEventActions() throws Exception {
        List<Object> items = new ArrayList<>();
        items.add(new JSONObject(auditEvent(
            "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f", "report.delivery.correct", "2026-09-30T01:03:00.000000Z"
        )));
        items.add(new JSONObject(auditEvent(
            eventId(3), "report.delivery.ACKNOWLEDGED.corrected", "2026-09-30T01:02:04.000000Z"
        )));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", AdminAuditModels.SCHEMA);
        root.put("items", new org.json.JSONArray(items));
        root.put("next_cursor", JSONObject.NULL);
        AdminAuditModels.Page page = AdminAuditModels.parsePage(new JSONObject(root).toString());
        assertEquals(2, page.items().size());
        assertEquals("report.delivery.correct", page.items().get(0).action());
        assertEquals("DELIVERY", page.items().get(0).eventType());
        assertEquals(REPORT_ID, page.items().get(0).resourceId());
        assertEquals("report.delivery.ACKNOWLEDGED.corrected", page.items().get(1).action());
    }

    @Test
    public void s18_40_f3_attemptKeepsTheIdForTheSameIntentAndRenewsItOtherwise() {
        String[] ids = {
            "10000000-0000-4000-8000-000000000001", "10000000-0000-4000-8000-000000000002",
            "10000000-0000-4000-8000-000000000003", "10000000-0000-4000-8000-000000000004",
            "10000000-0000-4000-8000-000000000005", "10000000-0000-4000-8000-000000000006",
            "10000000-0000-4000-8000-000000000007", "10000000-0000-4000-8000-000000000008"
        };
        int[] next = {0};
        AdminDeliveryCorrectionAttempt attempt = new AdminDeliveryCorrectionAttempt(() -> ids[next[0]++]);
        AdminDeliveryCorrection.Reason other = AdminDeliveryCorrection.Reason.OTHER;
        // Before any send the draft may change without spending an ID.
        assertEquals(ids[0], attempt.prepare(eventId(3), 3L, other, "초안").correctionId());
        AdminDeliveryCorrection first = attempt.prepare(eventId(3), 3L, other, "메모");
        assertEquals(ids[0], first.correctionId());
        assertFalse(attempt.uncertain());
        assertFalse(attempt.markSent(first));
        assertTrue(attempt.uncertain());
        // The same intent after an uncertain result keeps the ID and is a resend.
        AdminDeliveryCorrection again = attempt.prepare(eventId(3), 3L, other, "\u3000메모 ");
        assertEquals(ids[0], again.correctionId());
        assertTrue(attempt.markSent(again));
        // A changed target, expected revision, reason or memo gets a new ID.
        AdminDeliveryCorrection target = attempt.prepare(eventId(2), 3L, other, "메모");
        assertEquals(ids[1], target.correctionId());
        assertFalse(attempt.uncertain());
        assertFalse(attempt.markSent(target));
        AdminDeliveryCorrection revision = attempt.prepare(eventId(2), 4L, other, "메모");
        assertEquals(ids[2], revision.correctionId());
        attempt.markSent(revision);
        AdminDeliveryCorrection reason = attempt.prepare(
            eventId(2), 4L, AdminDeliveryCorrection.Reason.WRONG_STATUS, "메모"
        );
        assertEquals(ids[3], reason.correctionId());
        attempt.markSent(reason);
        AdminDeliveryCorrection memo = attempt.prepare(
            eventId(2), 4L, AdminDeliveryCorrection.Reason.WRONG_STATUS, "다른 메모"
        );
        assertEquals(ids[4], memo.correctionId());
        attempt.markSent(memo);
        // A server ID conflict, a confirmed success and a report change always renew it.
        attempt.renewAfterIdConflict();
        assertEquals(ids[5], attempt.id());
        assertFalse(attempt.uncertain());
        attempt.succeeded();
        assertEquals(ids[6], attempt.id());
        attempt.reset();
        assertEquals(ids[7], attempt.id());
        assertThrows(IllegalArgumentException.class, () -> attempt.markSent(first));
    }

    @Test
    public void s18_40_f3_aLoadedHistoryWithTheAttemptIdConfirmsTheUncertainCorrection() throws Exception {
        String[] ids = {"0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f", "20000000-0000-4000-8000-000000000002"};
        int[] next = {0};
        AdminDeliveryCorrectionAttempt attempt = new AdminDeliveryCorrectionAttempt(() -> ids[next[0]++]);
        attempt.markSent(attempt.prepare(eventId(2), 2L, AdminDeliveryCorrection.Reason.WRONG_STATUS, "메모"));
        FakeTransport unrelated = new FakeTransport();
        unrelated.history = page(
            item(1, "SUBMITTED", null),
            item(2, "ACKNOWLEDGED", correction(2, 2, "WRONG_STATUS"))
        );
        assertFalse(attempt.confirmedBy(
            client(unrelated).readDeliveries(SESSION, REPORT_ID, null).deliveryHistory()
        ));
        assertEquals(ids[0], attempt.id());
        Map<String, Object> recorded = correction(2, 2, "WRONG_STATUS");
        recorded.put("id", ids[0]);
        FakeTransport transport = new FakeTransport();
        transport.history = page(item(1, "SUBMITTED", null), item(2, "ACKNOWLEDGED", recorded));
        assertTrue(attempt.confirmedBy(
            client(transport).readDeliveries(SESSION, REPORT_ID, null).deliveryHistory()
        ));
        assertEquals(ids[1], attempt.id());
        assertFalse(attempt.uncertain());
    }

    @Test
    public void s18_40_f3_idConflictIsDistinguishedFromOtherConflicts() {
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.WRONG_REPORT, "다른 건 회신"
        );
        FakeTransport idConflict = new FakeTransport();
        idConflict.correctionStatus = 409;
        idConflict.correctionResponse = "{\"detail\":{\"code\":\"delivery_correction_idempotency_conflict\","
            + "\"message\":\"The correction ID was already used for a different correction.\"}}";
        assertThrows(AdminOperationsApi.CorrectionIdConflictException.class, () -> client(idConflict)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation()));
        for (String code : new String[] {
            "delivery_event_already_corrected", "delivery_correction_target_not_latest",
            "delivery_revision_conflict", "delivery_correction_conflict"
        }) {
            FakeTransport conflict = new FakeTransport();
            conflict.correctionStatus = 409;
            conflict.correctionResponse = "{\"detail\":{\"code\":\"" + code + "\",\"message\":\"x\"}}";
            assertThrows(code, AdminOperationsApi.MutationConflictException.class, () -> client(conflict)
                .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation(), true));
        }
    }

    @Test
    public void s18_40_f3_existingRecordFromAnEarlierSessionIsAcceptedOnlyOnAResend() throws Exception {
        AdminDeliveryCorrection correction = new AdminDeliveryCorrection(
            CORRECTION_ID, eventId(3), 3L, AdminDeliveryCorrection.Reason.WRONG_REPORT, "다른 건 회신"
        );
        Map<String, Object> earlier = correctionResponse(correction);
        earlier.put("session_id", "99999999-9999-4999-8999-999999999999");
        FakeTransport firstSend = new FakeTransport();
        firstSend.correctionResponse = new JSONObject(earlier).toString();
        assertThrows(IOException.class, () -> client(firstSend)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation()));
        assertThrows(IOException.class, () -> client(firstSend)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation(), false));
        FakeTransport resend = new FakeTransport();
        resend.correctionResponse = new JSONObject(earlier).toString();
        assertEquals(CORRECTION_ID, client(resend)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation(), true)
            .mutationId());
        // Even on a resend the stored intent and administrator must match.
        for (String[] change : new String[][] {
            {"admin_id", "admin-002"}, {"memo", "다른 메모"}, {"reason_code", "OTHER"},
            {"id", "12121212-1212-4212-8212-121212121212"}
        }) {
            Map<String, Object> changed = new LinkedHashMap<>(earlier);
            changed.put(change[0], change[1]);
            FakeTransport mismatch = new FakeTransport();
            mismatch.correctionResponse = new JSONObject(changed).toString();
            assertThrows(change[0], IOException.class, () -> client(mismatch)
                .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation(), true));
        }
        Map<String, Object> revision = new LinkedHashMap<>(earlier);
        revision.put("expected_revision", 4);
        FakeTransport revisionMismatch = new FakeTransport();
        revisionMismatch.correctionResponse = new JSONObject(revision).toString();
        assertThrows(IOException.class, () -> client(revisionMismatch)
            .recordDeliveryCorrection(SESSION, REPORT_ID, correction, reconfirmation(), true));
    }

    private static Map<String, Object> auditEvent(String eventId, String action, String occurredAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("event_id", eventId);
        value.put("event_type", "DELIVERY");
        value.put("action", action);
        value.put("outcome", "SUCCEEDED");
        value.put("actor_id", "admin-001");
        value.put("resource_type", "report");
        value.put("resource_id", REPORT_ID);
        value.put("occurred_at", occurredAt);
        value.put("correlation_id", "77777777-7777-4777-8777-777777777777");
        return value;
    }

    // --- fixtures ----------------------------------------------------------------------

    private static Map<String, String> reconfirmation() {
        return AdminJava8Collections.map(AdminHighRiskActionGate.RECONFIRMATION_NONCE_HEADER, NONCE);
    }

    private static String eventId(int revision) {
        return String.format("bbbbbbbb-bbbb-4bbb-8bbb-%012d", revision);
    }

    private static Map<String, Object> correction(int revision, int expectedRevision, String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema_version", AdminDeliveryCorrection.SCHEMA_VERSION);
        value.put("id", String.format("dddddddd-dddd-4ddd-8ddd-%012d", revision));
        value.put("report_id", REPORT_ID);
        value.put("corrects_event_id", eventId(revision));
        value.put("corrected_revision", revision);
        value.put("expected_revision", expectedRevision);
        value.put("reason_code", reason);
        value.put("memo", "관찰을 잘못 기록함");
        value.put("admin_id", "admin-001");
        value.put("session_id", AdminDeviceProofTest.SESSION_ID);
        value.put("device_id", AdminDeviceProofTest.DEVICE_ID);
        value.put("correlation_id", "77777777-7777-4777-8777-777777777777");
        value.put("created_at", "2026-09-30T01:02:05Z");
        return value;
    }

    private static Map<String, Object> correctionResponse(AdminDeliveryCorrection correction) {
        Map<String, Object> value = correction(3, 3, correction.reason().name());
        value.put("id", correction.correctionId());
        value.put("memo", correction.memo());
        return value;
    }

    private static Map<String, Object> item(int revision, String status, Map<String, Object> correction) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", eventId(revision));
        value.put("report_id", REPORT_ID);
        value.put("review_decision_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        value.put("package_id", PACKAGE_ID);
        value.put("package_revision", 1);
        value.put("revision", revision);
        value.put("institution", "서울시 도로관리과");
        value.put("channel", "phone");
        value.put("recipient", "duty officer");
        value.put("status", status);
        value.put(
            "external_receipt_id",
            "SUBMITTED".equals(status) || "FAILED".equals(status) ? JSONObject.NULL : "receipt-1"
        );
        value.put("reason", "manually recorded outside the app");
        value.put("evidence_sha256", JSONObject.NULL);
        value.put("observed_at", "2026-09-30T01:02:03Z");
        value.put("expected_revision", revision - 1);
        value.put("idempotency_key", String.format("55555555-5555-4555-8555-%012d", revision));
        value.put("admin_id", "admin-001");
        value.put("session_id", AdminDeviceProofTest.SESSION_ID);
        value.put("device_id", AdminDeviceProofTest.DEVICE_ID);
        value.put("correlation_id", "77777777-7777-4777-8777-777777777777");
        value.put("recorded_at", "2026-09-30T01:02:04Z");
        value.put("correction", correction == null ? JSONObject.NULL : new JSONObject(correction));
        return value;
    }

    @SafeVarargs
    private static String page(Map<String, Object>... items) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "walksafe.report-delivery-event-page.v1");
        root.put("report_id", REPORT_ID);
        root.put("snapshot_revision", items.length);
        root.put("total_count", items.length);
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> item : items) values.add(new JSONObject(item));
        root.put("items", new org.json.JSONArray(values));
        root.put("next_cursor", JSONObject.NULL);
        return new JSONObject(root).toString();
    }

    private static AdminOperationsHttpClient client(FakeTransport transport) {
        return new AdminOperationsHttpClient(
            ORIGIN,
            true,
            AdminDeviceProofTest.MARKER,
            1,
            payload -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            () -> AdminDeviceProofTest.ISSUED_AT + 1,
            transport
        );
    }

    private static final class FakeTransport implements AdminOperationsHttpClient.Transport {
        static final class Request {
            final String method;
            final String url;
            final Map<String, String> headers;
            final byte[] body;

            Request(String method, String url, Map<String, String> headers, byte[] body) {
                this.method = method;
                this.url = url;
                this.headers = headers;
                this.body = body == null ? new byte[0] : body.clone();
            }
        }

        final List<Request> requests = new ArrayList<>();
        String history;
        String correctionResponse;
        int correctionStatus = 201;

        @Override
        public AdminOperationsHttpClient.Response execute(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body
        ) throws IOException {
            Request request = new Request(method, url, headers, body);
            requests.add(request);
            try {
                if (url.endsWith(AdminOperationsHttpClient.CHALLENGE_PATH)) {
                    JSONObject intent = new JSONObject(new String(request.body, StandardCharsets.UTF_8));
                    Map<String, Object> exact18 = new LinkedHashMap<>();
                    exact18.put("action", intent.isNull("action") ? null : intent.getString("action"));
                    exact18.put("admin_id", intent.getString("admin_id"));
                    exact18.put("body_sha256", intent.getString("body_sha256"));
                    exact18.put("challenge_id", AdminDeviceProofTest.CHALLENGE_ID);
                    exact18.put("correlation_id", intent.getString("correlation_id"));
                    exact18.put("device_id", intent.getString("device_id"));
                    exact18.put("device_key_marker", intent.getString("device_key_marker"));
                    exact18.put("device_key_version", intent.getInt("device_key_version"));
                    exact18.put(
                        "expires_at_epoch_ms",
                        AdminDeviceProofTest.ISSUED_AT + AdminDeviceProof.CHALLENGE_TTL_MS
                    );
                    exact18.put("issued_at_epoch_ms", AdminDeviceProofTest.ISSUED_AT);
                    exact18.put("method", intent.getString("method"));
                    exact18.put("nonce", AdminDeviceProofTest.NONCE);
                    exact18.put("path", intent.getString("path"));
                    exact18.put("purpose", intent.getString("purpose"));
                    exact18.put("query_sha256", intent.getString("query_sha256"));
                    exact18.put(
                        "read_purpose",
                        intent.isNull("read_purpose") ? null : intent.getString("read_purpose")
                    );
                    exact18.put("schema_version", AdminDeviceProof.SCHEMA_VERSION);
                    exact18.put("session_id", intent.getString("session_id"));
                    Map<String, Object> exact19 = new LinkedHashMap<>(exact18);
                    exact19.put("signing_payload", AdminCanonicalEncoding.canonicalJson(exact18));
                    return new AdminOperationsHttpClient.Response(
                        200, AdminCanonicalEncoding.canonicalJson(exact19)
                    );
                }
            } catch (Exception error) {
                throw new IOException("fake challenge failed", error);
            }
            if ("GET".equals(method) && url.contains("/deliveries/history?")) {
                return new AdminOperationsHttpClient.Response(200, history);
            }
            if ("POST".equals(method) && url.endsWith("/delivery-corrections")) {
                return new AdminOperationsHttpClient.Response(
                    correctionStatus,
                    correctionResponse == null ? "{}" : correctionResponse
                );
            }
            return new AdminOperationsHttpClient.Response(500, "unexpected");
        }
    }
}
