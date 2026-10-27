package kr.co.hanium.dreamup.walksafe.voice

import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in actual tone API checks, without opening MainActivity or changing user data.
 * The scheduled terminal callback does not establish speaker audibility or recognition success.
 */
@RunWith(AndroidJUnit4::class)
class WakeAcknowledgementDeviceTest {
    /** Replays the pre-Whisper microphone entry, not a product STT switch or audibility assertion. */
    @Test(timeout = 60_000L)
    fun historicalPlatformStartForHumanCueComparison() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowHistoricalMicrophoneCueTest") == "true")
        val appCueOnly = InstrumentationRegistry.getArguments().getString("compareOriginalAppCueOnly") == "true"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        val host = instrumentation.startActivitySync(android.content.Intent(context,
            WalkVoiceForegroundServiceTestHostActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val initialized = CountDownLatch(1)
        val speechDone = CountDownLatch(1)
        val microphoneReady = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        var actuator: kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator? = null
        var recognizer: android.speech.SpeechRecognizer? = null
        var appCue: WakeAcknowledgementPlayer? = null
        try {
            instrumentation.runOnMainSync {
                host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                host.setContentView(android.widget.TextView(host).apply {
                    text = "이전 음성 인식 시작음 비교 시험\n안내 뒤에 들리는 소리를 확인해 주세요."
                    textSize = 28f
                })
                actuator = kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator(context,
                    onOfflineKoreanSpeechReady = { initialized.countDown() },
                    onOfflineKoreanSpeechUnavailable = { failure.set("TTS_UNAVAILABLE"); initialized.countDown() })
            }
            assertTrue(initialized.await(25, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            instrumentation.runOnMainSync {
                val dispatch = actuator!!.speakHomeCommandInteraction(
                    message = "안내가 끝나면 말씀해 주세요.",
                    onCompleted = { speechDone.countDown() },
                    onFailed = { failure.set("TTS_FAILED"); speechDone.countDown() })
                assertEquals(kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult.ACCEPTED, dispatch)
            }
            assertTrue(speechDone.await(15, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            if (appCueOnly) {
                val cueDone = CountDownLatch(1)
                val outcome = AtomicReference<WakeAcknowledgementResult?>(null)
                instrumentation.runOnMainSync {
                    appCue = WakeAcknowledgementPlayer(context, isAppSpeechActive = { false }).also {
                        it.play { result -> outcome.set(result); cueDone.countDown() }
                    }
                }
                assertTrue(cueDone.await(3, TimeUnit.SECONDS))
                assertEquals(WakeAcknowledgementResult.COMPLETED, outcome.get())
                report(instrumentation, "original_app_cue_terminal=${outcome.get()} microphone_started=false human_audibility=NOT_VERIFIED original_tone_parameters_unchanged=true")
                return
            }
            instrumentation.runOnMainSync {
                recognizer = android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                    it.setRecognitionListener(object : android.speech.RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { microphoneReady.countDown() }
                        override fun onError(error: Int) {
                            failure.set("RECOGNIZER_ERROR_$error")
                            microphoneReady.countDown()
                        }
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onResults(results: Bundle?) = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    it.startListening(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
                }
            }
            assertTrue("HISTORICAL_MICROPHONE_READY_TIMEOUT", microphoneReady.await(6, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            // Do not cancel onReady immediately: that could cut off the service's own start sound.
            SystemClock.sleep(1_500L)
            report(instrumentation, "historical_platform_ready=true app_tone_player_invoked=false human_audibility=NOT_VERIFIED transcripts_saved=false commands_executed=false")
        } finally {
            instrumentation.runOnMainSync {
                try { recognizer?.cancel() } finally {
                    try { recognizer?.destroy() } finally {
                        try { appCue?.close(); actuator?.close() } finally { host.finish() }
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000L)
    fun boundedCueReleasesBeforeItsSingleCompletionCallback() {
        val instrumentation = optIn()
        val terminal = CountDownLatch(1)
        val result = AtomicReference<WakeAcknowledgementResult?>(null)
        val count = AtomicInteger(0)
        var player: WakeAcknowledgementPlayer? = null
        var startedAtMs = 0L
        var finishedAtMs = 0L
        var playingInCallback = true
        try {
            instrumentation.runOnMainSync {
                val current = WakeAcknowledgementPlayer(instrumentation.targetContext, isAppSpeechActive = { false })
                player = current
                startedAtMs = SystemClock.elapsedRealtime()
                current.play {
                    finishedAtMs = SystemClock.elapsedRealtime()
                    playingInCallback = current.isPlaying()
                    result.set(it)
                    count.incrementAndGet()
                    terminal.countDown()
                }
            }
            assertTrue("WAKE_ACK_CALLBACK_TIMEOUT", terminal.await(3_000L, TimeUnit.MILLISECONDS))
            report(instrumentation, "result=${result.get()} human_audibility=NOT_TESTED")
            assertEquals(WakeAcknowledgementResult.COMPLETED, result.get())
            assertTrue(finishedAtMs - startedAtMs >= WakeAcknowledgementPlayer.TONE_DURATION_MS)
            assertFalse(playingInCallback)
            instrumentation.runOnMainSync { player?.close() }
            assertEquals(1, count.get())
        } finally {
            instrumentation.runOnMainSync { player?.close() }
        }
    }

    @Test(timeout = 10_000L)
    fun duplicatePlayAndRepeatedCancelCannotReplayOrCompleteTheRetiredCue() {
        val instrumentation = optIn()
        val drained = CountDownLatch(1)
        val original = mutableListOf<WakeAcknowledgementResult>()
        val duplicate = mutableListOf<WakeAcknowledgementResult>()
        var player: WakeAcknowledgementPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val current = WakeAcknowledgementPlayer(instrumentation.targetContext, isAppSpeechActive = { false })
                player = current
                current.play { original.add(it) }
                assertTrue("WAKE_ACK_START_REJECTED", current.isPlaying())
                current.play { duplicate.add(it) }
                assertTrue(current.isPlaying())
                current.cancel()
                current.cancel()
                assertFalse(current.isPlaying())
                Handler(Looper.getMainLooper()).postDelayed({ drained.countDown() }, 400L)
            }
            assertTrue(drained.await(2_000L, TimeUnit.MILLISECONDS))
            assertEquals(listOf(WakeAcknowledgementResult.CANCELLED), original)
            assertEquals(listOf(WakeAcknowledgementResult.FAILED), duplicate)
            report(instrumentation, "cancel=ONCE duplicate=REJECTED retired_completion=NONE")
        } finally {
            instrumentation.runOnMainSync { player?.close() }
        }
    }

    @Test(timeout = 10_000L)
    fun activeSpeechAndClosedOwnerRejectCueWithoutKeepingWork() {
        val instrumentation = optIn()
        val results = mutableListOf<WakeAcknowledgementResult>()
        instrumentation.runOnMainSync {
            WakeAcknowledgementPlayer(instrumentation.targetContext, isAppSpeechActive = { true }).use { player ->
                player.play { results.add(it) }
                assertFalse(player.isPlaying())
                player.close()
                player.play { results.add(it) }
                assertFalse(player.isPlaying())
            }
        }
        assertEquals(List(2) { WakeAcknowledgementResult.FAILED }, results)
    }

    private fun optIn(): Instrumentation {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowWakeAcknowledgementTest") == "true")
        return InstrumentationRegistry.getInstrumentation()
    }

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nWAKE_ACKNOWLEDGEMENT $message\n")
        })
    }
}
