package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import org.junit.Test;

public final class AdminIncidentModelsTest {
    private static final String INCIDENT = "11111111-1111-4111-8111-111111111111";
    private static final String EVENT = "22222222-2222-4222-8222-222222222222";
    private static final String SHA = "a".repeat(64);

    @Test
    public void parsesOnlyCriticalCurrentProjectionBoundToOpeningEvent() throws Exception {
        AdminIncidentModels.Detail detail = AdminIncidentModels.parseDetail(detailJson(), INCIDENT);

        assertEquals("CRITICAL", detail.summary().severity());
        assertEquals("OPEN", detail.summary().status());
        assertEquals("ACKNOWLEDGED", detail.allowedNextStates().get(0));
        assertEquals("OPENED", detail.events().get(0).eventType());
    }

    @Test
    public void rejectsNonCriticalUnknownFieldsAndBrokenHistory() {
        assertThrows(IOException.class, () -> AdminIncidentModels.parseDetail(
            detailJson().replace("\"CRITICAL\"", "\"HIGH\""), INCIDENT
        ));
        assertThrows(IOException.class, () -> AdminIncidentModels.parseDetail(
            detailJson().replace("\"events\":[", "\"unexpected\":true,\"events\":["), INCIDENT
        ));
        assertThrows(IOException.class, () -> AdminIncidentModels.parseDetail(
            detailJson().replace("\"status_version\":1", "\"status_version\":2"), INCIDENT
        ));
    }

    @Test
    public void parsesStrictBoundedHistoryPageAndRejectsSnapshotDrift() throws Exception {
        AdminIncidentModels.HistoryPage page = AdminIncidentModels.parseHistoryPage(
            historyJson(), INCIDENT
        );

        assertEquals(INCIDENT, page.incident().incidentId());
        assertEquals(1, page.snapshotRevision());
        assertEquals(1, page.totalCount());
        assertEquals(1, page.items().size());
        assertEquals("OPENED", page.items().get(0).eventType());

        assertThrows(IOException.class, () -> AdminIncidentModels.parseHistoryPage(
            historyJson().replace("\"total_count\":1", "\"total_count\":2"), INCIDENT
        ));
        assertThrows(IOException.class, () -> AdminIncidentModels.parseHistoryPage(
            historyJson().replace("\"items\":[", "\"private_log\":\"secret\",\"items\":["),
            INCIDENT
        ));
    }

    @Test
    public void statusRequestRequiresExactTransitionEvidenceAndCanonicalIdempotency() {
        AdminIncidentModels.StatusRequest request = new AdminIncidentModels.StatusRequest(
            "ACKNOWLEDGED",
            1,
            "33333333-3333-4333-8333-333333333333",
            "실제 장애를 확인했습니다.",
            "민감정보 없이 서비스 상태를 관찰했습니다.",
            SHA
        );
        assertEquals("ACKNOWLEDGED", request.nextState());
        assertThrows(IllegalArgumentException.class, () -> new AdminIncidentModels.StatusRequest(
            "ACKNOWLEDGED", 1, "not-a-uuid", "짧음", "관찰 내용입니다.", SHA
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminIncidentModels.StatusRequest(
            "ACKNOWLEDGED", 1, "33333333-3333-4333-8333-333333333333",
            "충분한 상태 변경 사유입니다.", "충분한 관찰 내용입니다.", "A".repeat(64)
        ));
        assertThrows(IllegalArgumentException.class, () -> new AdminIncidentModels.StatusRequest(
            "OPEN", 1, "33333333-3333-4333-8333-333333333333",
            "충분한 상태 변경 사유입니다.", "충분한 관찰 내용입니다.", SHA
        ));
    }

    @Test
    public void stateMachineHasNoSkippedTransitions() {
        assertEquals("ACKNOWLEDGED", AdminIncidentModels.expectedNextState("OPEN"));
        assertEquals("RESOLVED", AdminIncidentModels.expectedNextState("ACKNOWLEDGED"));
        assertEquals("REOPENED", AdminIncidentModels.expectedNextState("RESOLVED"));
        assertEquals("ACKNOWLEDGED", AdminIncidentModels.expectedNextState("REOPENED"));
    }

    static String detailJson() {
        return "{\"schema_version\":\"walksafe.admin-incident-detail.v1\","
            + "\"incident_id\":\"" + INCIDENT + "\",\"severity\":\"CRITICAL\","
            + "\"status\":\"OPEN\",\"status_version\":1,"
            + "\"reason_code\":\"USER_SAFETY_RISK\",\"summary\":\"안전 안내 기능 중단\","
            + "\"started_at\":\"2026-08-29T00:00:00Z\","
            + "\"detected_at\":\"2026-08-29T00:01:00Z\","
            + "\"updated_at\":\"2026-08-29T00:01:00Z\","
            + "\"allowed_next_states\":[\"ACKNOWLEDGED\"],\"events\":[{"
            + "\"event_id\":\"" + EVENT + "\",\"revision\":1,\"event_type\":\"OPENED\","
            + "\"previous_state\":null,\"next_state\":\"OPEN\","
            + "\"reason\":\"USER_SAFETY_RISK\",\"observation\":\"안전 안내 기능 중단\","
            + "\"evidence_sha256\":\"" + SHA + "\","
            + "\"observed_at\":\"2026-08-29T00:01:00Z\","
            + "\"recorded_at\":\"2026-08-29T00:01:01Z\",\"actor_id\":null}]}";
    }

    static String historyJson() {
        return "{\"schema_version\":\"walksafe.admin-incident-history-page.v1\","
            + "\"incident\":{"
            + "\"incident_id\":\"" + INCIDENT + "\",\"severity\":\"CRITICAL\","
            + "\"status\":\"OPEN\",\"status_version\":1,"
            + "\"reason_code\":\"USER_SAFETY_RISK\",\"summary\":\"안전 안내 기능 중단\","
            + "\"started_at\":\"2026-08-29T00:00:00Z\","
            + "\"detected_at\":\"2026-08-29T00:01:00Z\","
            + "\"updated_at\":\"2026-08-29T00:01:00Z\"},"
            + "\"allowed_next_states\":[\"ACKNOWLEDGED\"],"
            + "\"snapshot_revision\":1,\"total_count\":1,\"items\":[{"
            + "\"event_id\":\"" + EVENT + "\",\"revision\":1,\"event_type\":\"OPENED\","
            + "\"previous_state\":null,\"next_state\":\"OPEN\","
            + "\"reason\":\"USER_SAFETY_RISK\",\"observation\":\"안전 안내 기능 중단\","
            + "\"evidence_sha256\":\"" + SHA + "\","
            + "\"observed_at\":\"2026-08-29T00:01:00Z\","
            + "\"recorded_at\":\"2026-08-29T00:01:01Z\",\"actor_id\":null}],"
            + "\"next_cursor\":null}";
    }
}
