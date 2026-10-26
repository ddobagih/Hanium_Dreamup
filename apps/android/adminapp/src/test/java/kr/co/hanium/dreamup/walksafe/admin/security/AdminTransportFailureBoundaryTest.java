package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Failure boundaries shared by the real administrator URLConnection transports. */
@RunWith(Parameterized.class)
public final class AdminTransportFailureBoundaryTest {
    @Parameterized.Parameters(name = "{0}")
    public static Object[] transports() { return new Object[] {"operations", "raw", "security", "original"}; }

    @Parameterized.Parameter public String kind;
    private static final String URL_TEXT = "http://127.0.0.1:8000/synthetic-test";
    private static final byte[] DATA = "{}".getBytes(StandardCharsets.UTF_8);

    @Test public void preInterruptedRequestDoesNotOpenConnection() throws Exception {
        Fixture f = new Fixture();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, f::execute);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, f.opens);
        } finally { Thread.interrupted(); }
    }

    @Test public void disconnectRuntimeFailureCannotReplaceCompleteResponse() throws Exception {
        Fixture f = new Fixture();
        f.connection.onDisconnect = () -> { throw new IllegalStateException("synthetic disconnect failure"); };
        assertArrayEquals(DATA, f.execute());
        assertEquals(1, f.connection.disconnects);
        assertEquals(0, f.inFlight());
        assertTrue(f.cancelledAlarms.get() > 0);
    }

    @Test public void disconnectRuntimeFailureCannotReplaceOriginalReadFailure() throws Exception {
        Fixture f = new Fixture();
        IOException original = new IOException("synthetic original read failure");
        f.connection.input = new InputStream() {
            @Override public int read() throws IOException { throw original; }
        };
        f.connection.onDisconnect = () -> { throw new IllegalStateException("synthetic disconnect failure"); };
        assertSame(original, assertThrows(IOException.class, f::execute));
        assertEquals(1, f.connection.disconnects);
        assertEquals(0, f.inFlight());
        assertTrue(f.cancelledAlarms.get() > 0);
    }

    @Test public void disconnectRuntimeFailureCannotHideCancellation() throws Exception {
        Fixture f = new Fixture();
        f.connection.onDisconnect = () -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("synthetic disconnect failure");
        };
        assertInterruptedCleanup(f);
    }

    @Test public void interruptDuringConnectionCreationDoesNotSendRequest() throws Exception {
        Fixture f = new Fixture();
        f.onOpen = () -> Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, f::execute);
            assertEquals(0, f.connection.headerReads);
            assertEquals(0, f.connection.sent.size());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, f.connection.disconnects);
            assertEquals(0, f.inFlight());
        } finally { Thread.interrupted(); }
    }

    @Test public void interruptDuringStreamCloseRejectsLateSuccess() throws Exception {
        Fixture f = new Fixture();
        f.connection.input = new ByteArrayInputStream(DATA) {
            @Override public void close() { Thread.currentThread().interrupt(); }
        };
        assertInterruptedCleanup(f);
    }

    @Test public void interruptDuringDisconnectRejectsLateSuccess() throws Exception {
        Fixture f = new Fixture();
        f.connection.onDisconnect = () -> Thread.currentThread().interrupt();
        assertInterruptedCleanup(f);
    }

    private void assertInterruptedCleanup(Fixture f) throws Exception {
        try {
            assertThrows(IOException.class, f::execute);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, f.connection.disconnects);
            assertEquals(0, f.inFlight());
            // Original-image transport deliberately closes its owner again in finally.
            assertTrue("deadline alarm must be cancelled", f.cancelledAlarms.get() > 0);
        } finally { Thread.interrupted(); }
        f.connection = new Connection();
        assertArrayEquals(DATA, f.execute());
        assertEquals("a failed request must not replay itself", 2, f.opens);
    }

    @Test public void fourthConsecutiveEmptyReadStopsWithoutBusyLooping() throws Exception {
        Fixture f = new Fixture();
        f.connection.input = new InputStream() {
            int reads;
            @Override public int read() { throw new AssertionError("unexpected single-byte read"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (++reads > 4) throw new AssertionError("response kept reading without progress");
                return 0;
            }
        };
        IOException failure = assertThrows(IOException.class, f::execute);
        assertTrue(failure.getMessage().contains("no progress"));
        assertEquals(1, f.connection.disconnects);
        assertEquals(0, f.inFlight());
        f.connection = new Connection();
        assertArrayEquals(DATA, f.execute());
        assertEquals(2, f.opens);
    }

    @Test public void dataProgressResetsTheEmptyReadCount() throws Exception {
        Fixture f = new Fixture();
        f.connection.input = new InputStream() {
            int emptyReads;
            int offset;
            @Override public int read() { return offset < DATA.length ? DATA[offset++] : -1; }
            @Override public int read(byte[] bytes, int start, int length) {
                if (offset == DATA.length) return -1;
                if (++emptyReads <= 3) return 0;
                emptyReads = 0;
                bytes[start] = DATA[offset++];
                return 1;
            }
        };
        assertArrayEquals(DATA, f.execute());
        assertEquals(1, f.connection.disconnects);
        assertEquals(0, f.inFlight());
    }

    @Test public void streamCloseIoFailureReleasesRequestAndAllowsExplicitRetry() throws Exception {
        assertCloseFailure(new IOException("synthetic close failure"));
    }

    @Test public void streamCloseRuntimeFailureReleasesRequestAndAllowsExplicitRetry() throws Exception {
        assertCloseFailure(new IllegalStateException("synthetic close failure"));
    }

    private void assertCloseFailure(Exception failure) throws Exception {
        Fixture f = new Fixture();
        f.connection.input = new ByteArrayInputStream(DATA) {
            @Override public void close() throws IOException {
                if (failure instanceof IOException io) throw io;
                throw (RuntimeException) failure;
            }
        };
        assertSame(failure, assertThrows(failure.getClass(), f::execute));
        assertEquals(1, f.connection.disconnects);
        assertEquals(0, f.inFlight());
        assertEquals(1, f.cancelledAlarms.get());
        f.connection = new Connection();
        assertArrayEquals(DATA, f.execute());
        assertEquals(2, f.opens);
    }

    private final class Fixture {
        int opens;
        Connection connection = new Connection();
        Runnable onOpen = () -> {};
        AtomicInteger cancelledAlarms = new AtomicInteger();
        AdminHttpRequestDeadline deadline = new AdminHttpRequestDeadline(
            url -> open(), () -> 0L, (task, delay) -> cancelledAlarms::incrementAndGet);
        AdminOperationsHttpClient.UrlConnectionTransport operations = new AdminOperationsHttpClient.UrlConnectionTransport(deadline);
        AdminRawCollectionHttpClient.UrlConnectionTransport raw = new AdminRawCollectionHttpClient.UrlConnectionTransport(deadline);
        AdminSecurityHttpClient.UrlConnectionTransport security = new AdminSecurityHttpClient.UrlConnectionTransport(
            url -> open(), () -> 0L, (task, delay) -> cancelledAlarms::incrementAndGet);

        Fixture() throws Exception { }
        private HttpURLConnection open() {
            opens++;
            onOpen.run();
            return connection;
        }
        int inFlight() { return kind.equals("security") ? security.inFlightCount() : deadline.inFlightCount(); }
        byte[] execute() throws IOException {
            return switch (kind) {
                case "operations" -> operations.execute("POST", URL_TEXT, Collections.emptyMap(), DATA).body.getBytes(StandardCharsets.UTF_8);
                case "raw" -> raw.execute("POST", URL_TEXT, Collections.emptyMap(), DATA).body;
                case "security" -> security.execute("POST", URL_TEXT, Collections.emptyMap(), DATA).body.getBytes(StandardCharsets.UTF_8);
                case "original" -> {
                    try (var response = operations.executeBinary("GET", URL_TEXT, Collections.emptyMap(), DATA.length)) {
                        byte[] owned = response.takeBody();
                        byte[] copy = owned.clone();
                        Arrays.fill(owned, (byte) 0);
                        yield copy;
                    }
                }
                default -> throw new AssertionError(kind);
            };
        }
    }

    private static final class Connection extends HttpURLConnection {
        int headerReads;
        int disconnects;
        InputStream input = new ByteArrayInputStream(DATA);
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        Runnable onDisconnect = () -> {};
        Connection() throws Exception { super(new URL(URL_TEXT)); }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { headerReads++; return 200; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public long getContentLengthLong() { return DATA.length; }
        @Override public String getHeaderField(String name) {
            return switch (name) {
                case "Content-Type" -> "application/json";
                case "Cache-Control" -> "no-store";
                case "Pragma" -> "no-cache";
                default -> null;
            };
        }
        @Override public void disconnect() { disconnects++; onDisconnect.run(); }
    }
}
