package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceCommandPhaseTimeoutRegressionTest {
    @Test
    fun endpointNearUtteranceLimitAcceptsLaterFinalAndWaitsForOutput() = withFixture { fixture ->
        val generation = fixture.startAndOpenCommandWindow()
        fixture.runtime.advanceTo(5_000L)
        fixture.stream.transcript("서울역으로", isFinal = false, generation = generation)
        fixture.runtime.drain()

        fixture.runtime.advanceTo(24_900L)
        fixture.stream.audioEnd(generation = generation)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(25_100L)
        fixture.stream.transcript("서울역으로 안내해줘", isFinal = true, generation = generation)
        fixture.runtime.drain()

        assertEquals(listOf("서울역으로 안내해줘"), fixture.commands)
        assertEquals(0, fixture.commandTimeoutCount())
        assertEquals(1, fixture.waitingWakeCount())
        fixture.runtime.advanceTo(25_500L)
        assertEquals(1, fixture.waitingWakeCount())
        fixture.appSpeechActive = false
        fixture.runtime.advanceTo(26_100L)
        assertEquals(2, fixture.waitingWakeCount())
    }

    @Test
    fun silenceIncludingDecoderEndpointStillTimesOutAtSixSeconds() = withFixture { fixture ->
        val generation = fixture.startAndOpenCommandWindow()
        fixture.runtime.advanceTo(5_000L)
        fixture.stream.audioEnd(generation = generation)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(5_999L)
        assertEquals(0, fixture.commandTimeoutCount())
        fixture.runtime.advanceTo(6_000L)
        assertEquals(1, fixture.commandTimeoutCount())
        assertEquals(2, fixture.waitingWakeCount())
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun finalAfterEndpointGraceIsRejected() = withFixture { fixture ->
        val generation = fixture.startAndOpenCommandWindow()
        fixture.runtime.advanceTo(5_000L)
        fixture.stream.transcript("서울역으로", isFinal = false, generation = generation)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(5_100L)
        fixture.stream.audioEnd(generation = generation)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(6_299L)
        assertEquals(0, fixture.commandTimeoutCount())
        fixture.runtime.advanceTo(6_300L)
        assertEquals(1, fixture.commandTimeoutCount())
        fixture.runtime.advanceTo(6_301L)
        fixture.stream.transcript("서울역으로 안내해줘", isFinal = true, generation = generation)
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun repeatedEndpointCannotExtendFinalGrace() = withFixture { fixture ->
        val generation = fixture.startAndOpenCommandWindow()
        fixture.stream.transcript("서울역으로", isFinal = false, generation = generation)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(100L)
        fixture.stream.audioEnd(generation = generation)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(1_100L)
        fixture.stream.audioEnd(generation = generation)
        fixture.runtime.drain()
        fixture.runtime.advanceTo(1_299L)
        assertEquals(0, fixture.commandTimeoutCount())
        fixture.runtime.advanceTo(1_300L)
        assertEquals(1, fixture.commandTimeoutCount())
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun staleRunAndWindowEndpointsCannotChangeCurrentUtteranceDeadline() = withFixture { fixture ->
        val oldGeneration = fixture.startAndOpenCommandWindow()
        val oldRunId = fixture.stream.runId
        fixture.controller.stop()
        val currentGeneration = fixture.startAndOpenCommandWindow()
        assertTrue(fixture.stream.runId != oldRunId)
        fixture.stream.transcript("서울역으로", isFinal = false, generation = currentGeneration)
        fixture.runtime.drain()

        fixture.stream.audioEnd(runId = oldRunId, generation = currentGeneration)
        fixture.stream.audioEnd(generation = oldGeneration)
        fixture.runtime.drain()
        fixture.runtime.advanceBy(1_200L)
        assertEquals(0, fixture.commandTimeoutCount())
        fixture.runtime.advanceBy(18_800L)
        assertEquals(1, fixture.commandTimeoutCount())
        assertTrue(fixture.commands.isEmpty())
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
        lateinit var stream: FakeStream

        val controller = HandsFreeVoiceController(
            dependencies = HandsFreeVoiceDependencies(
                runtime = runtime,
                createStream = { listener, _, inputGeneration ->
                    FakeStream(listener, inputGeneration).also { stream = it }
                },
                playAcknowledgement = { callback -> callback(WakeAcknowledgementResult.COMPLETED) },
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
            stream.transcript("길라잡이", isFinal = true)
            runtime.drain()
            assertEquals("voice_hands_free=waiting_command", statuses.last())
            return stream.generation()
        }

        fun commandTimeoutCount(): Int = statuses.count { it == "voice_hands_free=command_timeout" }

        fun waitingWakeCount(): Int = statuses.count { it == "voice_hands_free=waiting_wake_phrase" }
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
