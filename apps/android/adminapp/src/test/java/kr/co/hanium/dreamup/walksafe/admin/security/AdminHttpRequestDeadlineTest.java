package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/**
 * S18-19: the operations and raw-collection HttpURLConnection transports now have the same
 * overall per-request deadline as the security client (20 s = connect 8 s + read 12 s), a
 * scheduled disconnect, an explicit cancellation and no automatic re-send of a write.
 * R14 (adm-F1): only the one-time original image GET has 20 s + expected bytes / 64 KiB/s
 * (at most 150 s); JSON and writes keep 20 s.
 */
public final class AdminHttpRequestDeadlineTest {
    private static final String URL_TEXT = "http://127.0.0.1:8000/admin/deadline-test";
    private static final long BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(20_000);
    private static final int EIGHT_MIB = 8 * 1024 * 1024;
    private static final long FLOOR_BYTES_PER_SECOND = 64L * 1024L;

    @Test
    public void operationsTransportRejectsSlowDripJsonAtTheOverallDeadline() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger reads = new AtomicInteger();
        FakeConnection connection = fixture.add(new FakeConnection(new InputStream() {
            @Override public int read() {
                fixture.clock.advanceMillis(5_000);
                reads.incrementAndGet();
                return ' ';
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                if (length == 0) return 0;
                buffer[offset] = (byte) read();
                return 1;
            }
        }));

        SocketTimeoutException error = assertThrows(SocketTimeoutException.class,
            () -> fixture.operations().execute("GET", URL_TEXT, Collections.emptyMap(), null));

        assertEquals(AdminHttpRequestDeadline.DEADLINE_MESSAGE, error.getMessage());
        assertTrue(error.getMessage().contains("server outcome unknown"));
        assertEquals(4, reads.get());
        assertEquals(1, connection.disconnects.get());
        assertEquals(BUDGET_NANOS, fixture.scheduler.tasks.get(0).delayNanos);
        assertTrue(fixture.scheduler.tasks.get(0).cancelled);
        assertEquals(0, fixture.deadline.inFlightCount());
    }

    @Test
    public void operationsTransportKeepsNormalResponsesAndThePhaseTimeouts() throws Exception {
        Fixture fixture = new Fixture();
        FakeConnection connection = fixture.add(new FakeConnection("{\"ok\":true}"));
        byte[] body = "{\"decision\":\"VALID\"}".getBytes(StandardCharsets.UTF_8);

        AdminOperationsHttpClient.Response response = fixture.operations().execute(
            "POST", URL_TEXT, Collections.singletonMap("Accept", "application/json"), body
        );

        assertEquals(200, response.statusCode);
        assertEquals("{\"ok\":true}", response.body);
        assertEquals("POST", connection.getRequestMethod());
        assertEquals("application/json", connection.getRequestProperty("Accept"));
        assertArrayEquals(body, connection.sentBody.toByteArray());
        assertEquals(8_000, connection.getConnectTimeout());
        assertEquals(12_000, connection.getReadTimeout());
        assertFalse(connection.getInstanceFollowRedirects());
        assertFalse(connection.getUseCaches());
        assertTrue(connection.getDoOutput());
        assertEquals(1, connection.disconnects.get());
        // R14: a write keeps the 20 s request budget.
        assertEquals(BUDGET_NANOS, fixture.scheduler.tasks.get(0).delayNanos);
        assertTrue(fixture.scheduler.tasks.get(0).cancelled);
        assertEquals(0, fixture.deadline.inFlightCount());
    }

    @Test(timeout = 5_000)
    public void operationsTransportBlockedWriteTimesOutOnceAndIsNeverReplayed() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        FakeConnection blocked = fixture.add(FakeConnection.blocking("write", entered));
        FakeConnection nextRead = fixture.add(new FakeConnection("{\"state\":\"COMMITTED\"}"));
        AdminOperationsHttpClient.UrlConnectionTransport transport = fixture.operations();
        byte[] body = "{\"decision_id\":\"same-intent\"}".getBytes(StandardCharsets.UTF_8);

        ExecutionException failure = fixture.runBlocked(entered,
            () -> transport.execute("POST", URL_TEXT, Collections.emptyMap(), body));

        assertTrue(failure.getCause() instanceof SocketTimeoutException);
        assertEquals(1, fixture.opens.get());
        assertEquals(1, blocked.outputOpens.get());
        assertEquals(1, blocked.disconnects.get());
        assertTrue(fixture.scheduler.tasks.get(0).cancelled);
        // The caller decides what to do next; a later read is a new, separate GET.
        assertEquals("{\"state\":\"COMMITTED\"}",
            transport.execute("GET", URL_TEXT, Collections.emptyMap(), null).body);
        assertEquals(2, fixture.opens.get());
        assertEquals(0, nextRead.outputOpens.get());
    }

    @Test(timeout = 5_000)
    public void operationsTransportStalledHeadersTimeOut() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        FakeConnection blocked = fixture.add(FakeConnection.blocking("headers", entered));

        ExecutionException failure = fixture.runBlocked(entered,
            () -> fixture.operations().execute("GET", URL_TEXT, Collections.emptyMap(), null));

        assertTrue(failure.getCause() instanceof SocketTimeoutException);
        assertEquals(1, blocked.disconnects.get());
    }

    @Test(timeout = 5_000)
    public void operationsOriginalImageGetHasItsOwnOverallDeadline() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        FakeConnection blocked = fixture.add(FakeConnection.blocking("body", entered));
        blocked.contentLength = 16;

        ExecutionException failure = fixture.runBlocked(entered,
            () -> fixture.operations().executeBinary("GET", URL_TEXT, Collections.emptyMap(), 16));

        assertTrue(failure.getCause() instanceof SocketTimeoutException);
        assertEquals(1, blocked.disconnects.get());
        assertTrue(fixture.scheduler.tasks.get(0).cancelled);
        // R14: 20 s + 16 B / 64 KiB/s (244 140 ns, rounded down).
        assertEquals(BUDGET_NANOS + 244_140L, fixture.scheduler.tasks.get(0).delayNanos);
    }

    @Test
    public void operationsOriginalImageFinishedAfterTheDeadlineIsRejected() throws Exception {
        Fixture fixture = new Fixture();
        byte[] image = new byte[] {1, 2, 3, 4};
        FakeConnection connection = fixture.add(new FakeConnection(new ByteArrayInputStream(image) {
            @Override public void close() {
                // R14: the 4-byte image budget is 20 s + 61 035 ns; this cleanup ends past it.
                fixture.clock.advanceMillis(20_001);
            }
        }));
        connection.contentLength = image.length;

        assertThrows(SocketTimeoutException.class,
            () -> fixture.operations().executeBinary("GET", URL_TEXT, Collections.emptyMap(), image.length));

        assertEquals(1, connection.disconnects.get());
    }

    @Test
    public void r14EightMibOriginalImageAtTheFloorRateArrivesWithinItsBudget() throws Exception {
        Fixture fixture = new Fixture();
        // 19 s of the 20 s allowance goes to opening the connection and waiting for headers.
        fixture.openDelayMillis = 19_000;
        AtomicLong delivered = new AtomicLong();
        FakeConnection connection = fixture.add(new FakeConnection(
            drip(fixture.clock, EIGHT_MIB, FLOOR_BYTES_PER_SECOND, delivered)
        ));
        connection.contentLength = EIGHT_MIB;

        try (AdminOperationsHttpClient.BinaryResponse response = fixture.operations().executeBinary(
            "GET", URL_TEXT, Collections.emptyMap(), EIGHT_MIB
        )) {
            assertEquals(200, response.statusCode);
            byte[] body = response.takeBody();
            assertEquals(EIGHT_MIB, body.length);
            assertEquals((byte) 0x5a, body[EIGHT_MIB - 1]);
        }

        assertEquals(EIGHT_MIB, delivered.get());
        // Budget = 20 s + 8 MiB / 64 KiB/s = 148 s; 19 s were spent before the alarm was armed.
        assertEquals(TimeUnit.SECONDS.toNanos(129), fixture.scheduler.tasks.get(0).delayNanos);
        assertEquals(TimeUnit.SECONDS.toNanos(147), fixture.clock.nanoTime());
        assertTrue(fixture.scheduler.tasks.get(0).cancelled);
        assertEquals(1, connection.disconnects.get());
        assertEquals(0, fixture.deadline.inFlightCount());
    }

    @Test
    public void r14OriginalImageSlowerThanTheFloorRateTimesOutAsOutcomeUnknown() throws Exception {
        Fixture fixture = new Fixture();
        AtomicLong delivered = new AtomicLong();
        FakeConnection connection = fixture.add(new FakeConnection(
            drip(fixture.clock, EIGHT_MIB, 48L * 1024L, delivered)
        ));
        connection.contentLength = EIGHT_MIB;

        SocketTimeoutException error = assertThrows(SocketTimeoutException.class,
            () -> fixture.operations().executeBinary("GET", URL_TEXT, Collections.emptyMap(), EIGHT_MIB));

        assertEquals(AdminHttpRequestDeadline.DEADLINE_MESSAGE, error.getMessage());
        assertTrue(error.getMessage().contains("server outcome unknown"));
        assertTrue(delivered.get() < EIGHT_MIB);
        assertTrue(fixture.clock.nanoTime() >= TimeUnit.SECONDS.toNanos(148));
        assertTrue(fixture.clock.nanoTime() < TimeUnit.SECONDS.toNanos(149));
        assertEquals(1, fixture.opens.get());
        assertEquals(1, connection.disconnects.get());
        assertEquals(0, fixture.deadline.inFlightCount());
    }

    @Test
    public void r14OriginalImageBudgetFormulaAndCap() throws Exception {
        // Reflection keeps this class compilable against the pre-R14 code (red = NoSuchMethod).
        java.lang.reflect.Method budget = AdminHttpRequestDeadline.class
            .getDeclaredMethod("originalEvidenceBudgetNanos", int.class);
        budget.setAccessible(true);
        assertEquals(BUDGET_NANOS + 15_258L, budget.invoke(null, 1));
        assertEquals(BUDGET_NANOS + TimeUnit.SECONDS.toNanos(1), budget.invoke(null, 65_536));
        assertEquals(TimeUnit.SECONDS.toNanos(148), budget.invoke(null, EIGHT_MIB));
        assertEquals(TimeUnit.SECONDS.toNanos(150), budget.invoke(null, 9 * 1024 * 1024));
        assertEquals(TimeUnit.SECONDS.toNanos(150), budget.invoke(null, Integer.MAX_VALUE));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> budget.invoke(null, 0));
    }

    /** {@code total} bytes at {@code bytesPerSecond} on the fake clock, then end of stream. */
    private static InputStream drip(FakeClock clock, int total, long bytesPerSecond, AtomicLong delivered) {
        return new InputStream() {
            @Override public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                long left = total - delivered.get();
                if (left <= 0L) return -1;
                if (length == 0) return 0;
                int count = (int) Math.min(length, left);
                clock.advanceNanos(count * 1_000_000_000L / bytesPerSecond);
                java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0x5a);
                delivered.addAndGet(count);
                return count;
            }
        };
    }

    @Test
    public void openingTheConnectionIsChargedToTheSameBudget() throws Exception {
        Fixture fixture = new Fixture();
        fixture.openDelayMillis = 8_000;
        fixture.add(new FakeConnection(new InputStream() {
            @Override public int read() {
                fixture.clock.advanceMillis(12_000);
                return -1;
            }
        }));

        assertThrows(SocketTimeoutException.class,
            () -> fixture.raw().execute("GET", URL_TEXT, Collections.emptyMap(), null));

        assertEquals(TimeUnit.MILLISECONDS.toNanos(12_000), fixture.scheduler.tasks.get(0).delayNanos);
    }

    @Test
    public void rawTransportKeepsHeadersAndRejectsSlowBodyAtTheDeadline() throws Exception {
        Fixture fixture = new Fixture();
        FakeConnection ok = fixture.add(new FakeConnection("{\"items\":[]}"));
        AdminRawCollectionHttpClient.Response response =
            fixture.raw().execute("GET", URL_TEXT, Collections.emptyMap(), null);
        assertEquals(200, response.statusCode);
        assertEquals("{\"items\":[]}", new String(response.body, StandardCharsets.UTF_8));
        assertEquals("application/json", response.contentType);
        assertEquals("no-store", response.cacheControl);
        assertEquals("no-cache", response.pragma);
        assertEquals(8_000, ok.getConnectTimeout());
        assertEquals(12_000, ok.getReadTimeout());
        assertFalse(ok.getInstanceFollowRedirects());
        assertEquals(1, ok.disconnects.get());

        FakeConnection slow = fixture.add(new FakeConnection(new InputStream() {
            @Override public int read() {
                fixture.clock.advanceMillis(20_000);
                return -1;
            }
        }));
        assertThrows(SocketTimeoutException.class,
            () -> fixture.raw().execute("GET", URL_TEXT, Collections.emptyMap(), null));
        assertEquals(1, slow.disconnects.get());
    }

    @Test(timeout = 5_000)
    public void rawTransportBlockedLegalHoldWriteTimesOutWithoutReplay() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        FakeConnection blocked = fixture.add(FakeConnection.blocking("write", entered));
        byte[] body = "{\"legal_hold\":true}".getBytes(StandardCharsets.UTF_8);

        ExecutionException failure = fixture.runBlocked(entered,
            () -> fixture.raw().execute("POST", URL_TEXT, Collections.emptyMap(), body));

        assertTrue(failure.getCause() instanceof SocketTimeoutException);
        assertEquals(1, fixture.opens.get());
        assertEquals(1, blocked.outputOpens.get());
        assertEquals(1, blocked.disconnects.get());
    }

    @Test(timeout = 5_000)
    public void explicitCancellationFailsTheInFlightRequestWithoutWaitingForTheDeadline()
        throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        FakeConnection blocked = fixture.add(FakeConnection.blocking("headers", entered));
        AdminRawCollectionHttpClient.UrlConnectionTransport transport = fixture.raw();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> call = worker.submit(() ->
                transport.execute("POST", URL_TEXT, Collections.emptyMap(), new byte[] {1}));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, fixture.deadline.inFlightCount());

            transport.cancelInFlight();

            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> call.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertFalse(failure.getCause() instanceof SocketTimeoutException);
            assertEquals(AdminHttpRequestDeadline.CANCELLED_MESSAGE, failure.getCause().getMessage());
            assertEquals(1, blocked.disconnects.get());
            assertEquals(0, fixture.deadline.inFlightCount());
            assertEquals(1, fixture.opens.get());
        } finally {
            blocked.disconnected.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void anOldCancelledAlarmCannotDisconnectTheNextRequest() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(new FakeConnection("first"));
        FakeConnection next = fixture.add(new FakeConnection("next"));
        next.responseBody = new ByteArrayInputStream("next".getBytes(StandardCharsets.UTF_8)) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                fixture.scheduler.tasks.get(0).fireEvenIfCancelled();
                assertEquals(0, next.disconnects.get());
                return super.read(buffer, offset, length);
            }
        };
        AdminOperationsHttpClient.UrlConnectionTransport transport = fixture.operations();

        assertEquals("first", transport.execute("GET", URL_TEXT, Collections.emptyMap(), null).body);
        fixture.clock.advanceMillis(20_000);
        assertEquals("next", transport.execute("GET", URL_TEXT, Collections.emptyMap(), null).body);

        assertEquals(1, next.disconnects.get());
        assertTrue(fixture.scheduler.tasks.get(1).cancelled);
    }

    @Test
    public void admF2CancellationIsStickyForTheNextRequestOfTheSameOperation() throws Exception {
        // Review probe (sweep/w25/review-adminapp/probes): challenge done, close, then the write.
        Fixture fixture = new Fixture();
        fixture.add(new FakeConnection("{\"challenge\":1}"));
        FakeConnection write = fixture.add(new FakeConnection("{\"ok\":true}"));
        AdminOperationsHttpClient.UrlConnectionTransport transport = fixture.operations();
        transport.execute("POST", URL_TEXT, Collections.emptyMap(), new byte[] {1});

        transport.cancelInFlight();

        IOException error = assertThrows(IOException.class,
            () -> transport.execute("POST", URL_TEXT, Collections.emptyMap(), new byte[] {2}));
        assertFalse(error instanceof SocketTimeoutException);
        assertEquals(AdminHttpRequestDeadline.CANCELLED_MESSAGE, error.getMessage());
        IOException image = assertThrows(IOException.class,
            () -> transport.executeBinary("GET", URL_TEXT, Collections.emptyMap(), 16));
        assertEquals(AdminHttpRequestDeadline.CANCELLED_MESSAGE, image.getMessage());
        assertEquals(1, fixture.opens.get());
        assertEquals(0, write.outputOpens.get());
        assertEquals(0, write.sentBody.size());
        assertEquals(1, fixture.scheduler.tasks.size());
        assertEquals(0, fixture.deadline.inFlightCount());
    }

    @Test
    public void admF2RawTransportCancellationIsStickyToo() throws Exception {
        Fixture fixture = new Fixture();
        FakeConnection write = fixture.add(new FakeConnection("{\"ok\":true}"));
        AdminRawCollectionHttpClient.UrlConnectionTransport transport = fixture.raw();

        transport.cancelInFlight();

        IOException error = assertThrows(IOException.class,
            () -> transport.execute("POST", URL_TEXT, Collections.emptyMap(), new byte[] {1}));
        assertEquals(AdminHttpRequestDeadline.CANCELLED_MESSAGE, error.getMessage());
        assertEquals(0, fixture.opens.get());
        assertEquals(0, write.outputOpens.get());
        assertTrue(fixture.scheduler.tasks.isEmpty());
    }

    @Test
    public void admF2CancellationThatRacesWithRegistrationStillWins() throws Exception {
        Fixture fixture = new Fixture();
        FakeConnection write = fixture.add(new FakeConnection("{\"ok\":true}"));
        AdminOperationsHttpClient.UrlConnectionTransport transport = fixture.operations();
        // close() lands after the pre-open check but before the request is registered.
        fixture.onOpen = transport::cancelInFlight;

        IOException error = assertThrows(IOException.class,
            () -> transport.execute("POST", URL_TEXT, Collections.emptyMap(), new byte[] {1}));

        assertFalse(error instanceof SocketTimeoutException);
        assertEquals(AdminHttpRequestDeadline.CANCELLED_MESSAGE, error.getMessage());
        assertEquals(1, fixture.opens.get());
        assertEquals(0, write.outputOpens.get());
        assertEquals(1, write.disconnects.get());
        assertTrue(fixture.scheduler.tasks.isEmpty());
        assertEquals(0, fixture.deadline.inFlightCount());
    }

    @Test
    public void admF5WritesUseFixedLengthStreamingSoThePlatformKeepsNoReplayableCopy() throws Exception {
        Fixture fixture = new Fixture();
        FakeConnection operationsWrite = fixture.add(new FakeConnection("{\"ok\":true}"));
        FakeConnection rawWrite = fixture.add(new FakeConnection("{\"ok\":true}"));
        FakeConnection emptyWrite = fixture.add(new FakeConnection("{\"ok\":true}"));
        FakeConnection read = fixture.add(new FakeConnection("{\"ok\":true}"));
        FakeConnection image = fixture.add(new FakeConnection(new ByteArrayInputStream(new byte[] {7, 7})));
        image.contentLength = 2;
        byte[] body = "{\"decision\":\"VALID\"}".getBytes(StandardCharsets.UTF_8);

        fixture.operations().execute("POST", URL_TEXT, Collections.emptyMap(), body);
        fixture.raw().execute("POST", URL_TEXT, Collections.emptyMap(), new byte[] {1, 2, 3});
        fixture.operations().execute("POST", URL_TEXT, Collections.emptyMap(), new byte[0]);
        fixture.operations().execute("GET", URL_TEXT, Collections.emptyMap(), null);
        fixture.operations().executeBinary("GET", URL_TEXT, Collections.emptyMap(), 2).close();

        assertEquals(body.length, operationsWrite.streamingLength());
        assertEquals("set before the body stream is opened", body.length, operationsWrite.streamingLengthAtOutputOpen);
        assertArrayEquals(body, operationsWrite.sentBody.toByteArray());
        assertEquals(3, rawWrite.streamingLength());
        assertEquals(3, rawWrite.streamingLengthAtOutputOpen);
        assertEquals(0, emptyWrite.streamingLength());
        assertEquals(-1, read.streamingLength());
        assertEquals(0, read.outputOpens.get());
        assertEquals(-1, image.streamingLength());
    }

    private interface BlockedCall {
        Object call() throws Exception;
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final FakeScheduler scheduler = new FakeScheduler();
        final Deque<FakeConnection> connections = new ArrayDeque<>();
        final List<FakeConnection> created = new ArrayList<>();
        final AtomicInteger opens = new AtomicInteger();
        volatile long openDelayMillis;
        /** Runs after the connection object exists but before the transport registers it. */
        volatile Runnable onOpen = () -> { };
        final AdminHttpRequestDeadline deadline = new AdminHttpRequestDeadline(url -> {
            opens.incrementAndGet();
            clock.advanceMillis(openDelayMillis);
            FakeConnection connection = connections.removeFirst();
            onOpen.run();
            return connection;
        }, clock, scheduler);

        FakeConnection add(FakeConnection connection) {
            connections.addLast(connection);
            created.add(connection);
            return connection;
        }

        AdminOperationsHttpClient.UrlConnectionTransport operations() {
            return new AdminOperationsHttpClient.UrlConnectionTransport(deadline);
        }

        AdminRawCollectionHttpClient.UrlConnectionTransport raw() {
            return new AdminRawCollectionHttpClient.UrlConnectionTransport(deadline);
        }

        /** Runs a call that blocks inside the fake connection, then fires the 20 s alarm. */
        ExecutionException runBlocked(CountDownLatch entered, BlockedCall call) throws Exception {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Object> result = worker.submit(call::call);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                clock.advanceMillis(20_000);
                scheduler.tasks.get(scheduler.tasks.size() - 1).fireEvenIfCancelled();
                return assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            } finally {
                for (FakeConnection connection : created) connection.disconnected.countDown();
                worker.shutdownNow();
            }
        }
    }

    private static final class FakeClock implements AdminHttpRequestDeadline.MonotonicClock {
        private final AtomicLong nanos = new AtomicLong();

        @Override public long nanoTime() { return nanos.get(); }

        void advanceMillis(long millis) { nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)); }

        void advanceNanos(long delta) { nanos.addAndGet(delta); }
    }

    private static final class FakeScheduler implements AdminHttpRequestDeadline.DeadlineScheduler {
        final List<FakeAlarm> tasks = Collections.synchronizedList(new ArrayList<>());

        @Override public AdminHttpRequestDeadline.Cancellation schedule(Runnable task, long delayNanos) {
            FakeAlarm alarm = new FakeAlarm(task, delayNanos);
            tasks.add(alarm);
            return alarm;
        }
    }

    private static final class FakeAlarm implements AdminHttpRequestDeadline.Cancellation {
        final Runnable task;
        final long delayNanos;
        volatile boolean cancelled;

        FakeAlarm(Runnable task, long delayNanos) {
            this.task = task;
            this.delayNanos = delayNanos;
        }

        @Override public void cancel() { cancelled = true; }

        void fireEvenIfCancelled() { task.run(); }
    }

    private static class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream sentBody = new ByteArrayOutputStream();
        final AtomicInteger outputOpens = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile InputStream responseBody;
        volatile long contentLength = -1L;

        FakeConnection(String response) throws Exception {
            this(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        }

        FakeConnection(InputStream response) throws Exception {
            super(new URL(URL_TEXT));
            responseBody = response;
        }

        /** A connection whose {@code phase} (write, headers or body) blocks until disconnected. */
        static FakeConnection blocking(String phase, CountDownLatch entered) throws Exception {
            FakeConnection[] self = new FakeConnection[1];
            self[0] = new FakeConnection("{}") {
                @Override public int getResponseCode() throws IOException {
                    if ("headers".equals(phase)) {
                        blockUntilDisconnected(entered);
                        throw new IOException("fake HTTP connection closed");
                    }
                    return super.getResponseCode();
                }

                @Override public OutputStream getOutputStream() throws IOException {
                    if (!"write".equals(phase)) return super.getOutputStream();
                    outputOpens.incrementAndGet();
                    return new OutputStream() {
                        @Override public void write(int value) throws IOException {
                            write(new byte[] {(byte) value}, 0, 1);
                        }

                        @Override public void write(byte[] value, int offset, int length) throws IOException {
                            sentBody.write(value, offset, length);
                            blockUntilDisconnected(entered);
                            throw new IOException("fake HTTP connection closed");
                        }
                    };
                }
            };
            if ("body".equals(phase)) {
                self[0].responseBody = new InputStream() {
                    @Override public int read() throws IOException {
                        self[0].blockUntilDisconnected(entered);
                        throw new IOException("fake HTTP connection closed");
                    }
                };
            }
            return self[0];
        }

        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() throws IOException { return 200; }
        @Override public InputStream getInputStream() { return responseBody; }
        @Override public InputStream getErrorStream() { return responseBody; }
        @Override public long getContentLengthLong() { return contentLength; }

        @Override public String getHeaderField(String name) {
            if ("Content-Type".equals(name)) return "application/json";
            if ("Cache-Control".equals(name)) return "no-store";
            if ("Pragma".equals(name)) return "no-cache";
            return null;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            outputOpens.incrementAndGet();
            streamingLengthAtOutputOpen = fixedContentLength;
            return sentBody;
        }

        /** adm-F5: the fixed-length streaming mode set on this connection (-1 = none). */
        volatile int streamingLengthAtOutputOpen = -2;

        int streamingLength() {
            return fixedContentLength;
        }

        @Override public void disconnect() {
            disconnects.incrementAndGet();
            disconnected.countDown();
        }

        void blockUntilDisconnected(CountDownLatch entered) throws IOException {
            entered.countDown();
            try {
                if (!disconnected.await(2, TimeUnit.SECONDS)) throw new IOException("fake HTTP wait expired");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("fake HTTP wait interrupted", error);
            }
        }
    }
}
