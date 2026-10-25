package kr.co.hanium.dreamup.walksafe.support

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.testing.syntheticBoundaryContext

internal data class TransferHttpRequest(
    val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray,
)

internal data class TransferHttpReply(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val allowResponseDisconnect: Boolean = false,
)

/** Binary request capture for the native multipart writer; shared JSON fixture is read-only. */
internal class NativeTransferServer(
    private val reply: (TransferHttpRequest) -> TransferHttpReply,
) : AutoCloseable {
    private val server = syntheticBoundaryContext().let { ServerSocket() }.apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        soTimeout = 15_000
        check(localPort !in setOf(8000, 8081, 9001))
    }
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    val requests: MutableList<TransferHttpRequest> = Collections.synchronizedList(mutableListOf())
    val received = CountDownLatch(1)
    private val active = AtomicReference<Socket?>()
    private val failure = AtomicReference<Throwable?>()
    private val worker = thread(name = "native-transfer-fixture", isDaemon = true) {
        try {
            while (!server.isClosed) {
                val socket = server.accept()
                active.set(socket)
                socket.use {
                    it.soTimeout = 10_000
                    val request = readRequest(it)
                    requests.add(request)
                    received.countDown()
                    val response = reply(request)
                    val bytes = response.body.toByteArray(Charsets.UTF_8)
                    try {
                        it.getOutputStream().apply {
                            write(buildString {
                                append("HTTP/1.1 ${response.status} Fixture\r\n")
                                append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                                response.headers.forEach { (key, value) ->
                                    require('\r' !in key && '\n' !in key && '\r' !in value && '\n' !in value)
                                    append("$key: $value\r\n")
                                }
                                append("\r\n")
                            }.toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    } catch (error: java.io.IOException) {
                        if (!response.allowResponseDisconnect) throw error
                    }
                }
                active.compareAndSet(socket, null)
            }
        } catch (error: Throwable) {
            if (!server.isClosed) failure.set(error)
        }
    }

    override fun close() {
        server.close()
        active.getAndSet(null)?.close()
        worker.join(5_000)
        check(!worker.isAlive) { "Owned native transfer worker did not terminate" }
        requests.forEach { it.body.fill(0) }
        failure.get()?.let { throw AssertionError("Native transfer fixture failed", it) }
    }

    private fun readRequest(socket: Socket): TransferHttpRequest {
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var suffix = 0
        while (suffix != 0x0d0a0d0a) {
            val byte = input.read()
            check(byte >= 0 && head.size() < 16_384)
            head.write(byte)
            suffix = (suffix shl 8) or byte
        }
        val lines = head.toString("US-ASCII").split("\r\n")
        val requestLine = lines.first().split(' ')
        check(requestLine.size == 3)
        val headers = lines.drop(1).filter { ':' in it }.associate {
            it.substringBefore(':').lowercase(java.util.Locale.ROOT) to it.substringAfter(':').trim()
        }
        check("transfer-encoding" !in headers)
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..(4 * 1_024 * 1_024 + 65_536))
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count > 0)
            offset += count
        }
        return TransferHttpRequest(requestLine[0], requestLine[1], headers, body)
    }
}

internal fun transferSession(origin: String, actor: String, generation: Long = 1L): GatewayFieldSession {
    syntheticBoundaryContext()
    return GatewayFieldSession.backendAccountDeviceSession(origin,
        BackendAccountDeviceCookieBinding(actor, generation, 1L, "native-transfer-device-0001",
            "s".repeat(32), Long.MAX_VALUE),
        "${GatewayFieldSession.COOKIE_NAME}=native-transfer-synthetic-cookie", Long.MAX_VALUE)
}

internal fun transferConsent(raw: Boolean = false) = IntegratedConsentConfirmation(
    INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, INTEGRATED_CONSENT_POLICY_VERSION,
    "native-transfer-device-0001", "native_transfer_consent_request_0001", IntegratedConsentItemVersions(),
    1L, 1L, IntegratedConsentSelections(rawSourceCollection = raw), "2026-10-11T00:00:00.000Z",
    "a".repeat(64), "c".repeat(64), "b".repeat(64))

internal fun transferWifiBinding() =
    IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { it.openConnection() }

/** Durable phase receipts use SDK AtomicFile; production reset journal is tested separately. */
internal fun writeTransferMarker(file: File, value: String) {
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try {
        output.write(value.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(output)
    } catch (error: Throwable) {
        atomic.failWrite(output)
        throw error
    }
    check(AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) } == value)
}

internal fun readTransferMarker(file: File): String =
    AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
