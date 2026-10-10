package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RawUploadCancellationConcurrencyTest {
    @Test
    fun deletionRemainsIncompleteUntilOldUploadZeroesPlaintext() {
        val rig = PostWalkRawRig()
        rig.capture(NP_WALK_1)
        val context = rig.after(NP_WALK_1)
        rig.coordinator.onWalkEnded(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.client.beforeReturn = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val call = requireNotNull(rig.coordinator.startNextUpload { context })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<RawCollectionUploadOutcome> { call.execute() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("cancel request is not plaintext release", rig.coordinator.onAccountDeleted())
            assertTrue(call.isCancelled())
            // Same process, new coordinator (as with Activity recreation), same encrypted store.
            rig.restartProcess()
            assertFalse("new coordinator must still see old plaintext", rig.coordinator.onAccountDeleted())
            assertEquals(0, rig.storage.collectionCount)
            assertTrue(rig.client.heldChunks.any { chunk -> chunk.plaintext.any { it != 0.toByte() } })
            assertFalse("new enrollment must not clear the fence during old upload cleanup",
                rig.coordinator.resetForNewEnrollment(requireNotNull(context.session), context.sessionGeneration, context.deviceCheck))
            release.countDown()
            assertEquals(RawCollectionUploadOutcome.CANCELLED, result.get(2, TimeUnit.SECONDS))
            assertTrue(rig.client.heldChunks.all { chunk -> chunk.plaintext.all { it == 0.toByte() } })
            assertTrue(rig.coordinator.onAccountDeleted())
            assertEquals(0, rig.storage.collectionCount)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationAndRevalidationDoNotWaitForCoordinatorOrStoreLocks() {
        for (invalidate in listOf(false, true)) {
            val rig = PostWalkRawRig()
            assertEquals(RawMetadataCaptureResult.CAPTURED, rig.capture(NP_WALK_1))
            val context = rig.after(NP_WALK_1)
            rig.coordinator.onWalkEnded(context)
            val call = requireNotNull(rig.coordinator.startNextUpload { context })
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val holder = executor.submit {
                    synchronized(rig.coordinator) {
                        synchronized(rig.store) {
                            locked.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
                assertTrue(locked.await(2, TimeUnit.SECONDS))
                executor.submit {
                    assertTrue(rig.coordinator.revalidate(context))
                    if (invalidate) assertFalse(rig.coordinator.revalidate(context.copy(charging = false)))
                    else rig.coordinator.cancelActiveUpload()
                }.get(2, TimeUnit.SECONDS)
                assertTrue(call.isCancelled())
                assertEquals(0, rig.client.calls)
                release.countDown()
                holder.get(2, TimeUnit.SECONDS)
                // Cancelling a not-yet-executed call releases the slot, without deleting data.
                assertNotNull(rig.coordinator.startNextUpload { context })
                assertEquals(1, rig.storage.collectionCount)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun cancellationDuringPreparationCannotPublishAnUncancelledUpload() {
        val rig = PostWalkRawRig()
        rig.capture(NP_WALK_1)
        val context = rig.after(NP_WALK_1)
        rig.coordinator.onWalkEnded(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val prepared = executor.submit<kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall<RawCollectionUploadOutcome>?> {
                rig.coordinator.startNextUpload {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    context
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            rig.coordinator.cancelActiveUpload()
            release.countDown()
            assertNull(prepared.get(2, TimeUnit.SECONDS))
            assertEquals(0, rig.client.calls)
            assertNotNull(rig.coordinator.startNextUpload { context })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun executingCancellationKeepsSlotUntilCleanupAndRejectsLateReceipt() {
        val rig = PostWalkRawRig()
        rig.capture(NP_WALK_1)
        val context = rig.after(NP_WALK_1)
        rig.coordinator.onWalkEnded(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.client.beforeReturn = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val call = requireNotNull(rig.coordinator.startNextUpload { context })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val executing = executor.submit<RawCollectionUploadOutcome> { call.execute() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            rig.coordinator.cancelActiveUpload()
            assertNull(rig.coordinator.startNextUpload { context })
            release.countDown()
            assertEquals(RawCollectionUploadOutcome.CANCELLED, executing.get(2, TimeUnit.SECONDS))
            assertEquals(1, rig.storage.collectionCount)
            rig.client.beforeReturn = null
            assertEquals(RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
                requireNotNull(rig.coordinator.startNextUpload { context }).execute())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
