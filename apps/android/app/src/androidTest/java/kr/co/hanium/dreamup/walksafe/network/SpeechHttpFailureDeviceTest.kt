package kr.co.hanium.dreamup.walksafe.network

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android HTTP/JSON against device loopback. Synthetic session/body only: no ASR,
 * real credentials, microphone, Main callback, or command execution. */
class SpeechHttpFailureDeviceTest {
    private val id = "d6aa8085-2ef2-42a2-94b7-197efcdaeb32"
    private fun body(allowed: Boolean = true) = JSONObject()
        .put("schema_version", SPEECH_STT_RESPONSE_SCHEMA).put("request_id", id)
        .put("transcript", "도움말").put("model_revision", "a".repeat(40))
        .put("acoustic", JSONObject().put("confidence", 0.95).put("avg_logprob", -0.1)
            .put("no_speech_probability", 0.01).put("execution_allowed", allowed))
    private fun call(server: Server, file: File) = AndroidGatewaySpeechClient().transcribeCall(
        GatewayFieldSession.verified(server.base, "speech-http-fixture",
            "walksafe_field_session=v2.test.session.cookie", Long.MAX_VALUE), file, id)
    private fun fixture(block: (File) -> Unit) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "speech-http-fixture-${UUID.randomUUID()}.m4a")
        try { file.writeBytes(ByteArray(128) { it.toByte() }); block(file) }
        finally { assertTrue(!file.exists() || file.delete()) }
    }
    private fun report(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(
        0, Bundle().apply { putString("stream", "SPEECH_HTTP $value\n") })

    @Test fun validAndAcousticallyRejectedRepliesPreserveTheirDistinctOutcomes() = fixture { file ->
        for (allowed in listOf(true, false)) Server(200, body(allowed).toString()).use { server ->
            val result = call(server, file).execute()
            assertEquals(id, result.requestId); assertEquals("도움말", result.transcript)
            assertEquals(allowed, result.acoustic.executionAllowed)
            server.finished(); assertEquals(1, server.hits.get())
            assertEquals(128, server.requestBytes)
            assertTrue(server.requestHeader.startsWith("POST /api/speech/stt "))
            assertTrue(server.requestHeader.contains("Content-Type: audio/mp4", true))
            // Audio ownership remains with Main's caller on success AND failure.
            assertEquals(128L, file.length())
        }
        report("valid_and_acoustically_denied_distinct=true native_posts=2 command_executed=false")
    }

    @Test fun malformedSuccessBodiesAndRequiredHeadersCannotBecomeTranscripts() = fixture { file ->
        val invalid = listOf(
            body().put("request_id", UUID.randomUUID().toString()),
            body().put("extra", true), body().put("transcript", "도움말\u0000"),
            body().put("transcript", "가".repeat(501)),
            body().apply { getJSONObject("acoustic").put("confidence", "0.95") },
            body().apply { getJSONObject("acoustic").put("confidence", 1.1) },
            body().apply { getJSONObject("acoustic").put("execution_allowed", "true") },
            body().apply { getJSONObject("acoustic").remove("no_speech_probability") },
            body().put("model_revision", "unverified-model"),
        )
        for (bad in invalid) Server(200, bad.toString()).use { server ->
            assertThrows(GatewaySpeechProtocolException::class.java) { call(server, file).execute() }
            server.finished(); assertEquals(1, server.hits.get()); assertEquals(128L, file.length())
        }
        val headers = listOf("Content-Type: text/plain\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n",
            "Content-Type: application/json\r\nX-Content-Type-Options: nosniff\r\n",
            "Content-Type: application/json\r\nCache-Control: no-store\r\n")
        for (bad in headers) Server(200, body().toString(), bad).use { server ->
            assertThrows(GatewaySpeechProtocolException::class.java) { call(server, file).execute() }
            server.finished()
        }
        Server(200, "x".repeat(SPEECH_STT_MAX_RESPONSE_BYTES + 1)).use { server ->
            assertThrows(NetworkResponseTooLargeException::class.java) { call(server, file).execute() }
            server.finished()
        }
        report("malformed_body_rejections=9 required_header_rejections=3 oversized_response_rejected=true")
    }

    @Test fun httpErrorsDoNotRetryAndRedirectNeverReachesAnotherPeer() = fixture { file ->
        val statuses = listOf(401, 403, 408, 413, 429, 500, 503)
        for (status in statuses) Server(status, "{}").use { server ->
            val error = assertThrows(GatewaySpeechHttpException::class.java) { call(server, file).execute() }
            assertEquals(status, error.statusCode); server.finished(); assertEquals(1, server.hits.get())
        }
        Server(200, body().toString()).use { target ->
            Server(302, "{}", "Location: ${target.base}/api/speech/stt\r\n").use { server ->
                assertEquals(302, assertThrows(GatewaySpeechHttpException::class.java) {
                    call(server, file).execute()
                }.statusCode)
                server.finished(); SystemClock.sleep(250); assertEquals(0, target.hits.get())
            }
        }
        report("http_status_rejections=7 automatic_retry_absent=true redirect_rejected=true target_requests=0")
    }

    @Test fun ambiguousOrNonJsonSuccessCannotAuthorizeATranscript() = fixture { file ->
        val valid = body().toString()
        val cases = linkedMapOf(
            "trailing_document" to (valid + " {}"),
            "trailing_junk" to (valid + "garbage"),
            "duplicate_transcript" to valid.replaceFirst("{", "{\"transcript\":\"취소\","),
            "escaped_duplicate" to valid.replaceFirst("{", "{\"\\u0074ranscript\":\"취소\","),
            "duplicate_execution_permission" to valid.replace("\"execution_allowed\":true", "\"execution_allowed\":false,\"execution_allowed\":true"),
            "comment" to valid.replaceFirst("{", "{/* not JSON */"),
            "single_quotes" to valid.replace('"', '\''),
            "unquoted_key" to valid.replace("\"transcript\":", "transcript:"),
            "semicolon" to valid.replaceFirst(',', ';'),
        )
        val accepted = mutableListOf<String>()
        for ((name, response) in cases) Server(200, response).use { server ->
            val failure = runCatching { call(server, file).execute() }.exceptionOrNull()
            if (failure == null) accepted += name
            else assertTrue("Unexpected failure $name: $failure", failure is GatewaySpeechProtocolException)
            server.finished()
            report("strict_json_case=$name rejected=${failure != null} command_executed=false")
        }
        assertEquals("Malformed JSON accepted as a transcript", emptyList<String>(), accepted)
    }

    @Test fun cancellationBeforeAndAfterUploadCannotReturnLateSuccess() = fixture { file ->
        Server(200, body().toString()).use { server ->
            val request = call(server, file); request.cancel()
            assertThrows(CancellationException::class.java) { request.execute() }
            SystemClock.sleep(250); assertEquals(0, server.hits.get())
        }
        val gate = CountDownLatch(1)
        Server(200, body().toString(), gate = gate).use { server ->
            val request = call(server, file); val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Throwable?> {
                    try { request.execute(); null } catch (failure: Throwable) { failure }
                }
                assertTrue(server.received.await(5, TimeUnit.SECONDS))
                val began = SystemClock.elapsedRealtime(); request.cancel()
                val cancelMs = SystemClock.elapsedRealtime() - began
                val failure = result.get(5, TimeUnit.SECONDS)
                assertTrue("Native cancellation returned $failure", failure is CancellationException)
                gate.countDown(); server.finished()
                assertEquals(1, server.hits.get()); assertEquals(128L, file.length())
                report("cancel_before_dispatch_requests=0 cancel_after_upload_requests=1 late_success=false cancel_call_ms=$cancelMs caller_audio_preserved=true")
            } finally { gate.countDown(); executor.shutdownNow() }
        }
    }

    private class Server(status: Int, body: String,
        headers: String = "Content-Type: application/json\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n",
        private val gate: CountDownLatch? = null) : AutoCloseable {
        private val socket = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 10_000
        }
        val base = "http://127.0.0.1:${socket.localPort}"
        val hits = AtomicInteger(); val received = CountDownLatch(1)
        var requestBytes = 0; var requestHeader = ""
        private val worker = Executors.newSingleThreadExecutor()
        private val task = worker.submit {
            socket.accept().use { peer ->
                hits.incrementAndGet(); peer.soTimeout = 5_000
                val input = peer.getInputStream(); val head = ByteArrayOutputStream()
                while (!head.toString("US-ASCII").endsWith("\r\n\r\n")) {
                    val next = input.read(); check(next >= 0 && head.size() < 16_384); head.write(next)
                }
                requestHeader = head.toString("US-ASCII")
                val size = requestHeader.lineSequence().first { it.startsWith("Content-Length:", true) }
                    .substringAfter(':').trim().toInt(); check(size in 0..1024)
                repeat(size) { check(input.read() >= 0); requestBytes++ }
                received.countDown(); check(gate?.await(10, TimeUnit.SECONDS) != false)
                val bytes = body.toByteArray()
                try {
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\n${headers}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } catch (failure: IOException) { if (gate == null) throw failure }
            }
        }
        fun finished() { task.get(15, TimeUnit.SECONDS) }
        override fun close() { gate?.countDown(); socket.close(); task.cancel(true); worker.shutdownNow() }
    }
}
