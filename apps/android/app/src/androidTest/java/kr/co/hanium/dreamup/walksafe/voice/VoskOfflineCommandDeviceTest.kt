package kr.co.hanium.dreamup.walksafe.voice

import android.media.AudioFormat
import android.os.Bundle
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/** Synthetic, microphone-free model smoke only; this does not certify human speech recognition. */
@RunWith(AndroidJUnit4::class)
class VoskOfflineCommandDeviceTest {
    @Test
    fun installedModelMapsOfflineSynthesizedKoreanCommands() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preservationRoot = if (
            InstrumentationRegistry.getArguments().getString("preserveOriginalVoskCommandSmokeInput") == "true"
        ) File(context.cacheDir, "vosk-original-smoke-input-" + SystemClock.elapsedRealtimeNanos()) else null
        val modelDirectory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
            "MODEL_NOT_INSTALLED: run the normal model preparation first; this test never installs it"
        }
        val model = try {
            Model(modelDirectory.absolutePath)
        } catch (error: Exception) {
            throw AssertionError("MODEL_LOAD_FAILED: ${error.javaClass.simpleName}")
        }
        var tts: TextToSpeech? = null
        try {
            val initialized = CountDownLatch(1)
            val initStatus = AtomicInteger(TextToSpeech.ERROR)
            instrumentation.runOnMainSync {
                tts = TextToSpeech(context) {
                    initStatus.set(it)
                    initialized.countDown()
                }
            }
            assertTrue("TTS_INIT_TIMEOUT", initialized.await(15, TimeUnit.SECONDS))
            assertEquals("TTS_INIT_FAILED", TextToSpeech.SUCCESS, initStatus.get())
            val engine = checkNotNull(tts)
            val offlineVoices = engine.voices.orEmpty().filter {
                it.locale.language == Locale.KOREAN.language &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            val voice = checkNotNull(
                engine.voice?.takeIf { it in offlineVoices }
                    ?: offlineVoices.minByOrNull { it.name },
            ) { "OFFLINE_KOREAN_VOICE_UNAVAILABLE" }
            assertEquals("OFFLINE_KOREAN_VOICE_REJECTED", TextToSpeech.SUCCESS, engine.setVoice(voice))
            assertEquals("TTS_RATE_REJECTED", TextToSpeech.SUCCESS, engine.setSpeechRate(1.0f))

            val fixtures = listOf(
                Fixture("HELP", "도움말", AndroidVoiceAction.SpeakVoiceHelp),
                Fixture("START", "안내 시작", AndroidVoiceAction.StartNavigation),
                Fixture("DESTINATION", "서울역으로 안내해줘", AndroidVoiceAction.SearchDestination("서울역")),
            )
            val mismatches = mutableListOf<String>()
            for (fixture in fixtures) {
                val scratch = File.createTempFile("walksafe-voice-fixture-", ".wav", context.cacheDir)
                var preservedCapture: SynthesisCapture? = null
                var preservedArtifacts: File? = null
                var preservedReceipt: JSONObject? = null
                var preservationInitializationError: String? = null
                try {
                    val capture = SynthesisCapture(fixture.id, preserveLifecycle = preservationRoot != null)
                    preservedCapture = capture
                    val artifacts = preservationRoot?.let {
                        runCatching { File(it, fixture.id) }
                            .onFailure { preservationInitializationError = it.javaClass.simpleName }
                            .getOrNull()
                    }
                    preservedArtifacts = artifacts
                    val receipt = if (preservationRoot != null) runCatching {
                        JSONObject()
                            .put("source", "same_original_smoke_synthesis_not_human_microphone")
                            .put("fixture_id", fixture.id)
                            .put("requested_utterance_id", fixture.id)
                            .put("requested_text", fixture.text)
                            .put("synthesis_bundle", JSONObject())
                            .put("metadata_observed", false)
                            .put("metadata_initialization_error", JSONObject.NULL)
                            .put("tts_engine", JSONObject.NULL)
                            .put("requested_voice", JSONObject.NULL)
                            .put("selected_voice", JSONObject.NULL)
                            .put("voice_locale", JSONObject.NULL)
                            .put("voice_requires_network", JSONObject.NULL)
                            .put("configured_speech_rate", 1.0)
                            .put("pitch_explicitly_set", false)
                            .put("expected_action", fixture.expectedAction.toString())
                            .put("decoder", JSONObject().put("sample_rate_hz", 16_000).put("chunk_frames", 512)
                                .put("words", true).put("partial_words", false).put("max_alternatives", 0)
                                .put("endpointer_mode", "SHORT").put("appended_silence_frames", 32_000))
                            .put("native_results", JSONArray())
                            .put("native_result_observed", false)
                            .put("decode_return_observed", false)
                            .put("transcript", JSONObject.NULL)
                            .put("parser_action", JSONObject.NULL)
                            .put("expected_action_match", JSONObject.NULL)
                    }.onFailure { preservationInitializationError = it.javaClass.simpleName }.getOrNull() else null
                    preservedReceipt = receipt
                    if (receipt != null) {
                        runCatching {
                            receipt.put("tts_engine", engine.defaultEngine)
                                .put("requested_voice", voice.name)
                                .put("selected_voice", engine.voice?.name ?: JSONObject.NULL)
                                .put("voice_locale", voice.locale.toLanguageTag())
                                .put("voice_requires_network", voice.isNetworkConnectionRequired)
                                .put("metadata_observed", true)
                        }.onFailure { preservationInitializationError = it.javaClass.simpleName }
                        runCatching {
                            preservationInitializationError?.let { receipt.put("metadata_initialization_error", it) }
                        }
                    }
                    assertEquals(
                        "TTS_LISTENER_REJECTED",
                        TextToSpeech.SUCCESS,
                        engine.setOnUtteranceProgressListener(capture),
                    )
                    assertEquals(
                        "TTS_SYNTHESIS_REJECTED: ${fixture.id}",
                        TextToSpeech.SUCCESS,
                        engine.synthesizeToFile(fixture.text, Bundle(), scratch, fixture.id).also {
                            if (receipt != null) {
                                runCatching { receipt.put("synthesis_return_code", it) }
                                    .onFailure { error -> receipt.put("synthesis_metadata_error", error.javaClass.simpleName) }
                            }
                        },
                    )
                    assertTrue("TTS_SYNTHESIS_TIMEOUT: ${fixture.id}", capture.done.await(20, TimeUnit.SECONDS))
                    assertEquals("TTS_SYNTHESIS_FAILED: ${fixture.id}", "DONE", capture.outcome)
                    if (artifacts != null && receipt != null) {
                        runCatching {
                            check(artifacts.mkdirs()) { "SMOKE_INPUT_ARTIFACT_DIRECTORY_FAILED" }
                            val wavBytes = scratch.readBytes()
                            File(artifacts, "original.wav").writeBytes(wavBytes)
                            receipt.put("original_wav", preservedSmokeBytes("original.wav", wavBytes))
                            val original = capture.diagnosticPcm()
                            File(artifacts, "callback-original.pcm").writeBytes(original.bytes)
                            receipt.put("callback_pcm", preservedSmokeBytes("callback-original.pcm", original.bytes)
                                .put("sample_rate_hz", original.sampleRate).put("audio_format", original.format)
                                .put("channels", original.channels))
                            receipt.put("tts_callbacks", capture.preservedLifecycle())
                            File(artifacts, "receipt.json").writeText(receipt.toString(2))
                        }.onFailure { receipt.put("input_preservation_error", it.javaClass.simpleName) }
                    }
                    val pcm = capture.pcmAt16Khz()
                    if (artifacts != null && receipt != null) {
                        runCatching {
                            receipt.put("resampled_pcm", preserveSmokePcm(artifacts, "resampled-16k.s16le", pcm))
                        }.onFailure { receipt.put("resampled_preservation_error", it.javaClass.simpleName) }
                    }
                    val observeDecode: ((ShortArray, Int, String?) -> Unit)? = if (artifacts != null && receipt != null) {
                        { input, offset, rawResult ->
                            runCatching {
                                when {
                                    offset == 0 -> receipt.put("decoder_feed", preserveSmokePcm(artifacts, "decoder-feed-16k.s16le", input))
                                    rawResult != null -> {
                                        receipt.put("native_result_observed", true)
                                        receipt.getJSONArray("native_results").put(
                                            JSONObject().put("offset_frames", offset).put("raw_json", rawResult),
                                        )
                                    }
                                    else -> receipt.put("submitted_frames", offset)
                                        .put("submitted_pcm_sha256", smokeSha256(pcm16Bytes(input.copyOf(offset))))
                                }
                            }.onFailure { receipt.put("decode_preservation_error", it.javaClass.simpleName) }
                        }
                    } else null
                    val transcript = recognizeFirstFinal(model, pcm, observeDecode)
                    if (receipt != null) {
                        runCatching {
                            receipt.put("decode_return_observed", true)
                            receipt.put("transcript", transcript?.let {
                                JSONObject().put("text", it.text).put("confidence", it.confidence ?: JSONObject.NULL)
                            } ?: JSONObject.NULL)
                        }.onFailure { receipt.put("transcript_preservation_error", it.javaClass.simpleName) }
                    }
                    val results = transcript?.toSpeechRecognitionResults()
                    val action = results?.let {
                        selectAndroidVoiceAction(
                            it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                            it.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES),
                        )
                    }
                    if (receipt != null) {
                        runCatching {
                            receipt.put("parser_action", action?.toString() ?: JSONObject.NULL)
                            receipt.put("expected_action_match", action == fixture.expectedAction)
                        }.onFailure { receipt.put("action_preservation_error", it.javaClass.simpleName) }
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("walksafe_voice_fixture", fixture.id)
                            putString("walksafe_voice_source", "offline_tts_not_human_microphone")
                            putString("walksafe_voice_recognized", transcript?.text.orEmpty())
                            putString("walksafe_voice_confidence", transcript?.confidence?.toString() ?: "missing")
                            putString("walksafe_voice_action", action?.toString() ?: "unmatched")
                        },
                    )
                    if (action != fixture.expectedAction) mismatches += fixture.id
                } finally {
                    try {
                        val artifacts = preservedArtifacts
                        val receipt = preservedReceipt
                        val capture = preservedCapture
                        if (artifacts != null && receipt != null) {
                            runCatching {
                                if (!artifacts.isDirectory) check(artifacts.mkdirs()) { "SMOKE_INPUT_ARTIFACT_DIRECTORY_FAILED" }
                                receipt.put("tts_callbacks", capture?.preservedLifecycle() ?: JSONObject.NULL)
                                    .put("tts_outcome", capture?.outcome ?: JSONObject.NULL)
                                preservationInitializationError?.let { receipt.put("metadata_initialization_error", it) }
                                File(artifacts, "receipt.json").writeText(receipt.toString(2))
                            }.onFailure { error ->
                                // Metadata only; an export failure must not replace the original smoke outcome.
                                runCatching {
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("walksafe_voice_preservation_error", error.javaClass.simpleName)
                                    })
                                }
                            }
                        } else if (preservationInitializationError != null) {
                            runCatching {
                                instrumentation.sendStatus(0, Bundle().apply {
                                    putString("walksafe_voice_preservation_error", preservationInitializationError)
                                })
                            }
                        }
                    } finally {
                        engine.stop()
                        scratch.delete()
                    }
                }
            }
            assertTrue("RECOGNITION_OR_MAPPING_FAILED: ${mismatches.joinToString()}", mismatches.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                tts?.stop()
                tts?.shutdown()
            }
            model.close()
        }
    }

    @Test
    fun diagnoseOfflineSyntheticPcmAndDecodeConditions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(line: String) = instrumentation.sendStatus(
            0,
            Bundle().apply { putString("walksafe_voice_diagnostic", line) },
        )
        val modelDirectory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
            "MODEL_NOT_INSTALLED"
        }
        val model = Model(modelDirectory.absolutePath)
        var tts: TextToSpeech? = null
        try {
            val initialized = CountDownLatch(1)
            val initStatus = AtomicInteger(TextToSpeech.ERROR)
            instrumentation.runOnMainSync {
                tts = TextToSpeech(context) {
                    initStatus.set(it)
                    initialized.countDown()
                }
            }
            assertTrue("TTS_INIT_TIMEOUT", initialized.await(15, TimeUnit.SECONDS))
            assertEquals("TTS_INIT_FAILED", TextToSpeech.SUCCESS, initStatus.get())
            val engine = checkNotNull(tts)
            val offlineVoices = engine.voices.orEmpty().filter {
                it.locale.language == Locale.KOREAN.language &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            val voices = (
                listOfNotNull(engine.voice?.takeIf { it in offlineVoices }) +
                    offlineVoices.sortedBy { it.name }
                ).distinctBy { it.name }.take(2)
            assertTrue("OFFLINE_KOREAN_VOICE_UNAVAILABLE", voices.isNotEmpty())
            assertEquals("TTS_RATE_REJECTED", TextToSpeech.SUCCESS, engine.setSpeechRate(1.0f))
            val artifacts = File(context.cacheDir, "voice-diagnostics-" + System.currentTimeMillis())
            assertTrue("ARTIFACT_DIRECTORY_FAILED", artifacts.mkdir())
            report(
                "source=offline_tts_not_human default_engine=" + engine.defaultEngine +
                    " abis=" + android.os.Build.SUPPORTED_ABIS.joinToString(",") +
                    " voices=" + voices.size + " artifacts=" + artifacts.absolutePath,
            )
            val fixtures = listOf(
                Fixture("HELP", "도움말", AndroidVoiceAction.SpeakVoiceHelp),
                Fixture("START", "안내 시작", AndroidVoiceAction.StartNavigation),
                Fixture("DESTINATION", "서울역으로 안내해줘", AndroidVoiceAction.SearchDestination("서울역")),
            )
            val failures = mutableListOf<String>()
            voices.forEachIndexed { voiceIndex, voice ->
                assertEquals("TTS_VOICE_REJECTED", TextToSpeech.SUCCESS, engine.setVoice(voice))
                report(
                    "voice_index=" + voiceIndex + " requested=" + voice.name +
                        " selected=" + engine.voice?.name + " locale=" + voice.locale.toLanguageTag() +
                        " network=" + voice.isNetworkConnectionRequired,
                )
                for (fixture in fixtures) {
                    val id = "v" + voiceIndex + "_" + fixture.id
                    val scratch = File(artifacts, id + ".wav")
                    var synthesisComplete = false
                    try {
                        val capture = SynthesisCapture(id)
                        assertEquals(
                            "TTS_LISTENER_REJECTED",
                            TextToSpeech.SUCCESS,
                            engine.setOnUtteranceProgressListener(capture),
                        )
                        assertEquals(
                            "TTS_SYNTHESIS_REJECTED",
                            TextToSpeech.SUCCESS,
                            engine.synthesizeToFile(fixture.text, Bundle(), scratch, id),
                        )
                        assertTrue("TTS_SYNTHESIS_TIMEOUT", capture.done.await(20, TimeUnit.SECONDS))
                        assertEquals("TTS_SYNTHESIS_FAILED", "DONE", capture.outcome)
                        synthesisComplete = true
                        val original = capture.diagnosticPcm()
                        val resampled = capture.pcmAt16Khz()
                        val rawSamples = pcm16Samples(original.bytes)
                        val wav = readDiagnosticWav(scratch)
                        val bytesMatch = wav.pcm.contentEquals(original.bytes)
                        val formatMatch = wav.sampleRate == original.sampleRate &&
                            wav.channels == original.channels && wav.bitsPerSample == 16
                        report(
                            "case=" + id + " rate=" + original.sampleRate + " channels=" + original.channels +
                                " format=" + original.format + " samples=" + rawSamples.size +
                                " duration_ms=" + rawSamples.size * 1000L / original.sampleRate +
                                " resample=" + original.sampleRate + ":16000" +
                                " resampled_samples=" + resampled.size +
                                " wav_pcm_equal=" + bytesMatch + " wav_format_equal=" + formatMatch +
                                " wav_rate=" + wav.sampleRate + " wav_channels=" + wav.channels +
                                " wav_bits=" + wav.bitsPerSample + " " + diagnosticWaveformStats(rawSamples),
                        )
                        if (!bytesMatch || !formatMatch || rawSamples.all { it == 0.toShort() }) {
                            failures += id + ":WAVEFORM"
                        }
                        val conditions = listOf(
                            DiagnosticCondition("linear16k_512", resampled, 16_000, 512),
                            DiagnosticCondition("linear16k_4096", resampled, 16_000, 4096),
                            DiagnosticCondition("linear16k_full", resampled, 16_000, resampled.size + 32_000),
                            DiagnosticCondition(
                                "native_rate_32ms",
                                rawSamples,
                                original.sampleRate,
                                maxOf(1, original.sampleRate * 32 / 1000),
                            ),
                        )
                        for (condition in conditions) {
                            val started = System.nanoTime()
                            val decoded = diagnosticDecode(model, condition)
                            val bundle = decoded.transcript?.toSpeechRecognitionResults()
                            val action = bundle?.let {
                                selectAndroidVoiceAction(
                                    it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                                    it.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES),
                                )
                            }
                            report(
                                "case=" + id + " decode=" + condition.name +
                                    " endpoint_ms=" + (decoded.endpointMs?.toString() ?: "none") +
                                    " elapsed_ms=" + (System.nanoTime() - started) / 1_000_000 +
                                    " text=" + decoded.transcript?.text.orEmpty().take(100) +
                                    " confidence=" + (decoded.transcript?.confidence?.toString() ?: "missing") +
                                    " action=" + (action?.toString() ?: "unmatched") +
                                    " expected_match=" + (action == fixture.expectedAction),
                            )
                            if (action != fixture.expectedAction) failures += id + ":" + condition.name
                        }
                    } catch (error: Exception) {
                        failures += id + ":" + error.javaClass.simpleName
                        report("case=" + id + " error=" + error.javaClass.simpleName)
                    } catch (error: AssertionError) {
                        failures += id + ":ASSERTION"
                        report("case=" + id + " assertion=" + error.message.orEmpty().take(140))
                    } finally {
                        engine.stop()
                        // Keep only completed, fixed synthetic fixtures for the root's waveform inspection.
                        if (!synthesisComplete) scratch.delete()
                    }
                }
            }
            report("synthetic_diagnostic_failures=" + failures.size + " artifacts=" + artifacts.absolutePath)
            assertTrue("SYNTHETIC_DIAGNOSTIC_FAILED: " + failures.joinToString(), failures.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                tts?.stop()
                tts?.shutdown()
            }
            model.close()
        }
    }

    @Test
    fun actualAdapterBundlePreservesConfidenceAndRejectsUnscoredOrLowConfidenceResults() {
        for (confidence in listOf(0.9, 0.2, null)) {
            val result = if (confidence == null) "" else ""","result":[{"conf":$confidence}]"""
            val transcript = checkNotNull(parseVoskTranscript("""{"text":"도움말"$result}""", isFinal = true))
            val bundle = transcript.toSpeechRecognitionResults()
            val scores = checkNotNull(bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
            val action = selectAndroidVoiceAction(
                bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                scores,
            )
            if (confidence == null) {
                assertTrue(scores.single().isNaN())
            } else {
                assertEquals(confidence.toFloat(), scores.single(), 0.0001f)
            }
            if (confidence == 0.9) assertEquals(AndroidVoiceAction.SpeakVoiceHelp, action)
            else assertNull(action)
        }
    }

    private fun recognizeFirstFinal(
        model: Model,
        pcm: ShortArray,
        observe: ((ShortArray, Int, String?) -> Unit)? = null,
    ): VoskTranscript? {
        val recognizer = Recognizer(model, 16_000f)
        try {
            recognizer.setWords(true)
            recognizer.setPartialWords(false)
            recognizer.setMaxAlternatives(0)
            recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
            // Silence exercises the production endpoint path, not an artificial finalResult flush.
            val input = pcm + ShortArray(32_000)
            var offset = 0
            observe?.invoke(input, 0, null)
            try {
                while (offset < input.size) {
                    val chunk = input.copyOfRange(offset, minOf(offset + 512, input.size))
                    offset += chunk.size
                    if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        val rawResult = recognizer.result
                        observe?.invoke(input, offset, rawResult)
                        parseVoskTranscript(rawResult, isFinal = true)?.let { return it }
                    }
                }
                return null
            } finally {
                observe?.invoke(input, offset, null)
            }
        } finally {
            recognizer.close()
        }
    }

    private fun pcm16Bytes(samples: ShortArray): ByteArray = ByteBuffer.allocate(samples.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).also { it.asShortBuffer().put(samples) }.array()

    private fun smokeSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun preservedSmokeBytes(name: String, bytes: ByteArray): JSONObject = JSONObject()
        .put("file", name).put("bytes", bytes.size).put("sha256", smokeSha256(bytes))

    private fun preserveSmokePcm(directory: File, name: String, samples: ShortArray): JSONObject {
        val bytes = pcm16Bytes(samples)
        File(directory, name).writeBytes(bytes)
        return preservedSmokeBytes(name, bytes).put("frames", samples.size).put("sample_rate_hz", 16_000)
            .put("channels", 1).put("format", "signed_pcm16_little_endian")
    }

    private data class DiagnosticPcm(
        val sampleRate: Int,
        val format: Int,
        val channels: Int,
        val bytes: ByteArray,
    )

    private data class DiagnosticWav(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pcm: ByteArray,
    )

    private data class DiagnosticCondition(
        val name: String,
        val pcm: ShortArray,
        val sampleRate: Int,
        val chunkSize: Int,
    )

    private data class DiagnosticDecode(
        val transcript: VoskTranscript?,
        val endpointMs: Long?,
    )

    private fun pcm16Samples(bytes: ByteArray): ShortArray {
        assertTrue("INVALID_PCM16_BYTES", bytes.isNotEmpty() && bytes.size % 2 == 0)
        return ShortArray(bytes.size / 2).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
    }

    private fun diagnosticWaveformStats(samples: ShortArray): String {
        var squares = 0.0
        var peak = 0
        var zeros = 0
        var clipped = 0
        for (sample in samples) {
            val value = sample.toInt()
            squares += value.toDouble() * value
            peak = maxOf(peak, kotlin.math.abs(value))
            if (value == 0) zeros += 1
            if (kotlin.math.abs(value) >= 32760) clipped += 1
        }
        return String.format(
            Locale.US,
            "rms=%.2f peak=%d zero_pct=%.2f clip_pct=%.2f",
            kotlin.math.sqrt(squares / samples.size),
            peak,
            zeros * 100.0 / samples.size,
            clipped * 100.0 / samples.size,
        )
    }

    private fun readDiagnosticWav(file: File): DiagnosticWav {
        val bytes = file.readBytes()
        assertTrue("WAV_HEADER_MISSING", bytes.size >= 12)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sampleRate: Int? = null
        var channels: Int? = null
        var bits: Int? = null
        var pcm: ByteArray? = null
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = buffer.getInt(offset + 4)
            val start = offset + 8
            val end = start.toLong() + length
            assertTrue("WAV_CHUNK_TRUNCATED", length >= 0 && end <= bytes.size)
            if (id == "fmt ") {
                assertTrue("WAV_FORMAT_TRUNCATED", length >= 16)
                assertEquals("WAV_NOT_INTEGER_PCM", 1, buffer.getShort(start).toInt() and 0xffff)
                channels = buffer.getShort(start + 2).toInt() and 0xffff
                sampleRate = buffer.getInt(start + 4)
                bits = buffer.getShort(start + 14).toInt() and 0xffff
            } else if (id == "data") {
                assertNull("MULTIPLE_WAV_DATA_CHUNKS", pcm)
                pcm = bytes.copyOfRange(start, end.toInt())
            }
            offset = (end + (length and 1)).toInt()
        }
        return DiagnosticWav(
            checkNotNull(sampleRate) { "WAV_SAMPLE_RATE_MISSING" },
            checkNotNull(channels) { "WAV_CHANNELS_MISSING" },
            checkNotNull(bits) { "WAV_BIT_DEPTH_MISSING" },
            checkNotNull(pcm) { "WAV_PCM_MISSING" },
        )
    }

    private fun diagnosticDecode(model: Model, condition: DiagnosticCondition): DiagnosticDecode {
        val recognizer = Recognizer(model, condition.sampleRate.toFloat())
        try {
            recognizer.setWords(true)
            recognizer.setPartialWords(false)
            recognizer.setMaxAlternatives(0)
            recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
            val input = condition.pcm + ShortArray(condition.sampleRate * 2)
            var offset = 0
            while (offset < input.size) {
                val chunk = input.copyOfRange(offset, minOf(offset + condition.chunkSize, input.size))
                offset += chunk.size
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    parseVoskTranscript(recognizer.result, isFinal = true)?.let {
                        return DiagnosticDecode(it, offset * 1000L / condition.sampleRate)
                    }
                }
            }
            return DiagnosticDecode(null, null)
        } finally {
            recognizer.close()
        }
    }

    private data class Fixture(val id: String, val text: String, val expectedAction: AndroidVoiceAction)

    private class SynthesisCapture(
        private val expectedId: String,
        private val preserveLifecycle: Boolean = false,
    ) : UtteranceProgressListener() {
        val done = CountDownLatch(1)
        @Volatile var outcome = "PENDING"
            private set
        private val audio = ByteArrayOutputStream()
        private var sampleRate = 0
        private var format = 0
        private var channels = 0
        @Volatile private var startedNanos: Long? = null
        @Volatile private var doneNanos: Long? = null

        override fun onStart(utteranceId: String?) {
            if (preserveLifecycle && utteranceId == expectedId) {
                synchronized(this) {
                    if (startedNanos == null) startedNanos = SystemClock.elapsedRealtimeNanos()
                }
            }
        }

        @Synchronized
        override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
            if (utteranceId != expectedId) return
            sampleRate = sampleRateInHz
            format = audioFormat
            channels = channelCount
        }

        @Synchronized
        override fun onAudioAvailable(utteranceId: String?, bytes: ByteArray?) {
            if (utteranceId != expectedId || bytes == null || outcome != "PENDING") return
            if (audio.size() + bytes.size > 2_000_000) {
                complete("PCM_TOO_LARGE")
            } else {
                audio.write(bytes)
            }
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == expectedId) {
                if (preserveLifecycle) {
                    synchronized(this) {
                        if (doneNanos == null) doneNanos = SystemClock.elapsedRealtimeNanos()
                    }
                }
                complete("DONE")
            }
        }

        @Deprecated("Required legacy callback")
        override fun onError(utteranceId: String?) {
            if (utteranceId == expectedId) complete("ERROR")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId == expectedId) complete("ERROR_$errorCode")
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId == expectedId) complete("STOPPED")
        }

        @Synchronized
        private fun complete(value: String) {
            if (outcome != "PENDING") return
            outcome = value
            done.countDown()
        }

        @Synchronized
        fun diagnosticPcm(): DiagnosticPcm = DiagnosticPcm(
            sampleRate = sampleRate,
            format = format,
            channels = channels,
            bytes = audio.toByteArray(),
        )

        @Synchronized
        fun preservedLifecycle(): JSONObject = JSONObject()
            .put("expected_utterance_id", expectedId)
            .put("clock", "elapsed_realtime_nanos")
            .put("start_utterance_id", if (startedNanos != null) expectedId else JSONObject.NULL)
            .put("start_callback_ns", startedNanos ?: JSONObject.NULL)
            .put("done_utterance_id", if (doneNanos != null) expectedId else JSONObject.NULL)
            .put("done_callback_ns", doneNanos ?: JSONObject.NULL)

        @Synchronized
        fun pcmAt16Khz(): ShortArray {
            assertEquals("UNSUPPORTED_TTS_PCM_FORMAT", AudioFormat.ENCODING_PCM_16BIT, format)
            assertEquals("UNSUPPORTED_TTS_CHANNEL_COUNT", 1, channels)
            assertTrue("INVALID_TTS_SAMPLE_RATE", sampleRate in 8_000..192_000)
            val bytes = audio.toByteArray()
            assertTrue("EMPTY_OR_TRUNCATED_TTS_PCM", bytes.isNotEmpty() && bytes.size % 2 == 0)
            val source = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(source)
            if (sampleRate == 16_000) return source
            val outputSize = (source.size.toLong() * 16_000L / sampleRate).toInt()
            assertTrue("EMPTY_RESAMPLED_TTS_PCM", outputSize > 0)
            return ShortArray(outputSize) { index ->
                val position = index.toDouble() * sampleRate / 16_000
                val lower = position.toInt().coerceAtMost(source.lastIndex)
                val upper = (lower + 1).coerceAtMost(source.lastIndex)
                (source[lower] + (source[upper] - source[lower]) * (position - lower)).toInt().toShort()
            }
        }
    }
}
