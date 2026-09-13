package kr.co.hanium.dreamup.walksafe.voice

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.AndroidKoreanTextToSpeechSynthesisProbe
import kr.co.hanium.dreamup.walksafe.device.KoreanTextToSpeechSynthesisProbeResult
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with allowKoreanVoiceReadinessTest=true. Default mode only queries metadata.
 * allowKoreanVoiceReadinessSpeech=true additionally plays one installed offline Korean voice.
 * No MainActivity, account fixture, preferences, microphone, model download, or saved output.
 * The separate production test uses the synthesis probe's existing temporary-file cleanup.
 * Query completion is not a readiness verdict; onDone is not proof of human-perceived sound.
 */
@RunWith(AndroidJUnit4::class)
class KoreanVoiceReadinessDeviceTest {
    /** Uses the production probe and actuator without creating MainActivity or account evidence. */
    @Test(timeout = 90_000L)
    fun actualProductionProbeAndActuatorCompleteKoreanSpeech() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowKoreanProductionTtsTest") == "true")
        assumeTrue(arguments.getString("allowKoreanVoiceReadinessSpeech") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val synthesized = CountDownLatch(1)
        val synthesisResult = AtomicReference<KoreanTextToSpeechSynthesisProbeResult?>(null)
        var probe: AndroidKoreanTextToSpeechSynthesisProbe? = null
        try {
            instrumentation.runOnMainSync {
                probe = AndroidKoreanTextToSpeechSynthesisProbe(instrumentation.targetContext) {
                    if (synthesisResult.compareAndSet(null, it)) synthesized.countDown()
                }.also { it.start() }
            }
            assertTrue("PRODUCTION_SYNTHESIS_CALLBACK_TIMEOUT", synthesized.await(30_000L, TimeUnit.MILLISECONDS))
            report(instrumentation, "production_synthesis=${synthesisResult.get()}")
            assertEquals(KoreanTextToSpeechSynthesisProbeResult.AVAILABLE, synthesisResult.get())
        } finally {
            instrumentation.runOnMainSync { probe?.close() }
        }

        val initialized = CountDownLatch(1)
        val readiness = AtomicReference<String?>(null)
        val terminal = CountDownLatch(1)
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        var actuator: AndroidFeedbackActuator? = null
        try {
            instrumentation.runOnMainSync {
                actuator = AndroidFeedbackActuator(
                    context = instrumentation.targetContext,
                    onOfflineKoreanSpeechReady = {
                        if (readiness.compareAndSet(null, "READY")) initialized.countDown()
                    },
                    onOfflineKoreanSpeechUnavailable = {
                        if (readiness.compareAndSet(null, "UNAVAILABLE")) initialized.countDown()
                    },
                )
            }
            assertTrue("PRODUCTION_ACTUATOR_INIT_TIMEOUT", initialized.await(25_000L, TimeUnit.MILLISECONDS))
            report(instrumentation, "production_actuator_readiness=${readiness.get()}")
            assertEquals("READY", readiness.get())
            instrumentation.runOnMainSync {
                val dispatch = checkNotNull(actuator).speakHomeCommandInteraction(
                    message = "한국어 음성 안내 준비를 확인했습니다.",
                    onCompleted = { completed.incrementAndGet(); terminal.countDown() },
                    onFailed = { failed.incrementAndGet(); terminal.countDown() },
                )
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatch)
            }
            assertTrue("PRODUCTION_ACTUATOR_TERMINAL_TIMEOUT", terminal.await(25_000L, TimeUnit.MILLISECONDS))
            report(instrumentation, "production_actuator_actual_onDone=${completed.get()} failures=${failed.get()} human_audibility=NOT_TESTED")
            assertEquals(1, completed.get())
            assertEquals(0, failed.get())
        } finally {
            instrumentation.runOnMainSync { actuator?.close() }
        }
    }

    @Test(timeout = 90_000L)
    fun inspectDefaultKoreanTtsAndOnDeviceRecognitionSupport() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowKoreanVoiceReadinessTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val speak = arguments.getString("allowKoreanVoiceReadinessSpeech") == "true"
        report(instrumentation, "sdk=${Build.VERSION.SDK_INT} speech_requested=$speak microphone_started=false model_download_requested=false")
        val speechFailure = inspectTts(instrumentation, speak)
        inspectOnDeviceRecognition(instrumentation)
        report(instrumentation, "diagnostic_completed=true human_audibility=NOT_TESTED human_recognition=NOT_TESTED")
        assertNull("Requested Korean TTS callback verification failed", speechFailure)
    }

    private fun inspectTts(instrumentation: Instrumentation, speak: Boolean): String? {
        var tts: TextToSpeech? = null
        fun failed(reason: String): String? {
            report(instrumentation, "tts_result=$reason actual_onDone=false")
            return reason.takeIf { speak }
        }
        try {
            val initialized = CountDownLatch(1)
            val initStatus = AtomicInteger(TextToSpeech.ERROR)
            instrumentation.runOnMainSync {
                tts = TextToSpeech(instrumentation.targetContext) { status ->
                    initStatus.set(status)
                    initialized.countDown()
                }
            }
            // Exceeds the app's 20.5-second initialization/readiness budget.
            if (!initialized.await(25_000L, TimeUnit.MILLISECONDS)) return failed("INIT_TIMEOUT")
            if (initStatus.get() != TextToSpeech.SUCCESS) return failed("INIT_ERROR_${initStatus.get()}")
            val engine = checkNotNull(tts)
            var installedOffline = emptyList<Voice>()
            var currentVoice: Voice? = null
            instrumentation.runOnMainSync {
                currentVoice = engine.voice
                val inventory = engine.voices.orEmpty()
                val korean = (listOfNotNull(currentVoice) + inventory).distinctBy { it.name }
                    .filter { isKoreanLocale(it.locale) }.sortedBy { it.name }
                installedOffline = korean.filter(::isInstalledOfflineKoreanVoice)
                report(instrumentation, "tts_init=SUCCESS default_engine=${token(engine.defaultEngine)} binding_identity=NOT_EXPOSED_BY_PUBLIC_API " +
                    "ko_language_support=${engine.isLanguageAvailable(Locale.KOREAN)} ko_country_support=${engine.isLanguageAvailable(Locale.KOREA)} " +
                    "ko_voice_count=${korean.size} ko_installed_offline_count=${installedOffline.size} " +
                    "current_voice_in_inventory=${currentVoice?.let { it in inventory } == true}")
                currentVoice?.let { reportVoice(instrumentation, "current_voice", it) }
                korean.take(16).forEach { reportVoice(instrumentation, "ko_voice", it) }
                if (korean.size > 16) report(instrumentation, "ko_voices_omitted=${korean.size - 16}")
            }
            if (!speak) {
                report(instrumentation, "tts_playback=NOT_REQUESTED")
                return null
            }
            if (installedOffline.isEmpty()) return failed("NO_INSTALLED_OFFLINE_KOREAN_VOICE")
            val terminal = CountDownLatch(1)
            val outcome = AtomicReference<String?>(null)
            fun finish(value: String) {
                if (outcome.compareAndSet(null, value)) terminal.countDown()
            }
            instrumentation.runOnMainSync {
                if (selectInstalledOfflineKoreanVoice(engine) != KoreanOfflineVoiceSelection.SELECTED) {
                    finish("VOICE_REJECTED")
                    return@runOnMainSync
                }
                reportVoice(instrumentation, "playback_voice", checkNotNull(engine.voice))
                val listenerStatus = engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == UTTERANCE_ID) finish("DONE")
                    }
                    @Deprecated("Android legacy callback")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == UTTERANCE_ID) finish("ERROR")
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == UTTERANCE_ID) finish("ERROR_$errorCode")
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == UTTERANCE_ID) finish("STOPPED")
                    }
                })
                if (listenerStatus != TextToSpeech.SUCCESS) finish("LISTENER_REJECTED")
                else if (engine.speak("한국어 음성 안내 준비를 확인합니다.", TextToSpeech.QUEUE_ADD, Bundle(), UTTERANCE_ID) != TextToSpeech.SUCCESS) {
                    finish("SPEAK_REJECTED")
                }
            }
            if (!terminal.await(25_000L, TimeUnit.MILLISECONDS)) finish("TERMINAL_TIMEOUT")
            val result = checkNotNull(outcome.get())
            report(instrumentation, "tts_playback=$result actual_onDone=${result == "DONE"}")
            return result.takeUnless { it == "DONE" }
        } catch (error: Exception) {
            return failed("EXCEPTION_${error.javaClass.simpleName}")
        } finally {
            instrumentation.runOnMainSync {
                runCatching { tts?.stop() }.onFailure {
                    report(instrumentation, "tts_stop_error=${it.javaClass.simpleName}")
                }
                runCatching { tts?.shutdown() }.onFailure {
                    report(instrumentation, "tts_shutdown_error=${it.javaClass.simpleName}")
                }
            }
        }
    }

    private fun inspectOnDeviceRecognition(instrumentation: Instrumentation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            report(instrumentation, "stt_support=REQUIRES_API_33")
            return
        }
        var recognizer: SpeechRecognizer? = null
        val terminal = CountDownLatch(1)
        val outcome = AtomicReference<String?>(null)
        fun finish(value: String) {
            if (outcome.compareAndSet(null, value)) terminal.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                val context = instrumentation.targetContext
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    finish("stt_support=ON_DEVICE_SERVICE_UNAVAILABLE")
                    return@runOnMainSync
                }
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                recognizer?.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        finish("stt_support=RESULT " +
                            languages("installed", support.installedOnDeviceLanguages) + " " +
                            languages("pending", support.pendingOnDeviceLanguages) + " " +
                            languages("supported", support.supportedOnDeviceLanguages))
                    }
                    override fun onError(error: Int) {
                        finish("stt_support=ERROR code=$error reason=${recognitionError(error)}")
                    }
                })
            }
            if (!terminal.await(15_000L, TimeUnit.MILLISECONDS)) finish("stt_support=TIMEOUT")
        } catch (error: Exception) {
            finish("stt_support=EXCEPTION reason=${error.javaClass.simpleName}")
        } finally {
            instrumentation.runOnMainSync {
                runCatching { recognizer?.destroy() }.onFailure {
                    report(instrumentation, "stt_destroy_error=${it.javaClass.simpleName}")
                }
            }
        }
        report(instrumentation, checkNotNull(outcome.get()))
    }

    private fun reportVoice(instrumentation: Instrumentation, label: String, voice: Voice) {
        report(instrumentation, "$label=${token(voice.name)} locale=${token(voice.locale.toLanguageTag())} " +
            "network_required=${voice.isNetworkConnectionRequired} " +
            "not_installed=${TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in voice.features.orEmpty()}")
    }

    private fun languages(label: String, values: List<String>): String {
        val korean = values.filter(::isKorean)
        return "${label}_count=${values.size} ko_$label=${korean.isNotEmpty()} " +
            "ko_${label}_tags=${korean.take(8).joinToString(",", transform = ::token).ifEmpty { "NONE" }}"
    }

    private fun isKorean(value: String): Boolean = isKoreanLocale(Locale.forLanguageTag(value.replace('_', '-')))

    private fun recognitionError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        else -> "OTHER_$error"
    }

    private fun token(value: String?): String = value?.replace(Regex("[^\\p{L}\\p{N}_.-]"), "_")?.take(120) ?: "NONE"

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "KOREAN_VOICE_READINESS $message\n") })
    }

    private companion object {
        const val UTTERANCE_ID = "korean-voice-readiness"
    }
}
