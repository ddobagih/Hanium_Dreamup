package kr.co.hanium.dreamup.walksafe.device

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidKoreanTextToSpeechSynthesisProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun androidAdapterSelectsAnOfflineKoreanVoiceAndRequiresActualFileSynthesis() {
        val source = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/device/" +
                "AndroidKoreanTextToSpeechSynthesisProbe.kt",
        ).readText()

        assertTrue(source.contains("textToSpeech.setLanguage(Locale.KOREAN)"))
        assertTrue(source.contains("selectInstalledOfflineKoreanVoice(textToSpeech) == KoreanOfflineVoiceSelection.SELECTED"))
        assertTrue(source.contains("textToSpeech.synthesizeToFile"))
        assertTrue(source.contains("object : UtteranceProgressListener()"))
        assertFalse(source.contains("Build.MANUFACTURER"))
        assertFalse(source.contains("Build.MODEL"))
    }

    @Test
    fun completedSynthesisWithOneSecondOfPcmIsAvailable() {
        val engine = FakeEngine(
            onSynthesize = { file, utteranceId, listener ->
                file.writeBytes(oneSecondPcmWav())
                listener.onDone(utteranceId)
            },
        )
        val fixture = fixture(engine)

        fixture.session.start()

        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.AVAILABLE), fixture.results)
        assertTrue(fixture.results.single().available)
        assertEquals(1, engine.koreanLanguageSelections)
        assertEquals(1, engine.synthesisRequests)
        assertEquals(1, engine.closeCalls)
        assertFalse(fixture.outputFile.exists())
        assertTrue(fixture.scheduler.cancellation?.cancelled == true)
    }

    @Test
    fun initializationAndKoreanLanguageFailuresFailClosed() {
        val initializationFailure = fixture(FakeEngine(initialized = false))
        initializationFailure.session.start()

        val languageFailureEngine = FakeEngine(koreanLanguageAvailable = false)
        val languageFailure = fixture(languageFailureEngine)
        languageFailure.session.start()

        assertEquals(
            listOf(KoreanTextToSpeechSynthesisProbeResult.INITIALIZATION_FAILED),
            initializationFailure.results,
        )
        assertEquals(
            listOf(KoreanTextToSpeechSynthesisProbeResult.KOREAN_LANGUAGE_UNAVAILABLE),
            languageFailure.results,
        )
        assertEquals(0, languageFailureEngine.synthesisRequests)
    }

    @Test
    fun listenerAndImmediateSynthesisRejectionsFailClosed() {
        val listenerFailure = fixture(FakeEngine(progressListenerAccepted = false))
        listenerFailure.session.start()

        val rejection = fixture(FakeEngine(synthesisAccepted = false))
        rejection.session.start()

        assertEquals(
            listOf(KoreanTextToSpeechSynthesisProbeResult.LISTENER_REGISTRATION_FAILED),
            listenerFailure.results,
        )
        assertEquals(
            listOf(KoreanTextToSpeechSynthesisProbeResult.SYNTHESIS_REJECTED),
            rejection.results,
        )
    }

    @Test
    fun progressErrorWinsOnceAndLateSuccessIsIgnored() {
        val engine = FakeEngine()
        val fixture = fixture(engine)
        fixture.session.start()
        val listener = requireNotNull(engine.progressListener)
        val utteranceId = requireNotNull(engine.utteranceId)

        listener.onError(utteranceId)
        listener.onDone(utteranceId)

        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.SYNTHESIS_ERROR), fixture.results)
        assertFalse(fixture.results.single().available)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun stoppedAndEmptySynthesisFailClosed() {
        val stoppedEngine = FakeEngine(
            onSynthesize = { _, utteranceId, listener -> listener.onStop(utteranceId) },
        )
        val stopped = fixture(stoppedEngine)
        stopped.session.start()

        val emptyEngine = FakeEngine(
            onSynthesize = { _, utteranceId, listener -> listener.onDone(utteranceId) },
        )
        val empty = fixture(emptyEngine)
        empty.session.start()

        assertEquals(
            listOf(KoreanTextToSpeechSynthesisProbeResult.SYNTHESIS_STOPPED),
            stopped.results,
        )
        assertEquals(
            listOf(KoreanTextToSpeechSynthesisProbeResult.EMPTY_OUTPUT),
            empty.results,
        )
    }

    @Test
    fun timeoutFailsClosedAndIgnoresLateEngineCallback() {
        val engine = FakeEngine()
        val fixture = fixture(engine)
        fixture.session.start()
        val listener = requireNotNull(engine.progressListener)
        val utteranceId = requireNotNull(engine.utteranceId)

        fixture.scheduler.fire()
        listener.onDone(utteranceId)

        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), fixture.results)
        assertEquals(1, engine.closeCalls)
        assertFalse(fixture.outputFile.exists())
    }

    @Test
    fun cancellationFailsClosedAndIsIdempotent() {
        val engine = FakeEngine()
        val fixture = fixture(engine)
        fixture.session.start()

        fixture.session.cancel()
        fixture.session.cancel()

        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.CANCELLED), fixture.results)
        assertEquals(1, engine.closeCalls)
        assertTrue(fixture.scheduler.cancellation?.cancelled == true)
    }

    @Test
    fun closeDuringProbeFailsClosedAndIgnoresLateInitialization() {
        val engine = FakeEngine(initialized = null)
        val fixture = fixture(engine)
        fixture.session.start()

        fixture.session.close()
        fixture.session.close()
        engine.dispatchInitialization(true)

        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.CLOSED), fixture.results)
        assertEquals(1, engine.closeCalls)
        assertEquals(0, engine.koreanLanguageSelections)
    }

    @Test
    fun mismatchedUtteranceCallbackCannotPassTheProbe() {
        val engine = FakeEngine()
        val fixture = fixture(engine)
        fixture.session.start()
        requireNotNull(engine.progressListener).onDone("another-probe")

        assertTrue(fixture.results.isEmpty())
        fixture.scheduler.fire()
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), fixture.results)
    }

    @Test
    fun successfulSynthesisHonorsTheDeadlineWithoutRunningItsTimer() {
        for (completedAt in listOf(1_999L, 2_000L, 2_001L, 999L)) {
            val clock = FakeClock(1_000L)
            val engine = FakeEngine()
            val f = fixture(engine, clock)
            f.session.start()
            f.outputFile.writeBytes(oneSecondPcmWav())
            clock.nowMs = completedAt
            val listener = requireNotNull(engine.progressListener)
            val utterance = requireNotNull(engine.utteranceId)
            listener.onDone(utterance)
            listener.onDone(utterance)
            f.session.cancel()
            assertEquals(listOf(if (completedAt == 1_999L) KoreanTextToSpeechSynthesisProbeResult.AVAILABLE
                else KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), f.results)
            assertEquals(1, engine.closeCalls)
            assertFalse(f.outputFile.exists())
            assertTrue(f.scheduler.cancellation?.cancelled == true)
        }
    }

    @Test
    fun initializationCallbackAfterTheDeadlineCannotStartSynthesis() {
        val clock = FakeClock(1_000L)
        val engine = FakeEngine(initialized = null)
        val f = fixture(engine, clock)
        f.session.start()
        clock.nowMs = 2_000L
        engine.dispatchInitialization(true)
        engine.dispatchInitialization(true)
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), f.results)
        assertEquals(0, engine.koreanLanguageSelections)
        assertEquals(0, engine.synthesisRequests)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun engineCreationDelayConsumesTheSameDeadlineAndClosesTheReturnedEngineOnce() {
        val clock = FakeClock(1_000L)
        val engine = FakeEngine()
        val f = fixture(engine, clock, onCreate = { clock.nowMs = 2_000L })
        f.session.start()
        f.session.close()
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), f.results)
        assertEquals(0, engine.koreanLanguageSelections)
        assertEquals(0, engine.synthesisRequests)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun engineCreationWithinTheBudgetDoesNotRestartTheOriginalTimeout() {
        val clock = FakeClock(1_000L)
        val engine = FakeEngine()
        val f = fixture(engine, clock, onCreate = { clock.nowMs = 1_500L })
        f.session.start()
        assertEquals(2_000L, f.scheduler.deadlineMs)
        f.scheduler.fire()
        assertEquals(2_000L, clock.nowMs)
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), f.results)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun earlyTimeoutCannotTerminateTheProbeAndRearmsOnlyItsRemainingBudget() {
        val clock = FakeClock(1_000L)
        val engine = FakeEngine()
        val f = fixture(engine, clock)
        f.session.start()
        clock.nowMs = 1_999L
        f.scheduler.fireEarly()
        assertTrue(f.results.isEmpty())
        assertEquals(0, engine.closeCalls)
        assertEquals(listOf(1_000L, 1L), f.scheduler.delays)
        f.scheduler.fire()
        assertEquals(2_000L, clock.nowMs)
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), f.results)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun synchronousEarlyTimeoutFailsSchedulingOnceWithoutRecursiveRegistration() {
        val engine = FakeEngine()
        val f = fixture(engine)
        f.scheduler.fireInline = true
        f.session.start()
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMEOUT_SCHEDULING_FAILED), f.results)
        assertEquals(listOf(1_000L), f.scheduler.delays)
        assertTrue(f.scheduler.cancellation?.cancelled == true)
        assertEquals(0, engine.koreanLanguageSelections)
    }

    @Test
    fun timeoutRegistrationDelayCannotStartAnEnginePastTheOriginalDeadline() {
        val clock = FakeClock(1_000L)
        val engine = FakeEngine()
        val f = fixture(engine, clock)
        f.scheduler.onRegistered = { clock.nowMs = 2_000L }
        f.session.start()
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), f.results)
        assertEquals(0, engine.koreanLanguageSelections)
        assertEquals(0, engine.synthesisRequests)
        assertTrue(f.scheduler.cancellation?.cancelled == true)
    }

    @Test
    fun outputValidationCrossingTheDeadlineCannotPublishAvailability() {
        val clock = FakeClock(1_000L)
        var readStarted = false
        val output = object : File(temporaryFolder.newFile().absolutePath) {
            override fun length(): Long {
                // readBytes obtains the output length before reading/verifying its WAV contents.
                readStarted = true
                clock.nowMs = 2_000L
                return super.length()
            }
        }
        val engine = FakeEngine()
        val f = fixture(engine, clock, output)
        f.session.start()
        output.writeBytes(oneSecondPcmWav())
        clock.nowMs = 1_999L
        requireNotNull(engine.progressListener).onDone(engine.utteranceId)
        assertTrue(readStarted)
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.TIMED_OUT), f.results)
        assertEquals(1, engine.closeCalls)
        assertFalse(output.exists())
    }

    @Test
    fun oldUtteranceAfterTheDeadlineCannotClaimTheCurrentProbe() {
        val clock = FakeClock(1_000L)
        val engine = FakeEngine()
        val f = fixture(engine, clock)
        f.session.start()
        clock.nowMs = 2_001L
        requireNotNull(engine.progressListener).onDone("old-utterance")
        assertTrue(f.results.isEmpty())
        assertEquals(0, engine.closeCalls)
        f.session.cancel()
        assertEquals(listOf(KoreanTextToSpeechSynthesisProbeResult.CANCELLED), f.results)
    }

    private fun oneSecondPcmWav(): ByteArray {
        val sampleRate = 16_000
        val bytesPerSample = 2
        val dataBytes = sampleRate * bytesPerSample
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // Integer PCM.
            putShort(1) // Mono.
            putInt(sampleRate)
            putInt(sampleRate * bytesPerSample)
            putShort(bytesPerSample.toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
            // Silence is sufficient for the structure/duration contract, not audible speech.
            put(ByteArray(dataBytes))
        }.array()
    }

    private fun fixture(engine: FakeEngine, clock: FakeClock = FakeClock(),
        outputFile: File = temporaryFolder.newFile(), onCreate: () -> Unit = {}): Fixture {
        val results = mutableListOf<KoreanTextToSpeechSynthesisProbeResult>()
        val scheduler = FakeTimeoutScheduler(clock)
        val session = KoreanTextToSpeechSynthesisProbeSession(
            engineFactory = KoreanTextToSpeechProbeEngineFactory { onCreate(); engine },
            outputFileFactory = KoreanTextToSpeechProbeOutputFileFactory { outputFile },
            timeoutScheduler = scheduler,
            timeoutMs = 1_000L,
            onResult = results::add,
            utteranceId = "test-utterance",
            elapsedRealtimeMs = { clock.nowMs },
        )
        return Fixture(session, scheduler, outputFile, results)
    }

    private data class Fixture(
        val session: KoreanTextToSpeechSynthesisProbeSession,
        val scheduler: FakeTimeoutScheduler,
        val outputFile: File,
        val results: MutableList<KoreanTextToSpeechSynthesisProbeResult>,
    )

    private class FakeClock(var nowMs: Long = 0L)

    private class FakeEngine(
        private val initialized: Boolean? = true,
        private val koreanLanguageAvailable: Boolean = true,
        private val progressListenerAccepted: Boolean = true,
        private val synthesisAccepted: Boolean = true,
        private val onSynthesize: (
            File,
            String,
            KoreanTextToSpeechProbeProgressListener,
        ) -> Unit = { _, _, _ -> },
    ) : KoreanTextToSpeechProbeEngine {
        private var initializationListener: ((Boolean) -> Unit)? = null
        var progressListener: KoreanTextToSpeechProbeProgressListener? = null
            private set
        var utteranceId: String? = null
            private set
        var koreanLanguageSelections = 0
            private set
        var synthesisRequests = 0
            private set
        var closeCalls = 0
            private set

        override fun setInitializationListener(listener: (Boolean) -> Unit) {
            initializationListener = listener
            initialized?.let(listener)
        }

        fun dispatchInitialization(success: Boolean) {
            initializationListener?.invoke(success)
        }

        override fun selectKoreanLanguage(): Boolean {
            koreanLanguageSelections += 1
            return koreanLanguageAvailable
        }

        override fun setProgressListener(
            listener: KoreanTextToSpeechProbeProgressListener,
        ): Boolean {
            progressListener = listener
            return progressListenerAccepted
        }

        override fun synthesizeToFile(
            text: CharSequence,
            outputFile: File,
            utteranceId: String,
        ): Boolean {
            synthesisRequests += 1
            this.utteranceId = utteranceId
            if (synthesisAccepted) {
                onSynthesize(outputFile, utteranceId, requireNotNull(progressListener))
            }
            return synthesisAccepted
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeTimeoutScheduler(private val clock: FakeClock) : KoreanTextToSpeechProbeTimeoutScheduler {
        val delays = mutableListOf<Long>()
        var fireInline = false
        var onRegistered: (() -> Unit)? = null
        var deadlineMs = 0L
            private set
        var cancellation: FakeCancellation? = null
            private set
        private var task: (() -> Unit)? = null

        override fun schedule(
            delayMs: Long,
            task: () -> Unit,
        ): KoreanTextToSpeechProbeTimeoutCancellation {
            assertTrue(delayMs > 0L)
            delays += delayMs
            deadlineMs = clock.nowMs + delayMs
            this.task = task
            return FakeCancellation().also {
                cancellation = it
                onRegistered?.invoke()
                if (fireInline) task()
            }
        }

        fun fire() {
            clock.nowMs = maxOf(clock.nowMs, deadlineMs)
            fireEarly()
        }

        fun fireEarly() {
            if (cancellation?.cancelled != true) task?.invoke()
        }
    }

    private class FakeCancellation : KoreanTextToSpeechProbeTimeoutCancellation {
        var cancelled = false
            private set

        override fun cancel() {
            cancelled = true
        }
    }
}
