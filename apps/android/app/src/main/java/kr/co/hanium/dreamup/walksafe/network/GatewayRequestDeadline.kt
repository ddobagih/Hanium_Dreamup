package kr.co.hanium.dreamup.walksafe.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One monotonic call budget. Caller cancellation never claims that underlying I/O has ended. */
internal class GatewayRequestDeadline(
    totalTimeoutMs: Long,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private val startedAtNanos = clockNanos()
    private val budgetNanos = TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs)
    private val cancelled = AtomicBoolean(false)

    init { require(totalTimeoutMs in 1L..120_000L) }

    fun remainingTimeoutMs(): Int {
        if (cancelled.get()) throw SocketTimeoutException("gateway request cancelled or timed out")
        val remaining = budgetNanos - (clockNanos() - startedAtNanos).coerceAtLeast(0L)
        if (remaining <= 0L) throw SocketTimeoutException("gateway request total deadline exceeded")
        return ((remaining + 999_999L) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun <T> await(cancelOperation: () -> Unit = {}, operation: () -> T): T {
        val task = FutureTask<T> {
            remainingTimeoutMs()
            operation().also { remainingTimeoutMs() }
        }
        try {
            calls.execute(task)
        } catch (_: RejectedExecutionException) {
            throw IOException("gateway request capacity exhausted")
        }
        fun cancel() {
            cancelled.set(true)
            task.cancel(true)
            // A blocked platform DNS/socket close cannot hold the caller past its deadline.
            // The bounded executor and the operation's finally also retain cleanup ownership.
            runCatching { cleanup.execute(cancelOperation) }
        }
        return try {
            task.get(remainingTimeoutMs().toLong(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            cancel()
            throw SocketTimeoutException("gateway request total deadline exceeded")
        } catch (error: SocketTimeoutException) {
            cancel()
            throw error
        } catch (_: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("gateway request interrupted")
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private companion object {
        fun executor(name: String) = ThreadPoolExecutor(
            0, 4, 60L, TimeUnit.SECONDS, SynchronousQueue(),
            { runnable -> Thread(runnable, name).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val calls = executor("walksafe-otp-request")
        val cleanup = executor("walksafe-otp-cancel")
    }
}
