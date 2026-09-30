package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * S09-55: pedestrian-profile saves (a synchronous encrypted commit) leave the UI/sensor thread.
 * Each save is pinned to the actor and store it was taken for, so an account change after
 * submission can never redirect it; saves still waiting for the same actor collapse into the
 * latest one. A single serial executor keeps different actors' saves in submission order.
 */
class SerialPedestrianProfileWriter(private val executor: Executor) {
    private class PendingSave(
        val profile: PersistedPedestrianMotionProfile,
        val save: (String, PersistedPedestrianMotionProfile) -> Boolean,
    )

    private val pending = HashMap<String, PendingSave>()

    /** Returns false when the executor refused the work; nothing is left pending then. */
    fun submit(
        actorId: String,
        profile: PersistedPedestrianMotionProfile,
        save: (String, PersistedPedestrianMotionProfile) -> Boolean,
    ): Boolean {
        val scheduleDrain = synchronized(pending) { pending.put(actorId, PendingSave(profile, save)) == null }
        if (!scheduleDrain) return true
        return try {
            executor.execute { drain(actorId) }
            true
        } catch (_: RejectedExecutionException) {
            synchronized(pending) { pending.remove(actorId) }
            false
        }
    }

    /**
     * S09-56 (w25 review pos-F2): barrier for an explicit profile reset such as account deletion. Every
     * save not yet written is dropped, then [block] runs on the same serial I/O executor, after a save that
     * is already being written, and the caller waits for it. Returns [block]'s result, or false when the
     * executor refuses the work, [block] fails, or it does not finish within [timeoutMs]; the caller then
     * keeps its deletion evidence unconfirmed.
     */
    fun clearPendingAndRun(timeoutMs: Long = BARRIER_TIMEOUT_MS, block: () -> Boolean): Boolean {
        synchronized(pending) { pending.clear() }
        val task = FutureTask<Boolean> { block() }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            return false
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (_: ExecutionException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** A save not yet written wins over the stored value, so a quick account switch-back reads it. */
    fun pendingProfileOrNull(actorId: String): PersistedPedestrianMotionProfile? =
        synchronized(pending) { pending[actorId]?.profile }

    /**
     * The entry stays visible to [pendingProfileOrNull] until its save has returned, so a reader never
     * falls back to the stored value while that value is still the old one (S09-55 review). A newer
     * save submitted meanwhile replaces the entry without a second drain; it is written here next.
     */
    private fun drain(actorId: String) {
        var job = synchronized(pending) { pending[actorId] } ?: return
        while (true) {
            val current = job
            runCatching { current.save(actorId, current.profile) }
            job = synchronized(pending) {
                val latest = pending[actorId]
                if (latest === current) pending.remove(actorId)
                latest?.takeIf { it !== current }
            } ?: return
        }
    }

    companion object {
        /** Upper bound for [clearPendingAndRun]; one encrypted commit normally takes milliseconds. */
        const val BARRIER_TIMEOUT_MS = 10_000L

        /** Process-wide, so recreated activities share one ordered writer and no thread leaks. */
        val shared: SerialPedestrianProfileWriter by lazy {
            SerialPedestrianProfileWriter(
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "walksafe-pedestrian-profile-io").apply {
                        isDaemon = true
                        priority = Thread.NORM_PRIORITY - 1
                    }
                },
            )
        }
    }
}
