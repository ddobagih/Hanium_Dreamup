package kr.co.hanium.dreamup.walksafe.voice

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/** Fixed synthetic SDK observations. Completion is not a wake-recognition or AudioRecord PASS. */
@RunWith(AndroidJUnit4::class)
class VoskFixedWakeReplayDeviceTest {
    @Test(timeout = 60_000L)
    fun fixedOriginalWakeAndCapturedHostMonitorAreDecodedInOrder() {
        assumeTrue(InstrumentationRegistry.getArguments()
            .getString("allowFixedSyntheticWakeReplay") == "true")
        assertTrue("EMULATOR_ONLY", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("kr.co.hanium.dreamup.walksafe", context.packageName)
        val journal = File(context.cacheDir, "fixed-wake-replay-events.jsonl")
        assertTrue("NEW_JOURNAL_REQUIRED", journal.createNewFile())
        FileOutputStream(journal).use { output ->
            var sequence = 0
            fun emit(event: JSONObject) {
                event.put("sequence", sequence++)
                    .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                output.write((event.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                emit(JSONObject().put("event", "START")
                    .put("pass_means_structure_complete_only", true)
                    .put("guest_pcm_or_production_session_identity_proved", false)
                    .put("host_monitor_leading_silence_is_native_ready_boundary", false)
                    .put("all_endpoints_continue_after_addressed_unlike_probe", true)
                    .put("reset_calls", 0).put("eof_final_flush_calls", 0))
                assertEquals("0fab0196407d1a533311458551db6c0da734f8f3c3c04b9c5becf36a79c974c2",
                    sha256(File(context.applicationInfo.sourceDir)))
                val manifest = context.assets.open(
                    "voice-models/vosk-model-small-ko-0.22/MODEL_FILES.sha256",
                ).use { it.readBytes() }
                assertEquals("8396458f1990e174b88c531f7db14587a4b52273443a1b39dfd6b75552e135c3",
                    sha256(manifest))
                assertEquals(17, manifest.toString(Charsets.UTF_8).lineSequence().count { it.isNotBlank() })
                // This existing read-only API verifies every installed manifest file; it never repairs.
                val modelDirectory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context))
                val inputs = listOf(
                    Triple("fixed-wake-original-16k.s16le", 127_788,
                        "8df86f60229cb133941673b7cb3d2a5cc4c9651fb3791062cc3527272998cd5a"),
                    Triple("fixed-wake-host-monitor-16k.s16le", 879_360,
                        "23392d72bf8f45ba31987f7703d5ddd55f131755333afc9035e78400f90b86bc"),
                )
                val decodeDeadline = SystemClock.elapsedRealtime() + 30_000L
                emit(JSONObject().put("event", "MODEL_OPEN_BEGIN"))
                Model(modelDirectory.absolutePath).use { model ->
                    emit(JSONObject().put("event", "MODEL_OPENED"))
                    inputs.forEachIndexed { inputIndex, (name, expectedBytes, expectedSha) ->
                        val input = File(context.cacheDir, name)
                        assertTrue(input.isFile && !Files.isSymbolicLink(input.toPath()))
                        assertEquals(expectedBytes.toLong(), input.length())
                        val bytes = input.readBytes()
                        assertEquals(expectedBytes, bytes.size)
                        assertEquals(expectedSha, sha256(bytes))
                        val samples = ShortArray(bytes.size / 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        emit(JSONObject().put("event", "RECOGNIZER_OPEN_BEGIN")
                            .put("input_index", inputIndex).put("input", name)
                            .put("input_bytes", bytes.size).put("input_sha256", expectedSha)
                            .put("pcm_format", "S16LE_MONO_16000")
                            .put("input_kind", if (inputIndex == 0) "ORIGINAL_WAV_PCM_UNCHANGED"
                                else "HOST_VIRTUAL_INPUT_MONITOR_NOT_GUEST_PCM"))
                        val recognizer = Recognizer(model, 16_000f)
                        var consumed = 0
                        var chunks = 0
                        var endpoints = 0
                        try {
                            recognizer.setWords(true)
                            recognizer.setPartialWords(false)
                            recognizer.setMaxAlternatives(0)
                            recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
                            recognizer.setEndpointerDelays(5.0f, 0.35f, 5.0f)
                            emit(JSONObject().put("event", "RECOGNIZER_STARTED").put("input_index", inputIndex))
                            // Production READ_CHUNK_SAMPLES is private: 320, including the final remainder.
                            while (consumed < samples.size) {
                                assertTrue("DECODE_30_SECONDS_EXPIRED", SystemClock.elapsedRealtime() < decodeDeadline)
                                val chunk = samples.copyOfRange(consumed, minOf(consumed + 320, samples.size))
                                val offset = consumed
                                val endpoint = recognizer.acceptWaveForm(chunk, chunk.size)
                                consumed += chunk.size
                                emit(JSONObject().put("event", "ACCEPT").put("input_index", inputIndex)
                                    .put("chunk_index", chunks++).put("sample_offset", offset)
                                    .put("sample_count", chunk.size).put("accept_waveform", endpoint))
                                if (endpoint) {
                                    // Exactly one result read per true, even when its text is empty.
                                    val raw = recognizer.result
                                    val event = endpointObservation(raw)
                                        .put("event", "ENDPOINT_RESULT").put("input_index", inputIndex)
                                        .put("endpoint_index", endpoints++).put("consumed_samples", consumed)
                                    emit(event)
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("fixed_wake_endpoint", event.toString())
                                    })
                                }
                            }
                            val partialRaw = recognizer.partialResult
                            val partial = parseVoskTranscript(partialRaw, isFinal = false)
                            emit(JSONObject().put("event", "EOF_PARTIAL_ONLY").put("input_index", inputIndex)
                                .put("is_final", false).put("raw", partialRaw)
                                .put("text", partial?.text ?: JSONObject.NULL)
                                .put("wake_matcher_applied", false).put("forced_flush", false))
                            assertTrue("DECODE_30_SECONDS_EXPIRED", SystemClock.elapsedRealtime() < decodeDeadline)
                            assertEquals(samples.size, consumed)
                            assertEquals((samples.size + 319) / 320, chunks)
                            emit(JSONObject().put("event", "INPUT_COMPLETE").put("input_index", inputIndex)
                                .put("consumed_samples", consumed).put("accept_calls", chunks)
                                .put("endpoint_count", endpoints))
                        } finally {
                            try {
                                emit(JSONObject().put("event", "RECOGNIZER_CLOSE_BEGIN").put("input_index", inputIndex))
                            } finally {
                                recognizer.close()
                            }
                            emit(JSONObject().put("event", "RECOGNIZER_CLOSED").put("input_index", inputIndex))
                        }
                    }
                }
                emit(JSONObject().put("event", "MODEL_CLOSED"))
                val result = JSONObject().put("event", "STRUCTURE_COMPLETE")
                    .put("fixed_inputs_completed", 2).put("wake_recognition_pass_claim", false)
                    .put("journal", journal.name).put("reset_calls", 0).put("eof_final_flush_calls", 0)
                emit(result)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("fixed_wake_replay_result", result.toString())
                })
            } catch (error: Throwable) {
                emit(JSONObject().put("event", "FAILED").put("error_type", error.javaClass.name))
                throw error
            }
        }
    }

    private fun endpointObservation(raw: String): JSONObject {
        val transcript = parseVoskTranscript(raw, isFinal = true)
        val extraction = transcript?.let {
            WakePhraseCommandExtractor.extractFinalTranscript(it.text, WakePhraseCommandMode.WAKE_PHRASE_REQUIRED)
        }
        val trusted = transcript?.isTrustedForCommand()
        val wakeSpan = if (transcript != null && extraction is WakePhraseCommandExtraction.Command)
            transcript.wakeSpanTrustedBefore(extraction.text) else null
        val classification = when {
            transcript == null -> "NO_TRANSCRIPT"
            trusted != true -> "UNTRUSTED"
            wakeSpan == false -> "UNTRUSTED_WAKE_SPAN"
            extraction is WakePhraseCommandExtraction.NotAddressed -> "NOT_ADDRESSED"
            else -> "ADDRESSED"
        }
        val matcher = when (extraction) {
            WakePhraseCommandExtraction.NotAddressed -> "NOT_ADDRESSED"
            WakePhraseCommandExtraction.AwaitingCommand -> "AWAITING_COMMAND"
            is WakePhraseCommandExtraction.Command -> "COMMAND"
            null -> JSONObject.NULL
        }
        return JSONObject().put("raw", raw).put("is_final", true)
            .put("text", transcript?.text ?: JSONObject.NULL)
            .put("confidence", transcript?.confidence ?: JSONObject.NULL)
            .put("word_confidences", transcript?.wordConfidences?.let { JSONArray(it) } ?: JSONObject.NULL)
            .put("trusted_for_command", trusted ?: JSONObject.NULL)
            .put("extractor_observation_even_if_untrusted", matcher)
            .put("command_text", (extraction as? WakePhraseCommandExtraction.Command)?.text ?: JSONObject.NULL)
            .put("wake_span_trusted", wakeSpan ?: JSONObject.NULL)
            .put("production_probe_classification", classification)
            .put("production_probe_would_accept", classification == "ADDRESSED")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
