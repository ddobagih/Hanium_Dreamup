package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceStartSpeechDeliveryTest {
    @Test
    fun sameRequestCannotSubmitTwiceWhileSpeechIsInFlight() {
        val delivery = GuidanceStartSpeechDelivery()
        val attempt = requireNotNull(delivery.begin(10L))

        assertEquals(GuidanceStartSpeechDelivery.Attempt(10L, 1), attempt)
        assertNull(delivery.begin(10L))
        assertNull(delivery.begin(10L))
        assertTrue(delivery.complete(attempt))
    }

    @Test
    fun onlyActualCompletionMakesTheRequestDelivered() {
        val delivery = GuidanceStartSpeechDelivery()
        val first = requireNotNull(delivery.begin(10L))

        // An accepted submission can still fail before the engine reports onDone.
        assertTrue(delivery.fail(first))
        assertFalse(delivery.complete(first))
        val retry = requireNotNull(delivery.begin(10L))
        assertEquals(2, retry.ordinal)
        assertTrue(delivery.complete(retry))
        assertNull(delivery.begin(10L))
        assertFalse(delivery.complete(retry))
        assertFalse(delivery.fail(retry))
    }

    @Test
    fun oneRetryIsAllowedAndASecondFailureExhaustsTheRequest() {
        val delivery = GuidanceStartSpeechDelivery()
        val first = requireNotNull(delivery.begin(10L))

        assertTrue(delivery.fail(first))
        val retry = requireNotNull(delivery.begin(10L))
        assertEquals(GuidanceStartSpeechDelivery.Attempt(10L, 2), retry)
        assertFalse(delivery.fail(retry))
        assertFalse(delivery.complete(retry))
        assertNull(delivery.begin(10L))
        assertNull(delivery.begin(10L))

        assertEquals(GuidanceStartSpeechDelivery.Attempt(11L, 1), delivery.begin(11L))
    }

    @Test
    fun duplicateFailureCannotScheduleAnotherRetryOrReleaseTheCurrentAttempt() {
        val delivery = GuidanceStartSpeechDelivery()
        val first = requireNotNull(delivery.begin(10L))

        assertTrue(delivery.fail(first))
        assertFalse(delivery.fail(first))
        val retry = requireNotNull(delivery.begin(10L))
        assertFalse(delivery.fail(first))
        assertFalse(delivery.complete(first))
        assertNull(delivery.begin(10L))
        assertTrue(delivery.complete(retry))
    }

    @Test
    fun newerRequestSupersedesInFlightSpeechAndRejectsItsLateCallbacks() {
        val delivery = GuidanceStartSpeechDelivery()
        val old = requireNotNull(delivery.begin(10L))
        val current = requireNotNull(delivery.begin(11L))

        assertEquals(GuidanceStartSpeechDelivery.Attempt(11L, 1), current)
        assertFalse(delivery.complete(old))
        assertFalse(delivery.fail(old))
        assertNull(delivery.begin(10L))
        assertNull(delivery.begin(11L))
        assertTrue(delivery.fail(current))
        assertEquals(GuidanceStartSpeechDelivery.Attempt(11L, 2), delivery.begin(11L))
    }

    @Test
    fun earlierRequestIdCannotReplaceTheCurrentRequest() {
        val delivery = GuidanceStartSpeechDelivery()
        val current = requireNotNull(delivery.begin(10L))

        assertNull(delivery.begin(9L))
        assertTrue(delivery.complete(current))
        assertNull(delivery.begin(9L))
        assertNull(delivery.begin(10L))
        assertEquals(GuidanceStartSpeechDelivery.Attempt(11L, 1), delivery.begin(11L))
    }

    @Test
    fun cancelRejectsLateCallbacksAndKeepsATombstoneUntilANewerRequest() {
        val delivery = GuidanceStartSpeechDelivery()
        val cancelled = requireNotNull(delivery.begin(10L))

        delivery.cancel()
        delivery.cancel()

        assertFalse(delivery.complete(cancelled))
        assertFalse(delivery.fail(cancelled))
        assertNull(delivery.begin(10L))
        assertNull(delivery.begin(9L))
        val current = requireNotNull(delivery.begin(11L))
        assertEquals(1, current.ordinal)
        assertFalse(delivery.complete(cancelled))
        assertFalse(delivery.fail(cancelled))
        assertTrue(delivery.complete(current))
    }

    @Test
    fun cancelBetweenFailureAndQueuedRetryPreventsTheRetryFromReopeningTheRequest() {
        val delivery = GuidanceStartSpeechDelivery()
        val first = requireNotNull(delivery.begin(10L))
        assertTrue(delivery.fail(first))

        delivery.cancel()

        assertNull(delivery.begin(10L))
        assertFalse(delivery.fail(first))
        assertFalse(delivery.complete(first))
        assertEquals(GuidanceStartSpeechDelivery.Attempt(11L, 1), delivery.begin(11L))
    }

    @Test
    fun cancelAfterCompletionDoesNotAllowReplayButDoesAllowANewerRequest() {
        val delivery = GuidanceStartSpeechDelivery()
        val completed = requireNotNull(delivery.begin(10L))
        assertTrue(delivery.complete(completed))

        delivery.cancel()

        assertNull(delivery.begin(10L))
        assertFalse(delivery.complete(completed))
        assertEquals(GuidanceStartSpeechDelivery.Attempt(11L, 1), delivery.begin(11L))
    }

    @Test
    fun cancellationBeforeAnyRequestDoesNotRejectTheFirstRequest() {
        val delivery = GuidanceStartSpeechDelivery()
        delivery.cancel()

        assertEquals(GuidanceStartSpeechDelivery.Attempt(0L, 1), delivery.begin(0L))
    }
}
