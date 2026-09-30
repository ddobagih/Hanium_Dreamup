package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A hazard alert that starts inside an open command window must close it, not run its clock out. */
class HandsFreeVoiceOutputPreemptionRegressionTest {
    @Test
    fun outputThatPreemptsAnOpenCommandWindowEndsItAndOldSpeechCannotExecute() = withFixture { fixture ->
        val oldGeneration = fixture.startAndOpenCommandWindow()
        fixture.runtime.advanceTo(300L)
        fixture.appSpeechActive = true // e.g. a long hazard alert takes the output
        fixture.runtime.advanceTo(600L)
        assertEquals("voice_hands_free=command_interrupted_by_output", fixture.statuses.last())
        // Audio decoded before the alert, delivered late: its window is gone.
        fixture.stream.transcript("서울역으로 안내해줘", isFinal = true, generation = oldGeneration)
        fixture.runtime.advanceTo(7_000L) // alert longer than the whole 6 s command window
        assertTrue(fixture.commands.isEmpty())
        assertFalse("the preempted window must not also time out",
            fixture.statuses.contains("voice_hands_free=command_timeout"))
        fixture.appSpeechActive = false
        fixture.runtime.advanceTo(7_500L)
        // Input is available again only through a fresh wake phrase and a new generation.
        fixture.stream.transcript("서울역으로 안내해줘", isFinal = true)
        fixture.runtime.drain()
        assertTrue("a bare command after the alert is not addressed", fixture.commands.isEmpty())
        val newGeneration = fixture.openCommandWindow()
        assertTrue(newGeneration > oldGeneration)
        fixture.stream.transcript("서울역으로 안내해줘", isFinal = true, generation = newGeneration)
        fixture.runtime.drain()
        assertEquals(listOf("서울역으로 안내해줘"), fixture.commands)
    }

    @Test
    fun outputAlreadyPlayingWhenTheWakeIsAcknowledgedNeverOpensTheWindow() = withFixture { fixture ->
        assertTrue(fixture.controller.start())
        fixture.stream.ready()
        fixture.runtime.drain()
        fixture.acknowledgement = {
            fixture.appSpeechActive = true
            WakeAcknowledgementResult.COMPLETED
        }
        fixture.stream.transcript("길라잡이", isFinal = true)
        fixture.runtime.drain()
        assertFalse(fixture.statuses.contains("voice_hands_free=waiting_command"))
        assertEquals("voice_hands_free=waiting_wake_phrase", fixture.statuses.last())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.controller.close()
        }
    }

    private class Fixture {
        val runtime = ManualRuntime()
        val statuses = mutableListOf<String>()
        val commands = mutableListOf<String>()
        var appSpeechActive = false
        var acknowledgement: () -> WakeAcknowledgementResult = { WakeAcknowledgementResult.COMPLETED }
        lateinit var stream: FakeStream

        val controller = HandsFreeVoiceController(
            dependencies = HandsFreeVoiceDependencies(
                runtime = runtime,
                createStream = { listener, _, inputGeneration ->
                    FakeStream(listener, inputGeneration).also { stream = it }
                },
                playAcknowledgement = { callback -> callback(acknowledgement()) },
                cancelAcknowledgement = {},
                closeAcknowledgement = {},
            ),
            eligibility = {
                HandsFreeVoiceEligibility(
                    walkState = WalkSessionState.ACTIVE,
                    microphoneGranted = true,
                    disclosureAccepted = true,
                    modelReady = true,
                )
            },
            isAppSpeechActive = { appSpeechActive },
            onCommand = { text, _ ->
                commands += text
                appSpeechActive = true
            },
            onStatus = { statuses += it },
        )

        fun startAndOpenCommandWindow(): Long {
            assertTrue(controller.start())
            stream.ready()
            runtime.drain()
            return openCommandWindow()
        }

        fun openCommandWindow(): Long {
            stream.transcript("길라잡이", isFinal = true)
            runtime.drain()
            assertEquals("voice_hands_free=waiting_command", statuses.last())
            return stream.generation()
        }
    }

    private class FakeStream(
        private val listener: VoskStreamingListener,
        private val currentGeneration: () -> Long,
    ) : HandsFreeStreamingPort {
        var runId = 0L
            private set
        private var running = false

        override fun start(onRunReserved: (Long) -> Unit): Long {
            runId += 1L
            running = true
            onRunReserved(runId)
            return runId
        }

        override fun isRunning(): Boolean = running

        override fun stop() {
            running = false
        }

        override fun close() {
            running = false
        }

        fun generation(): Long = currentGeneration()

        fun ready() {
            listener.onReady(runId)
        }

        fun transcript(text: String, isFinal: Boolean, generation: Long = currentGeneration()) {
            listener.onTranscript(
                runId,
                VoskTranscript(text, if (isFinal) 0.95f else null, isFinal),
                generation,
            )
        }

        fun audioEnd(runId: Long = this.runId, generation: Long = currentGeneration()) {
            listener.onAudioEnd(runId, generation)
        }
    }

    private data class Scheduled(val task: Runnable, val dueMs: Long, val sequence: Long)

    private class ManualRuntime : VoiceCallbackRuntime {
        private var currentMs = 0L
        private var sequence = 0L
        private val pending = mutableListOf<Scheduled>()

        override fun checkMainThread(owner: String) = Unit

        override fun nowMs(): Long = currentMs

        override fun post(task: Runnable) = postDelayed(task, 0L)

        override fun postDelayed(task: Runnable, delayMs: Long) {
            pending += Scheduled(task, currentMs + delayMs, sequence++)
        }

        override fun removeCallbacks(task: Runnable) {
            pending.removeAll { it.task === task }
        }

        override fun removeAllCallbacks() {
            error("controller must not clear other queue owners")
        }

        fun drain() = advanceTo(currentMs)

        fun advanceBy(deltaMs: Long) = advanceTo(currentMs + deltaMs)

        fun advanceTo(targetMs: Long) {
            require(targetMs >= currentMs)
            while (true) {
                val next = pending.filter { it.dueMs <= targetMs }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                currentMs = next.dueMs
                next.task.run()
            }
            currentMs = targetMs
        }
    }
}
