package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CancellableNetworkCall<T> internal constructor(
    private val executeBlock: () -> T,
    private val cancelBlock: () -> Unit = {},
) {
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    fun execute(): T {
        check(started.compareAndSet(false, true)) { "network call can only be executed once" }
        if (cancelled.get()) throw CancellationException("network call cancelled")
        return executeBlock()
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
        runCatching { stream.getAndSet(null)?.close() }
        connection.getAndSet(null)?.disconnect()
    }

    fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("network call cancelled")
    }

    fun isCancelled(): Boolean = cancelled.get()
}

internal fun <T> cancellableHttpCall(
    block: (HttpConnectionCancellation) -> T,
): CancellableNetworkCall<T> {
    val cancellation = HttpConnectionCancellation()
    return CancellableNetworkCall(
        executeBlock = {
            cancellation.throwIfCancelled()
            try {
                block(cancellation).also { cancellation.throwIfCancelled() }
            } catch (error: Exception) {
                if (!cancellation.isCancelled()) throw error
                throw CancellationException("network call cancelled").apply { initCause(error) }
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

internal fun HttpURLConnection.readBoundedResponse(
    maximumBytes: Int,
    cancellation: HttpConnectionCancellation,
): BoundedHttpResponse {
    require(maximumBytes > 0)
    val status = responseCode
    val contentLength = getHeaderFieldLong("Content-Length", -1L)
    if (contentLength > maximumBytes.toLong()) {
        throw NetworkResponseTooLargeException(maximumBytes)
    }
    val stream = if (status in 200..299) inputStream else errorStream
    if (stream != null) cancellation.attach(stream)
    return BoundedHttpResponse(
        statusCode = status,
        body = try {
            stream?.use { it.readBoundedText(maximumBytes, contentLength) }.orEmpty()
        } finally {
            if (stream != null) cancellation.detach(stream)
        },
    )
}

private fun InputStream.readBoundedText(maximumBytes: Int, contentLength: Long): String {
    val initialSize = contentLength
        .takeIf { it in 0..maximumBytes.toLong() }
        ?.toInt()
        ?: minOf(8 * 1024, maximumBytes)
    val output = ByteArrayOutputStream(initialSize)
    val buffer = ByteArray(8 * 1024)
    var totalBytes = 0
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, maximumBytes - totalBytes + 1))
        if (read < 0) break
        totalBytes += read
        if (totalBytes > maximumBytes) throw NetworkResponseTooLargeException(maximumBytes)
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

internal fun newNavigationRequestExecutor(): ExecutorService {
    return Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "walksafe-route-client").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
}
