package kr.co.hanium.dreamup.walksafe.feedback

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.navigation.TactileDotReferenceNotice
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TactileDotReferenceDeliveryPolicyTest {
    private val owner = TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("walk", 0L), 1, 2, 3L)

    @Test
    fun reservesOnlyPublishedCurrentNoticeAndOnlyOnePending() {
        val policy = TactileDotReferenceDeliveryPolicy()
        assertNull(policy.reserveCurrent(owner, 1_000L))
        val notice = notice()
        policy.updateObservation(notice, owner, 1_000L)
        val reserved = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertSame(notice, reserved.notice)
        assertEquals(owner, reserved.owner)
        assertTrue(policy.isCurrent(reserved, 1_000L))
        assertNull(policy.reserveCurrent(owner, 1_000L))
    }

    @Test
    fun futureNegativeMalformedAndExpiredObservationsCannotReserve() {
        for (invalid in listOf(
            notice().copy(trackId = " "),
            notice().copy(captureFrameId = 0L),
            notice().copy(captureFrameId = -1L),
            notice().copy(capturedAtElapsedRealtimeMs = -1L),
            notice().copy(capturedAtElapsedRealtimeMs = 1_001L),
            notice().copy(validUntilElapsedRealtimeMs = 999L),
        )) {
            val policy = TactileDotReferenceDeliveryPolicy()
            policy.updateObservation(invalid, owner, 1_000L)
            assertNull(invalid.toString(), policy.reserveCurrent(owner, 1_000L))
        }
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(notice(), owner, -1L)
        assertNull(policy.reserveCurrent(owner, -1L))
        assertNull(policy.reserveCurrent(owner, 1_000L))
        policy.updateObservation(notice(), owner, 1_401L)
        assertNull(policy.reserveCurrent(owner, 1_401L))
    }

    @Test
    fun sameTrackNewFrameCannotRenewOriginalInclusiveStartDeadline() {
        val policy = TactileDotReferenceDeliveryPolicy()
        val original = notice()
        policy.updateObservation(original, owner, 1_000L)
        val reserved = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        val latest = notice(frame = 2L, captured = 1_200L, until = 1_800L)
        policy.updateObservation(latest, owner, 1_200L)
        assertSame(original, reserved.notice)
        assertEquals(1_400L, reserved.notice.validUntilElapsedRealtimeMs)
        assertTrue(policy.isCurrent(reserved, 1_400L))
        assertFalse(policy.isCurrent(reserved, 1_401L))
        assertNull(policy.reserveCurrent(owner, 1_401L))
        assertTrue(policy.fail(reserved))
        assertSame(latest, requireNotNull(policy.reserveCurrent(owner, 1_401L)).notice)
    }

    @Test
    fun nullEvidenceStopsStartWithoutDiscardingTheTerminalOwnerOrRestoringOldUiEvidence() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(notice(), owner, 1_000L)
        val reserved = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        policy.updateObservation(null, owner, 1_100L)
        assertFalse(policy.isCurrent(reserved, 1_100L))
        assertNull(policy.reserveCurrent(owner, 1_100L))
        assertTrue(policy.fail(reserved))
        assertNull(policy.reserveCurrent(owner, 1_100L))
        // A delayed publication of the old capture cannot overwrite the newer null receipt.
        policy.updateObservation(notice(), owner, 1_200L)
        assertNull(policy.reserveCurrent(owner, 1_200L))
        policy.updateObservation(notice(frame = 2L, captured = 1_250L, until = 1_650L), owner, 1_250L)
        assertNotNull(policy.reserveCurrent(owner, 1_250L))
    }

    @Test
    fun differentLatestTrackPreventsOldStartAndHoldsOnePendingUntilFailure() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(notice(), owner, 1_000L)
        val old = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        val other = notice(track = "other", frame = 2L, captured = 1_100L, until = 1_500L)
        policy.updateObservation(other, owner, 1_100L)
        assertFalse(policy.isCurrent(old, 1_100L))
        assertNull(policy.reserveCurrent(owner, 1_100L))
        assertTrue(policy.fail(old))
        assertSame(other, requireNotNull(policy.reserveCurrent(owner, 1_100L)).notice)
    }

    @Test
    fun actualDoneAfterOriginalExpiryAndNullEvidenceStartsCooldownAtCompletion() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(notice(), owner, 1_000L)
        val started = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertTrue(policy.isCurrent(started, 1_000L))
        policy.updateObservation(null, owner, 2_000L)
        assertFalse(policy.isCurrent(started, 7_000L))
        assertTrue(policy.complete(started, 7_000L)) // Actual DONE receipt from the caller.
        assertFalse(policy.complete(started, 7_001L))
        policy.updateObservation(notice(frame = 2L, captured = 14_999L, until = 15_399L), owner, 14_999L)
        assertNull(policy.reserveCurrent(owner, 14_999L))
        assertNotNull(policy.reserveCurrent(owner, 15_000L))
    }

    @Test
    fun failureAndSynchronousRejectionDoNotConsumeCompletionInterval() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(notice(), owner, 1_000L)
        val rejected = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertTrue(policy.fail(rejected))
        val retry = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertFalse(policy.fail(rejected))
        assertFalse(policy.complete(rejected, 1_001L))
        assertTrue(policy.isCurrent(retry, 1_001L))
    }

    @Test
    fun eachOwnerComponentRetiresOldTokensAndOldResetCannotClearNewPending() {
        val changedOwners = listOf(
            owner.copy(epoch = WalkRuntimeEpoch("new-walk", 0L)),
            owner.copy(epoch = WalkRuntimeEpoch("walk", 1L)),
            owner.copy(feedbackLifecycleGeneration = 2),
            owner.copy(frameGeneration = 3),
            owner.copy(arGeneration = 4L),
        )
        for (nextOwner in changedOwners) {
            val policy = TactileDotReferenceDeliveryPolicy()
            policy.updateObservation(notice(), owner, 1_000L)
            val old = requireNotNull(policy.reserveCurrent(owner, 1_000L))
            policy.updateObservation(notice(frame = 2L, captured = 1_100L, until = 1_500L), nextOwner, 1_100L)
            assertNull(policy.reserveCurrent(owner, 1_100L))
            val next = requireNotNull(policy.reserveCurrent(nextOwner, 1_100L))
            assertFalse(policy.isCurrent(old, 1_100L))
            assertFalse(policy.complete(old, 1_100L))
            assertFalse(policy.fail(old))
            policy.reset(owner)
            assertTrue(policy.isCurrent(next, 1_100L))
            assertTrue(policy.complete(next, 1_101L))
        }
    }

    @Test
    fun resetRetiresExactOldTokenEvenWhenTheOwnerAndNoticeValuesAreReused() {
        val policy = TactileDotReferenceDeliveryPolicy()
        val notice = notice()
        policy.updateObservation(notice, owner, 1_000L)
        val old = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        policy.reset()
        policy.updateObservation(notice, owner, 1_000L)
        val next = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertFalse(policy.complete(old, 1_001L))
        assertFalse(policy.fail(old))
        val copied = TactileDotReferenceDeliveryReservation(notice, owner, 1_000L)
        assertFalse(policy.complete(copied, 1_001L))
        assertTrue(policy.isCurrent(next, 1_001L))
        assertTrue(policy.complete(next, 1_001L))
    }

    @Test
    fun newerGlPublicationCannotBeOverwrittenByOlderCaptureOrStaleUiClock() {
        val policy = TactileDotReferenceDeliveryPolicy()
        val latest = notice(frame = 20L, captured = 1_100L, until = 1_800L)
        policy.updateObservation(latest, owner, 1_100L)
        policy.updateObservation(notice(frame = 19L, captured = 1_050L, until = 1_900L), owner, 1_150L)
        policy.updateObservation(notice(frame = 21L, captured = 1_099L, until = 1_900L), owner, 1_160L)
        assertNull(policy.reserveCurrent(owner, 1_099L))
        assertSame(latest, requireNotNull(policy.reserveCurrent(owner, 1_100L)).notice)
    }

    @Test
    fun invalidCompletionClockCannotConsumePendingAndClockRollbackCannotBypassCooldown() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(notice(), owner, 1_000L)
        val reserved = requireNotNull(policy.reserveCurrent(owner, 1_000L))
        assertFalse(policy.complete(reserved, -1L))
        assertFalse(policy.complete(reserved, 999L))
        assertTrue(policy.isCurrent(reserved, 1_000L))
        assertTrue(policy.complete(reserved, 1_200L))
        assertNull(policy.reserveCurrent(owner, 1_199L))
    }

    @Test(timeout = 10_000L)
    fun concurrentUiReservationsCanAcquireOnlyOnePendingIdentity() {
        val policy = TactileDotReferenceDeliveryPolicy()
        policy.updateObservation(notice(), owner, 1_000L)
        val startLatch = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val reservations = ConcurrentLinkedQueue<TactileDotReferenceDeliveryReservation>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = List(8) {
            Thread {
                try {
                    if (startLatch.await(5L, TimeUnit.SECONDS)) policy.reserveCurrent(owner, 1_000L)?.let(reservations::add)
                } catch (failure: Throwable) {
                    errors.add(failure)
                } finally {
                    finished.countDown()
                }
            }.apply { this.start() }
        }
        startLatch.countDown()
        assertTrue(finished.await(5L, TimeUnit.SECONDS))
        threads.forEach { it.join(50L) }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, reservations.size)
        assertTrue(policy.isCurrent(reservations.single(), 1_000L))
    }

    private fun notice(
        track: String = "dot",
        frame: Long = 1L,
        captured: Long = 1_000L,
        until: Long = 1_400L,
    ) = TactileDotReferenceNotice(track, frame, captured, until)
}
