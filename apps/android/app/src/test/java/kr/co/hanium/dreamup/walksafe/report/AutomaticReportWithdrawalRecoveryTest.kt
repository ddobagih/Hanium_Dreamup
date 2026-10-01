package kr.co.hanium.dreamup.walksafe.report

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AutomaticReportWithdrawalRecoveryTest {
    private val receipt = "a".repeat(64)

    @Test fun readinessReadDoesNotWaitForQueueCleanupLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val recovery = AutomaticReportWithdrawalRecovery({ null }, { true }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        })
        try {
            val cleanup = executor.submit<Boolean> { recovery.recordWithdrawal(receipt) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(executor.submit<Boolean> { recovery.isReady() }.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(cleanup.get(2, TimeUnit.SECONDS))
            assertTrue(recovery.isReady())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun failedFenceWriteSurvivesConsentRegrantAndProcessRecreation() {
        var durable: String? = null
        var cleanupWorks = false
        fun instance() = AutomaticReportWithdrawalRecovery({ durable }, { durable = it; true }, { cleanupWorks })
        val first = instance()
        assertTrue(first.isReady())
        assertFalse(first.recordWithdrawal(receipt))
        assertNotNull(durable)
        // A new server receipt/account cannot erase this independent recovery marker.
        assertFalse(first.isReady())
        val restored = instance()
        assertFalse(restored.isReady())
        assertFalse(restored.recover())
        cleanupWorks = true
        assertTrue(restored.recover())
        assertNull(durable)
        assertTrue(restored.isReady())
        assertTrue(instance().isReady())
    }

    @Test fun markerWriteFailureStopsCleanupUntilItCanBePersisted() {
        var writable = false
        var calls = 0
        val recovery = AutomaticReportWithdrawalRecovery({ null }, { writable }, { calls++; true })
        assertFalse(recovery.recordWithdrawal(receipt))
        assertEquals(0, calls)
        assertFalse(recovery.isReady())
        writable = true
        assertTrue(recovery.recover())
        assertEquals(1, calls)
    }

    @Test fun markerRemovalFailureStillBlocksAndRetriesIdempotentCleanup() {
        var durable: String? = null
        var mayRemove = false
        val recovery = AutomaticReportWithdrawalRecovery({ durable }, {
            if (it == null && !mayRemove) false else { durable = it; true }
        }, { true })
        assertFalse(recovery.recordWithdrawal(receipt))
        assertNotNull(durable)
        assertFalse(recovery.isReady())
        mayRemove = true
        assertTrue(recovery.recover())
        assertNull(durable)
    }

    @Test fun corruptMarkerNeverBecomesPermissionToSend() {
        for (raw in listOf("not-json", "[1]", "[\"invalid\"]", "{}")) {
            var calls = 0
            val recovery = AutomaticReportWithdrawalRecovery({ raw }, { true }, { calls++; true })
            assertFalse(recovery.isReady())
            assertFalse(recovery.recordWithdrawal(receipt))
            assertFalse(recovery.recover())
            assertEquals(0, calls)
        }
    }

    @Test fun pendingReceiptsFromDifferentSessionsMustAllBeCleaned() {
        var durable: String? = null
        var writable = false
        val cleaned = mutableListOf<String>()
        val recovery = AutomaticReportWithdrawalRecovery({ durable }, { durable = it; true }, {
            if (!writable) false else { cleaned += it; true }
        })
        assertFalse(recovery.recordWithdrawal(receipt))
        assertFalse(recovery.recordWithdrawal("b".repeat(64)))
        writable = true
        assertTrue(recovery.recover())
        assertEquals(setOf(receipt, "b".repeat(64)), cleaned.toSet())
        assertNull(durable)
    }
}
