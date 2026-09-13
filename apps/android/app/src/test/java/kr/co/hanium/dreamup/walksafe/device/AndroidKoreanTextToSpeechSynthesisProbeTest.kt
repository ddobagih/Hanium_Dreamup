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

    private fun fixture(engine: FakeEngine): Fixture {
        val results = mutableListOf<KoreanTextToSpeechSynthesisProbeResult>()
        val outputFile = temporaryFolder.newFile()
        val scheduler = FakeTimeoutScheduler()
        val session = KoreanTextToSpeechSynthesisProbeSession(
            engineFactory = KoreanTextToSpeechProbeEngineFactory { engine },
            outputFileFactory = KoreanTextToSpeechProbeOutputFileFactory { outputFile },
            timeoutScheduler = scheduler,
            timeoutMs = 1_000L,
            onResult = results::add,
            utteranceId = "test-utterance",
        )
        return Fixture(session, scheduler, outputFile, results)
    }

    private data class Fixture(
        val session: KoreanTextToSpeechSynthesisProbeSession,
        val scheduler: FakeTimeoutScheduler,
        val outputFile: File,
        val results: MutableList<KoreanTextToSpeechSynthesisProbeResult>,
    )

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

    private class FakeTimeoutScheduler : KoreanTextToSpeechProbeTimeoutScheduler {
        var cancellation: FakeCancellation? = null
            private set
        private var task: (() -> Unit)? = null

        override fun schedule(
            delayMs: Long,
            task: () -> Unit,
        ): KoreanTextToSpeechProbeTimeoutCancellation {
            assertTrue(delayMs > 0L)
            this.task = task
            return FakeCancellation().also { cancellation = it }
        }

        fun fire() {
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
