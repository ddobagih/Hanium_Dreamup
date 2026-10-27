package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskSpeechPhaseTimeoutRegressionTest {
    @Test
    fun silencingKeepsItsCauseStopsOnceAndOnlyAnExplicitRequestRestarts() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.runtime.drain()
        f.stream.error(run, VoskStreamingError.AUDIO_SILENCED)
        f.stream.error(run, VoskStreamingError.AUDIO_SILENCED)
        f.stream.transcript(run, transcript("late final", final = true))
        f.runtime.drain()
        f.runtime.advanceBy(60_000L)
        assertEquals(listOf(SPEECH_ERROR_INPUT_SILENCED), f.events.errors)
        assertEquals(0, f.events.resultCount)
        assertEquals(1, f.stream.startCalls)
        assertEquals(1, f.stream.stopCalls)

        val next = f.startRun()
        f.stream.error(run, VoskStreamingError.AUDIO_SILENCED)
        f.stream.ready(next)
        f.stream.transcript(next, transcript("new final", final = true))
        f.runtime.drain()
        assertEquals(1, f.events.errors.size)
        assertEquals(1, f.events.resultCount)
        assertEquals(2, f.stream.startCalls)
    }

    @Test
    fun decoderEndpointGivesDelayedFinalItsOwnTenSecondWindow() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.runtime.drain()
        f.stream.transcript(run, transcript("speaking", final = false))
        f.runtime.drain()
        val utterance = f.runtime.lastScheduled()
        assertEquals(20_000L, utterance.dueMs)

        f.runtime.advanceBy(19_000L)
        f.stream.audioEnd(run)
        f.runtime.drain()
        val finalWait = f.runtime.lastScheduled()
        assertEquals(29_000L, finalWait.dueMs)
        assertFalse(f.runtime.isQueued(utterance.task))
        f.runtime.advanceBy(6_000L)
        f.stream.transcript(run, transcript("destination", final = true))
        f.runtime.drain()

        assertEquals(1, f.events.resultCount)
        assertEquals(1, f.events.endCount)
        assertTrue(f.events.errors.isEmpty())
        assertEquals(1, f.stream.stopCalls)
        assertFalse(f.runtime.isQueued(finalWait.task))
        utterance.task.run()
        finalWait.task.run()
        assertTrue(f.events.errors.isEmpty())
    }

    @Test
    fun decoderEndpointWithoutFinalTimesOutOnceAndRejectsLateFinal() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.runtime.drain()
        f.stream.transcript(run, transcript("speaking", final = false))
        f.runtime.drain()
        f.runtime.advanceBy(1_000L)
        f.stream.audioEnd(run)
        f.runtime.drain()
        val finalWait = f.runtime.lastScheduled()
        assertEquals(11_000L, finalWait.dueMs)
        f.runtime.advanceBy(9_999L)
        assertTrue(f.events.errors.isEmpty())
        f.runtime.advanceBy(1L)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
        assertEquals(1, f.stream.stopCalls)

        f.stream.transcript(run, transcript("late final", final = true))
        f.stream.audioEnd(run)
        f.runtime.drain()
        finalWait.task.run()
        assertEquals(0, f.events.resultCount)
        assertEquals(1, f.events.endCount)
        assertEquals(1, f.events.errors.size)
        assertEquals(1, f.stream.stopCalls)
    }

    @Test
    fun endpointBeforePartialAndDuplicateEndpointKeepOriginalFinalDeadline() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.runtime.drain()
        f.stream.audioEnd(run)
        f.runtime.drain()
        val finalWait = f.runtime.lastScheduled()
        assertEquals(10_000L, finalWait.dueMs)

        f.runtime.advanceBy(5_000L)
        f.stream.transcript(run, transcript("partial", final = false))
        f.stream.audioEnd(run)
        f.runtime.drain()
        assertSame(finalWait, f.runtime.lastScheduled(10_000L))
        assertEquals(1, f.events.beginningCount)
        assertEquals(1, f.events.partialCount)
        f.runtime.advanceBy(4_999L)
        f.stream.transcript(run, transcript("final", final = true))
        f.runtime.drain()
        assertEquals(1, f.events.resultCount)
        assertTrue(f.events.errors.isEmpty())
        finalWait.task.run()
        assertTrue(f.events.errors.isEmpty())
    }

    @Test
    fun oldEndpointAfterRestartCannotReplaceTheNewRunsWatchdog() = withFixture { f ->
        val oldRun = f.startRun()
        f.stream.ready(oldRun)
        f.runtime.drain()
        f.stream.audioEnd(oldRun)
        f.runtime.drain()
        val oldFinalWait = f.runtime.lastScheduled()
        f.adapter.cancel()

        val newRun = f.startRun()
        f.stream.ready(newRun)
        f.runtime.drain()
        val newWaiting = f.runtime.lastScheduled()
        assertEquals(10_000L, newWaiting.delayMs)
        f.stream.audioEnd(oldRun)
        f.stream.transcript(oldRun, transcript("old final", final = true))
        f.runtime.drain()
        oldFinalWait.task.run()
        assertSame(newWaiting, f.runtime.lastScheduled(10_000L))
        assertTrue(f.runtime.isQueued(newWaiting.task))
        assertTrue(f.events.errors.isEmpty())
        assertEquals(0, f.events.resultCount)

        f.runtime.advanceBy(10_000L)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
        assertEquals(2, f.stream.stopCalls)
    }

    @Test
    fun endpointReentrancyPreservesReplacementWatchdogAndRejectsTheOldFinal() = withFixture { f ->
        val oldRun = f.startRun()
        f.stream.ready(oldRun)
        f.runtime.drain()
        val replacementEvents = Events()
        var oldFinalWait: Scheduled? = null
        var replacementLoad: Scheduled? = null
        var newRun = 0L
        f.events.endAction = {
            oldFinalWait = f.runtime.lastScheduled()
            f.adapter.cancel()
            f.adapter.setRecognitionListener(replacementEvents)
            f.adapter.startRequest()
            newRun = f.stream.lastReservedRunId
            replacementLoad = f.runtime.lastScheduled()
        }

        f.stream.audioEnd(oldRun)
        f.runtime.drain()
        assertEquals(listOf("ready", "end"), f.events.order)
        val load = checkNotNull(replacementLoad)
        assertEquals(20_000L, load.delayMs)
        assertTrue(f.runtime.isQueued(load.task))
        assertFalse(f.runtime.isQueued(checkNotNull(oldFinalWait).task))

        f.stream.transcript(oldRun, transcript("old final", final = true))
        f.stream.audioEnd(oldRun)
        f.stream.error(oldRun, VoskStreamingError.INFERENCE_FAILED)
        f.runtime.drain()
        checkNotNull(oldFinalWait).task.run()
        assertTrue(f.runtime.isQueued(load.task))
        assertTrue(f.converted.isEmpty())
        assertEquals(0, f.events.resultCount)
        assertEquals(0, replacementEvents.resultCount)
        assertTrue(replacementEvents.errors.isEmpty())

        f.stream.ready(newRun)
        f.runtime.drain()
        val waiting = f.runtime.lastScheduled()
        assertEquals(10_000L, waiting.delayMs)
        load.task.run()
        assertTrue(f.runtime.isQueued(waiting.task))
        f.runtime.advanceBy(10_000L)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), replacementEvents.errors)
        assertTrue(f.events.errors.isEmpty())
        assertEquals(1, f.events.endCount)
        assertEquals(2, f.stream.stopCalls)
    }

    @Test
    fun emptyAndNonemptyFirstPartialsAllowFinalBeyondTheOriginalReadyBudget() {
        for (text in listOf("", "partial")) withFixture { f ->
            val run = f.startRun()
            f.stream.ready(run)
            assertEquals(0, f.events.readyCount)
            f.runtime.drain()
            val waiting = f.runtime.lastScheduled()
            f.runtime.advanceBy(9_000L)
            val partial = transcript(text, final = false)
            f.stream.transcript(run, partial)
            assertTrue(f.converted.isEmpty())
            f.runtime.drain()
            val speaking = f.runtime.lastScheduled()
            assertEquals(29_000L, speaking.dueMs)
            assertEquals(1, f.events.beginningCount)
            assertEquals(1, f.events.partialCount)
            assertSame(partial, f.converted.single())
            waiting.task.run()
            assertTrue(f.runtime.isQueued(speaking.task))

            f.runtime.advanceBy(1_001L)
            val final = transcript("destination", final = true)
            f.stream.transcript(run, final)
            assertEquals(0, f.events.resultCount)
            f.runtime.drain()

            assertEquals(listOf(partial, final), f.converted)
            assertEquals(listOf("ready", "begin", "partial", "end", "result"), f.events.order)
            assertEquals(1, f.events.endCount)
            assertEquals(1, f.events.resultCount)
            assertTrue(f.events.errors.isEmpty())
            assertEquals(1, f.stream.stopCalls)
            assertEquals(1, f.stream.startCalls)
            assertFalse(f.runtime.isQueued(speaking.task))
            speaking.task.run()
            assertEquals(1, f.stream.stopCalls)
            assertEquals(1, f.events.resultCount)
        }
    }

    @Test
    fun repeatedReadyAndPartialsCannotExtendFirstTranscriptToFinalBudget() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.runtime.drain()
        val waiting = f.runtime.lastScheduled()
        f.runtime.advanceBy(9_999L)
        f.stream.transcript(run, transcript("first", final = false))
        f.runtime.drain()
        val speaking = f.runtime.lastScheduled()
        assertEquals(29_999L, speaking.dueMs)
        f.runtime.advanceBy(1L)
        waiting.task.run()
        f.stream.ready(run)
        f.stream.transcript(run, transcript("", final = false))
        f.runtime.drain()
        assertTrue(f.runtime.isQueued(speaking.task))
        assertEquals(1, f.events.readyCount)
        assertEquals(1, f.readyBundleCalls)
        f.runtime.advanceBy(19_998L)
        f.stream.transcript(run, transcript("last partial", final = false))
        f.stream.ready(run)
        f.runtime.drain()
        assertTrue(f.runtime.isQueued(speaking.task))
        assertTrue(f.events.errors.isEmpty())

        f.runtime.advanceBy(1L)

        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
        assertEquals(1, f.events.beginningCount)
        assertEquals(3, f.events.partialCount)
        assertEquals(3, f.converted.size)
        assertEquals(1, f.stream.stopCalls)
        assertEquals(1, f.stream.startCalls)
        f.stream.transcript(run, transcript("too late", final = true))
        f.stream.ready(run)
        f.stream.error(run, VoskStreamingError.AUDIO_READ_FAILED)
        f.runtime.drain()
        speaking.task.run()
        assertEquals(3, f.converted.size)
        assertEquals(0, f.events.endCount)
        assertEquals(0, f.events.resultCount)
        assertEquals(1, f.readyBundleCalls)
        assertEquals(1, f.events.errors.size)
        assertEquals(1, f.stream.stopCalls)
        assertEquals(1, f.stream.startCalls)
    }

    @Test
    fun beginningReentrancyRejectsOldTranscriptWithoutTouchingTheNewRunWatchdog() = withFixture { f ->
        val oldRun = f.startRun()
        f.stream.ready(oldRun)
        f.runtime.drain()
        val oldWaiting = f.runtime.lastScheduled()
        var phaseBeforeCallback: Scheduled? = null
        var replacementLoad: Scheduled? = null
        var newRun = 0L
        f.events.beginAction = {
            phaseBeforeCallback = f.runtime.lastScheduled()
            f.adapter.cancel()
            f.adapter.startRequest()
            newRun = f.stream.lastReservedRunId
            replacementLoad = f.runtime.lastScheduled()
        }

        f.stream.transcript(oldRun, transcript("old", final = false))
        f.runtime.drain()

        f.events.beginAction = {}
        val oldPhase = checkNotNull(phaseBeforeCallback)
        val load = checkNotNull(replacementLoad)
        assertNotSame(oldWaiting, oldPhase)
        assertEquals(20_000L, oldPhase.delayMs)
        assertEquals(20_000L, load.delayMs)
        assertTrue(f.runtime.isQueued(load.task))
        assertEquals(2, f.stream.startCalls)
        assertEquals(1, f.stream.stopCalls)
        assertTrue(f.converted.isEmpty())
        assertEquals(0, f.events.partialCount)
        oldWaiting.task.run()
        oldPhase.task.run()
        f.stream.ready(oldRun)
        f.stream.error(oldRun, VoskStreamingError.INFERENCE_FAILED)
        f.runtime.drain()
        assertTrue(f.runtime.isQueued(load.task))
        assertTrue(f.events.errors.isEmpty())
        f.stream.ready(newRun)
        f.runtime.drain()
        val listening = f.runtime.lastScheduled()
        assertEquals(10_000L, listening.delayMs)
        load.task.run()
        assertTrue(f.runtime.isQueued(listening.task))
        assertEquals(2, f.events.readyCount)
        f.runtime.advanceBy(9_999L)
        assertTrue(f.events.errors.isEmpty())
        f.runtime.advanceBy(1L)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
        assertEquals(2, f.stream.stopCalls)
        assertEquals(2, f.stream.startCalls)
    }

    @Test
    fun finalKeepsItsCapturedListenerWhenEndStartsANewRequest() = withFixture { f ->
        val oldRun = f.startRun()
        f.stream.ready(oldRun)
        f.runtime.drain()
        val oldWaiting = f.runtime.lastScheduled()
        val replacementEvents = Events()
        var newRun = 0L
        var replacementLoad: Scheduled? = null
        f.events.endAction = {
            f.adapter.setRecognitionListener(replacementEvents)
            f.adapter.startRequest()
            newRun = f.stream.lastReservedRunId
            replacementLoad = f.runtime.lastScheduled()
        }

        f.stream.transcript(oldRun, transcript("old final", final = true))
        f.runtime.drain()

        val load = checkNotNull(replacementLoad)
        assertEquals(listOf("ready", "begin", "end", "result"), f.events.order)
        assertEquals(1, f.events.resultCount)
        assertEquals(0, replacementEvents.resultCount)
        assertEquals(0, replacementEvents.endCount)
        assertEquals(1, f.converted.size)
        assertEquals(1, f.stream.stopCalls)
        assertEquals(2, f.stream.startCalls)
        assertTrue(f.runtime.isQueued(load.task))
        oldWaiting.task.run()
        f.stream.error(oldRun, VoskStreamingError.AUDIO_READ_FAILED)
        f.runtime.drain()
        assertTrue(f.runtime.isQueued(load.task))
        f.stream.ready(newRun)
        f.runtime.drain()
        val listening = f.runtime.lastScheduled()
        load.task.run()
        assertTrue(f.runtime.isQueued(listening.task))
        assertEquals(1, replacementEvents.readyCount)
        f.stream.transcript(newRun, transcript("new final", final = true))
        f.runtime.drain()
        assertEquals(1, replacementEvents.resultCount)
        assertEquals(1, replacementEvents.endCount)
        assertEquals(1, f.events.resultCount)
        assertEquals(2, f.converted.size)
        assertEquals(2, f.stream.stopCalls)
        assertTrue(f.events.errors.isEmpty())
        assertTrue(replacementEvents.errors.isEmpty())
    }

    @Test
    fun earlyTranscriptsRetainTheirOriginalTimestampAndNoSpeechStillHasTenSeconds() {
        withFixture { f ->
            val run = f.startRun()
            f.stream.ready(run)
            f.runtime.drain()
            f.runtime.advanceBy(9_999L)
            assertTrue(f.events.errors.isEmpty())
            f.runtime.advanceBy(1L)
            assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
            assertTrue(f.converted.isEmpty())
            assertEquals(0, f.events.beginningCount)
            assertEquals(1, f.stream.startCalls)
            assertEquals(1, f.stream.stopCalls)
        }
        for (text in listOf("", "early")) withFixture { f ->
            val run = f.startRun()
            val load = f.runtime.lastScheduled()
            f.runtime.advanceBy(1_000L)
            f.stream.transcript(run, transcript(text, final = false))
            f.runtime.drain()
            assertTrue(f.runtime.isQueued(load.task))
            assertEquals(1, f.events.beginningCount)
            assertEquals(1, f.events.partialCount)
            assertEquals(0, f.events.readyCount)
            f.runtime.advanceBy(2_000L)
            f.stream.transcript(run, transcript("repeat", final = false))
            f.runtime.drain()
            assertTrue(f.runtime.isQueued(load.task))
            f.runtime.advanceBy(2_000L)

            f.stream.ready(run)
            f.runtime.drain()

            val speaking = f.runtime.lastScheduled()
            assertEquals(16_000L, speaking.delayMs)
            assertEquals(21_000L, speaking.dueMs)
            assertFalse(f.runtime.isQueued(load.task))
            load.task.run()
            f.stream.ready(run)
            f.runtime.drain()
            assertTrue(f.runtime.isQueued(speaking.task))
            assertEquals(1, f.readyBundleCalls)
            assertEquals(1, f.events.readyCount)
            assertEquals(1, f.events.beginningCount)
            f.runtime.advanceBy(15_999L)
            assertTrue(f.events.errors.isEmpty())
            f.runtime.advanceBy(1L)
            assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
            assertEquals(1, f.stream.startCalls)
            assertEquals(1, f.stream.stopCalls)
        }
    }

    private fun transcript(text: String, final: Boolean): VoskTranscript =
        VoskTranscript(text = text, isFinal = final, confidence = 0.9f)

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            test(fixture)
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            fixture.adapter.destroy()
        } catch (caught: Throwable) {
            val first = failure
            if (first == null) failure = caught
            else if (first !== caught) runCatching { first.addSuppressed(caught) }
        }
        failure?.let { throw it }
        assertEquals(1, fixture.stream.closeCalls)
        assertEquals(1, fixture.runtime.removeAllCalls)
    }

    private class Fixture {
        val runtime = ManualRuntime()
        val events = Events()
        val converted = mutableListOf<VoskTranscript>()
        var readyBundleCalls = 0
        lateinit var stream: FakeStream
        val adapter = VoskSpeechRecognizer(
            dependencies = VoskSpeechDependencies(
                runtime = runtime,
                microphoneGranted = { true },
                createStream = { listener -> FakeStream(listener).also { stream = it } },
                readyBundle = { readyBundleCalls += 1; null },
                transcriptResults = { transcript -> converted += transcript; null },
            ),
        )

        init {
            adapter.setRecognitionListener(events)
        }

        fun startRun(): Long {
            val count = stream.startCalls
            adapter.startRequest()
            assertEquals(count + 1, stream.startCalls)
            return stream.lastReservedRunId
        }
    }

    private class Events : RecognitionListener {
        var readyCount = 0
        var beginningCount = 0
        var partialCount = 0
        var endCount = 0
        var resultCount = 0
        val errors = mutableListOf<Int>()
        val order = mutableListOf<String>()
        var beginAction: () -> Unit = {}
        var endAction: () -> Unit = {}

        override fun onReadyForSpeech(params: Bundle?) { readyCount += 1; order += "ready" }
        override fun onBeginningOfSpeech() { beginningCount += 1; order += "begin"; beginAction() }
        override fun onPartialResults(partialResults: Bundle?) { partialCount += 1; order += "partial" }
        override fun onEndOfSpeech() { endCount += 1; order += "end"; endAction() }
        override fun onResults(results: Bundle?) { resultCount += 1; order += "result" }
        override fun onError(error: Int) { errors += error }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Emits callbacks and reserves runs, without reproducing adapter decisions. */
    private class FakeStream(private val listener: VoskStreamingListener) : VoskRequestStream {
        var lastReservedRunId = 0L
            private set
        var startCalls = 0
            private set
        var stopCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun start(onRunReserved: (Long) -> Unit): Long? {
            startCalls += 1
            lastReservedRunId += 1L
            onRunReserved(lastReservedRunId)
            return lastReservedRunId
        }
        override fun stop() { stopCalls += 1 }
        override fun close() { closeCalls += 1 }
        fun ready(runId: Long) { listener.onReady(runId) }
        fun audioEnd(runId: Long) { listener.onAudioEnd(runId) }
        fun transcript(runId: Long, transcript: VoskTranscript) { listener.onTranscript(runId, transcript) }
        fun error(runId: Long, error: VoskStreamingError) { listener.onError(runId, error) }
    }

    private data class Scheduled(
        val task: Runnable,
        val delayMs: Long,
        val dueMs: Long,
        val sequence: Long,
    )

    private class ManualRuntime : VoiceCallbackRuntime {
        private var currentMs = 0L
        private var sequence = 0L
        private val pending = mutableListOf<Scheduled>()
        private val scheduled = mutableListOf<Scheduled>()
        var removeAllCalls = 0
            private set

        override fun checkMainThread(owner: String) = Unit
        override fun nowMs(): Long = currentMs
        override fun post(task: Runnable) { postDelayed(task, 0L) }
        override fun postDelayed(task: Runnable, delayMs: Long) {
            require(delayMs >= 0L)
            val entry = Scheduled(task, delayMs, currentMs + delayMs, sequence++)
            scheduled += entry
            pending += entry
        }
        override fun removeCallbacks(task: Runnable) { pending.removeAll { it.task === task } }
        override fun removeAllCallbacks() { removeAllCalls += 1; pending.clear() }
        fun lastScheduled(): Scheduled = scheduled.last()
        fun lastScheduled(delayMs: Long): Scheduled = scheduled.last { it.delayMs == delayMs }
        fun isQueued(task: Runnable): Boolean = pending.any { it.task === task }
        fun drain() { advanceBy(0L) }

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = currentMs + deltaMs
            while (true) {
                val next = pending.filter { it.dueMs <= target }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                currentMs = next.dueMs
                next.task.run()
            }
            currentMs = target
        }
    }
}
