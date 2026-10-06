package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S04-27: "the system silenced the microphone" is not "the user did not speak". */
class PcmInputSilencingTest {
    @Test
    fun gateFiresOnceOnlyForAnExplicitSilencedReport() {
        var fired = 0
        val gate = PcmInputSilencingGate { fired += 1 }
        gate.observe(null)
        gate.observe(false)
        assertEquals(0, fired)
        gate.observe(true)
        gate.observe(true)
        gate.observe(false)
        gate.observe(true)
        assertEquals(1, fired)
    }

    @Test
    fun silencedCodeMapsToItsOwnErrorAndOrdinaryReadErrorsStayReadFailures() {
        assertEquals(VoskStreamingError.AUDIO_SILENCED, voskAudioInputFailure(PCM_INPUT_SILENCED_CODE))
        for (code in listOf(-1, -2, -3, -6)) {
            assertEquals(VoskStreamingError.AUDIO_READ_FAILED, voskAudioInputFailure(code))
        }
        assertEquals("audio_input_silenced", HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.AUDIO_SILENCED).statusToken)
    }

    @Test
    fun silencedDuringARunningStreamIsReportedAsAudioSilenced() {
        val worker = Executors.newSingleThreadExecutor()
        val inputFailure = AtomicReference<(Int) -> Unit>()
        val ready = CountDownLatch(1)
        val errorDelivered = CountDownLatch(1)
        val errors = mutableListOf<VoskStreamingError>()
        val stops = AtomicInteger()
        val transcriber = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { ready.countDown() }, onError = { error ->
                synchronized(errors) { errors += error }
                errorDelivered.countDown()
            }),
            loader = worker,
            preparedRunFactory = { callback ->
                inputFailure.set(callback)
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() = Unit
                        override fun stop() {
                            stops.incrementAndGet()
                        }
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        try {
            assertNotNull(transcriber.start())
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            inputFailure.get().invoke(PCM_INPUT_SILENCED_CODE)
            assertTrue(errorDelivered.await(2, TimeUnit.SECONDS))
            assertFalse(transcriber.isRunning())
        } finally {
            transcriber.close()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
        assertEquals(listOf(VoskStreamingError.AUDIO_SILENCED), synchronized(errors) { errors.toList() })
    }

    @Test
    fun silencedWhileStartingNeverPublishesReady() {
        val worker = Executors.newSingleThreadExecutor()
        val readyCount = AtomicInteger()
        val errorDelivered = CountDownLatch(1)
        val errors = mutableListOf<VoskStreamingError>()
        val transcriber = VoskStreamingTranscriber(
            context = null,
            modelDirectory = File("unused-test-model"),
            isInputSuppressed = { false },
            listener = listener(onReady = { readyCount.incrementAndGet() }, onError = { error ->
                synchronized(errors) { errors += error }
                errorDelivered.countDown()
            }),
            loader = worker,
            preparedRunFactory = { callback ->
                VoskPreparedRun(
                    audioSource = object : VoskPcmSource {
                        override fun start() = callback(PCM_INPUT_SILENCED_CODE)
                        override fun stop() = Unit
                    },
                    recognizer = null,
                    model = null,
                )
            },
        )
        try {
            assertNotNull(transcriber.start())
            assertTrue(errorDelivered.await(2, TimeUnit.SECONDS))
        } finally {
            transcriber.close()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
        assertEquals(0, readyCount.get())
        assertEquals(listOf(VoskStreamingError.AUDIO_SILENCED), synchronized(errors) { errors.toList() })
    }

    @Test
    fun silencedInputBlocksAutomaticRetryUntilTheUserReturns() {
        val recovery = WalkVoiceStartRecovery()
        val presentation = recovery.recordFailure(WalkVoiceStartFailure.INPUT_SILENCED, microphoneGranted = true)
        assertEquals("microphone_input_silenced_retry_required", presentation.statusToken)
        assertTrue(presentation.guidance.contains("통화나 다른 앱이 마이크를 사용하고 있어"))
        assertTrue(presentation.guidance.contains("자동 재시도를 중단했습니다"))
        assertTrue(recovery.blocksAutomaticRetry)
        recovery.onForegroundReturn()
        assertTrue("a UI refresh without leaving is not an explicit retry", recovery.blocksAutomaticRetry)
        recovery.onStopped()
        recovery.onForegroundReturn()
        assertFalse(recovery.blocksAutomaticRetry)
    }

    @Test
    fun androidCaptureWatchesSilencingAndTheWalkPathNeverAutoRestartsIntoIt() {
        val pcm = source("voice/AndroidPcmAudioSource.kt")
        val watch = pcm.substringAfter("private fun watchSystemSilencing(session: CaptureSession)")
            .substringBefore("private fun capture(session: CaptureSession)")
        assertTrue(watch.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return"))
        assertTrue(watch.contains("registerAudioRecordingCallback("))
        assertTrue(watch.contains("isClientSilenced"))
        assertTrue(watch.contains("activeRecordingConfiguration"))
        assertTrue(watch.contains("onReadError(PCM_INPUT_SILENCED_CODE)"))
        val start = pcm.substringAfter("override fun start()").substringBefore("override fun stop()")
        assertTrue(start.indexOf("watchSystemSilencing(session)") < start.indexOf("session.worker.start()"))
        val finally = pcm.substringAfter("private fun capture(session: CaptureSession)").substringAfter("} finally {")
        assertTrue(finally.indexOf("session.unwatchSilencing()") < finally.indexOf("session.releaseOnce()"))
        assertFalse(pcm.contains("FileOutputStream"))

        val recognizer = source("voice/VoskSpeechRecognizer.kt")
        val mapping = recognizer.substringAfter("override fun onError(runId: Long, error: VoskStreamingError)")
            .substringBefore("fun setRecognitionListener(")
        assertTrue(mapping.contains("VoskStreamingError.AUDIO_SILENCED -> SPEECH_ERROR_INPUT_SILENCED"))

        val activity = source("MainActivity.kt")
        val terminal = activity.substringAfter("private fun handleHandsFreeVoiceTerminalFailure(")
            .substringBefore("private fun maybeStartHandsFreeVoiceService()")
        val branch = terminal.substringAfter("if (failure.error == VoskStreamingError.AUDIO_SILENCED) {")
            .substringBefore("// FP-025: only a persistent recognizer failure")
        assertTrue(branch.contains("reportWalkVoiceStartFailure(WalkVoiceStartFailure.INPUT_SILENCED)"))
        assertTrue(branch.contains("speakInteraction("))
        assertTrue(branch.contains("return"))
        assertFalse(branch.contains("scheduleHandsFreeVoiceRestart"))
        assertFalse(branch.contains("onDeviceSpeechRecognitionCapabilityOverride"))
        assertTrue(terminal.indexOf("VoskStreamingError.AUDIO_SILENCED") < terminal.indexOf("handsFreeTranscriberFailureCount += 1"))
    }

    @Test
    fun activityKeepsSilencingOutOfGenericNoMatchAndAutomaticRetryPaths() {
        val activity = source("MainActivity.kt")
        val home = activity.substringAfter("event=HOME_WAKE_TERMINAL")
            .substringBefore("foregroundHomeWakeProbe = probe")
        assertTrue(home.contains("result.streamingError == VoskStreamingError.AUDIO_SILENCED"))
        assertTrue(home.contains("speakInteraction(MICROPHONE_INPUT_SILENCED_GUIDANCE)"))
        val listener = activity.substringAfter("private fun buildVoiceCommandRecognitionListener(")
        val error = listener.substringAfter("override fun onError(error: Int)")
            .substringBefore("override fun onResults(results: Bundle?)")
        val silenced = error.substringAfter("if (error == SPEECH_ERROR_INPUT_SILENCED) {")
            .substringBefore("if (nativeGuidanceConfirmationAction != null")
        assertTrue(silenced.contains("foregroundHomeWakeFailed = true"))
        assertTrue(silenced.contains("reportWalkVoiceStartFailure(WalkVoiceStartFailure.INPUT_SILENCED)"))
        assertTrue(silenced.contains("showVoiceHelpRecovery(message)"))
        assertTrue(silenced.contains("handleWalkSessionResumeRecognizerNotStarted()"))
        assertTrue(silenced.contains("handleGatewayWalkTakeoverRecognition(emptyList())"))
        assertTrue(silenced.contains("speakInteraction(message)"))
        assertTrue(silenced.contains("return"))
        assertFalse(silenced.contains("scheduleHandsFreeVoiceRestart"))
        assertFalse(silenced.contains("permanentlyLimit"))
    }

    private fun listener(
        onReady: () -> Unit,
        onError: (VoskStreamingError) -> Unit,
    ): VoskStreamingListener = object : VoskStreamingListener {
        override fun onReady(runId: Long) = onReady()
        override fun onTranscript(runId: Long, transcript: VoskTranscript) = Unit
        override fun onError(runId: Long, error: VoskStreamingError) = onError(error)
    }

    private fun source(relative: String): String = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
    ).first { it.isFile }.readText()
}
