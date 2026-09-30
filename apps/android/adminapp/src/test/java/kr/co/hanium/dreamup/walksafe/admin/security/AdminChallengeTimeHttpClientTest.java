package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public final class AdminChallengeTimeHttpClientTest {
    private static final String ORIGIN = "http://127.0.0.1:8000";
    private static final AdminOperationsApi.SessionContext SESSION =
        new AdminOperationsApi.SessionContext(
            "t".repeat(48),
            "admin-001",
            "22222222-2222-4222-8222-222222222222",
            "device-12345678"
        );

    @Test
    public void forwardAndBackwardWallJumpsAfterReceiptDoNotChangeLiveProof() throws Exception {
        for (long jumpedWall : new long[] {
            AdminDeviceProofTest.ISSUED_AT + 600_000L,
            AdminDeviceProofTest.ISSUED_AT - 600_000L
        }) {
            JumpingClock clock = new JumpingClock(jumpedWall, 1_000_000_000L);
            ChallengeTransport transport = new ChallengeTransport();

            client(clock, transport).list(SESSION, new AdminIncidentModels.Filters(null), null);

            assertEquals(jumpedWall, clock.wallEpochMs);
            assertEquals(1, clock.wallSamples);
            assertEquals(2, clock.monotonicSamples);
            assertEquals(2, transport.requests);
        }
    }

    @Test
    public void proofIsAcceptedBeforeTtlAndRejectedAtExactTtlWithoutProtectedRequest() throws Exception {
        JumpingClock liveClock = new JumpingClock(AdminDeviceProofTest.ISSUED_AT, 119_998_000_000L);
        ChallengeTransport liveTransport = new ChallengeTransport();
        client(liveClock, liveTransport).list(SESSION, new AdminIncidentModels.Filters(null), null);
        assertEquals(2, liveTransport.requests);

        JumpingClock expiredClock = new JumpingClock(AdminDeviceProofTest.ISSUED_AT, 119_999_000_000L);
        ChallengeTransport expiredTransport = new ChallengeTransport();
        assertThrows(AdminDeviceProof.ChallengeTimeException.class, () ->
            client(expiredClock, expiredTransport).list(
                SESSION, new AdminIncidentModels.Filters(null), null
            )
        );
        assertEquals(1, expiredTransport.requests);
    }

    @Test
    public void ordinaryProofAndInvalidElapsedAreHandledWithoutDispatchingOnFailure() throws Exception {
        JumpingClock clock = new JumpingClock(AdminDeviceProofTest.ISSUED_AT + 1L, 0L);
        ChallengeTransport transport = new ChallengeTransport();
        client(clock, transport).list(SESSION, new AdminIncidentModels.Filters(null), null);
        assertEquals(2, transport.requests);

        assertThrows(IOException.class, () -> new AdminChallengeTime(1L, 10L).estimatedNowEpochMs(9L));
        assertThrows(IOException.class, () -> new AdminChallengeTime(1L, Long.MAX_VALUE)
            .estimatedNowEpochMs(Long.MIN_VALUE));
        assertThrows(IOException.class, () -> new AdminChallengeTime(Long.MAX_VALUE, 0L)
            .estimatedNowEpochMs(1_000_000L));
    }

    private static AdminIncidentHttpClient client(
        JumpingClock clock,
        ChallengeTransport transport
    ) {
        return new AdminIncidentHttpClient(
            ORIGIN,
            true,
            AdminDeviceProofTest.MARKER,
            1,
            ignored -> new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01},
            clock,
            transport
        );
    }

    private static final class JumpingClock implements AdminIncidentHttpClient.Clock {
        private final long jumpedWall;
        private final long elapsedNanos;
        long wallEpochMs = AdminDeviceProofTest.ISSUED_AT + 1L;
        int wallSamples;
        int monotonicSamples;

        JumpingClock(long jumpedWall, long elapsedNanos) {
            this.jumpedWall = jumpedWall;
            this.elapsedNanos = elapsedNanos;
        }

        @Override public long nowEpochMs() {
            wallSamples++;
            return wallEpochMs;
        }

        @Override public long monotonicNanos() {
            monotonicSamples++;
            if (monotonicSamples == 2) {
                wallEpochMs = jumpedWall;
                return elapsedNanos;
            }
            return 0L;
        }
    }

    private static final class ChallengeTransport implements AdminIncidentHttpClient.Transport {
        int requests;

        @Override public AdminIncidentHttpClient.Response execute(
            String method, String url, Map<String, String> headers, byte[] body
        ) throws IOException {
            requests++;
            if (url.endsWith(AdminIncidentHttpClient.CHALLENGE_PATH)) {
                Map<String, Object> fields = AdminStrictJson.parseObject(
                    new String(body, StandardCharsets.UTF_8)
                );
                AdminDeviceProof.Intent intent = new AdminDeviceProof.Intent(
                    (String) fields.get("action"),
                    (String) fields.get("admin_id"),
                    (String) fields.get("body_sha256"),
                    (String) fields.get("correlation_id"),
                    (String) fields.get("device_id"),
                    (String) fields.get("device_key_marker"),
                    ((Long) fields.get("device_key_version")).intValue(),
                    (String) fields.get("method"),
                    (String) fields.get("path"),
                    AdminDeviceProof.Purpose.valueOf((String) fields.get("purpose")),
                    (String) fields.get("query_sha256"),
                    (String) fields.get("read_purpose"),
                    (String) fields.get("session_id")
                );
                return new AdminIncidentHttpClient.Response(
                    200, AdminDeviceProofTest.challengeResponse(intent, AdminDeviceProofTest.ISSUED_AT, null)
                );
            }
            return new AdminIncidentHttpClient.Response(
                200, "{\"schema_version\":\"walksafe.admin-incident-list.v1\",\"items\":[],\"next_cursor\":null}"
            );
        }
    }
}
