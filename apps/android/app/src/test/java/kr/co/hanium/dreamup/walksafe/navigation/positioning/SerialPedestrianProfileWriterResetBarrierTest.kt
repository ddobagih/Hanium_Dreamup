package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S09-56 (originals/09.txt:154, w25 review pos-F2): the explicit profile reset of account deletion must not
 * race with the serial profile writer. A save being written when the reset starts, and saves still queued,
 * must not leave a stored profile after the reset has reported success.
 */
class SerialPedestrianProfileWriterResetBarrierTest {
    private class MemoryStorage : PedestrianProfileStorage {
        val values = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
        override fun isBlocked(): Boolean = false
        override fun contains(key: String): Boolean = key in values
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
        override fun failClosed() = Unit
        override fun resetAll(): Boolean {
            values.clear()
            return true
        }
    }

    private fun profile(stepLengthM: Double) = PersistedPedestrianMotionProfile(stepLengthM, 1.2, 0.05, 10L)

    @Test
    fun aResetBehindTheBarrierLeavesNoProfileEvenWhenASaveIsBeingWrittenAndAnotherIsQueued() {
        val storage = MemoryStorage()
        val store = AndroidPedestrianProfileStore(storage, nowEpochMs = { 1_000L })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = SerialPedestrianProfileWriter(executor)
            val saving = CountDownLatch(1)
            val release = CountDownLatch(1)
            assertTrue(
                writer.submit("actor-a", profile(0.71)) { id, value ->
                    saving.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    store.save(id, value)
                },
            )
            assertTrue(saving.await(5, TimeUnit.SECONDS))
            assertTrue(writer.submit("actor-b", profile(0.82)) { id, value -> store.save(id, value) })
            val result = AtomicReference<Boolean?>(null)
            val purge = Thread {
                result.set(writer.clearPendingAndRun { store.resetAllProfiles() })
            }.apply { start() }
            // The purge waits for the save in progress instead of resetting underneath it.
            purge.join(200)
            assertTrue(purge.isAlive)
            assertNull(writer.pendingProfileOrNull("actor-b"))
            release.countDown()
            purge.join(5_000)
            assertEquals(true, result.get())
            assertTrue("profiles left after the reset: ${storage.values.keys}", storage.values.isEmpty())
            assertNull(store.load("actor-a"))
            assertNull(store.load("actor-b"))
            assertNull(writer.pendingProfileOrNull("actor-a"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun theBarrierReportsFailureWhenTheResetCannotRunOrFinish() {
        val rejecting = SerialPedestrianProfileWriter { throw RejectedExecutionException("stopped") }
        var ran = false
        assertFalse(rejecting.clearPendingAndRun { ran = true; true })
        assertFalse(ran)

        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = SerialPedestrianProfileWriter(executor)
            val saving = CountDownLatch(1)
            val release = CountDownLatch(1)
            assertTrue(
                writer.submit("actor-a", profile(0.7)) { _, _ ->
                    saving.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    true
                },
            )
            assertTrue(saving.await(5, TimeUnit.SECONDS))
            assertFalse(writer.clearPendingAndRun(timeoutMs = 50L) { true })
            release.countDown()
            assertFalse(writer.clearPendingAndRun { error("reset failed") })
            assertFalse(writer.clearPendingAndRun { false })
            assertTrue(writer.clearPendingAndRun { true })
        } finally {
            executor.shutdownNow()
        }
    }
}
