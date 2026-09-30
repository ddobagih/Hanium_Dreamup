package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CancellableNetworkCall<T> internal constructor(
    private val executeBlock: () -> T,
    private val cancelBlock: () -> Unit = {},
    private val cancelledFailure: () -> Exception = { CancellationException("network call cancelled") },
    private val rejectLateResult: Boolean = false,
) {
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    fun execute(): T {
        check(started.compareAndSet(false, true)) { "network call can only be executed once" }
        if (cancelled.get()) throw cancelledFailure()
        val result = executeBlock()
        // Mapped route responses need this guard; other callers can return their own CANCELLED outcome.
        if (rejectLateResult && cancelled.get()) throw cancelledFailure()
        return result
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) cancelBlock()
    }

    fun isCancelled(): Boolean = cancelled.get()

    internal fun <R> map(transform: (T) -> R): CancellableNetworkCall<R> {
        return CancellableNetworkCall(
            executeBlock = { transform(execute()) },
            cancelBlock = ::cancel,
        )
    }

    /** Keeps the deadline active across mapping/parsing after the transport has returned. */
    internal fun withDeadline(
        deadlineMs: Long,
        deadlineScheduler: NetworkDeadlineScheduler = NetworkDeadlineScheduler.shared,
    ): CancellableNetworkCall<T> {
        require(deadlineMs > 0L) { "deadline must be positive" }
        val delegate = this
        val expired = AtomicBoolean(false)
        lateinit var deadlineCall: CancellableNetworkCall<T>
        deadlineCall = CancellableNetworkCall(
            executeBlock = {
                val cancelWatchdog = deadlineScheduler.schedule(deadlineMs) {
                    if (!deadlineCall.isCancelled() && expired.compareAndSet(false, true)) {
                        deadlineCall.cancel()
                    }
                }
                try {
                    val result = delegate.execute()
                    if (expired.get()) throw NetworkDeadlineExceededException(deadlineMs)
                    result
                } catch (error: Exception) {
                    if (!expired.get() || error is NetworkDeadlineExceededException) throw error
                    throw NetworkDeadlineExceededException(deadlineMs).apply { initCause(error) }
                } finally {
                    cancelWatchdog()
                }
            },
            cancelBlock = delegate::cancel,
            cancelledFailure = {
                if (expired.get()) NetworkDeadlineExceededException(deadlineMs)
                else CancellationException("network call cancelled")
            },
            rejectLateResult = true,
        )
        return deadlineCall
    }

    internal companion object {
        fun <T> blocking(block: () -> T): CancellableNetworkCall<T> = CancellableNetworkCall(block)
    }
}

class ActiveNetworkCalls {
    private val calls = ConcurrentHashMap.newKeySet<CancellableNetworkCall<*>>()

    fun <T> track(call: CancellableNetworkCall<T>): CancellableNetworkCall<T> {
        calls += call
        return call
    }

    fun complete(call: CancellableNetworkCall<*>) {
        calls -= call
    }

    fun cancelAll() {
        calls.toList().forEach(CancellableNetworkCall<*>::cancel)
    }
}

internal enum class AndroidNavigationRequestEvent {
    DESTINATION_SELECTED,
    ACTIVITY_PAUSED,
    ACTIVITY_DESTROYED,
    GATEWAY_SESSION_CLEARED,
}

internal data class AndroidNavigationCancellation(
    val routeCancelled: Boolean,
    val destinationSearchCancelled: Boolean,
)

internal class AndroidNavigationRequestCoordinator {
    private val route = AtomicReference<CancellableNetworkCall<*>?>(null)
    private val destinationSearch = AtomicReference<CancellableNetworkCall<*>?>(null)

    fun <T> trackRoute(call: CancellableNetworkCall<T>): CancellableNetworkCall<T> {
        check(route.compareAndSet(null, call)) { "route request is already active" }
        return call
    }

    fun <T> trackDestinationSearch(call: CancellableNetworkCall<T>): CancellableNetworkCall<T> {
        check(destinationSearch.compareAndSet(null, call)) { "destination search is already active" }
        return call
    }

    fun completeRoute(call: CancellableNetworkCall<*>) {
        route.compareAndSet(call, null)
    }

    fun completeDestinationSearch(call: CancellableNetworkCall<*>) {
        destinationSearch.compareAndSet(call, null)
    }

    fun hasActiveRoute(): Boolean = route.get() != null

    fun hasActiveDestinationSearch(): Boolean = destinationSearch.get() != null

    fun cancelRoute(): Boolean = cancel(route)

    fun cancelDestinationSearch(): Boolean = cancel(destinationSearch)

    fun handle(event: AndroidNavigationRequestEvent): AndroidNavigationCancellation {
        return when (event) {
            AndroidNavigationRequestEvent.DESTINATION_SELECTED,
            AndroidNavigationRequestEvent.ACTIVITY_PAUSED,
            AndroidNavigationRequestEvent.ACTIVITY_DESTROYED,
            AndroidNavigationRequestEvent.GATEWAY_SESSION_CLEARED,
            -> AndroidNavigationCancellation(
                routeCancelled = cancelRoute(),
                destinationSearchCancelled = cancelDestinationSearch(),
            )
        }
    }

    private fun cancel(slot: AtomicReference<CancellableNetworkCall<*>?>): Boolean {
        val call = slot.getAndSet(null) ?: return false
        call.cancel()
        return true
    }
}

internal class HttpConnectionCancellation {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()
    private val stream = AtomicReference<Closeable?>()

    fun attach(value: HttpURLConnection) {
        check(connection.compareAndSet(null, value)) { "HTTP connection already attached" }
        if (cancelled.get() && connection.compareAndSet(value, null)) {
            value.disconnect()
            throw CancellationException("network call cancelled")
        }
    }

    fun detach(value: HttpURLConnection) {
        connection.compareAndSet(value, null)
    }

    fun attach(value: Closeable) {
        check(stream.compareAndSet(null, value)) { "HTTP stream already attached" }
        if (cancelled.get() && stream.compareAndSet(value, null)) {
            runCatching { value.close() }
            throw CancellationException("network call cancelled")
        }
    }

    fun detach(value: Closeable) {
        stream.compareAndSet(value, null)
    }

    fun cancel() {
        cancelled.set(true)
        // Disconnect first: closing a request stream whose write is blocked waits for that write's
        // lock (JDK StreamingOutputStream.close -> flush), so the socket must be closed before it.
        connection.getAndSet(null)?.disconnect()
        runCatching { stream.getAndSet(null)?.close() }
    }

    fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("network call cancelled")
    }

    fun isCancelled(): Boolean = cancelled.get()
}

/**
 * A whole-request deadline expired and the call was cancelled through [HttpConnectionCancellation]
 * (S15-23). It is a timeout, not a user cancellation, so callers keep treating it like any other
 * socket timeout (the queued item stays and a later drain retries it).
 */
class NetworkDeadlineExceededException(val deadlineMs: Long) :
    SocketTimeoutException("network call exceeded its $deadlineMs ms deadline")

/** Schedules the whole-request deadline watchdog; replaceable in tests. */
internal fun interface NetworkDeadlineScheduler {
    /** Runs [action] once after [delayMs] unless the returned handle is cancelled first. */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit

    companion object {
        private val executor: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "walksafe-network-deadline").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
        }

        val shared = NetworkDeadlineScheduler { delayMs, action ->
            val future = executor.schedule(Runnable { action() }, delayMs, TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { future.cancel(false) }
            cancel
        }
    }
}

/**
 * [deadlineMs] bounds the whole request (connect, body write, response wait and read). Connect and
 * read timeouts alone do not bound a stalled body write or the sum of the stages; on expiry the
 * existing cancellation closes the stream and disconnects, and the call fails with
 * [NetworkDeadlineExceededException]. `null` keeps the previous behaviour.
 */
internal fun <T> cancellableHttpCall(
    deadlineMs: Long? = null,
    deadlineScheduler: NetworkDeadlineScheduler = NetworkDeadlineScheduler.shared,
    block: (HttpConnectionCancellation) -> T,
): CancellableNetworkCall<T> {
    require(deadlineMs == null || deadlineMs > 0L) { "deadline must be positive" }
    val cancellation = HttpConnectionCancellation()
    val deadlineExpired = AtomicBoolean(false)
    return CancellableNetworkCall(
        executeBlock = {
            cancellation.throwIfCancelled()
            val cancelWatchdog = deadlineMs?.let { limit ->
                deadlineScheduler.schedule(limit) {
                    if (!cancellation.isCancelled() && deadlineExpired.compareAndSet(false, true)) {
                        cancellation.cancel()
                    }
                }
            }
            try {
                block(cancellation).also { cancellation.throwIfCancelled() }
            } catch (error: Exception) {
                if (!cancellation.isCancelled()) throw error
                if (deadlineExpired.get() && deadlineMs != null) {
                    throw NetworkDeadlineExceededException(deadlineMs).apply { initCause(error) }
                }
                throw CancellationException("network call cancelled").apply { initCause(error) }
            } finally {
                cancelWatchdog?.invoke()
            }
        },
        cancelBlock = cancellation::cancel,
    )
}

internal data class BoundedHttpResponse(
    val statusCode: Int,
    val body: String,
)

class NetworkResponseTooLargeException(
    val maximumBytes: Int,
) : IllegalStateException("network response exceeds $maximumBytes bytes")

class NetworkResponseProtocolException(val reason: String) :
    IOException("invalid HTTP response: $reason")

internal fun HttpURLConnection.readBoundedResponse(
    maximumBytes: Int,
    cancellation: HttpConnectionCancellation,
    strictSuccessFraming: Boolean = false,
): BoundedHttpResponse {
    require(maximumBytes > 0)
    // A cancelled or expired call must not reach responseCode: some HttpURLConnection
    // implementations silently reconnect and resend after disconnect().
    cancellation.throwIfCancelled()
    val status = responseCode
    val strict = strictSuccessFraming && status in 200..299
    val contentLength = if (strict) strictBodyLength() else getHeaderFieldLong("Content-Length", -1L)
    if (contentLength > maximumBytes.toLong()) {
        throw NetworkResponseTooLargeException(maximumBytes)
    }
    val stream = if (status in 200..299) inputStream else errorStream
    if (stream != null) cancellation.attach(stream)
    return BoundedHttpResponse(
        statusCode = status,
        body = try {
            if (stream == null && strict && contentLength > 0L) {
                throw NetworkResponseProtocolException("content_length_mismatch")
            }
            stream?.use { it.readBoundedText(maximumBytes, contentLength, cancellation, strict) }.orEmpty()
        } finally {
            if (stream != null) cancellation.detach(stream)
        },
    )
}

private fun HttpURLConnection.strictBodyLength(): Long {
    val headers = headerFields
    fun values(name: String): List<String> = headers.entries
        .filter { entry -> entry.key?.equals(name, ignoreCase = true) == true }
        .flatMap { entry -> entry.value.orEmpty() }

    val encodings = values("Content-Encoding")
    if (encodings.any { !it.trim().equals("identity", ignoreCase = true) }) {
        throw NetworkResponseProtocolException("content_encoding_unsupported")
    }
    val transfers = values("Transfer-Encoding")
    val chunked = when {
        transfers.isEmpty() -> false
        transfers.size == 1 && transfers.single().trim().equals("chunked", ignoreCase = true) -> true
        else -> throw NetworkResponseProtocolException("transfer_encoding_invalid")
    }
    val lengths = values("Content-Length")
    if (chunked && lengths.isNotEmpty()) {
        throw NetworkResponseProtocolException("response_framing_ambiguous")
    }
    if (lengths.isEmpty()) {
        return -1L
    }
    if (lengths.size != 1) throw NetworkResponseProtocolException("content_length_invalid")
    val literal = lengths.single().trim()
    if (literal.isEmpty() || literal.any { !it.isDigit() }) {
        throw NetworkResponseProtocolException("content_length_invalid")
    }
    return literal.toLongOrNull() ?: throw NetworkResponseProtocolException("content_length_invalid")
}

private fun InputStream.readBoundedText(
    maximumBytes: Int,
    contentLength: Long,
    cancellation: HttpConnectionCancellation,
    strict: Boolean,
): String {
    val initialSize = contentLength
        .takeIf { it in 0..maximumBytes.toLong() }
        ?.toInt()
        ?: minOf(8 * 1024, maximumBytes)
    val output = ByteArrayOutputStream(initialSize)
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0
    while (true) {
        cancellation.throwIfCancelled()
        val read = try {
            read(buffer, 0, minOf(buffer.size, maximumBytes - totalBytes + 1))
        } catch (error: IOException) {
            if (!strict || error is SocketTimeoutException) throw error
            throw NetworkResponseProtocolException("response_body_incomplete").apply { initCause(error) }
        }
        cancellation.throwIfCancelled()
        if (read < 0) break
        totalBytes += read
        if (totalBytes > maximumBytes) throw NetworkResponseTooLargeException(maximumBytes)
        output.write(buffer, 0, read)
    }
    if (strict && contentLength >= 0L && totalBytes.toLong() != contentLength) {
        throw NetworkResponseProtocolException("content_length_mismatch")
    }
    if (!strict) return output.toString(Charsets.UTF_8.name())
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
    } catch (error: CharacterCodingException) {
        throw NetworkResponseProtocolException("response_utf8_invalid").apply { initCause(error) }
    }
}

internal fun newNavigationRequestExecutor(): ExecutorService {
    return Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "walksafe-route-client").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
}
