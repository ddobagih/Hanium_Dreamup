package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class RawMetadataTaskAdmissionTest {
    @Test fun boundsOnlyMetadataAndResetsAfterTheMissingSampleBeforeAcceptingTheNext() {
        val queue = ArrayDeque<Runnable>()
        val executor = Executor { queue.add(it) }
        val admission = RawMetadataTaskAdmission(2)
        val events = mutableListOf<String>()
        fun submit(label: String) = admission.submit(executor, { events += "reset" }) { events += label }
        assertTrue(submit("a")); assertTrue(submit("b")); assertFalse(submit("dropped"))
        executor.execute { events += "consent" }
        executor.execute { events += "delete" }
        executor.execute { events += "END" }
        queue.removeFirst().run(); queue.removeFirst().run()
        assertTrue(submit("after-gap"))
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(listOf("a", "b", "consent", "delete", "END", "reset", "after-gap"), events)
    }

    @Test fun oneThousandSubmissionsDuringAStalledWorkerKeepEightMetadataSlotsAndAllCriticalTasks() {
        val queue = ArrayDeque<Runnable>()
        val executor = Executor { queue.add(it) }
        val admission = RawMetadataTaskAdmission(8)
        val events = mutableListOf<String>()
        var accepted = 0
        repeat(1_000) { index ->
            if (admission.submit(executor, { events += "reset" }) { events += "metadata:$index" }) accepted++
        }
        assertEquals(8, accepted); assertEquals(8, queue.size)
        for (critical in listOf("consent", "delete", "END")) executor.execute { events += critical }
        assertEquals(11, queue.size)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals((0..7).map { "metadata:$it" } + listOf("consent", "delete", "END"), events)
        assertTrue(admission.submit(executor, { events += "reset" }) { events += "after-gap" })
        queue.removeFirst().run()
        assertEquals(listOf("reset", "after-gap"), events.takeLast(2))
    }

    @Test fun rejectedAndFailedTasksReleaseCapacityAndDoNotBridgeTheMissingInterval() {
        val admission = RawMetadataTaskAdmission(1)
        var resets = 0
        assertFalse(admission.submit(Executor { throw RejectedExecutionException() }, { resets++ }) { fail() })
        assertThrows(IllegalStateException::class.java) {
            admission.submit(Executor { it.run() }, { resets++ }) { throw IllegalStateException("fixture") }
        }
        assertTrue(admission.submit(Executor { it.run() }, { resets++ }) {})
        assertEquals(2, resets)
    }
}
