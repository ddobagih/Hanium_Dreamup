package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
 * 별도 opt-in native 진단이며 원 인수 시험 묶음의 성공 개수에 포함하지 않는다.
 * zero PCM은 제어된 재현 조건이다. 실제 crash 입력이 zero였다는 증거가 아니다.
 * 실제 packaged Model/Recognizer를 사용하지만 production processPcm, AudioRecord,
 * 사람 발화, 실제 호출어 수락 또는 가청 안내를 검증하지 않는다.
 * private JSONL 하나를 성공/실패/crash 모두에서 보존하며 기존 파일은 삭제하지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class VoskContinuousEndpointDiagnosticDeviceTest {
    @Test(timeout = WALL_LIMIT_MS)
    fun controlledQuietAcrossContinuousEndpoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runVoskContinuousEndpointDiagnostic") == "true")
        val mode = checkNotNull(arguments.getString("diagnosticMode")) {
            "diagnosticMode=continuous or recreate is required"
        }
        require(mode == "continuous" || mode == "recreate") { "Unsupported diagnosticMode" }
        val context = instrumentation.targetContext
        val directory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
            "Existing bundled model must be prepared; this diagnostic never installs a model"
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val deadlineMs = startedAtMs + WALL_LIMIT_MS
        val runId = SystemClock.elapsedRealtimeNanos()
        val artifact = File(context.cacheDir, "vosk-continuous-endpoint-$runId-$mode.jsonl")
        check(artifact.createNewFile()) { "Cannot create the exact diagnostic-owned artifact" }
        val chunk = ShortArray(CHUNK_SAMPLES)
        val expectedChunkBytes = ByteArray(CHUNK_SAMPLES * 2)
        val expectedDigest = MessageDigest.getInstance("SHA-256")
        repeat(TOTAL_CHUNKS) { expectedDigest.update(expectedChunkBytes) }
        val expectedFullSha = hex(expectedDigest.digest())
        val submittedDigest = MessageDigest.getInstance("SHA-256")
        var chunksAttempted = 0
        var chunksCompleted = 0
        var samplesSubmitted = 0
        var firstChunkSamples = 0
        var lastChunkSamples = 0
        var endpoints = 0
        var fiveSecondIntervals = 0
        var lastEndpointSamples = 0
        var minimumEndpointIntervalSamples = Int.MAX_VALUE
        var nativeInstance = 1
        var recreations = 0
        var recreationTotalNanos = 0L
        var recreationMaxNanos = 0L
        var completed = false

        FileOutputStream(artifact).use { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            fun record(event: String, fields: JSONObject = JSONObject()) {
                fields.put("event", event).put("run_id", runId).put("mode", mode)
                    .put("wall_elapsed_ms", SystemClock.elapsedRealtime() - startedAtMs)
                    .put("chunks_attempted", chunksAttempted).put("chunks_completed", chunksCompleted)
                    .put("samples_submitted", samplesSubmitted).put("endpoints", endpoints)
                    .put("diagnostic_native_instance", nativeInstance)
                    .put("generation_kind", "DIAGNOSTIC_NATIVE_INSTANCE")
                val line = fields.toString()
                writer.write(line)
                writer.newLine()
                writer.flush()
                output.fd.sync()
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", line + "\n") })
            }
            var model: Model? = null
            var recognizer: Recognizer? = null
            try {
                record("DIAGNOSTIC_BEGIN", JSONObject()
                    .put("input_kind", "CONTROLLED_ZERO_PCM_NOT_OBSERVED_CRASH_INPUT")
                    .put("sample_rate", SAMPLE_RATE_HZ).put("chunk_samples", CHUNK_SAMPLES)
                    .put("audio_seconds", AUDIO_SECONDS).put("total_samples", TOTAL_SAMPLES)
                    .put("total_chunks", TOTAL_CHUNKS).put("input_full_sha256", expectedFullSha)
                    .put("wall_limit_ms", WALL_LIMIT_MS).put("model_directory_name", directory.name)
                    .put("artifact", artifact.absolutePath).put("artifact_preserved", true)
                    .put("production_process_pcm_tested", false))
                requireBeforeDeadline(deadlineMs)
                val modelStarted = SystemClock.elapsedRealtimeNanos()
                val openedModel = Model(directory.absolutePath)
                model = openedModel
                record("MODEL_OPENED", JSONObject()
                    .put("model_open_nanos", SystemClock.elapsedRealtimeNanos() - modelStarted))
                requireBeforeDeadline(deadlineMs)
                record("RECOGNIZER_CREATE_ENTER")
                val initialStarted = SystemClock.elapsedRealtimeNanos()
                recognizer = createHomeRecognizer(openedModel)
                record("RECOGNIZER_CREATED", JSONObject()
                    .put("initial_create_nanos", SystemClock.elapsedRealtimeNanos() - initialStarted)
                    .put("words", true).put("partial_words", false).put("max_alternatives", 0)
                    .put("endpointer_mode", "SHORT").put("start_max_seconds", 5.0)
                    .put("end_seconds", 0.35).put("max_seconds", 5.0))
                for (index in 1..TOTAL_CHUNKS) {
                    requireBeforeDeadline(deadlineMs)
                    val bytes = pcm16LittleEndian(chunk)
                    var zeroSamples = 0
                    var squareSum = 0.0
                    for (sample in chunk) {
                        if (sample == 0.toShort()) zeroSamples++
                        squareSum += sample.toDouble() * sample.toDouble()
                    }
                    assertEquals("Controlled input must remain exactly zero", CHUNK_SAMPLES, zeroSamples)
                    chunksAttempted = index
                    // JNI 호출 전에 durable 기록: abort가 나도 마지막 진입 chunk를 식별한다.
                    record("CHUNK_ACCEPT_ENTER", JSONObject()
                        .put("chunk_index", index).put("chunk_samples", chunk.size)
                        .put("zero_samples", zeroSamples).put("zero_ratio", zeroSamples.toDouble() / chunk.size)
                        .put("rms", sqrt(squareSum / chunk.size))
                        .put("chunk_sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                    val active = checkNotNull(recognizer)
                    val endpoint = active.acceptWaveForm(chunk, chunk.size)
                    chunksCompleted++
                    samplesSubmitted += chunk.size
                    submittedDigest.update(bytes)
                    if (firstChunkSamples == 0) firstChunkSamples = chunk.size
                    lastChunkSamples = chunk.size
                    requireBeforeDeadline(deadlineMs)
                    if (endpoint) {
                        record("ENDPOINT_RESULT_ENTER")
                        val nativeResult = active.result // 실제 endpoint 결과를 정확히 한 번 읽는다.
                        val intervalSamples = samplesSubmitted - lastEndpointSamples
                        lastEndpointSamples = samplesSubmitted
                        minimumEndpointIntervalSamples = minOf(minimumEndpointIntervalSamples, intervalSamples)
                        if (intervalSamples >= MIN_ENDPOINT_INTERVAL_SAMPLES) fiveSecondIntervals++
                        endpoints++
                        record("ENDPOINT_RESULT", JSONObject().put("native_result", nativeResult)
                            .put("endpoint_interval_samples", intervalSamples)
                            .put("endpoint_interval_audio_ms", intervalSamples * 1000L / SAMPLE_RATE_HZ)
                            .put("five_second_intervals", fiveSecondIntervals))
                        if (mode == "recreate" && samplesSubmitted < TOTAL_SAMPLES) {
                            requireBeforeDeadline(deadlineMs)
                            record("RECOGNIZER_RECREATE_ENTER")
                            val recreationStarted = SystemClock.elapsedRealtimeNanos()
                            recognizer = null
                            active.close()
                            recognizer = createHomeRecognizer(openedModel)
                            val recreationNanos = SystemClock.elapsedRealtimeNanos() - recreationStarted
                            nativeInstance++
                            recreations++
                            recreationTotalNanos += recreationNanos
                            recreationMaxNanos = maxOf(recreationMaxNanos, recreationNanos)
                            record("RECOGNIZER_RECREATED", JSONObject()
                                .put("recreation_nanos", recreationNanos)
                                .put("same_model_instance", true))
                        }
                    }
                }
                val submittedSha = hex(submittedDigest.digest())
                record("INPUT_COMPLETED", JSONObject()
                    .put("first_chunk_samples", firstChunkSamples).put("last_chunk_samples", lastChunkSamples)
                    .put("input_full_sha256", expectedFullSha)
                    .put("submitted_sha256", submittedSha)
                    .put("five_second_intervals", fiveSecondIntervals)
                    .put("minimum_endpoint_interval_samples", minimumEndpointIntervalSamples)
                    .put("recreations", recreations).put("recreation_total_nanos", recreationTotalNanos)
                    .put("recreation_max_nanos", recreationMaxNanos))
                assertEquals("Exactly ninety audio seconds must be submitted", TOTAL_SAMPLES, samplesSubmitted)
                assertEquals("Every fixed chunk must complete", TOTAL_CHUNKS, chunksCompleted)
                assertEquals("Submitted PCM must match the fixed input SHA", expectedFullSha, submittedSha)
                assertTrue("At least ten endpoint intervals must span five audio seconds", fiveSecondIntervals >= 10)
                requireBeforeDeadline(deadlineMs)
                completed = true
            } finally {
                // EOF flush와 reset은 호출하지 않는다. 이 mode의 native 객체만 정리한다.
                try {
                    recognizer?.close()
                } finally {
                    try {
                        model?.close()
                    } finally {
                        record("DIAGNOSTIC_FINISHED", JSONObject().put("completed", completed)
                            .put("first_chunk_samples", firstChunkSamples).put("last_chunk_samples", lastChunkSamples)
                            .put("five_second_intervals", fiveSecondIntervals).put("recreations", recreations)
                            .put("recreation_total_nanos", recreationTotalNanos)
                            .put("recreation_max_nanos", recreationMaxNanos)
                            .put("artifact_preserved", true))
                    }
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

    private fun requireBeforeDeadline(deadlineMs: Long) {
        assertTrue("Native diagnostic exceeded its 120 second wall deadline",
            SystemClock.elapsedRealtime() < deadlineMs)
    }

    private fun pcm16LittleEndian(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        for (index in samples.indices) {
            val value = samples[index].toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value ushr 8).toByte()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_SAMPLES = 320
        const val AUDIO_SECONDS = 90
        const val TOTAL_SAMPLES = SAMPLE_RATE_HZ * AUDIO_SECONDS
        const val TOTAL_CHUNKS = TOTAL_SAMPLES / CHUNK_SAMPLES
        const val MIN_ENDPOINT_INTERVAL_SAMPLES = SAMPLE_RATE_HZ * 5
        const val WALL_LIMIT_MS = 120_000L
    }
}
