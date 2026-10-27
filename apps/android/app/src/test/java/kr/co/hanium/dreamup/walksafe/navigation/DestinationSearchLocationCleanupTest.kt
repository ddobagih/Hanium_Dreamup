package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class DestinationSearchLocationCleanupTest {
    private val lease = DestinationSearchLocationLease("location-test", 1L)
    private val fix = DestinationSearchLocationFix(35.8714, 128.6014, 60f, 10_000L)

    @Test fun synchronousSuccessSurvivesCancellationCleanupFailure() {
        val results = mutableListOf<RoutePoint?>()
        var cleanupCalls = 0
        val source = DestinationSearchLocationSource { callback ->
            callback(fix)
            val cleanup: () -> Unit = { cleanupCalls++; throw IllegalStateException("cleanup failed") }
            cleanup
        }
        val controller = DestinationSearchLocationController(source) { 10_000L }
        assertTrue(controller.acquire(lease, { true }, results::add))
        assertEquals(listOf(RoutePoint(fix.latitude, fix.longitude)), results)
        assertEquals(1, cleanupCalls)
        assertFalse(controller.isAcquiring)
        assertEquals(results.single(), controller.originOrNull(lease))
    }

    @Test fun synchronousUnavailableResultIsDeliveredOnceDespiteCleanupFailure() {
        val results = mutableListOf<RoutePoint?>()
        val source = DestinationSearchLocationSource { callback ->
            callback(null)
            val cleanup: () -> Unit = { throw IllegalStateException("cleanup failed") }
            cleanup
        }
        val controller = DestinationSearchLocationController(source) { 10_000L }
        assertTrue(controller.acquire(lease, { true }, results::add))
        assertEquals(listOf<RoutePoint?>(null), results)
        assertFalse(controller.isAcquiring)
        assertNull(controller.originOrNull(lease))
    }

    @Test fun permissionLossAndCleanupFailureCannotDeliverOrCacheTheFix() {
        var allowed = true
        var resultCount = 0
        val source = DestinationSearchLocationSource { callback ->
            allowed = false
            callback(fix)
            val cleanup: () -> Unit = { throw IllegalStateException("cleanup failed") }
            cleanup
        }
        val controller = DestinationSearchLocationController(source) { 10_000L }
        controller.acquire(lease, { allowed }) { resultCount++ }
        assertEquals(0, resultCount)
        assertFalse(controller.isAcquiring)
        assertNull(controller.originOrNull(lease))
    }

    @Test fun cleanupOfSynchronousRequestCannotBreakTheRetryStartedByItsResult() {
        var calls = 0
        var retryCancelCount = 0
        var retryCallback: ((DestinationSearchLocationFix?) -> Unit)? = null
        val source = DestinationSearchLocationSource { callback ->
            if (++calls == 1) {
                callback(null)
                val cleanup: () -> Unit = { throw IllegalStateException("old cleanup failed") }
                cleanup
            } else {
                retryCallback = callback
                val cleanup: () -> Unit = { retryCancelCount++ }
                cleanup
            }
        }
        val controller = DestinationSearchLocationController(source) { 10_000L }
        val results = mutableListOf<RoutePoint?>()
        controller.acquire(lease, { true }) { result ->
            results += result
            controller.acquire(lease, { true }, results::add)
        }
        assertTrue(controller.isAcquiring)
        assertEquals(0, retryCancelCount)
        requireNotNull(retryCallback).invoke(fix)
        assertFalse(controller.isAcquiring)
        assertEquals(listOf(null, RoutePoint(fix.latitude, fix.longitude)), results)
        assertEquals(0, retryCancelCount)
    }

    @Test fun consumerLogicFailureIsNotMistakenForACleanupFailure() {
        val failure = IllegalStateException("consumer failed")
        val source = DestinationSearchLocationSource { callback -> callback(fix); {} }
        val controller = DestinationSearchLocationController(source) { 10_000L }
        assertSame(failure, runCatching { controller.acquire(lease, { true }) { throw failure } }.exceptionOrNull())
    }
}
