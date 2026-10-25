package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.*
import org.junit.Test

class CameraUiPresentationCoordinatorTest {
    private val capture = CameraObjectPresentation.Capture(1, 1_000_000_000L, 1_000L, "portrait")

    @Test fun unchangedTicksSkipDrawingButAnEqualNewCaptureStillUpdatesItsSourceMetadata() {
        val f = Fixture()
        assertTrue(f.ui.begin(capture))
        assertTrue(f.ui.shouldRender(capture, listOf(0, 2)))
        repeat(20) { assertFalse(f.ui.shouldRender(capture, listOf(0, 2))) }
        assertTrue(f.ui.shouldRender(capture, listOf(2)))
        val newer = capture.copy(frameId = capture.frameId + 1L, capturedAtMs = 1_100L)
        assertTrue(f.ui.begin(newer))
        assertTrue(f.ui.shouldRender(newer, listOf(2)))
        assertFalse(f.ui.shouldRender(capture, listOf(0, 2)))
    }

    @Test fun duplicateOlderAndReversedTimeCallbacksCannotReplaceTheNewestCapture() {
        val f = Fixture()
        assertTrue(f.ui.begin(capture))
        assertFalse(f.ui.begin(capture))
        assertFalse(f.ui.begin(capture.copy(frameId = capture.frameId - 1L)))
        assertFalse(f.ui.begin(capture.copy(frameId = capture.frameId + 1L, capturedAtMs = 999L)))
        assertFalse(f.ui.begin(capture.copy(epoch = 0, frameId = capture.frameId + 1L)))
        assertTrue(f.ui.shouldRender(capture, listOf(0)))
        val rotated = capture.copy(frameId = capture.frameId + 1L, geometryId = "landscape")
        assertTrue(f.ui.begin(rotated))
        assertFalse(f.ui.shouldRender(capture, listOf(1)))
        val resumed = capture.copy(epoch = 2, frameId = 1L)
        assertTrue(f.ui.begin(resumed))
        assertTrue(f.ui.shouldRender(resumed, listOf(0)))
    }

    @Test fun manyDeadlinesAndRepeatedTicksKeepOnlyOneInclusiveExpiryThenScheduleTheNext() {
        val f = Fixture()
        f.ui.begin(capture)
        val deadlines = listOf(null, 1_100L, 1_100L, 1_200L, 1_300L)
        fun render() {
            f.renders++
            f.schedule(capture, deadlines, ::render)
        }
        repeat(50) { f.schedule(capture, deadlines, ::render) }
        assertEquals(1, f.posts)
        assertEquals(1, f.queued.size)
        assertEquals(101L, f.queued.values.single())
        f.fireAt(1_100L)
        assertEquals(0, f.renders)
        f.fireAt(1_101L)
        assertEquals(1, f.renders)
        assertEquals(2, f.posts)
        assertEquals(1, f.queued.size)
        assertEquals(100L, f.queued.values.single())
        f.fireAt(1_301L)
        assertEquals(2, f.renders)
        assertTrue(f.queued.isEmpty())
    }

    @Test fun newCaptureCancelsOldExpiryAndALateDequeuedCallbackCannotRenderOldData() {
        val f = Fixture()
        f.ui.begin(capture)
        f.schedule(capture, listOf(1_100L))
        val old = f.queued.keys.single()
        val newer = capture.copy(frameId = capture.frameId + 1L, capturedAtMs = 1_050L)
        assertTrue(f.ui.begin(newer))
        f.schedule(newer, listOf(1_200L))
        assertEquals(1, f.removes)
        assertEquals(1, f.queued.size)
        old.run()
        assertEquals(0, f.renders)
        assertEquals(1, f.queued.size)
        f.fireAt(1_201L)
        assertEquals(1, f.renders)
    }

    @Test fun aLateOldCaptureCannotCancelTheCurrentCapturesExpiry() {
        val f = Fixture()
        f.ui.begin(capture)
        val newer = capture.copy(frameId = capture.frameId + 1L, capturedAtMs = 1_050L)
        f.ui.begin(newer)
        f.schedule(newer, listOf(1_200L))
        val current = f.queued.keys.single()
        f.schedule(capture, emptyList())
        assertSame(current, f.queued.keys.single())
        assertEquals(0, f.removes)
    }

    @Test fun sourceRetirementAtDispatchPreventsRenderingEvenWhenIdentityAndFreshnessStillMatch() {
        val f = Fixture()
        f.ui.begin(capture)
        f.schedule(capture, listOf(1_100L))
        f.live = false
        f.fireAt(1_101L)
        assertEquals(0, f.renders)
        assertTrue(f.queued.isEmpty())
    }

    @Test fun pauseOrClearCancelsExpiryAndReuseOfTheSameCaptureCannotReviveTheOldCallback() {
        val f = Fixture()
        f.ui.begin(capture)
        assertTrue(f.ui.shouldRender(capture, listOf(0)))
        f.schedule(capture, listOf(1_100L))
        val old = f.queued.keys.single()
        f.ui.clear()
        assertTrue(f.queued.isEmpty())
        assertFalse(f.ui.shouldRender(capture, listOf(1)))
        assertTrue(f.ui.begin(capture))
        assertTrue(f.ui.shouldRender(capture, listOf(0)))
        f.schedule(capture, listOf(1_200L))
        old.run()
        assertEquals(0, f.renders)
        assertEquals(1, f.queued.size)
    }

    @Test fun changedDeadlinesReplaceTheSinglePendingCallbackInsteadOfAccumulatingWork() {
        val f = Fixture()
        f.ui.begin(capture)
        f.schedule(capture, listOf(1_200L, 1_300L))
        val old = f.queued.keys.single()
        f.schedule(capture, listOf(1_100L, 1_300L))
        assertEquals(1, f.removes)
        assertEquals(1, f.queued.size)
        old.run()
        assertEquals(0, f.renders)
        f.schedule(capture, listOf(null, 999L))
        assertTrue(f.queued.isEmpty())
        assertEquals(2, f.removes)
    }

    private class Fixture {
        var nowMs = 1_000L
        var live = true
        var renders = 0
        var posts = 0
        var removes = 0
        val queued = linkedMapOf<Runnable, Long>()
        private val due = mutableMapOf<Runnable, Long>()
        val ui = CameraUiPresentationCoordinator<List<Int>>(
            { callback, delay -> posts++; queued[callback] = delay; due[callback] = nowMs + delay },
            { callback -> removes++; queued.remove(callback); due.remove(callback) },
        )
        fun schedule(capture: CameraObjectPresentation.Capture, deadlines: List<Long?>, render: () -> Unit = { renders++ }) {
            ui.scheduleExpiry(capture, deadlines, nowMs, { live }, render)
        }
        fun fireAt(atMs: Long) {
            nowMs = atMs
            due.entries.filter { it.value <= nowMs }.map { it.key }.forEach { callback ->
                queued.remove(callback)
                due.remove(callback)
                callback.run()
            }
        }
    }
}
