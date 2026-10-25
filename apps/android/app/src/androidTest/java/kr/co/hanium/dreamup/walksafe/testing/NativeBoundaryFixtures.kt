package kr.co.hanium.dreamup.walksafe.testing

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.BuildConfig

/** Explicitly isolated APK only. Missing opt-in is a failure, never an ignored green test. */
internal fun syntheticBoundaryContext(): Context {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(InstrumentationRegistry.getArguments().getString("runNativeBoundaryFixture") == "true")
    check(BuildConfig.DEBUG)
    check(instrumentation.targetContext.packageName == "kr.co.hanium.dreamup.walksafe.speechstorefixture")
    check(Build.HARDWARE in setOf("ranchu", "goldfish"))
    return instrumentation.targetContext
}

/** Both overrides matter: production key storage dereferences applicationContext. */
internal class IsolatedBoundaryContext private constructor(base: Context, val root: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getNoBackupFilesDir(): File = root
    override fun getCacheDir(): File = File(root, "cache").also { check(it.isDirectory || it.mkdirs()) }

    fun dispose() {
        check(root.name.startsWith("walksafe-native-probe-"))
        check(root.parentFile?.canonicalFile == baseContext.noBackupFilesDir.canonicalFile)
        check(!root.exists() || root.deleteRecursively())
    }

    companion object {
        fun create(purpose: String, runId: String = UUID.randomUUID().toString(), fresh: Boolean = true): IsolatedBoundaryContext {
            require(purpose.matches(Regex("[a-z-]{1,24}")))
            require(runId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
            val base = syntheticBoundaryContext()
            val root = File(base.noBackupFilesDir.canonicalFile, "walksafe-native-probe-$purpose-$runId")
            if (fresh) {
                check(!root.exists()) { "Existing probe data must be preserved; choose a new run id" }
                check(root.mkdir())
            } else check(root.isDirectory) { "Read requires the completed writer phase" }
            return IsolatedBoundaryContext(base, root)
        }
    }
}

internal data class NativeHttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

internal data class NativeHttpReply(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val release: CountDownLatch? = null,
    val declaredLength: Int? = null,
)

/** Native loopback peer with bounded parsing, exact request receipts and owned shutdown. */
internal class NativeLoopbackServer(
    replies: List<NativeHttpReply>,
    private val inspect: (Int, NativeHttpRequest) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val server = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        soTimeout = 15_000
    }
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    val received = CountDownLatch(1)
    val requests: MutableList<NativeHttpRequest> = Collections.synchronizedList(mutableListOf())
    private val executor = Executors.newSingleThreadExecutor()
    private val activeSocket = AtomicReference<Socket?>()
    private val task = executor.submit {
        replies.forEachIndexed { index, reply ->
            server.accept().use { socket ->
                activeSocket.set(socket)
                socket.soTimeout = 15_000
                val input = socket.getInputStream()
                val head = ByteArrayOutputStream()
                var suffix = 0
                while (suffix != 0x0d0a0d0a) {
                    val byte = input.read()
                    check(byte >= 0 && head.size() < 16_384) { "Incomplete or oversized request headers" }
                    head.write(byte)
                    suffix = (suffix shl 8) or byte
                }
                val lines = head.toString("US-ASCII").split("\r\n")
                val requestLine = lines.first().split(' ')
                check(requestLine.size == 3)
                val headers = lines.drop(1).filter { ':' in it }.associate {
                    it.substringBefore(':').lowercase(java.util.Locale.ROOT) to it.substringAfter(':').trim()
                }
                check(!headers.containsKey("transfer-encoding")) { "This fixture requires a declared request length" }
                val length = headers["content-length"]?.toInt() ?: 0
                check(length in 0..1_048_576)
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    check(count > 0) { "Request body ended before its declared length" }
                    offset += count
                }
                val request = NativeHttpRequest(requestLine[0], requestLine[1], headers, bytes.toString(Charsets.UTF_8))
                requests.add(request)
                inspect(index, request)
                received.countDown()
                check(reply.release?.await(10, TimeUnit.SECONDS) != false) { "Response release timed out" }
                val responseBytes = reply.body.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(buildString {
                        append("HTTP/1.1 ${reply.status} Fixture\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${reply.declaredLength ?: responseBytes.size}\r\n")
                        append("Connection: close\r\n")
                        reply.headers.forEach { (key, value) ->
                            require('\r' !in key && '\n' !in key && '\r' !in value && '\n' !in value)
                            append("$key: $value\r\n")
                        }
                        append("\r\n")
                    }.toByteArray(Charsets.US_ASCII))
                    write(responseBytes)
                    flush()
                }
                activeSocket.compareAndSet(socket, null)
            }
        }
    }

    fun finished() { task.get(20, TimeUnit.SECONDS) }

    override fun close() {
        server.close()
        activeSocket.getAndSet(null)?.close()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Fixture HTTP worker did not terminate" }
    }
}
