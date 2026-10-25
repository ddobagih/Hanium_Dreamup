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
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Source55 actual callback에서 보존된 PCM 하나의 별도 opt-in native 재생이다.
 * 원 crash PCM이나 원 JNI accept 입력이라는 증거가 아니며, 원 255개 시험 밖 진단이다.
 * 원 suppression/input generation/AudioRecord/production processPcm/수명주기는 재현하지 않는다.
 * source 경계를 합치거나 반복하지 않고 EOF flush/reset/grammar/quiet 삭제도 하지 않는다.
 * native 결과는 새 private JSONL에만 기록하며 원 PCM/기록 파일을 수정하거나 삭제하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class VoskObservedPcmReplayDiagnosticDeviceTest {
    @Test(timeout = 180_000L)
    fun replayOneExactObservedCallbackSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runVoskObservedPcmReplayDiagnostic") == "true")
        val sourceNumber = arguments.getString("observedPcmSource")
        require(sourceNumber == "2" || sourceNumber == "3") { "observedPcmSource=2 or 3 is required" }
        val mode = arguments.getString("observedPcmMode")
        require(mode == "continuous" || mode == "recreate") { "observedPcmMode=continuous or recreate is required" }
        val context = instrumentation.targetContext
        require(context.packageName == EXPECTED_PACKAGE) { "The original private accountrestartfixture package is required" }
        val cache = context.cacheDir
        check(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
        val runId = SystemClock.elapsedRealtimeNanos()
        val startedAtMs = SystemClock.elapsedRealtime()
        val deadlineMs = startedAtMs + WALL_LIMIT_MS
        val artifact = File(cache, "vosk-observed-pcm-replay-$runId-$sourceNumber-$mode.jsonl")
        check(artifact.createNewFile()) { "Cannot create the exact replay-owned artifact" }
        var chunksAttempted = 0
        var chunksCompleted = 0
        var samplesSubmitted = 0
        var endpoints = 0
        var resultsRead = 0
        var nativeInstance = 1
        var recreations = 0
        var recreationTotalNanos = 0L
        var recreationMaxNanos = 0L
        var completed = false
        var model: Model? = null
        var recognizer: Recognizer? = null
        var primaryFailure: Throwable? = null

        FileOutputStream(artifact).use { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            fun record(event: String, fields: JSONObject = JSONObject()) {
                writer.write(fields.put("event", event).put("run_id", runId)
                    .put("source_number", sourceNumber).put("mode", mode)
                    .put("wall_elapsed_ms", SystemClock.elapsedRealtime() - startedAtMs)
                    .put("chunks_attempted", chunksAttempted).put("chunks_completed", chunksCompleted)
                    .put("samples_submitted", samplesSubmitted).put("endpoints", endpoints)
                    .put("results_read", resultsRead).put("diagnostic_native_instance", nativeInstance)
                    .put("generation_kind", "DIAGNOSTIC_NATIVE_INSTANCE").toString())
                writer.newLine()
                writer.flush()
                output.fd.sync()
            }
            try {
                record("REPLAY_BEGIN", JSONObject().put("input_kind", "SOURCE55_OBSERVED_CALLBACK_PCM")
                    .put("original_jni_accept_proved", false).put("original_crash_input_proved", false)
                    .put("production_process_pcm_tested", false).put("original_suppression_replayed", false)
                    .put("original_input_generation_replayed", false).put("original_lifecycle_replayed", false)
                    .put("wall_limit_ms", WALL_LIMIT_MS).put("artifact", artifact.absolutePath))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "OBSERVED_PCM_REPLAY artifact=${artifact.absolutePath} source=$sourceNumber mode=$mode\n")
                })
                val observed = File(cache, OBSERVED_DIRECTORY)
                check(observed.isDirectory && !Files.isSymbolicLink(observed.toPath()))
                check(observed.canonicalFile.parentFile == cache.canonicalFile)
                // receipt의 원 3개 파일만 고정 검사하며 임의 path나 새 input을 받지 않는다.
                for ((number, expected) in SOURCES) {
                    requireBeforeDeadline(deadlineMs)
                    val file = File(observed, "source-$number.s16le")
                    check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                    check(!Files.isSymbolicLink(file.toPath()))
                    check(file.canonicalFile.parentFile == observed.canonicalFile)
                    assertEquals("Observed PCM length must match the export receipt", expected.first.toLong(), file.length())
                    assertEquals("Observed PCM SHA must match the export receipt", expected.second, fileSha(file, deadlineMs))
                    record("SOURCE_RECEIPT_VERIFIED", JSONObject().put("verified_source_number", number)
                        .put("bytes", expected.first).put("sha256", expected.second))
                }
                val expected = checkNotNull(SOURCES[sourceNumber])
                val inputFile = File(observed, "source-$sourceNumber.s16le")
                // 검증된 원파일의 불변 사본만 재생하여 precheck 이후 파일 변경을 JNI에 전달하지 않는다.
                val input = ByteArray(expected.first)
                DataInputStream(FileInputStream(inputFile)).use { stream ->
                    stream.readFully(input)
                    assertEquals("Observed source must have no additional bytes", -1, stream.read())
                }
                assertEquals(expected.first, input.size)
                assertEquals(expected.second, sha(input))
                check(input.size % (CHUNK_SAMPLES * 2) == 0)
                val totalSamples = input.size / 2
                val totalChunks = input.size / (CHUNK_SAMPLES * 2)
                val directory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
                    "Existing full-hash verified model is required; replay never installs a model"
                }
                record("INPUT_VERIFIED", JSONObject().put("input_bytes", input.size)
                    .put("input_full_sha256", expected.second).put("sample_rate_hz", SAMPLE_RATE_HZ)
                    .put("chunk_samples", CHUNK_SAMPLES).put("total_samples", totalSamples)
                    .put("total_chunks", totalChunks).put("audio_seconds", totalSamples.toDouble() / SAMPLE_RATE_HZ)
                    .put("source_boundaries_joined", false).put("source_repeated", false)
                    .put("model_directory_name", directory.name)
                    .put("model_all_file_hashes_verified", true)
                    .put("model_manifest_sha256", File(directory, ".verified-model").readText().trim()))
                requireBeforeDeadline(deadlineMs)
                record("MODEL_OPEN_ENTER")
                val modelStarted = SystemClock.elapsedRealtimeNanos()
                val openedModel = Model(directory.absolutePath)
                model = openedModel
                record("MODEL_OPENED", JSONObject().put("open_nanos", SystemClock.elapsedRealtimeNanos() - modelStarted))
                requireBeforeDeadline(deadlineMs)
                record("RECOGNIZER_CREATE_ENTER")
                val initialStarted = SystemClock.elapsedRealtimeNanos()
                recognizer = createHomeRecognizer(openedModel)
                record("RECOGNIZER_CREATED", JSONObject()
                    .put("create_nanos", SystemClock.elapsedRealtimeNanos() - initialStarted)
                    .put("words", true).put("partial_words", false).put("max_alternatives", 0)
                    .put("endpointer_mode", "SHORT").put("start_max_seconds", 5.0)
                    .put("end_seconds", 0.35).put("max_seconds", 5.0))
                val submittedDigest = MessageDigest.getInstance("SHA-256")
                var lastEndpointSamples = 0
                var lastEndpointWasFinalChunk = false
                for (index in 0 until totalChunks) {
                    requireBeforeDeadline(deadlineMs)
                    val bytes = input.copyOfRange(index * CHUNK_SAMPLES * 2, (index + 1) * CHUNK_SAMPLES * 2)
                    val samples = ShortArray(CHUNK_SAMPLES) { offset ->
                        ((bytes[offset * 2].toInt() and 0xff) or
                            ((bytes[offset * 2 + 1].toInt() and 0xff) shl 8)).toShort()
                    }
                    var zero = 0
                    var squares = 0.0
                    var peak = 0
                    for (sample in samples) {
                        if (sample == 0.toShort()) zero++
                        val value = sample.toInt()
                        squares += value.toDouble() * value
                        peak = maxOf(peak, abs(value))
                    }
                    val rms = sqrt(squares / samples.size)
                    check(rms.isFinite())
                    chunksAttempted++
                    // 각 원 chunk를 정확히 한 번 accept하며 abort 직전 진입 기록을 먼저 fd.sync한다.
                    record("CHUNK_ACCEPT_ENTER", JSONObject().put("chunk_index", index + 1)
                        .put("source_byte_offset", index * CHUNK_SAMPLES * 2).put("chunk_samples", samples.size)
                        .put("s16le_sha256", sha(bytes)).put("zero_ratio", zero.toDouble() / samples.size)
                        .put("rms", rms).put("peak", peak))
                    val active = checkNotNull(recognizer)
                    val endpoint = active.acceptWaveForm(samples, samples.size)
                    chunksCompleted++
                    samplesSubmitted += samples.size
                    submittedDigest.update(bytes)
                    record("CHUNK_ACCEPT_RETURNED", JSONObject().put("chunk_index", index + 1).put("endpoint", endpoint))
                    requireBeforeDeadline(deadlineMs)
                    if (endpoint) {
                        endpoints++
                        record("ENDPOINT_RESULT_ENTER")
                        val nativeResult = active.result // endpoint당 실제 원 결과를 정확히 한 번 읽는다.
                        resultsRead++
                        val intervalSamples = samplesSubmitted - lastEndpointSamples
                        lastEndpointSamples = samplesSubmitted
                        lastEndpointWasFinalChunk = samplesSubmitted == totalSamples
                        // 원 결과와 인식 문장은 private 파일에만 남긴다. sendStatus로 전송하지 않는다.
                        record("ENDPOINT_RESULT", JSONObject().put("native_result", nativeResult)
                            .put("endpoint_interval_samples", intervalSamples)
                            .put("endpoint_interval_audio_ms", intervalSamples * 1000L / SAMPLE_RATE_HZ))
                        if (mode == "recreate" && samplesSubmitted < totalSamples) {
                            requireBeforeDeadline(deadlineMs)
                            record("RECOGNIZER_RECREATE_ENTER")
                            val recreationStarted = SystemClock.elapsedRealtimeNanos()
                            recognizer = null
                            active.close()
                            recognizer = createHomeRecognizer(openedModel)
                            val duration = SystemClock.elapsedRealtimeNanos() - recreationStarted
                            nativeInstance++
                            recreations++
                            recreationTotalNanos += duration
                            recreationMaxNanos = maxOf(recreationMaxNanos, duration)
                            record("RECOGNIZER_RECREATED", JSONObject().put("same_model_instance", true)
                                .put("close_and_create_nanos", duration).put("production_latency_proved", false))
                        }
                    }
                }
                val submittedSha = hex(submittedDigest.digest())
                record("INPUT_COMPLETED", JSONObject().put("input_full_sha256", expected.second)
                    .put("submitted_sha256", submittedSha).put("first_chunk_samples", CHUNK_SAMPLES)
                    .put("last_chunk_samples", CHUNK_SAMPLES).put("recreations", recreations)
                    .put("recreation_total_nanos", recreationTotalNanos).put("recreation_max_nanos", recreationMaxNanos))
                assertEquals("Every observed chunk must be attempted exactly once", totalChunks, chunksAttempted)
                assertEquals("Every observed chunk must return", totalChunks, chunksCompleted)
                assertEquals("Exact observed audio length must be submitted", totalSamples, samplesSubmitted)
                assertEquals("Submitted bytes must equal the exact source SHA", expected.second, submittedSha)
                assertEquals("Each actual endpoint has one result read", endpoints, resultsRead)
                val expectedRecreations = if (mode == "recreate") endpoints - (if (lastEndpointWasFinalChunk) 1 else 0) else 0
                assertEquals("Only endpoint-triggered recreation is allowed", expectedRecreations, recreations)
                requireBeforeDeadline(deadlineMs)
                completed = true
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "OBSERVED_PCM_REPLAY completed=true chunks=$chunksCompleted endpoints=$endpoints artifact=${artifact.absolutePath}\n")
                })
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                // 원 source와 기존 zero 진단은 건드리지 않고, 이 호출의 native 객체만 닫는다.
                var cleanupFailure: Throwable? = null
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        val existing = cleanupFailure
                        if (existing == null) cleanupFailure = failure
                        else if (existing !== failure) existing.addSuppressed(failure)
                    }
                }
                cleanup { recognizer?.close() }
                cleanup { model?.close() }
                cleanup { record("REPLAY_FINISHED", JSONObject().put("completed", completed)
                    .put("failure_class", primaryFailure?.javaClass?.simpleName ?: "NONE")
                    .put("recreations", recreations).put("recreation_total_nanos", recreationTotalNanos)
                    .put("recreation_max_nanos", recreationMaxNanos).put("artifact_preserved", true)) }
                cleanupFailure?.let { failure ->
                    val original = primaryFailure
                    if (original == null) throw failure
                    if (original !== failure) original.addSuppressed(failure)
                }
            }
        }
    }

    private fun createHomeRecognizer(model: Model): Recognizer {
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

    private fun fileSha(file: File, deadlineMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                requireBeforeDeadline(deadlineMs)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun requireBeforeDeadline(deadlineMs: Long) {
        assertTrue("Observed PCM replay exceeded its 120 second wall deadline",
            SystemClock.elapsedRealtime() < deadlineMs)
    }

    private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val EXPECTED_PACKAGE = "kr.co.hanium.dreamup.walksafe.accountrestartfixture"
        const val OBSERVED_DIRECTORY = "vosk-actual-home-pcm-19958740314349"
        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_SAMPLES = 320
        const val WALL_LIMIT_MS = 120_000L
        val SOURCES = mapOf(
            "1" to (314_240 to "f7d54e5d0a6b44ac5ff20f91222e4055f6ae1507ea0d3a56d050adcc0f6cd291"),
            "2" to (1_074_560 to "0843affe8cfb3b7c63229b69c567594a2c0bc5d29a83a21eb46d0564ca3b4cec"),
            "3" to (1_333_120 to "2a797600024aa4f26eeea99ae57d4eb10f23f463e9e0cc0343e7a344aab66fbc"),
        )
    }
}

