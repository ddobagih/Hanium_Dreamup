package kr.co.hanium.dreamup.walksafe.voice

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Old-target-compatible synthetic diagnostics, not microphone or production command validation.
 * The installer lookup below is the only app API used. Do not add newer adapter/parser helpers.
 */
@RunWith(AndroidJUnit4::class)
class PlatformOfflineVoiceComparisonDeviceTest {
    @Test
    fun compareSamsungOfflineSpeechAndPlatformSupport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val limitations = mutableListOf<String>()
        queryPlatformSupport(instrumentation, context)?.let(limitations::add)

        val directory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(context)) {
            "MODEL_NOT_INSTALLED: this diagnostic never installs or downloads a model"
        }
        val samsungVisible = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(SAMSUNG_ENGINE),
            0,
        ).isNotEmpty()
        report(instrumentation, "requested_engine=$SAMSUNG_ENGINE service_visible=$samsungVisible")
        assertTrue("SAMSUNG_TTS_SERVICE_NOT_INSTALLED_OR_NOT_VISIBLE", samsungVisible)

        val model = Model(directory.absolutePath)
        var tts: TextToSpeech? = null
        try {
            val initialized = CountDownLatch(1)
            val initStatus = AtomicInteger(TextToSpeech.ERROR)
            instrumentation.runOnMainSync {
                tts = TextToSpeech(context, {
                    initStatus.set(it)
                    initialized.countDown()
                }, SAMSUNG_ENGINE)
            }
            assertTrue("SAMSUNG_TTS_INIT_TIMEOUT", initialized.await(15, TimeUnit.SECONDS))
            assertEquals("SAMSUNG_TTS_INIT_FAILED", TextToSpeech.SUCCESS, initStatus.get())
            val engine = checkNotNull(tts)
            val offlineVoices = engine.voices.orEmpty().filter {
                it.locale.language == Locale.KOREAN.language &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            val voice = checkNotNull(
                engine.voice?.takeIf { it in offlineVoices } ?: offlineVoices.minByOrNull { it.name },
            ) { "REQUESTED_SAMSUNG_ENGINE_HAS_NO_INSTALLED_OFFLINE_KOREAN_VOICE" }
            assertEquals("OFFLINE_KOREAN_VOICE_REJECTED", TextToSpeech.SUCCESS, engine.setVoice(voice))
            assertEquals("TTS_RATE_REJECTED", TextToSpeech.SUCCESS, engine.setSpeechRate(1.0f))
            // Android may fall back internally. Neither defaultEngine nor voice proves binder identity.
            report(
                instrumentation,
                "requested_engine=$SAMSUNG_ENGINE default_engine=${engine.defaultEngine} " +
                    "selected_voice=${voice.name} locale=${voice.locale.toLanguageTag()} " +
                    "network_required=${voice.isNetworkConnectionRequired} " +
                    "actual_engine_binding=requires_external_confirmation source=synthetic_not_microphone",
            )

            val artifacts = File(context.cacheDir, "platform-voice-diagnostics-${System.currentTimeMillis()}")
            assertTrue("ARTIFACT_DIRECTORY_CREATE_FAILED", artifacts.mkdir())
            report(instrumentation, "artifact_dir=${artifacts.name} max_wav_files=3")
            val fixtures = listOf(
                Fixture("HELP", "도움말"),
                Fixture("START", "안내 시작"),
                Fixture("DESTINATION", "서울역으로 안내해줘"),
            )
            for (fixture in fixtures) {
                val wav = File(artifacts, "${fixture.id}.wav")
                var completeWav = false
                try {
                    val capture = SynthesisCompletion(fixture.id)
                    assertEquals(
                        "TTS_LISTENER_REJECTED",
                        TextToSpeech.SUCCESS,
                        engine.setOnUtteranceProgressListener(capture),
                    )
                    assertEquals(
                        "TTS_SYNTHESIS_REJECTED: ${fixture.id}",
                        TextToSpeech.SUCCESS,
                        engine.synthesizeToFile(fixture.text, Bundle(), wav, fixture.id),
                    )
                    assertTrue("TTS_SYNTHESIS_TIMEOUT: ${fixture.id}", capture.done.await(20, TimeUnit.SECONDS))
                    assertEquals("TTS_SYNTHESIS_FAILED: ${fixture.id}", "DONE", capture.outcome.get())
                    val pcm = readOriginalPcm(wav)
                    completeWav = true
                    report(instrumentation, "${fixture.id} ${pcm.summary()} resampling=vosk_native_only")
                    val recognized = decodeAllStages(model, pcm, fixture.id, instrumentation)
                    val exact = recognized.filterNot(Char::isWhitespace) == fixture.text.filterNot(Char::isWhitespace)
                    report(
                        instrumentation,
                        "${fixture.id} expected=${fixture.text} raw_exact_match=$exact " +
                            "production_mapping=not_exercised human_accuracy=not_measured",
                    )
                    if (!exact) limitations += "SYNTHETIC_TEXT_MISMATCH_${fixture.id}"
                } finally {
                    engine.stop()
                    if (!completeWav) wav.delete()
                }
            }
            assertTrue(
                "PLATFORM_VOICE_DIAGNOSTIC_LIMITATIONS: ${limitations.joinToString()}",
                limitations.isEmpty(),
            )
        } finally {
            try {
                instrumentation.runOnMainSync {
                    tts?.stop()
                    tts?.shutdown()
                }
            } finally {
                model.close()
            }
        }
    }

    private fun queryPlatformSupport(instrumentation: Instrumentation, context: Context): String? {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        report(
            instrumentation,
            "sdk=${Build.VERSION.SDK_INT} recognition_service=$available on_device_service=$onDevice " +
                "microphone_started=false model_download_requested=false",
        )
        if (!onDevice) return "ON_DEVICE_RECOGNITION_SERVICE_UNAVAILABLE"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "ON_DEVICE_LANGUAGE_SUPPORT_QUERY_REQUIRES_API_33"
        }

        val done = CountDownLatch(1)
        val result = AtomicReference<SupportResult?>()
        var recognizer: SpeechRecognizer? = null
        fun complete(value: SupportResult) {
            if (result.compareAndSet(null, value)) done.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) = Unit
                    override fun onResults(results: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                recognizer?.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.any(::isKorean)
                        val pending = support.pendingOnDeviceLanguages.any(::isKorean)
                        val downloadable = support.supportedOnDeviceLanguages.any(::isKorean)
                        complete(
                            SupportResult(
                                "ko_installed=$installed ko_pending=$pending ko_downloadable=$downloadable " +
                                    "online_language_count=${support.onlineLanguages.size} " +
                                    "microphone_recognition=not_exercised",
                                if (installed) null else "ON_DEVICE_KOREAN_NOT_INSTALLED",
                            ),
                        )
                    }

                    override fun onError(error: Int) {
                        complete(SupportResult("support_error=$error", "ON_DEVICE_SUPPORT_QUERY_ERROR_$error"))
                    }
                })
            }
            if (!done.await(10, TimeUnit.SECONDS)) {
                complete(SupportResult("support_query=timeout", "ON_DEVICE_SUPPORT_QUERY_TIMEOUT"))
            }
        } catch (error: Exception) {
            complete(
                SupportResult(
                    "support_query_exception=${error.javaClass.simpleName}",
                    "ON_DEVICE_SUPPORT_QUERY_FAILED",
                ),
            )
        } finally {
            instrumentation.runOnMainSync { recognizer?.destroy() }
        }
        val completed = checkNotNull(result.get())
        report(instrumentation, completed.summary)
        return completed.limitation
    }

    private fun decodeAllStages(
        model: Model,
        pcm: Pcm,
        fixture: String,
        instrumentation: Instrumentation,
    ): String {
        val recognizer = Recognizer(model, pcm.sampleRate.toFloat())
        try {
            recognizer.setWords(true)
            recognizer.setPartialWords(false)
            recognizer.setMaxAlternatives(0)
            recognizer.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
            val input = pcm.bytes + ByteArray(pcm.sampleRate * 2 * 2)
            val chunkBytes = (pcm.sampleRate * 32 / 1_000).coerceAtLeast(1) * 2
            val endpoints = mutableListOf<String>()
            var offset = 0
            var endpointCount = 0
            while (offset < input.size) {
                val chunk = input.copyOfRange(offset, minOf(offset + chunkBytes, input.size))
                offset += chunk.size
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    val decoded = decodedResult(recognizer.result)
                    endpointCount++
                    endpoints += decoded.text
                    if (endpointCount <= 8) {
                        report(
                            instrumentation,
                            "$fixture endpoint=$endpointCount at_ms=${offset.toLong() * 1_000 / (pcm.sampleRate * 2)} " +
                                "text=${decoded.text} confidence=${decoded.confidence}",
                        )
                    }
                }
            }
            val partial = JSONObject(recognizer.partialResult).optString("partial").singleLine()
            val final = decodedResult(recognizer.finalResult)
            report(
                instrumentation,
                "$fixture endpoint_count=$endpointCount partial_before_eof=$partial " +
                    "final_after_eof=${final.text} final_confidence=${final.confidence}",
            )
            return (endpoints + final.text).filter(String::isNotBlank).joinToString(" ")
        } finally {
            recognizer.close()
        }
    }

    private fun decodedResult(raw: String): Decoded {
        val json = JSONObject(raw)
        val words = json.optJSONArray("result")
        val scores = (0 until (words?.length() ?: 0)).mapNotNull { index ->
            words?.optJSONObject(index)?.optDouble("conf")?.takeIf { it.isFinite() && it in 0.0..1.0 }
        }
        return Decoded(
            json.optString("text").singleLine(),
            if (scores.isEmpty()) "missing" else String.format(Locale.ROOT, "%.4f", scores.average()),
        )
    }

    private fun readOriginalPcm(file: File): Pcm {
        assertTrue("WAV_SIZE_INVALID", file.length() in 44L..2_000_000L)
        val wav = file.readBytes()
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
        assertEquals("WAV_RIFF_MISSING", "RIFF", tag(0))
        assertEquals("WAV_TYPE_UNSUPPORTED", "WAVE", tag(8))
        var sampleRate = 0
        var data: ByteArray? = null
        var offset = 12
        while (offset + 8 <= wav.size) {
            val size = buffer.getInt(offset + 4)
            assertTrue("WAV_CHUNK_INVALID", size >= 0 && offset.toLong() + 8 + size <= wav.size)
            val start = offset + 8
            when (tag(offset)) {
                "fmt " -> {
                    assertTrue("WAV_FORMAT_TRUNCATED", size >= 16)
                    assertEquals("WAV_NOT_INTEGER_PCM", 1, buffer.getShort(start).toInt())
                    assertEquals("WAV_NOT_MONO", 1, buffer.getShort(start + 2).toInt())
                    sampleRate = buffer.getInt(start + 4)
                    assertTrue("WAV_SAMPLE_RATE_INVALID", sampleRate in 8_000..192_000)
                    assertEquals("WAV_NOT_PCM16", 16, buffer.getShort(start + 14).toInt())
                    assertEquals("WAV_BLOCK_ALIGNMENT_INVALID", 2, buffer.getShort(start + 12).toInt())
                    assertEquals("WAV_BYTE_RATE_INVALID", sampleRate * 2, buffer.getInt(start + 8))
                }
                "data" -> data = wav.copyOfRange(start, start + size)
            }
            offset = start + size + (size and 1)
        }
        val pcm = checkNotNull(data) { "WAV_DATA_MISSING" }
        assertTrue("WAV_FORMAT_MISSING", sampleRate > 0)
        assertTrue("WAV_PCM_EMPTY_OR_TRUNCATED", pcm.isNotEmpty() && pcm.size % 2 == 0)
        return Pcm(pcm, sampleRate)
    }

    private fun isKorean(tag: String): Boolean {
        val normalized = tag.lowercase(Locale.ROOT).replace('_', '-')
        return normalized == "ko" || normalized.startsWith("ko-")
    }

    private fun String.singleLine() = replace('\n', ' ').replace('\r', ' ').take(160)

    private fun report(instrumentation: Instrumentation, value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("walksafe_platform_voice", value) })
    }

    private data class Fixture(val id: String, val text: String)
    private data class SupportResult(val summary: String, val limitation: String?)
    private data class Decoded(val text: String, val confidence: String)

    private data class Pcm(val bytes: ByteArray, val sampleRate: Int) {
        fun summary(): String {
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var squareSum = 0.0
            var peak = 0
            var clipped = 0
            var zeros = 0
            val count = samples.remaining()
            while (samples.hasRemaining()) {
                val value = samples.get().toInt()
                squareSum += value.toDouble() * value
                peak = maxOf(peak, abs(value))
                if (value == 0) zeros++
                if (abs(value) >= 32_767) clipped++
            }
            return "rate=$sampleRate channels=1 format=PCM16 samples=$count " +
                "duration_ms=${count.toLong() * 1_000 / sampleRate} " +
                "rms=${String.format(Locale.ROOT, "%.2f", sqrt(squareSum / count))} peak=$peak " +
                "clip_pct=${String.format(Locale.ROOT, "%.2f", clipped * 100.0 / count)} " +
                "zero_pct=${String.format(Locale.ROOT, "%.2f", zeros * 100.0 / count)}"
        }
    }

    private class SynthesisCompletion(private val expectedId: String) : UtteranceProgressListener() {
        val done = CountDownLatch(1)
        val outcome = AtomicReference("PENDING")

        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = complete(utteranceId, "DONE")

        @Deprecated("Required legacy callback")
        override fun onError(utteranceId: String?) = complete(utteranceId, "ERROR")
        override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId, "ERROR_$errorCode")
        override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId, "STOPPED")

        private fun complete(utteranceId: String?, value: String) {
            if (utteranceId == expectedId && outcome.compareAndSet("PENDING", value)) done.countDown()
        }
    }

    private companion object {
        const val SAMSUNG_ENGINE = "com.samsung.SMT"
    }
}

