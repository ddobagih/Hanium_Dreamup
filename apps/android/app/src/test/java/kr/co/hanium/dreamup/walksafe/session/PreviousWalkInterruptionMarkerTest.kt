package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviousWalkInterruptionMarkerTest {
    @Test
    fun crashBeforeNoticeRetainsThePreviousInterruption() {
        val store = MarkerStore()
        val marker = store.marker()

        assertTrue(marker.isPending)
        assertTrue(store.value)
        assertTrue(store.marker().isPending)
    }

    @Test
    fun noticeBeforeSuccessDoesNotClearTheMarker() {
        val store = MarkerStore()
        val marker = store.marker()

        marker.onNoticeAcknowledged(currentWalkRequiresMarker = false)

        assertTrue(marker.isPending)
        assertTrue(store.value)
    }

    @Test
    fun successBeforeNoticeDoesNotClearTheMarker() {
        val store = MarkerStore()
        val marker = store.marker()

        marker.onNewWalkActivationCompleted(currentWalkRequiresMarker = true)

        assertTrue(marker.isPending)
        assertTrue(store.value)
    }

    @Test
    fun nonactivePersistenceCannotEraseAnUnannouncedInterruption() {
        val store = MarkerStore()
        val marker = store.marker()

        store.value = marker.isPending
        marker.onNewWalkActivationCompleted(currentWalkRequiresMarker = true)
        store.value = marker.isPending

        assertTrue(store.value)
        assertTrue(marker.isPending)
    }

    @Test
    fun successfulNewWalkTransfersTheTrueMarkerWithoutAFalseWindow() {
        val store = MarkerStore()
        val marker = store.marker()

        marker.onNoticeAcknowledged(currentWalkRequiresMarker = false)
        marker.onNewWalkActivationCompleted(currentWalkRequiresMarker = true)

        assertFalse(marker.isPending)
        assertTrue(store.value)
        assertTrue(store.writes.all { it })
        assertTrue(store.marker().isPending)
    }

    @Test
    fun successThenNoticeAfterWalkEndsClearsOnlyAfterBothConditions() {
        val store = MarkerStore()
        val marker = store.marker()

        marker.onNewWalkActivationCompleted(currentWalkRequiresMarker = true)
        marker.onNoticeAcknowledged(currentWalkRequiresMarker = false)

        assertFalse(marker.isPending)
        assertFalse(store.value)
    }

    @Test
    fun failedReleaseWriteKeepsThePreviousInterruption() {
        val store = MarkerStore()
        store.allowWrite = false
        val marker = store.marker()

        marker.onNoticeAcknowledged(currentWalkRequiresMarker = false)
        marker.onNewWalkActivationCompleted(currentWalkRequiresMarker = true)

        assertTrue(marker.isPending)
        assertTrue(store.value)
    }

    private class MarkerStore {
        var value = true
        var allowWrite = true
        val writes = mutableListOf<Boolean>()

        fun marker() = PreviousWalkInterruptionMarker(value) { currentWalkRequiresMarker ->
            writes += currentWalkRequiresMarker
            if (!allowWrite) {
                false
            } else {
                value = currentWalkRequiresMarker
                true
            }
        }
    }
}
