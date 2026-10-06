package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AdminCancellationDeviceTest {
    @Test public void cancelledChallengeStopsReportAndIncidentRequestsWithoutAffectingOtherOwners() throws Exception {
        for (boolean report : new boolean[] {true, false}) {
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                server.enqueue(new MockResponse.Builder().code(200).bodyDelay(5, TimeUnit.SECONDS).body("delayed challenge").build());
                var descriptor = new AdminDeviceKeyStore.Descriptor("a".repeat(64), 1, "test-only");
                AdminDeviceProof.Signer unused = payload -> { throw new AssertionError("cancelled challenge must never sign"); };
                var reports = new AdminReportHttpClient(server.url("/").toString(), true, descriptor, unused);
                var incidents = new AdminIncidentHttpClient(server.url("/").toString(), true, descriptor, unused);
                var session = new AdminOperationsApi.SessionContext("synthetic-cancellation-token-000000000000000000", "admin-001",
                    "66666666-6666-4666-8666-666666666666", "device-cancellation-only");
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try {
                        if (report) reports.detail(session, AdminDeviceFixtures.REPORT_ID);
                        else incidents.detail(session, AdminDeviceFixtures.INCIDENT_ID);
                    } catch (Throwable error) { failure.set(error); }
                });
                worker.start();
                try {
                    assertNotNull("request must reach socket before cancellation", server.takeRequest(3, TimeUnit.SECONDS));
                    if (report) reports.cancelInFlightRequests(); else incidents.cancelInFlightRequests();
                    worker.join(2000);
                    assertFalse("cancel must not wait for five-second response", worker.isAlive());
                    assertTrue(String.valueOf(failure.get()), failure.get() instanceof IOException);
                    assertThrows(IOException.class, () -> {
                        if (report) reports.detail(session, AdminDeviceFixtures.REPORT_ID);
                        else incidents.detail(session, AdminDeviceFixtures.INCIDENT_ID);
                    });
                    assertEquals(1, server.getRequestCount());
                    server.enqueue(new MockResponse.Builder().code(200).body("alive").build());
                    assertEquals(200, new AdminOkHttpTransport().execute("GET", server.url("/other-owner").toString(),
                        Map.of(), null, 1024, "cancelled", "no progress", "too large").statusCode);
                    assertEquals(2, server.getRequestCount());
                } finally {
                    reports.cancelInFlightRequests(); incidents.cancelInFlightRequests();
                    worker.interrupt(); worker.join(2000);
                }
            }
        }
    }
}
