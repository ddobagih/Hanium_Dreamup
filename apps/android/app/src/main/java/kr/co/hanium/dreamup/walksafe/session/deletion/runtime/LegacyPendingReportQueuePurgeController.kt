package kr.co.hanium.dreamup.walksafe.session.deletion.runtime

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Owns the original report cleanup lifetime and coalesced legacy-purge waiters. */
internal class LegacyPendingReportQueuePurgeController(
    val activityToken: String,
    private val persistentQueueEnabled: Boolean,
    private val executor: () -> Executor,
    private val callbacks: LegacyQueuePurgeCallbacks,
    private val purgeLegacyStore: () -> Boolean,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val lock = Any()
    private var inFlight = false
    private var succeeded = false
    private val waiters = mutableListOf<Waiter>()
    private var generation = 0L
    @Volatile private var destroyed = false
    val currentGeneration get() = generation
    val isDestroyed get() = destroyed
    fun schedule(
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        if (persistentQueueEnabled) {
            val token = activityToken
            val generation = this.generation
            callbacks.post {
                if (
                    !destroyed &&
                    token == activityToken &&
                    generation == this.generation
                ) {
                    onComplete(false)
                }
            }
            return false
        }
        val executionGeneration = synchronized(lock) {
            if (destroyed) return false
            if (this.succeeded) {
                val token = activityToken
                val generation = this.generation
                callbacks.post {
                    if (
                        !destroyed &&
                        token == activityToken &&
                        generation == this.generation
                    ) {
                        onComplete(true)
                    }
                }
                return true
            }
            if (inFlight) {
                this.waiters +=
                    Waiter(
                        activityToken = activityToken,
                        generation = this.generation,
                        callback = onComplete,
                    )
                return false
            }
            inFlight = true
            this.generation += 1L
            this.waiters +=
                Waiter(
                    activityToken = activityToken,
                    generation = this.generation,
                    callback = onComplete,
                )
            this.generation
        }
        try {
            executor().execute {
                val succeeded = purgeOnWorker()
                complete(
                    generation = executionGeneration,
                    succeeded = succeeded,
                )
            }
        } catch (_: RejectedExecutionException) {
            complete(
                generation = executionGeneration,
                succeeded = false,
            )
        }
        return false
    }

    fun purgeOnWorker(): Boolean {
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0) {
                try {
                    sleep(
                        INITIAL_BACKOFF_MS *
                            (1L shl (attempt - 1)),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            val succeeded =
                try {
                    purgeLegacyStore()
                } catch (_: RuntimeException) {
                    false
                }
            if (succeeded) return true
        }
        return false
    }

    fun complete(
        generation: Long,
        succeeded: Boolean,
    ) {
        val waiters = synchronized(lock) {
            if (
                destroyed ||
                generation != this.generation
            ) {
                return
            }
            this.succeeded = succeeded
            inFlight = false
            this.waiters.toList().also {
                this.waiters.clear()
            }
        }
        callbacks.post {
            waiters.forEach { waiter ->
                if (
                    !destroyed &&
                    waiter.activityToken == activityToken &&
                    waiter.generation == this.generation
                ) {
                    waiter.callback(succeeded)
                }
            }
        }
    }

    fun destroy() = synchronized(lock) {
        destroyed = true
        generation += 1L
        inFlight = false
        waiters.clear()
    }
    private data class Waiter(val activityToken: String, val generation: Long, val callback: (Boolean) -> Unit)
    private companion object { const val MAX_ATTEMPTS = 3; const val INITIAL_BACKOFF_MS = 250L }
}
internal fun interface LegacyQueuePurgeCallbacks { fun post(callback: () -> Unit): Boolean }
