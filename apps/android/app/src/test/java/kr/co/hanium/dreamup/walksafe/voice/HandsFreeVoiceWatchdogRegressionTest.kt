package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceWatchdogRegressionTest {
    @Test
    fun readyBeforeDeadlineCancelsOnlyItsLoadingWatchdog() = withFixture { fixture ->
        val runId = fixture.startRun()
        val watchdog = fixture.loadingWatchdog()
        var foreignCallbacks = 0
        val foreign = Runnable { foreignCallbacks += 1 }
        fixture.runtime.postDelayed(foreign, MODEL_LOAD_TIMEOUT_MS + 1L)

        fixture.stream.ready(runId)
        assertEquals("ready must first pass through the production queue post", 0, fixture.waitingWakeCount())
        assertTrue(fixture.runtime.isQueued(watchdog.task))
        fixture.runtime.drain()

        assertEquals(1, fixture.waitingWakeCount())
        assertFalse(fixture.runtime.isQueued(watchdog.task))
        assertTrue("another owner on the same queue must survive", fixture.runtime.isQueued(foreign))
        fixture.runtime.advanceBy(MODEL_LOAD_TIMEOUT_MS)
        assertTrue(fixture.failures.isEmpty())
        assertEquals(0, fixture.stream.stopCalls)
        assertEquals(0, fixture.acknowledgementPlays)
        assertTrue(fixture.commands.isEmpty())
        fixture.runtime.advanceBy(1L)
        assertEquals(1, foreignCallbacks)
    }

    @Test
    fun missingReadyTimesOutOnceWithoutStartingAnotherStream() = withFixture { fixture ->
        fixture.startRun()
        val watchdog = fixture.loadingWatchdog()
        fixture.runtime.advanceBy(MODEL_LOAD_TIMEOUT_MS - 1L)
        assertTrue(fixture.failures.isEmpty())
        assertEquals(0, fixture.stream.stopCalls)

        fixture.runtime.advanceBy(1L)
        assertEquals(listOf(modelLoadFailure()), fixture.failures)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(1, fixture.statuses.count { it == "voice_hands_free=error_model_load_failed" })
        assertEquals(0, fixture.sessionEnds)
        assertEquals(0, fixture.waitingWakeCount())
        assertEquals(0, fixture.acknowledgementPlays)
        assertTrue(fixture.commands.isEmpty())
        assertFalse(fixture.runtime.isQueued(watchdog.task))

        fixture.runtime.advanceBy(MODEL_LOAD_TIMEOUT_MS)
        assertEquals(listOf(modelLoadFailure()), fixture.failures)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(1, fixture.stream.startCalls)
    }

    @Test
    fun lateReadyAndErrorAfterTimeoutCannotReviveTheRunButANewRunCanStart() = withFixture { fixture ->
        val oldRun = fixture.startRun()
        fixture.runtime.advanceBy(MODEL_LOAD_TIMEOUT_MS)
        assertEquals(listOf(modelLoadFailure()), fixture.failures)

        fixture.stream.ready(oldRun)
        fixture.stream.error(oldRun, VoskStreamingError.MODEL_LOAD_FAILED)
        fixture.runtime.drain()
        assertEquals(0, fixture.waitingWakeCount())
        assertEquals(listOf(modelLoadFailure()), fixture.failures)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(0, fixture.acknowledgementPlays)
        assertTrue(fixture.commands.isEmpty())

        val newRun = fixture.startRun()
        assertTrue(newRun != oldRun)
        fixture.stream.ready(newRun)
        assertEquals(0, fixture.waitingWakeCount())
        fixture.runtime.drain()
        assertEquals(1, fixture.waitingWakeCount())
        assertEquals(2, fixture.stream.startCalls)
        fixture.runtime.advanceBy(MODEL_LOAD_TIMEOUT_MS)
        assertEquals(listOf(modelLoadFailure()), fixture.failures)
        assertEquals(1, fixture.stream.stopCalls)
    }

    @Test
    fun oldTimeoutAndCallbacksCannotCancelTheNewRunsOwnWatchdog() = withFixture { fixture ->
        val oldRun = fixture.startRun()
        val oldWatchdog = fixture.loadingWatchdog()
        fixture.controller.stop()
        assertFalse(fixture.runtime.isQueued(oldWatchdog.task))
        assertEquals(1, fixture.stream.stopCalls)

        fixture.runtime.advanceBy(ELIGIBILITY_POLL_MS)
        val newRun = fixture.startRun()
        val newWatchdog = fixture.loadingWatchdog()
        assertTrue(newRun != oldRun)
        assertTrue(newWatchdog.task !== oldWatchdog.task)
        fixture.runtime.advanceTo(oldWatchdog.dueMs)

        // Inject the real removed Runnable, not a copied fence or timeout algorithm.
        oldWatchdog.task.run()
        fixture.stream.ready(oldRun)
        fixture.stream.error(oldRun, VoskStreamingError.MODEL_LOAD_FAILED)
        fixture.runtime.drain()
        assertTrue(fixture.failures.isEmpty())
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(0, fixture.waitingWakeCount())
        assertTrue("the new run must retain its own timeout", fixture.runtime.isQueued(newWatchdog.task))

        fixture.runtime.advanceTo(newWatchdog.dueMs - 1L)
        assertTrue(fixture.failures.isEmpty())
        fixture.runtime.advanceTo(newWatchdog.dueMs)
        assertEquals("positive control: the new run's real watchdog still fires", listOf(modelLoadFailure()), fixture.failures)
        assertEquals(2, fixture.stream.stopCalls)
        assertEquals(2, fixture.stream.startCalls)
        assertFalse(fixture.runtime.isQueued(newWatchdog.task))
    }

    @Test
    fun explicitStopDiscardsOldReadyErrorAndTranscriptAfterRestart() = withFixture { fixture ->
        val oldRun = fixture.startRun()
        fixture.controller.stop()
        val newRun = fixture.startRun()
        assertTrue(newRun != oldRun)

        fixture.stream.ready(oldRun)
        fixture.stream.error(oldRun, VoskStreamingError.AUDIO_READ_FAILED)
        fixture.stream.transcript(oldRun, VoskTranscript("길라잡이 도움말", 0.95f, true))
        fixture.stream.ready(newRun)
        fixture.runtime.drain()

        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(1, fixture.waitingWakeCount())
        assertTrue(fixture.failures.isEmpty())
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun consentRevokedDuringLoadingEndsTheSessionAndRejectsLateReady() = withFixture { fixture ->
        val runId = fixture.startRun()
        val watchdog = fixture.loadingWatchdog()
        fixture.disclosureAccepted = false
        fixture.runtime.advanceBy(ELIGIBILITY_POLL_MS - 1L)
        assertEquals(0, fixture.sessionEnds)
        assertEquals(0, fixture.stream.stopCalls)

        fixture.runtime.advanceBy(1L)
        assertEquals(1, fixture.sessionEnds)
        assertEquals(1, fixture.stream.stopCalls)
        assertTrue(fixture.failures.isEmpty())
        assertTrue(fixture.statuses.contains("voice_hands_free=blocked eligibility_changed"))
        assertFalse(fixture.runtime.isQueued(watchdog.task))

        fixture.stream.ready(runId)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(watchdog.dueMs)
        assertEquals(0, fixture.waitingWakeCount())
        assertEquals(1, fixture.sessionEnds)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(0, fixture.acknowledgementPlays)
        assertTrue(fixture.commands.isEmpty())
        assertTrue(fixture.failures.isEmpty())
    }

    private fun modelLoadFailure(): HandsFreeVoiceTerminalFailure =
        HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.MODEL_LOAD_FAILED)

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.controller.close()
        }
        assertEquals(1, fixture.stream.closeCalls)
        assertEquals(1, fixture.acknowledgementCloses)
        assertEquals("a controller must never clear another Handler owner's work", 0, fixture.runtime.removeAllCalls)
    }

    private class Fixture {
        val runtime = ManualRuntime()
        var disclosureAccepted = true
        val statuses = mutableListOf<String>()
        val failures = mutableListOf<HandsFreeVoiceTerminalFailure>()
        val commands = mutableListOf<String>()
        var sessionEnds = 0
        var acknowledgementPlays = 0
        var acknowledgementCloses = 0
        var streamCreations = 0
        lateinit var stream: FakeStream

        val controller = HandsFreeVoiceController(
            dependencies = HandsFreeVoiceDependencies(
                runtime = runtime,
                createStream = { listener, _, inputGeneration ->
                    streamCreations += 1
                    FakeStream(listener, inputGeneration).also { stream = it }
                },
                playAcknowledgement = { _ -> acknowledgementPlays += 1 },
                cancelAcknowledgement = {},
                closeAcknowledgement = { acknowledgementCloses += 1 },
            ),
            eligibility = {
                HandsFreeVoiceEligibility(
                    walkState = WalkSessionState.ACTIVE,
                    microphoneGranted = true,
                    disclosureAccepted = disclosureAccepted,
                    modelReady = true,
                )
            },
            isAppSpeechActive = { false },
            onCommand = { text, _ -> commands += text },
            onStatus = { statuses += it },
            onTerminalFailure = { failures += it },
            onSessionEnded = { sessionEnds += 1 },
        )

        fun startRun(): Long {
            assertTrue(controller.start())
            assertEquals("one stream wrapper is created for this controller", 1, streamCreations)
            return stream.lastReservedRunId
        }

        fun loadingWatchdog(): Scheduled =
            runtime.scheduled.last { it.delayMs == MODEL_LOAD_TIMEOUT_MS }

        fun waitingWakeCount(): Int =
            statuses.count { it == "voice_hands_free=waiting_wake_phrase" }
    }

    /** Engine ownership and callback delivery only; no controller transition logic. */
    private class FakeStream(
        private val listener: VoskStreamingListener,
        private val inputGeneration: () -> Long,
    ) : HandsFreeStreamingPort {
        var lastReservedRunId = 0L
            private set
        var startCalls = 0
            private set
        var stopCalls = 0
            private set
        var closeCalls = 0
            private set
        private var running = false

        override fun start(onRunReserved: (Long) -> Unit): Long? {
            startCalls += 1
            lastReservedRunId += 1L
            running = true
            onRunReserved(lastReservedRunId)
            return lastReservedRunId
        }

        override fun isRunning(): Boolean = running

        override fun stop() {
            stopCalls += 1
            running = false
        }

        override fun close() {
            closeCalls += 1
            running = false
        }

        // These may intentionally deliver an already-produced callback after stop.
        fun ready(runId: Long) {
            listener.onReady(runId)
        }

        fun error(runId: Long, error: VoskStreamingError) {
            listener.onError(runId, error)
        }

        fun transcript(runId: Long, transcript: VoskTranscript) {
            listener.onTranscript(runId, transcript, inputGeneration())
        }
    }

    private data class Scheduled(
        val task: Runnable,
        val delayMs: Long,
        val dueMs: Long,
        val sequence: Long,
    )

    /** A FIFO queue at equal deadlines; post never executes the listener inline. */
    private class ManualRuntime : VoiceCallbackRuntime {
        private var currentMs = 0L
        private var nextSequence = 0L
        private val pending = mutableListOf<Scheduled>()
        val scheduled = mutableListOf<Scheduled>()
        var removeAllCalls = 0
            private set

        override fun checkMainThread(owner: String) = Unit

        override fun nowMs(): Long = currentMs

        override fun post(task: Runnable) {
            postDelayed(task, 0L)
        }

        override fun postDelayed(task: Runnable, delayMs: Long) {
            require(delayMs >= 0L)
            val entry = Scheduled(task, delayMs, currentMs + delayMs, nextSequence++)
            scheduled += entry
            pending += entry
        }

        override fun removeCallbacks(task: Runnable) {
            pending.removeAll { it.task === task }
        }

        override fun removeAllCallbacks() {
            removeAllCalls += 1
            pending.clear()
        }

        fun isQueued(task: Runnable): Boolean = pending.any { it.task === task }

        fun drain() {
            advanceTo(currentMs)
        }

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            advanceTo(currentMs + deltaMs)
        }

        fun advanceTo(targetMs: Long) {
            require(targetMs >= currentMs)
            while (true) {
                val next = pending
                    .filter { it.dueMs <= targetMs }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                currentMs = next.dueMs
                next.task.run()
            }
            currentMs = targetMs
        }
    }

    private companion object {
        const val MODEL_LOAD_TIMEOUT_MS = 20_000L
        const val ELIGIBILITY_POLL_MS = 250L
    }
}
