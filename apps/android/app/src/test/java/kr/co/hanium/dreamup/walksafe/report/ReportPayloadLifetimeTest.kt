package kr.co.hanium.dreamup.walksafe.report

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import org.junit.Assert.*
import org.junit.Test

class ReportPayloadLifetimeTest {
    @Test
    fun frozenOwnerWipesBothBuffersWithoutChangingCallerBytesAndCannotBeReused() {
        val metadata = "{\"private\":true}".toByteArray()
        val image = reportTestJpeg()
        val payload = requireNotNull(FrozenReportPayload.freeze(ID, metadata, image))
        val fields = listOf("frozenMetadata", "frozenImage").map { name ->
            payload.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(payload) as ByteArray
        }
        payload.close()
        payload.close()
        assertTrue(fields.all { bytes -> bytes.all { it == 0.toByte() } })
        assertArrayEquals("{\"private\":true}".toByteArray(), metadata)
        assertArrayEquals(reportTestJpeg(), image)
        assertThrows(IllegalStateException::class.java) { payload.metadataUtf8() }
        assertThrows(IllegalStateException::class.java) { payload.imageJpeg() }
        assertFalse(payload.contentEquals(payload))
    }

    @Test
    fun uploadSnapshotClearsOnSuccessFailureAndCancellationBeforeExecution() {
        for (failure in listOf(false, true)) {
            val bytes = byteArrayOf(1, 2, 3)
            val call = CancellableNetworkCall.blocking {
                assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                if (failure) throw IOException("setup failed")
                7
            }.clearingOwnedReportImage(bytes)
            if (failure) assertThrows(IOException::class.java) { call.execute() }
            else assertEquals(7, call.execute())
            assertArrayEquals(ByteArray(3), bytes)
        }
        val bytes = byteArrayOf(4, 5)
        val call = CancellableNetworkCall.blocking { error("must not run") }.clearingOwnedReportImage(bytes)
        call.cancel()
        assertArrayEquals(ByteArray(2), bytes)
        assertThrows(CancellationException::class.java) { call.execute() }
    }

    @Test
    fun cancellationWaitsForRunningWriterBeforeWipingItsSnapshot() {
        val bytes = byteArrayOf(1, 2, 3)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val call = CancellableNetworkCall(
            executeBlock = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
            },
            cancelBlock = { cancelled.countDown() },
        ).clearingOwnedReportImage(bytes)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit { call.execute() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            call.cancel()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
            release.countDown()
            result.get(5, TimeUnit.SECONDS)
            assertArrayEquals(ByteArray(3), bytes)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private companion object { const val ID = "123e4567-e89b-42d3-a456-426614174000" }
}
