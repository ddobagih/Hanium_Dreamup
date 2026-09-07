package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingExplicitRouteStartTest {
    @Test
    fun missingGpsLeavesTheExplicitRequestPendingBeforeTheDeadline() {
        val pending = PendingExplicitRouteStart<String>()
        val request = pending.replace(CONTEXT, nowElapsedRealtimeMs = 1_000L)

        assertSame(request, pending.currentOrNull())
        assertFalse(pending.expireIfCurrent(request, CONTEXT, nowElapsedRealtimeMs = 15_999L))
        assertSame(request, pending.currentOrNull())
    }

    @Test
    fun matchingFreshContextConsumesTheRequestExactlyOnce() {
        val pending = PendingExplicitRouteStart<String>()
        pending.replace(CONTEXT, nowElapsedRealtimeMs = 1_000L)

        assertEquals(CONTEXT, pending.consumeIfCurrent(CONTEXT, nowElapsedRealtimeMs = 4_000L))
        assertNull(pending.consumeIfCurrent(CONTEXT, nowElapsedRealtimeMs = 4_001L))
    }

    @Test
    fun deadlineExpiresTheRequestForAnExplicitRetry() {
        val pending = PendingExplicitRouteStart<String>()
        val request = pending.replace(CONTEXT, nowElapsedRealtimeMs = 1_000L)

        assertTrue(pending.expireIfCurrent(request, CONTEXT, nowElapsedRealtimeMs = 16_000L))
        assertNull(pending.currentOrNull())
    }

    @Test
    fun expiredConsumeLeavesTheTokenForItsTimeoutExplanation() {
        val pending = PendingExplicitRouteStart<String>()
        val request = pending.replace(CONTEXT, nowElapsedRealtimeMs = 1_000L)

        assertNull(pending.consumeIfCurrent(CONTEXT, nowElapsedRealtimeMs = 16_000L))
        assertSame(request, pending.currentOrNull())
        assertTrue(pending.expireIfCurrent(request, CONTEXT, nowElapsedRealtimeMs = 16_000L))
        assertNull(pending.currentOrNull())
    }

    @Test
    fun actorEpochDestinationOrGatewayContextChangeDiscardsTheRequest() {
        val pending = PendingExplicitRouteStart<String>()
        pending.replace(CONTEXT, nowElapsedRealtimeMs = 1_000L)

        assertNull(pending.consumeIfCurrent("changed-context", nowElapsedRealtimeMs = 2_000L))
        assertNull(pending.currentOrNull())
    }

    @Test
    fun staleTimeoutCannotExpireAReplacementRequest() {
        val pending = PendingExplicitRouteStart<String>()
        val first = pending.replace(CONTEXT, nowElapsedRealtimeMs = 1_000L)
        val replacement = pending.replace(CONTEXT, nowElapsedRealtimeMs = 2_000L)

        assertFalse(pending.expireIfCurrent(first, CONTEXT, nowElapsedRealtimeMs = 16_000L))
        assertSame(replacement, pending.currentOrNull())
    }

    @Test
    fun explicitCancellationClearsWithoutConsumption() {
        val pending = PendingExplicitRouteStart<String>()
        pending.replace(CONTEXT, nowElapsedRealtimeMs = 1_000L)

        pending.cancel()

        assertNull(pending.currentOrNull())
    }

    private companion object {
        const val CONTEXT = "actor|walk-epoch|gateway-generation|destination"
    }
}
