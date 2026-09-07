package kr.co.hanium.dreamup.walksafe.positioneval.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCancellationTest {
    @Test
    fun preCancelledSignalInvokesLateRegistrationWithoutAffectingAnotherRun() {
        val first = AnalysisCancellation()
        val second = AnalysisCancellation()
        assertTrue(first.cancel())
        var invoked = false
        first.register { invoked = true }.close()

        assertTrue(invoked)
        assertFalse(second.isCancelled())
        assertNotEquals(AnalysisRunGeneration.next(), AnalysisRunGeneration.next())
    }

    @Test
    fun cancellingDownloadDisconnectsActiveConnectionAndDeletesPartFile() {
        val enteredRead = CountDownLatch(1)
        val input = BlockingInputStream(enteredRead)
        val connection = FakeHttpsConnection(URL("https://geodesy.ngii.go.kr/file/ngii/test"), input)
        val transport = StrictHttpsTransport(connectionFactory = { connection })
        val cancellation = AnalysisCancellation()
        val root = createTempDirectory("ngii-cancel").toFile()
        val destination = File(root, "base.zip")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Unit> {
                transport.download(
                    "https://geodesy.ngii.go.kr/file/ngii/test",
                    "test-key".toCharArray(),
                    destination,
                    cancellation,
                )
            }
            assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
            assertTrue(cancellation.cancel())
            assertThrows(Exception::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertTrue(connection.disconnected)
            assertFalse(destination.exists())
            assertFalse(File(root, "base.zip.part").exists())
        } finally {
            cancellation.cancel()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    private class BlockingInputStream(private val entered: CountDownLatch) : InputStream() {
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            try {
                closed.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            throw IOException("disconnected")
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class FakeHttpsConnection(url: URL, private val body: InputStream) : HttpsURLConnection(url) {
        @Volatile var disconnected = false
            private set

        override fun getResponseCode(): Int = 200
        override fun getContentLengthLong(): Long = -1L
        override fun getInputStream(): InputStream = body
        override fun disconnect() {
            disconnected = true
            body.close()
        }
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getCipherSuite(): String = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
