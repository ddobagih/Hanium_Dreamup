package kr.co.hanium.dreamup.walksafe.runtime.metric

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MetricParentLane(val queueCapacity: Int) {
    COORDINATOR(1), IPC(2), UNBIND(1), EXIT(2),
}

/** Process-wide bounds also apply after a timed-out Android/Binder call refuses to return. */
internal class MetricParentExecution internal constructor(private val threadPrefix: String = "WalkSafeMetricSupport") {
    private val lanes = MetricParentLane.entries.associateWith { lane ->
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(lane.queueCapacity),
            { task -> Thread(task, "$threadPrefix-${lane.name.lowercase()}").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }

    fun <T> submit(lane: MetricParentLane, transport: MetricParentTransport? = null, action: Callable<T>): Future<T> {
        val work = transport?.reserve() ?: if (transport != null)
            throw RejectedExecutionException("metric_transport_retired") else null
        val result = CompletableFuture<T>()
        val task = Runnable {
            var outcome: Result<T>? = null
            try {
                // A cancelled queued Future has not run, but its transport reservation remains until here.
                if (!result.isCancelled) outcome = Result.success(action.call())
            } catch (failure: Throwable) { outcome = Result.failure(failure) }
            finally { work?.finish() }
            outcome?.fold(result::complete, result::completeExceptionally)
        }
        try { lanes.getValue(lane).execute(task) }
        catch (failure: Throwable) { work?.finish(); throw failure }
        return result
    }

    internal fun queueCapacity(lane: MetricParentLane) = lane.queueCapacity
    internal fun poolSize(lane: MetricParentLane) = lanes.getValue(lane).poolSize
    internal fun queued(lane: MetricParentLane) = lanes.getValue(lane).queue.size
    internal fun shutdownForTest() { lanes.values.forEach { it.shutdown() } }

    companion object {
        private val shared by lazy { MetricParentExecution() }
        fun process(): MetricParentExecution = shared
    }
}

/**
 * Native-free is different from a returned parent system IPC. Only actual Runnable finally blocks
 * drain these reservations; Future cancellation, semantic timeout and executor shutdown cannot.
 * The orchestration body must finish separately so it never waits for its own final release.
 */
internal class MetricParentTransport(private val changed: () -> Unit) {
    private val lock = Object()
    private var tasks = 0
    private var outerFinished = false
    private var releaseClaimed = false

    internal class Work(private val owner: MetricParentTransport) {
        private val finished = AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) owner.finished() }
    }

    fun reserve(): Work? = synchronized(lock) {
        if (releaseClaimed) null else { tasks++; Work(this) }
    }

    private fun finished() {
        synchronized(lock) { check(tasks > 0); tasks--; lock.notifyAll() }
        changed()
    }

    fun outerFinished() {
        synchronized(lock) { outerFinished = true; lock.notifyAll() }
        changed()
    }

    fun awaitDrained(timeoutMs: Long): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        synchronized(lock) {
            while (tasks != 0) {
                val left = end - System.nanoTime()
                if (left <= 0L) return false
                TimeUnit.NANOSECONDS.timedWait(lock, left)
            }
            return true
        }
    }

    fun claimRelease(nativeConfirmed: Boolean, bindingConfirmed: Boolean = true): Boolean = synchronized(lock) {
        if (!nativeConfirmed || !bindingConfirmed || releaseClaimed || !outerFinished || tasks != 0) false
        else { releaseClaimed = true; true }
    }

    internal fun pendingTasks(): Int = synchronized(lock) { tasks }
}
