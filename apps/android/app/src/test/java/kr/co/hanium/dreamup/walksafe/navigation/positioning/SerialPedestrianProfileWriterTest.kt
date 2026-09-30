package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S09-55: profile saves run on a serial I/O executor, pinned to the actor they were taken for. */
class SerialPedestrianProfileWriterTest {
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private fun profile(stepLengthM: Double) = PersistedPedestrianMotionProfile(stepLengthM, 1.2, 0.05, 10L)

    @Test fun submissionDoesNotWriteOnTheCallingThread() {
        val executor = ManualExecutor()
        val writes = mutableListOf<Pair<String, Double>>()
        val writer = SerialPedestrianProfileWriter(executor)
        assertTrue(writer.submit("actor-a", profile(0.7)) { id, value -> writes += id to value.stepLengthM; true })
        assertTrue(writes.isEmpty())
        executor.runAll()
        assertEquals(listOf("actor-a" to 0.7), writes)
    }

    @Test fun theActorIsFixedAtSubmissionEvenIfTheAccountChangesBeforeTheWrite() {
        val executor = ManualExecutor()
        val writes = mutableListOf<Pair<String, Double>>()
        val writer = SerialPedestrianProfileWriter(executor)
        var currentActor = "actor-a"
        writer.submit(currentActor, profile(0.71)) { id, value -> writes += id to value.stepLengthM; true }
        currentActor = "actor-b"
        executor.runAll()
        assertEquals(listOf("actor-a" to 0.71), writes)
        assertEquals("actor-b", currentActor)
    }

    @Test fun waitingSavesForOneActorCollapseToTheLatestAndOrderIsKept() {
        val executor = ManualExecutor()
        val writes = mutableListOf<Pair<String, Double>>()
        val writer = SerialPedestrianProfileWriter(executor)
        val save: (String, PersistedPedestrianMotionProfile) -> Boolean = { id, value -> writes += id to value.stepLengthM; true }
        writer.submit("actor-a", profile(0.70), save)
        writer.submit("actor-b", profile(0.80), save)
        writer.submit("actor-a", profile(0.72), save)
        assertEquals(0.72, writer.pendingProfileOrNull("actor-a")?.stepLengthM)
        assertEquals(2, executor.tasks.size)
        executor.runAll()
        assertEquals(listOf("actor-a" to 0.72, "actor-b" to 0.80), writes)
        assertNull(writer.pendingProfileOrNull("actor-a"))
    }

    @Test fun aRejectedExecutorLeavesNothingPendingAndAFailingSaveDoesNotEscape() {
        val rejecting = SerialPedestrianProfileWriter { throw RejectedExecutionException("stopped") }
        assertFalse(rejecting.submit("actor-a", profile(0.7)) { _, _ -> true })
        assertNull(rejecting.pendingProfileOrNull("actor-a"))
        val executor = ManualExecutor()
        val writer = SerialPedestrianProfileWriter(executor)
        writer.submit("actor-a", profile(0.7)) { _, _ -> error("storage blocked") }
        executor.runAll()
        assertNull(writer.pendingProfileOrNull("actor-a"))
    }

    /**
     * S09-55 review: while the I/O thread is inside store.save, the main thread must still see the
     * pending value; otherwise it loads the not-yet-committed old value after a quick switch-back.
     */
    @Test fun thePendingValueStaysReadableUntilItsSaveHasReturned() {
        val executor = ManualExecutor()
        val writer = SerialPedestrianProfileWriter(executor)
        val seenDuringSave = mutableListOf<Double?>()
        writer.submit("actor-a", profile(0.70)) { id, _ ->
            seenDuringSave += writer.pendingProfileOrNull(id)?.stepLengthM
            true
        }
        executor.runAll()
        assertEquals(listOf<Double?>(0.70), seenDuringSave)
        assertNull(writer.pendingProfileOrNull("actor-a"))
    }

    @Test fun aSaveSubmittedWhileAnotherIsBeingWrittenIsWrittenAfterItAndNothingIsLost() {
        val executor = ManualExecutor()
        val writer = SerialPedestrianProfileWriter(executor)
        val writes = mutableListOf<Double>()
        val seenAfterResubmit = mutableListOf<Double?>()
        lateinit var save: (String, PersistedPedestrianMotionProfile) -> Boolean
        save = { id, value ->
            writes += value.stepLengthM
            if (value.stepLengthM == 0.70) {
                writer.submit(id, profile(0.75), save)
                seenAfterResubmit += writer.pendingProfileOrNull(id)?.stepLengthM
            }
            true
        }
        writer.submit("actor-a", profile(0.70), save)
        executor.runAll()
        assertEquals(listOf(0.70, 0.75), writes)
        assertEquals(listOf<Double?>(0.75), seenAfterResubmit)
        assertNull(writer.pendingProfileOrNull("actor-a"))
    }

    @Test fun mainActivityNoLongerCommitsTheProfileOnItsOwnThread() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val persist = source.substringAfter("private fun persistPositioningProfileForCurrentActor() {")
            .substringBefore("private fun updatePositioningHeadingInputs(")
        assertFalse(persist.contains("runCatching { pedestrianProfileStore?.save("))
        assertTrue(persist.contains("SerialPedestrianProfileWriter.shared"))
        assertTrue(persist.contains(".submit(actorId, profile) { id, value -> store.save(id, value) }"))
        val restore = source.substringAfter("private fun ensurePositioningProfileForCurrentActor() {")
            .substringBefore("private fun persistPositioningProfileForCurrentActor() {")
        // S09-56: the store read is loadResult(id) (status kept for the field log).
        assertTrue(restore.indexOf(".pendingProfileOrNull(id)") in 0 until restore.indexOf("pedestrianProfileStore?.loadResult(id)"))
    }
}
