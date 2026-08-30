package kr.co.hanium.dreamup.walksafe.network

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class LocalHttpTestServer(
    private val handler: (requestIndex: Int, socket: Socket) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val requestCount = AtomicInteger(0)
    private val handlers: ExecutorService = Executors.newCachedThreadPool()
    private val serverSocket = ServerSocket(0)
    private val acceptThread = Thread({ acceptRequests() }, "walksafe-test-http-accept").apply {
        isDaemon = true
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    private fun acceptRequests() {
        while (!closed.get()) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: return
            val index = requestCount.getAndIncrement()
            handlers.execute {
                socket.use {
                    readRequest(it)
                    handler(index, it)
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        serverSocket.close()
        acceptThread.join(1_000)
        handlers.shutdownNow()
    }
}

internal fun Socket.writeFixedResponse(
    statusCode: Int,
    body: ByteArray,
    declaredLength: Long = body.size.toLong(),
    headers: Map<String, String> = emptyMap(),
) {
    val output = getOutputStream()
    val extraHeaders = headers.entries.joinToString(separator = "") { (name, value) ->
        "$name: $value\r\n"
    }
    output.write(
        (
            "HTTP/1.1 $statusCode Test\r\nContent-Type: application/json\r\n" +
                "Content-Length: $declaredLength\r\n$extraHeaders" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII),
    )
    output.write(body)
    output.flush()
}

internal fun Socket.writeChunkedResponse(
    statusCode: Int,
    body: ByteArray,
    chunkSize: Int = 4_096,
) {
    val output = getOutputStream()
    output.write(
        "HTTP/1.1 $statusCode Test\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII),
    )
    var offset = 0
    while (offset < body.size) {
        val length = minOf(chunkSize, body.size - offset)
        output.write("${length.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body, offset, length)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        offset += length
    }
    output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
    output.flush()
}

internal fun Socket.writeStalledChunkedHeaders(statusCode: Int = 200) {
    getOutputStream().apply {
        write(
            "HTTP/1.1 $statusCode Test\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        flush()
    }
}

internal fun Socket.awaitPeerDisconnect(timeoutMs: Int): Boolean {
    soTimeout = timeoutMs
    return try {
        getInputStream().read() == -1
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: java.io.IOException) {
        true
    }
}

private fun readRequest(socket: Socket) {
    val input = socket.getInputStream()
    val headers = ByteArrayOutputStream()
    var matched = 0
    while (matched < HEADER_TERMINATOR.size) {
        val value = input.read()
        if (value < 0) return
        headers.write(value)
        matched = if (value == HEADER_TERMINATOR[matched].toInt()) matched + 1 else 0
        check(headers.size() <= MAX_REQUEST_HEADER_BYTES) { "test request headers too large" }
    }
    val headerText = headers.toString(Charsets.US_ASCII.name())
    val contentLength = headerText.lineSequence()
        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()
        ?: 0
    if (headerText.lineSequence().any { line ->
            line.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                line.substringAfter(':').contains("chunked", ignoreCase = true)
        }
    ) {
        readChunkedBody(input)
        return
    }
    var remaining = contentLength
    val buffer = ByteArray(8 * 1024)
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) return
        remaining -= read
    }
}

private fun readChunkedBody(input: java.io.InputStream) {
    while (true) {
        val chunkSize = readAsciiLine(input)
            .substringBefore(';')
            .trim()
            .toInt(16)
        if (chunkSize == 0) {
            while (readAsciiLine(input).isNotEmpty()) Unit
            return
        }
        var remaining = chunkSize
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            check(read >= 0) { "unexpected end of chunked test request" }
            remaining -= read
        }
        check(input.read() == '\r'.code && input.read() == '\n'.code) { "invalid chunk terminator" }
    }
}

private fun readAsciiLine(input: java.io.InputStream): String {
    val line = ByteArrayOutputStream()
    while (true) {
        val value = input.read()
        check(value >= 0) { "unexpected end of test request" }
        if (value == '\r'.code) {
            check(input.read() == '\n'.code) { "invalid line terminator" }
            return line.toString(Charsets.US_ASCII.name())
        }
        line.write(value)
    }
}

private val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
private const val MAX_REQUEST_HEADER_BYTES = 64 * 1024
