package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Normal USER 앱의 보존된 postlogin callback PCM 하나만 새 native instance에 한 번 재생한다.
 * 원 AudioRecord jitter/JNI 진입/suppression/recognizer 상태/스레드/수명주기와 wake UI는 재현하지 않는다.
 * EOF flush/reset/grammar/무음 삭제/소스 연결/반복은 없다. 완료는 구조 완료이며 인식 성공이 아니다.
 * 원 PCM/journal은 읽기 전용이며 native 문장과 confidence는 새 private JSONL에만 기록한다.
 */
@RunWith(AndroidJUnit4::class)
class VoskPostLoginGuestPcmReplayDiagnosticDeviceTest {
    @Test(timeout = 180_000L)
    fun replayOneExactPostLoginGuestCallbackSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runVoskPostLoginGuestPcmReplayDiagnostic") == "true")
        val source = requireNotNull(Source.values().singleOrNull {
            it.name == arguments.getString("postLoginGuestPcmSource")
        }) { "An exact postLoginGuestPcmSource enum is required" }
        val context = instrumentation.targetContext
        require(context.packageName == "kr.co.hanium.dreamup.walksafe") { "Normal USER package is required" }
        val cache = context.cacheDir
        check(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
        val startedMs = SystemClock.elapsedRealtime()
        val deadlineMs = startedMs + WALL_LIMIT_MS
        val runId = SystemClock.elapsedRealtimeNanos()
        val artifact = File(cache, "vosk-post-login-guest-pcm-replay-$runId-${source.name}.jsonl")
        check(artifact.createNewFile()) { "A new replay-owned artifact is required" }
        var attempted = 0
        var completedChunks = 0
        var samplesSubmitted = 0
        var endpoints = 0
        var resultsRead = 0
        var partialReads = 0
        var completed = false
        var model: Model? = null
        var recognizer: Recognizer? = null
        var primaryFailure: Throwable? = null

        FileOutputStream(artifact).use { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            fun record(event: String, fields: JSONObject = JSONObject()) {
                writer.write(fields.put("event", event).put("run_id", runId).put("source", source.name)
                    .put("diagnostic_native_instance", 1).put("wall_elapsed_ms", SystemClock.elapsedRealtime() - startedMs)
                    .put("chunks_attempted", attempted).put("chunks_completed", completedChunks)
                    .put("samples_submitted", samplesSubmitted).toString())
                writer.newLine()
                writer.flush()
                output.fd.sync()
            }
            try {
                record("REPLAY_BEGIN", JSONObject().put("original_callback_source", source.origin)
                    .put("original_jni_accept_proved", false).put("original_recognizer_state_replayed", false)
                    .put("original_audio_record_jitter_replayed", false).put("original_suppression_replayed", false)
                    .put("original_threads_or_lifecycle_replayed", false).put("wake_ui_tested", false)
                    .put("source_boundaries_joined", false).put("source_repeated", false).put("wall_limit_ms", WALL_LIMIT_MS))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "POSTLOGIN_GUEST_PCM_REPLAY source=${source.name} artifact=${artifact.absolutePath}\n")
                })
                val directory = File(cache, INPUT_DIRECTORY)
                check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
                check(directory.canonicalFile.parentFile == cache.canonicalFile)
                val inputFile = File(directory, source.fileName)
                check(Files.isRegularFile(inputFile.toPath(), LinkOption.NOFOLLOW_LINKS))
                check(inputFile.canonicalFile.parentFile == directory.canonicalFile)
                assertEquals("Exact exported callback length", source.bytes.toLong(), inputFile.length())
                requireBeforeDeadline(deadlineMs)
                val input = ByteArray(source.bytes)
                DataInputStream(FileInputStream(inputFile)).use { stream ->
                    stream.readFully(input)
                    assertEquals("No trailing input bytes", -1, stream.read())
                }
                assertEquals("Exact immutable callback snapshot", source.sha256, sha(input))
                check(input.size % CHUNK_BYTES == 0)
                val totalChunks = input.size / CHUNK_BYTES
                val modelDirectory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
                    "Existing full-hash verified model is required; this diagnostic never installs"
                }
                val manifestSha = File(modelDirectory, ".verified-model").readText().trim()
                assertEquals("Original packaged model manifest", MODEL_MANIFEST_SHA256, manifestSha)
                record("INPUT_AND_MODEL_VERIFIED", JSONObject().put("input_bytes", input.size)
                    .put("input_sha256", source.sha256).put("total_chunks", totalChunks)
                    .put("sample_rate_hz", SAMPLE_RATE_HZ).put("chunk_samples", CHUNK_SAMPLES)
                    .put("model_manifest_sha256", manifestSha).put("model_all_file_hashes_verified", true))
                requireBeforeDeadline(deadlineMs)
                record("MODEL_OPEN_ENTER")
                requireBeforeDeadline(deadlineMs)
                val openedModel = Model(modelDirectory.absolutePath)
                model = openedModel
                requireBeforeDeadline(deadlineMs)
                record("RECOGNIZER_CREATE_ENTER")
                requireBeforeDeadline(deadlineMs)
                val active = createWakeRecognizer(openedModel)
                recognizer = active
                record("RECOGNIZER_CREATED", JSONObject().put("words", true).put("partial_words", false)
                    .put("max_alternatives", 0).put("endpointer_mode", "SHORT")
                    .put("start_max_seconds", 5.0).put("end_seconds", 0.35).put("max_seconds", 5.0)
                    .put("minimum_chunk_interval_ms", 20).put("partial_interval_ms", 200))
                val submittedDigest = MessageDigest.getInstance("SHA-256")
                var nextSubmissionNanos = System.nanoTime()
                var lastPartial = ""
                var lastPartialEmittedAtNanos = 0L
                for (index in 0 until totalChunks) {
                    requireBeforeDeadline(deadlineMs)
                    val offset = index * CHUNK_BYTES
                    val samples = ShortArray(CHUNK_SAMPLES) { sample ->
                        ((input[offset + sample * 2].toInt() and 0xff) or
                            ((input[offset + sample * 2 + 1].toInt() and 0xff) shl 8)).toShort()
                    }
                    val waitNanos = nextSubmissionNanos - System.nanoTime()
                    if (waitNanos > 0L) SystemClock.sleep((waitNanos + 999_999L) / 1_000_000L)
                    requireBeforeDeadline(deadlineMs)
                    attempted++
                    record("CHUNK_ACCEPT_ENTER", JSONObject().put("chunk_index", index + 1)
                        .put("source_byte_offset", offset).put("chunk_samples", samples.size))
                    requireBeforeDeadline(deadlineMs)
                    val acceptStartedNanos = System.nanoTime()
                    nextSubmissionNanos = acceptStartedNanos + CHUNK_INTERVAL_NANOS
                    val endpoint = active.acceptWaveForm(samples, samples.size)
                    completedChunks++
                    samplesSubmitted += samples.size
                    submittedDigest.update(input, offset, CHUNK_BYTES)
                    record("CHUNK_ACCEPT_RETURNED", JSONObject().put("chunk_index", index + 1)
                        .put("endpoint", endpoint).put("accept_started_nanos", acceptStartedNanos))
                    requireBeforeDeadline(deadlineMs)
                    if (endpoint) {
                        endpoints++
                        record("ENDPOINT_RESULT_ENTER")
                        requireBeforeDeadline(deadlineMs)
                        val payload = active.result
                        resultsRead++
                        val transcript = parseVoskTranscript(payload, isFinal = true)
                        record("ENDPOINT_RESULT", JSONObject().put("native_result", payload)
                            .put("parsed_text", transcript?.text ?: JSONObject.NULL)
                            .put("parsed_confidence", transcript?.confidence ?: JSONObject.NULL))
                        lastPartial = ""
                    } else {
                        // Same gate/update order as VoskStreamingTranscriber.maybeEmitPartial.
                        val nowNanos = System.nanoTime()
                        if (nowNanos - lastPartialEmittedAtNanos >= PARTIAL_INTERVAL_NANOS) {
                            record("PARTIAL_RESULT_ENTER")
                            requireBeforeDeadline(deadlineMs)
                            val payload = active.partialResult
                            partialReads++
                            val transcript = parseVoskTranscript(payload, isFinal = false)
                            val emitted = transcript != null && transcript.text != lastPartial
                            if (emitted) {
                                lastPartial = requireNotNull(transcript).text
                                lastPartialEmittedAtNanos = nowNanos
                            }
                            record("PARTIAL_RESULT", JSONObject().put("native_result", payload)
                                .put("parsed_text", transcript?.text ?: JSONObject.NULL)
                                .put("parsed_confidence", transcript?.confidence ?: JSONObject.NULL)
                                .put("would_emit_partial", emitted))
                        }
                    }
                    requireBeforeDeadline(deadlineMs)
                }
                val submittedSha = hex(submittedDigest.digest())
                assertEquals("Every chunk attempted once", totalChunks, attempted)
                assertEquals("Every chunk returned", totalChunks, completedChunks)
                assertEquals("Exact source sample count", source.bytes / 2, samplesSubmitted)
                assertEquals("Submitted bytes unchanged", source.sha256, submittedSha)
                assertEquals("One result read per endpoint", endpoints, resultsRead)
                record("INPUT_COMPLETED", JSONObject().put("submitted_sha256", submittedSha)
                    .put("endpoints", endpoints).put("results_read", resultsRead).put("partial_reads", partialReads)
                    .put("eof_final_read", false).put("recognition_success_asserted", false))
                requireBeforeDeadline(deadlineMs)
                completed = true
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        val previous = cleanupFailure
                        if (previous == null) cleanupFailure = failure
                        else if (previous !== failure) previous.addSuppressed(failure)
                    }
                }
                cleanup { recognizer?.close() }
                cleanup { model?.close() }
                cleanup { requireBeforeDeadline(deadlineMs) }
                cleanup { record("REPLAY_FINISHED", JSONObject()
                    .put("structure_completed", completed && primaryFailure == null && cleanupFailure == null)
                    .put("failure_class", primaryFailure?.javaClass?.simpleName ?: "NONE")
                    .put("cleanup_failure_class", cleanupFailure?.javaClass?.simpleName ?: "NONE")
                    .put("artifact_preserved", true)) }
                cleanupFailure?.let { failure ->
                    val original = primaryFailure
                    if (original == null) throw failure
                    if (original !== failure) original.addSuppressed(failure)
                }
            }
        }
    }

    private fun createWakeRecognizer(model: Model): Recognizer {
        val recognizer = Recognizer(model, SAMPLE_RATE_HZ.toFloat())
        var configured = false
        try {
            recognizer.setWords(true)
            recognizer.setPartialWords(false)
            recognizer.setMaxAlternatives(0)
            recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
            recognizer.setEndpointerDelays(5.0f, 0.35f, 5.0f)
            configured = true
            return recognizer
        } finally {
            if (!configured) recognizer.close()
        }
    }

    private fun requireBeforeDeadline(deadlineMs: Long) = assertTrue(
        "Postlogin callback replay exceeded its 120 second wall deadline", SystemClock.elapsedRealtime() < deadlineMs,
    )

    private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private enum class Source(val fileName: String, val bytes: Int, val sha256: String, val origin: String) {
        OLD_NORMAL_PASS1("old-normal-pass1.s16le", 95_360,
            "b841caaf4a8cd01c912364e44abe35fbc1b9151bdeebd3d685b986ecf3a312e4",
            "password-recovery-pcm-clone-20261015/normal-wake-pcm-education-handoff/guest-callback.s16le"),
        PREF_DIFF_UNTRUSTED("pref-diff-untrusted.s16le", 631_040,
            "0da9b280ffad76370530c7ae015f93bb00f22b30cf2da6d81244617be769d74d",
            "password-pref-diff-clone-20261015/normal-wake-pcm-education-handoff/guest-callback.s16le"),
        NEW_NORMAL_PASS2("new-normal-pass2.s16le", 96_000,
            "fe48e13fac93d10b6705613dccbba4110af063f75caa373a1d3aaf46d9a91072",
            "password-ui-recovery-clone-20261015/normal-wake-pcm-education-handoff/guest-callback.s16le"),
    }

    private companion object {
        const val INPUT_DIRECTORY = "vosk-post-login-guest-pcm-20261015"
        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_SAMPLES = 320
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
        const val CHUNK_INTERVAL_NANOS = 20_000_000L
        const val PARTIAL_INTERVAL_NANOS = 200_000_000L
        const val WALL_LIMIT_MS = 120_000L
        const val MODEL_MANIFEST_SHA256 = "8396458f1990e174b88c531f7db14587a4b52273443a1b39dfd6b75552e135c3"
    }
}
