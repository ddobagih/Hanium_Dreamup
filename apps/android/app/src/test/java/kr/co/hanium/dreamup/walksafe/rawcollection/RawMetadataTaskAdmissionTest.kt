package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class RawMetadataTaskAdmissionTest {
    @Test
    fun stalledWorkerBoundsFrameBacklogAndResetsOnlyAfterTheGap() {
        val queued = ArrayDeque<Runnable>()
        val worker = Executor { queued.add(it) }
        val admission = RawMetadataTaskAdmission(maxPending = 2)
        val events = mutableListOf<String>()
        fun submit(value: String) = admission.submit(worker, { events += "reset" }) { events += value }
        assertTrue(submit("before-1"))
        assertTrue(submit("before-2"))
        repeat(1000) { assertFalse(submit("dropped")) }
        assertEquals(2, queued.size)
        // Critical operations use the same worker without the frame admission limit.
        worker.execute { events += "consent-withdrawal" }
        queued.removeFirst().run()
        assertTrue(submit("after"))
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(listOf("before-1", "before-2", "consent-withdrawal", "reset", "after"), events)
        assertTrue(submit("following"))
        queued.removeFirst().run()
        assertEquals(1, events.count { it == "reset" })
    }

    @Test
    fun rejectedSubmissionAndFailedWorkReleaseCapacity() {
        val admission = RawMetadataTaskAdmission(maxPending = 1)
        val queued = ArrayDeque<Runnable>()
        val worker = Executor { queued.add(it) }
        var resets = 0
        assertFalse(admission.submit(Executor { throw RejectedExecutionException() }, {}) { fail() })
        assertTrue(admission.submit(worker, { resets++ }) { throw IllegalStateException("write failed") })
        assertThrows(IllegalStateException::class.java) { queued.removeFirst().run() }
        assertEquals(1, resets)
        assertTrue(admission.submit(worker, { resets++ }) {})
        queued.removeFirst().run()
        assertEquals(2, resets)
    }
}
