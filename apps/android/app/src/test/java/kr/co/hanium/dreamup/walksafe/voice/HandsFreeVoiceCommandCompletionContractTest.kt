package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.feedback.utteranceTerminalTimeoutMs
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S04-11/S05-28: "no speech right now" is not "the command finished". An asynchronous command
 * (search, route request) keeps its result bound to its command id until that work ends.
 * S04-11 review: during the silent part of that wait the wake phrase is accepted again after the
 * normal no-output settle, so "보행 일시정지"/"멈춰" are never unavailable for the whole request
 * latency; the command's own result still suppresses input while it is spoken.
 * S04-12/S05-29: a normal long response is not an output failure; only a stall is.
 */
class HandsFreeVoiceCommandCompletionContractTest {
    @Test
    fun asyncResultDelayedHalfTwoOrTenSecondsIsHeardBeforeWakeListeningReturns() {
        for (resultDelayMs in listOf(500L, 2_000L, 10_000L)) withFixture { f ->
            val dispatchedAt = f.dispatchCommand("서울역으로 안내해줘")
            val commandId = f.dispatchedCommandIds.single()
            f.work[commandId] = true
            // "서울역 목적지를 검색합니다." is spoken, then nothing while the search is in flight.
            f.speak(from = dispatchedAt, untilMs = dispatchedAt + 400L)
            f.runtime.advanceTo(dispatchedAt + resultDelayMs - 1L)
            assertFalse(
                "delay $resultDelayMs: the command's wait ended before its own result",
                f.returnedToWakeSinceLastDispatch(),
            )
            // Not deaf: once the acknowledgement has been quiet for the settle, the wake phrase is open.
            assertEquals(
                "delay $resultDelayMs",
                resultDelayMs > 400L + 1_200L + 100L,
                f.wakeReopenedDuringWorkSinceLastDispatch(),
            )
            // The result arrives: work ends and the candidate response is spoken in the same task.
            f.work[commandId] = false
            f.speak(from = f.runtime.nowMs(), untilMs = f.runtime.nowMs() + 3_000L)
            val resultSpokenUntil = f.runtime.nowMs() + 3_000L
            f.runtime.advanceTo(resultSpokenUntil + 400L)
            assertFalse("delay $resultDelayMs: returned during the result", f.returnedToWakeSinceLastDispatch())
            f.runtime.advanceTo(resultSpokenUntil + 700L)
            assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
            assertTrue(f.failures.isEmpty())
        }
    }

    /**
     * S04-11 review P2: while a search or route request is pending in silence, "길라잡이 보행 일시정지"
     * is heard. Before the fix the controller stayed in Speaking for up to 30 s.
     */
    @Test
    fun theWakePhraseIsAcceptedDuringASilentWorkWaitSoPauseStaysAvailable() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("서울역으로 안내해줘")
        val searchId = f.dispatchedCommandIds.single()
        f.work[searchId] = true
        f.speak(from = dispatchedAt, untilMs = dispatchedAt + 400L)
        f.runtime.advanceTo(dispatchedAt + 1_500L)
        assertFalse("still inside the no-output settle", f.wakeReopenedDuringWorkSinceLastDispatch())
        f.runtime.advanceTo(dispatchedAt + 1_700L)
        val reopenedAt = checkNotNull(f.wakeReopenedAtMs())
        assertTrue("deaf for at most the settle after the last output", reopenedAt - (dispatchedAt + 400L) <= 1_300L)
        assertFalse(f.returnedToWakeSinceLastDispatch())

        f.runtime.advanceTo(dispatchedAt + 8_000L)
        f.dispatchCommand("보행 일시정지")
        assertEquals(2, f.dispatchedCommandIds.size)
        assertTrue(f.statuses.entries.any { it.second == "voice_hands_free=command_result_wait_interrupted_by_wake" })
        assertTrue(f.failures.isEmpty())
    }

    /** S04-11 review: the late result is still spoken with input suppressed, then listening returns. */
    @Test
    fun aResultArrivingAfterTheWakePhraseReopenedIsHeardWithInputSuppressed() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("서울역으로 안내해줘")
        val commandId = f.dispatchedCommandIds.single()
        f.work[commandId] = true
        f.speak(from = dispatchedAt, untilMs = dispatchedAt + 400L)
        f.runtime.advanceTo(dispatchedAt + 12_000L)
        assertTrue(f.wakeReopenedDuringWorkSinceLastDispatch())
        // The result arrives: work ends and the candidates are read in the same task.
        f.work[commandId] = false
        val resultAt = f.runtime.nowMs()
        f.speak(from = resultAt, untilMs = resultAt + 3_000L)
        f.runtime.advanceTo(resultAt + 1_000L)
        assertTrue(f.statuses.entries.any { it.second == "voice_hands_free=command_result_output" })
        // A wake phrase picked up from the result's own audio is ignored while it plays.
        f.stream.transcript("길라잡이", isFinal = true)
        f.runtime.drain()
        assertFalse(f.statuses.entries.any { it.second == "voice_hands_free=acknowledging_wake" && it.first >= resultAt })
        assertEquals(1, f.dispatchedCommandIds.size)
        f.runtime.advanceTo(resultAt + 3_400L)
        assertFalse(f.returnedToWakeSinceLastDispatch())
        f.runtime.advanceTo(resultAt + 3_700L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun failureOrCancellationWithoutSpeechReleasesTheCommandAfterTheNoOutputSettle() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("서울역으로 안내해줘")
        val commandId = f.dispatchedCommandIds.single()
        f.work[commandId] = true
        f.runtime.advanceTo(dispatchedAt + 5_000L)
        assertFalse(f.returnedToWakeSinceLastDispatch())
        f.work[commandId] = false // cancelled, no speech
        val releasedAt = f.runtime.nowMs()
        f.runtime.advanceTo(releasedAt + 1_000L)
        assertFalse("settle is measured from the release, not from dispatch", f.returnedToWakeSinceLastDispatch())
        f.runtime.advanceTo(releasedAt + 1_400L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
    }

    @Test
    fun sentencesSeparatedBySevenHundredMillisecondsWhileTheWorkRunsAreOneResponse() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("서울역으로 안내해줘")
        val commandId = f.dispatchedCommandIds.single()
        f.work[commandId] = true
        f.speak(from = dispatchedAt, untilMs = dispatchedAt + 1_000L)
        f.speak(from = dispatchedAt + 1_700L, untilMs = dispatchedAt + 2_500L)
        f.runtime.advanceTo(dispatchedAt + 2_600L)
        f.work[commandId] = false
        f.speak(from = dispatchedAt + 2_600L, untilMs = dispatchedAt + 4_000L)
        f.runtime.advanceTo(dispatchedAt + 4_300L)
        assertFalse(f.returnedToWakeSinceLastDispatch())
        f.runtime.advanceTo(dispatchedAt + 4_700L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
    }

    @Test
    fun workThatNeverEndsFailsClosedAtTheCeilingInsteadOfReopeningTheMicrophone() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("서울역으로 안내해줘")
        f.work[f.dispatchedCommandIds.single()] = true
        f.runtime.advanceTo(dispatchedAt + 29_900L)
        assertTrue(f.failures.isEmpty())
        assertFalse(f.returnedToWakeSinceLastDispatch())
        f.runtime.advanceTo(dispatchedAt + 30_100L)
        assertEquals(listOf<HandsFreeVoiceTerminalFailure>(HandsFreeVoiceTerminalFailure.CommandResultTimeout), f.failures)
        assertEquals("voice_hands_free=error_command_result_timeout", f.statuses.last())
        assertFalse(f.returnedToWakeSinceLastDispatch())
        assertFalse(f.stream.isRunning())
    }

    @Test
    fun anUnreadableWorkStateIsTreatedAsPending() = withFixture { f ->
        f.workThrows = true
        val dispatchedAt = f.dispatchCommand("서울역으로 안내해줘")
        f.runtime.advanceTo(dispatchedAt + 10_000L)
        assertFalse(f.returnedToWakeSinceLastDispatch())
        f.runtime.advanceTo(dispatchedAt + 30_100L)
        assertEquals(listOf<HandsFreeVoiceTerminalFailure>(HandsFreeVoiceTerminalFailure.CommandResultTimeout), f.failures)
    }

    @Test
    fun workBoundToAnEarlierCommandIdCannotHoldTheNextCommand() = withFixture { f ->
        val first = f.dispatchCommand("서울역으로 안내해줘")
        val firstId = f.dispatchedCommandIds.single()
        f.runtime.advanceTo(first + 1_300L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
        f.work[firstId] = true // a late/stale completion signal for the old id
        val second = f.dispatchCommand("도움말")
        val secondId = f.dispatchedCommandIds.last()
        assertTrue(secondId > firstId)
        assertNull("no command is dispatching outside onCommand", f.controller.dispatchingCommandId())
        f.runtime.advanceTo(second + 1_300L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
        assertEquals(listOf(secondId), f.workQueries.filter { it != firstId }.distinct())
    }

    @Test
    fun aSynchronousCommandStillReturnsAfterItsResponseOrTheShortNoOutputSettle() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("도움말")
        f.runtime.advanceTo(dispatchedAt + 1_100L)
        assertFalse(f.returnedToWakeSinceLastDispatch())
        f.runtime.advanceTo(dispatchedAt + 1_300L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
    }

    @Test
    fun aFortySecondResponseCompletesNormally() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("도움말")
        f.speak(from = dispatchedAt, untilMs = dispatchedAt + 40_000L)
        f.runtime.advanceTo(dispatchedAt + 40_300L)
        assertTrue("a normal long response is not an output failure", f.failures.isEmpty())
        assertFalse(f.returnedToWakeSinceLastDispatch())
        f.runtime.advanceTo(dispatchedAt + 40_700L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
    }

    @Test
    fun anUnrelatedHazardAlertInsideTheResponseExtendsButDoesNotFailIt() = withFixture { f ->
        val dispatchedAt = f.dispatchCommand("다음 안내 알려줘")
        f.speak(from = dispatchedAt, untilMs = dispatchedAt + 20_000L)
        // Hazard alert starts right after the response (a new utterance start) and runs 15 s.
        f.speak(from = dispatchedAt + 20_300L, untilMs = dispatchedAt + 35_300L)
        f.runtime.advanceTo(dispatchedAt + 35_700L)
        assertTrue(f.failures.isEmpty())
        f.runtime.advanceTo(dispatchedAt + 36_000L)
        assertEquals("voice_hands_free=waiting_wake_phrase", f.statuses.last())
    }

    @Test
    fun outputStuckOnWithoutAnyNewUtteranceStillFailsClosed() = withFixture { f ->
        assertTrue(STALL_MS > utteranceTerminalTimeoutMs(Int.MAX_VALUE))
        val dispatchedAt = f.dispatchCommand("도움말")
        f.speak(from = dispatchedAt, untilMs = Long.MAX_VALUE)
        f.runtime.advanceTo(dispatchedAt + STALL_MS - 200L)
        assertTrue(f.failures.isEmpty())
        f.runtime.advanceTo(dispatchedAt + STALL_MS + 200L)
        assertEquals(listOf<HandsFreeVoiceTerminalFailure>(HandsFreeVoiceTerminalFailure.OutputTimeout), f.failures)
        assertEquals("voice_hands_free=error_output_timeout", f.statuses.last())
    }

    @Test
    fun dispatchingCommandIdIsVisibleOnlyInsideTheCommandCallback() = withFixture { f ->
        f.dispatchCommand("도움말")
        assertNotNull(f.dispatchedCommandIds.singleOrNull())
        assertNull(f.controller.dispatchingCommandId())
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
        val statuses = mutableListOf<Pair<Long, String>>().let { StatusLog(it) }
        val failures = mutableListOf<HandsFreeVoiceTerminalFailure>()
        val dispatchedCommandIds = mutableListOf<Long>()
        val work = mutableMapOf<Long, Boolean>()
        val workQueries = mutableListOf<Long>()
        var workThrows = false
        private val speech = mutableListOf<LongRange>()
        lateinit var stream: FakeStream

        val controller: HandsFreeVoiceController = HandsFreeVoiceController(
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
            isAppSpeechActive = { speech.any { runtime.nowMs() in it } },
            onCommand = { _, _ ->
                dispatchedCommandIds += checkNotNull(controller.dispatchingCommandId())
            },
            onStatus = { statuses.add(runtime.nowMs(), it) },
            onTerminalFailure = { failures += it },
            isCommandWorkPending = { commandId ->
                workQueries += commandId
                if (workThrows) error("work state unavailable")
                work[commandId] == true
            },
        )

        init {
            assertTrue(controller.start())
            stream.ready()
            runtime.drain()
        }

        /** Speech is active on [from, untilMs). */
        fun speak(from: Long, untilMs: Long) {
            speech += from until untilMs
        }

        fun dispatchCommand(text: String): Long {
            stream.transcript("길라잡이", isFinal = true)
            runtime.drain()
            assertEquals("voice_hands_free=waiting_command", statuses.last())
            stream.transcript(text, isFinal = true)
            lastDispatchIndex = statuses.entries.size
            runtime.drain()
            return runtime.nowMs()
        }

        private var lastDispatchIndex = 0

        /** The command's wait is over (result heard or released) and plain wake listening is back. */
        fun returnedToWakeSinceLastDispatch(): Boolean = statuses.entries.drop(lastDispatchIndex)
            .any { (_, status) -> status == "voice_hands_free=waiting_wake_phrase" }

        /** S04-11 review: the wake phrase is accepted while the command's result is still awaited. */
        fun wakeReopenedDuringWorkSinceLastDispatch(): Boolean = statuses.entries.drop(lastDispatchIndex)
            .any { (_, status) -> status == "voice_hands_free=waiting_wake_phrase_command_pending" }

        fun wakeReopenedAtMs(): Long? = statuses.entries.drop(lastDispatchIndex)
            .firstOrNull { (_, status) -> status == "voice_hands_free=waiting_wake_phrase_command_pending" }
            ?.first
    }

    private class StatusLog(val entries: MutableList<Pair<Long, String>>) {
        fun add(at: Long, status: String) {
            entries += at to status
        }

        fun last(): String = entries.last().second
    }

    private class FakeStream(
        private val listener: VoskStreamingListener,
        private val currentGeneration: () -> Long,
    ) : HandsFreeStreamingPort {
        private var runId = 0L
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

        fun ready() {
            listener.onReady(runId)
        }

        fun transcript(text: String, isFinal: Boolean) {
            listener.onTranscript(runId, VoskTranscript(text, if (isFinal) 0.95f else null, isFinal), currentGeneration())
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

    private companion object {
        const val STALL_MS = 130_000L
    }
}
