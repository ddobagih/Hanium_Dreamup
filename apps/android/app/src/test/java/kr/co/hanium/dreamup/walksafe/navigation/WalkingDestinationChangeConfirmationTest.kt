package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-40: a walking selection is only a selection until one matching "길안내 시작" consumes it. */
class WalkingDestinationChangeConfirmationTest {
    private val place = DestinationSearchResult("p1", "도서관", RoutePoint(37.5, 127.0), null, null, null, 300)
    private val other = place.copy(id = "p2", name = "공원")

    @Test fun theSameOwnerConsumesTheSelectionExactlyOnce() {
        val confirmation = WalkingDestinationChangeConfirmation<String>()
        confirmation.offer(place, "walk-1", nowElapsedRealtimeMs = 1_000L)
        assertTrue(confirmation.hasPending())
        assertEquals(place, confirmation.consume("walk-1", 29_999L + 1_000L))
        assertFalse(confirmation.hasPending())
        assertNull(confirmation.consume("walk-1", 2_000L))
    }

    @Test fun anotherOwnerOrAnExpiredOrBackwardClockNeverStartsTheRoute() {
        val confirmation = WalkingDestinationChangeConfirmation<String>()
        confirmation.offer(place, "walk-1", 1_000L)
        assertNull(confirmation.consume("walk-2", 1_001L))
        assertFalse(confirmation.hasPending())
        confirmation.offer(place, "walk-1", 1_000L)
        assertNull(confirmation.consume("walk-1", 31_000L))
        confirmation.offer(place, "walk-1", 1_000L)
        assertNull(confirmation.consume("walk-1", 999L))
        assertFalse(confirmation.hasPending())
    }

    /** S06-40 x S05-11: repeating the prompt reads the selection without using up "길안내 시작". */
    @Test fun peekReadsTheSelectionForTheSameOwnerWithoutConsumingIt() {
        val confirmation = WalkingDestinationChangeConfirmation<String>()
        confirmation.offer(place, "walk-1", 1_000L)
        assertEquals(place, confirmation.peek("walk-1", 5_000L))
        assertEquals(place, confirmation.peek("walk-1", 6_000L))
        assertTrue(confirmation.hasPending())
        assertEquals(place, confirmation.consume("walk-1", 7_000L))
        assertNull(confirmation.peek("walk-1", 7_001L))
    }

    @Test fun peekFollowsTheSameOwnerAndWindowRulesAsConsume() {
        val confirmation = WalkingDestinationChangeConfirmation<String>()
        confirmation.offer(place, "walk-1", 1_000L)
        assertNull(confirmation.peek("walk-2", 1_001L))
        assertNull(confirmation.peek("walk-1", 31_000L))
        assertNull(confirmation.peek("walk-1", 999L))
        // A refused peek never drops the selection; only consume or clear do.
        assertTrue(confirmation.hasPending())
        assertEquals(place, confirmation.consume("walk-1", 30_999L))
    }

    @Test fun aNewerSelectionReplacesTheOlderAndClearDropsIt() {
        val confirmation = WalkingDestinationChangeConfirmation<String>(timeoutMs = 5_000L)
        confirmation.offer(place, "walk-1", 1_000L)
        confirmation.offer(other, "walk-1", 2_000L)
        assertEquals(other, confirmation.consume("walk-1", 6_999L))
        confirmation.offer(place, "walk-1", 1_000L)
        confirmation.clear()
        assertNull(confirmation.consume("walk-1", 1_001L))
    }
}
