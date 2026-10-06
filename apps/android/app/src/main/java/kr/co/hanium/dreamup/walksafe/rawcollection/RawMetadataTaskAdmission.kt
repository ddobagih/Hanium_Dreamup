package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/** Bounds frame metadata work without dropping consent, deletion or walk-end tasks.
 * Use one serial executor. After a missing sample, reset the aggregate on that executor
 * before the next accepted sample; an incomplete interval must not look like a full window.
 */
internal class RawMetadataTaskAdmission(maxPending: Int = 8) {
    private val permits = Semaphore(maxPending.also { require(it > 0) })
    private val gapGeneration = AtomicLong()
    private var lastAcceptedGeneration = 0L // Serial executor only.
    private var resetAfterFailure = false

    fun submit(executor: Executor, resetWindow: () -> Unit, task: () -> Unit): Boolean {
        if (!permits.tryAcquire()) {
            gapGeneration.incrementAndGet()
            return false
        }
        val generation = gapGeneration.get()
        try {
            executor.execute {
                try {
                    if (resetAfterFailure || lastAcceptedGeneration != generation) {
                        resetWindow()
                        lastAcceptedGeneration = generation
                        resetAfterFailure = false
                    }
                    task()
                } catch (failure: Exception) {
                    resetAfterFailure = true
                    throw failure
                } finally {
                    permits.release()
                }
            }
        } catch (_: RejectedExecutionException) {
            permits.release()
            gapGeneration.incrementAndGet()
            return false
        }
        return true
    }
}
