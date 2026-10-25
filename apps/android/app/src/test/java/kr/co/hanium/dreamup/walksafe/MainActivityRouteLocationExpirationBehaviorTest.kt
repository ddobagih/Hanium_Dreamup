package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceDecision
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceFailureReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidencePolicy
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionConfidenceTransition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the real MainActivity cleanup helper with deterministic publication/reader order.
 * Android looper dispatch and GL scheduling are outside this JVM test's scope.
 */
class MainActivityRouteLocationExpirationBehaviorTest {
    @Test
    fun anOlderReaderTimeCannotClearANewerFixOrCreateAnInvalidTimeEpisode() {
        val activity = MainActivity()
        val policy = PositionConfidencePolicy()
        val newlyPublished = fix(2_000L)
        publish(activity, newlyPublished)
        policy.observe(PositionQuality.MEDIUM, newlyPublished.elapsedRealtimeMs)
        var expiredCallbacks = 0

        val retired = activity.retireExpiredRouteLocationIfCurrent(
            newlyPublished,
            nowElapsedRealtimeMs = 1_999L,
            isCollectionCurrent = { true },
            onExpired = { now -> expiredCallbacks++; policy.evaluate(now) },
        )

        assertFalse(retired)
        assertSame(newlyPublished, published(activity))
        assertEquals(0, expiredCallbacks)
        val stillHealthy = policy.evaluate(2_001L)
        assertFalse(stillHealthy.guidancePaused)
        assertNull(stillHealthy.failureReason)
        assertNull(stillHealthy.degradedEpisodeId)
    }

    @Test
    fun aQueuedExpiredFixCannotClearTheFreshReplacementPublishedBeforeCleanup() {
        val activity = MainActivity()
        val expired = fix(1_000L)
        publish(activity, expired)
        // The queued cleanup keeps the old reference while a real new fix is published.
        val replacement = fix(12_000L)
        publish(activity, replacement)
        var expiredCallbacks = 0

        val retired = activity.retireExpiredRouteLocationIfCurrent(
            expired,
            nowElapsedRealtimeMs = 12_001L,
            isCollectionCurrent = { true },
            onExpired = { expiredCallbacks++ },
        )

        assertFalse(retired)
        assertSame(replacement, published(activity))
        assertEquals(0, expiredCallbacks)
    }

    @Test
    fun structuralEqualityDoesNotGiveAQueuedReaderOwnershipOfAnotherPublishedInstance() {
        val activity = MainActivity()
        val expired = fix(1_000L)
        val replacement = expired.copy()
        assertEquals(expired, replacement)
        assertNotSame(expired, replacement)
        publish(activity, replacement)
        var expiredCallbacks = 0

        val retired = activity.retireExpiredRouteLocationIfCurrent(
            expired,
            nowElapsedRealtimeMs = 11_001L,
            isCollectionCurrent = { true },
            onExpired = { expiredCallbacks++ },
        )

        assertFalse(retired)
        assertSame(replacement, published(activity))
        assertEquals(0, expiredCallbacks)
    }

    @Test
    fun theSameActuallyExpiredFixIsClearedBeforeTheRealWatchdogLatchesUnavailable() {
        val activity = MainActivity()
        val policy = PositionConfidencePolicy()
        val expired = fix(1_000L)
        publish(activity, expired)
        policy.observe(PositionQuality.MEDIUM, expired.elapsedRealtimeMs)
        val expiredCallbacks = mutableListOf<Long>()
        var decision: PositionConfidenceDecision? = null

        val retired = activity.retireExpiredRouteLocationIfCurrent(
            expired,
            nowElapsedRealtimeMs = 11_001L,
            isCollectionCurrent = { true },
            onExpired = { now ->
                assertNull(published(activity))
                expiredCallbacks += now
                decision = policy.evaluate(now)
            },
        )

        assertTrue(retired)
        assertNull(published(activity))
        assertEquals(listOf(11_001L), expiredCallbacks)
        val unavailable = requireNotNull(decision)
        assertEquals(PositionQuality.UNAVAILABLE, unavailable.quality)
        assertEquals(PositionConfidenceFailureReason.STALE, unavailable.failureReason)
        assertEquals(PositionConfidenceTransition.FAIL_CLOSED, unavailable.transition)
        assertTrue(unavailable.guidancePaused)
        assertNotNull(unavailable.degradedEpisodeId)
        val firstFresh = policy.observe(PositionQuality.MEDIUM, 12_000L)
        assertTrue(firstFresh.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERY_PENDING, firstFresh.transition)
        assertTrue(policy.evaluate(12_001L).guidancePaused)
        val duplicate = policy.observe(PositionQuality.MEDIUM, 12_000L, 12_002L)
        assertTrue(duplicate.guidancePaused)
        assertEquals(PositionConfidenceFailureReason.OUT_OF_ORDER, duplicate.failureReason)
        assertTrue(policy.observe(PositionQuality.MEDIUM, 13_000L).guidancePaused)
        val recovered = policy.observe(PositionQuality.MEDIUM, 14_000L)
        assertFalse(recovered.guidancePaused)
        assertEquals(PositionQuality.MEDIUM, recovered.quality)
        assertEquals(PositionConfidenceTransition.RECOVERED, recovered.transition)
    }

    @Test
    fun theInclusiveTenSecondBoundaryDoesNotClearTheFixOrRunCleanup() {
        val activity = MainActivity()
        val policy = PositionConfidencePolicy()
        val current = fix(1_000L)
        publish(activity, current)
        policy.observe(PositionQuality.MEDIUM, current.elapsedRealtimeMs)
        var expiredCallbacks = 0

        val retired = activity.retireExpiredRouteLocationIfCurrent(
            current,
            nowElapsedRealtimeMs = 11_000L,
            isCollectionCurrent = { true },
            onExpired = { expiredCallbacks++ },
        )

        assertFalse(retired)
        assertSame(current, published(activity))
        assertEquals(0, expiredCallbacks)
        assertFalse(policy.evaluate(11_000L).guidancePaused)
    }

    @Test
    fun aChangedOwnerOrGenerationDisablesAnOtherwiseEligibleQueuedCleanup() {
        listOf(true, false).forEach { changeOwner ->
            val activity = MainActivity()
            val current = fix(1_000L)
            publish(activity, current)
            val capturedOwner = "owner-1"
            val capturedGeneration = 1
            val currentOwner = if (changeOwner) "owner-2" else capturedOwner
            val currentGeneration = if (changeOwner) capturedGeneration else 2
            var expiredCallbacks = 0

            val retired = activity.retireExpiredRouteLocationIfCurrent(
                current,
                nowElapsedRealtimeMs = 11_001L,
                isCollectionCurrent = {
                    currentOwner == capturedOwner && currentGeneration == capturedGeneration
                },
                onExpired = { expiredCallbacks++ },
            )

            assertFalse(retired)
            assertSame(current, published(activity))
            assertEquals(0, expiredCallbacks)
        }
    }

    @Test
    fun aNegativeReaderClockCannotMutateLocationOrTheConfidencePolicy() {
        val activity = MainActivity()
        val policy = PositionConfidencePolicy()
        val current = fix(1_000L)
        publish(activity, current)
        policy.observe(PositionQuality.MEDIUM, current.elapsedRealtimeMs)
        var expiredCallbacks = 0

        val retired = activity.retireExpiredRouteLocationIfCurrent(
            current,
            nowElapsedRealtimeMs = -1L,
            isCollectionCurrent = { true },
            onExpired = { now -> expiredCallbacks++; policy.evaluate(now) },
        )

        assertFalse(retired)
        assertSame(current, published(activity))
        assertEquals(0, expiredCallbacks)
        val stillHealthy = policy.evaluate(1_001L)
        assertFalse(stillHealthy.guidancePaused)
        assertNull(stillHealthy.failureReason)
        assertNull(stillHealthy.degradedEpisodeId)
    }

    private fun fix(timeMs: Long) = TrustedLocation(37.0, 127.0, 3f, timeMs)

    private fun publish(activity: MainActivity, location: TrustedLocation) {
        MainActivityControllerTestFixture.field("latestTrustedLocation").apply {
            isAccessible = true
            set(activity, location)
        }
    }

    private fun published(activity: MainActivity): TrustedLocation? =
        MainActivityControllerTestFixture.field("latestTrustedLocation").run {
            isAccessible = true
            get(activity) as TrustedLocation?
        }
}
