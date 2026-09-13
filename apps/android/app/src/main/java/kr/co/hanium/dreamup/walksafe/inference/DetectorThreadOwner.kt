package kr.co.hanium.dreamup.walksafe.inference

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/** Owns the thread used to create, invoke, and release a thread-affine detector. */
internal class DetectorThreadOwner(
    threadName: String = "walksafe-inference",
) {
    private val stateLock = Any()

    @Volatile
    private var ownerThread: Thread? = null

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).also { ownerThread = it }
    }
    private var closeTask: FutureTask<Unit>? = null

    /** Runs synchronously on the owner thread. Calls accepted before close finish before release. */
    fun <T> call(block: () -> T): T {
        val task = synchronized(stateLock) {
            if (closeTask != null) {
                throw RejectedExecutionException("Detector thread owner is closed")
            }
            if (Thread.currentThread() === ownerThread) {
                null
            } else {
                FutureTask(Callable { block() }).also(executor::execute)
            }
        }
        // A nested call is part of the current task, so submitting it would deadlock.
        return if (task == null) block() else awaitCompletion(task)
    }

    /**
     * Stops admission, then releases on the owner thread after all accepted work.
     * External callers all wait for the same release and observe its failure, if any.
     * The first close must be external: the owner cannot wait for its own queued work.
     * A reentrant close after shutdown has started returns immediately on the owner.
     */
    fun close(release: () -> Unit) {
        val fromOwner = Thread.currentThread() === ownerThread
        val task = synchronized(stateLock) {
            closeTask ?: run {
                check(!fromOwner) { "Detector thread owner cannot initiate close from its own task" }
                FutureTask(Callable { release() }).also { closing ->
                    closeTask = closing
                    executor.execute(closing)
                    executor.shutdown()
                }
            }
        }
        if (!fromOwner) {
            awaitCompletion(task)
        }
    }

    private fun <T> awaitCompletion(task: Future<T>): T {
        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (_: InterruptedException) {
                    // The caller may still own the camera Image used by the accepted task.
                    // Returning or cancelling here could let that image close during inference.
                    interrupted = true
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
