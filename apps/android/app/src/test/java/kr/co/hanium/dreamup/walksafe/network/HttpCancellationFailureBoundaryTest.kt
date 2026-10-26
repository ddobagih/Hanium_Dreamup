package kr.co.hanium.dreamup.walksafe.network

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class HttpCancellationFailureBoundaryTest {
    @Test
    fun disconnectFailureStillClosesStreamAndCancelsOnce() {
        val events = mutableListOf<String>()
        val cancellation = HttpConnectionCancellation()
        cancellation.attach(connection { events += "disconnect"; error("disconnect failed") })
        cancellation.attach(Closeable { events += "close" })
        cancellation.cancel()
        cancellation.cancel()
        assertEquals(listOf("disconnect", "close"), events)
        assertTrue(cancellation.isCancelled())
        assertThrows(CancellationException::class.java) { cancellation.throwIfCancelled() }
    }

    @Test
    fun streamCloseFailureDoesNotLoseConnectionCleanup() {
        val events = mutableListOf<String>()
        val cancellation = HttpConnectionCancellation()
        cancellation.attach(connection { events += "disconnect" })
        cancellation.attach(Closeable { events += "close"; throw IOException("close failed") })
        cancellation.cancel()
        cancellation.cancel()
        assertEquals(listOf("disconnect", "close"), events)
        assertTrue(cancellation.isCancelled())
    }

    @Test
    fun lateConnectionWithDisconnectFailureStillReportsCancellation() {
        val cancellation = HttpConnectionCancellation()
        var disconnects = 0
        cancellation.cancel()
        assertThrows(CancellationException::class.java) {
            cancellation.attach(connection { disconnects++; error("disconnect failed") })
        }
        cancellation.cancel()
        assertEquals(1, disconnects)
    }

    @Test
    fun lateStreamWithCloseFailureStillReportsCancellation() {
        val cancellation = HttpConnectionCancellation()
        var closes = 0
        cancellation.cancel()
        assertThrows(CancellationException::class.java) {
            cancellation.attach(Closeable { closes++; throw IOException("close failed") })
        }
        cancellation.cancel()
        assertEquals(1, closes)
    }

    @Test
    fun oneCancelCallbackFailureDoesNotSkipOtherTrackedCalls() {
        val active = ActiveNetworkCalls()
        var attempts = 0
        val calls = List(3) {
            active.track(CancellableNetworkCall(executeBlock = { error("must not execute") },
                cancelBlock = { attempts++; error("synthetic cancel failure") }))
        }
        try {
            active.cancelAll()
            active.cancelAll()
            assertEquals(3, attempts)
            calls.forEach {
                assertTrue(it.isCancelled())
                assertThrows(CancellationException::class.java) { it.execute() }
            }
        } finally { calls.forEach(active::complete) }
    }

    @Test
    fun deadlineWithDisconnectFailureStillReleasesStreamAndReportsTimeout() {
        var fire: () -> Unit = {}
        var released = 0
        val scheduler = NetworkDeadlineScheduler { _, action -> fire = action; { released++ } }
        val events = mutableListOf<String>()
        val call = cancellableHttpCall(deadlineMs = 100L, deadlineScheduler = scheduler) { cancellation ->
            cancellation.attach(connection { events += "disconnect"; error("disconnect failed") })
            cancellation.attach(Closeable { events += "close" })
            fire()
            cancellation.throwIfCancelled()
            "late response"
        }
        val failure = assertThrows(NetworkDeadlineExceededException::class.java) { call.execute() }
        assertEquals(100L, failure.deadlineMs)
        assertEquals(listOf("disconnect", "close"), events)
        assertEquals(1, released)
        assertEquals("next explicit request", cancellableHttpCall { "next explicit request" }.execute())
    }

    private fun connection(onDisconnect: () -> Unit) = object : HttpURLConnection(URL("http://127.0.0.1/synthetic")) {
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = onDisconnect()
    }
}
