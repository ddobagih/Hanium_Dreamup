package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 (voice-F1, S04-19/S05-23; FP-026 "낮은 신뢰도는 no-op 후 재질문", failure_behavior "한 번에 하나씩
 * 다시 묻는다"): a command rejected for low confidence while the command window is open - or a wake
 * utterance whose command or only its wake phrase was doubtful - is not acted on; one short spoken
 * re-ask plays and the command window opens again with a new timeout. A second doubtful command in a
 * row closes the window without another re-ask.
 */
class HandsFreeVoiceLowConfidenceRepromptTest {
    @Test
    fun aLowActionWordInTheWindowIsAskedForOnceAndTheWindowReopens() = withFixture { fixture ->
        fixture.openWindow()
        fixture.stream.send(words("길안내" to 0.95, "종료" to 0.10))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertEquals(1, fixture.reprompts)
        assertEquals(1, fixture.wakeCues)
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence"))
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_reprompt"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        assertEquals(2, fixture.listeningStarted)
        // The repeated command, heard clearly now, is dispatched once.
        fixture.stream.send(words("길안내" to 0.9, "종료" to 0.9))
        fixture.runtime.drain()
        assertEquals(listOf("길안내 종료"), fixture.commands)
    }

    @Test
    fun theReopenedWindowHasANewTimeout() = withFixture { fixture ->
        fixture.openWindow()
        fixture.runtime.advanceTo(5_000L)
        fixture.stream.send(words("안내" to 0.95, "중지" to 0.50))
        fixture.runtime.drain()
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        // The first window would have ended at 6 s; the reopened one lasts 6 s from the re-ask.
        fixture.runtime.advanceTo(10_900L)
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        fixture.runtime.advanceTo(11_100L)
        assertTrue(fixture.statuses.contains("voice_hands_free=command_timeout"))
        assertEquals("voice_hands_free=waiting_wake_phrase", fixture.statuses.last())
    }

    @Test
    fun aSecondLowCommandInARowClosesTheWindowWithoutAnotherReask() = withFixture { fixture ->
        fixture.openWindow()
        fixture.stream.send(words("안내" to 1.0, "멈춰줘" to 0.45))
        fixture.runtime.drain()
        assertEquals(1, fixture.reprompts)
        fixture.stream.send(words("안내" to 1.0, "멈춰줘" to 0.45))
        fixture.runtime.drain()
        assertEquals(1, fixture.reprompts)
        assertTrue(fixture.commands.isEmpty())
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_window_closed"))
        assertEquals("voice_hands_free=waiting_wake_phrase", fixture.statuses.last())
        // The window is closed: a clear command without the wake phrase is not addressed.
        fixture.stream.send(words("안내" to 0.9, "멈춰줘" to 0.9))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        // A clearly heard wake phrase starts a new exchange with its own one re-ask.
        fixture.stream.send(words("길라잡이" to 0.9))
        fixture.runtime.drain()
        fixture.stream.send(words("안내" to 1.0, "멈춰줘" to 0.45))
        fixture.runtime.drain()
        assertEquals(2, fixture.reprompts)
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    @Test
    fun aDoubtfulWholeUtteranceInTheWindowIsAskedForToo() = withFixture { fixture ->
        fixture.openWindow()
        fixture.stream.send(VoskTranscript("길안내 종료", 0.40f, isFinal = true))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertEquals(1, fixture.reprompts)
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    @Test
    fun onlyTheWakePhraseDoubtfulIsAskedForOnceAndNotActedOn() = withFixture { fixture ->
        fixture.start()
        fixture.stream.send(words("길라잡이" to 0.50, "멈춰" to 0.95))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertEquals(1, fixture.reprompts)
        assertEquals(0, fixture.wakeCues)
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_wake"))
        assertFalse(fixture.statuses.contains("voice_hands_free=acknowledging_wake"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        // The re-ask was the one allowed: another doubtful command closes the window.
        fixture.stream.send(words("멈춰" to 0.30))
        fixture.runtime.drain()
        assertEquals(1, fixture.reprompts)
        assertEquals("voice_hands_free=waiting_wake_phrase", fixture.statuses.last())
    }

    @Test
    fun aVeryDoubtfulWakeStillGetsOneReaskWhenCommandWordsAreClear() = withFixture { fixture ->
        fixture.start()
        fixture.stream.send(words("길라잡이" to 0.10, "안내" to 0.80, "종료" to 0.80))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertEquals(1, fixture.reprompts)
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_wake"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    @Test
    fun aVeryDoubtfulCommandStillGetsOneReaskWhenWakeWordsAreClear() = withFixture { fixture ->
        fixture.start()
        fixture.stream.send(words("길라잡이" to 0.80, "안내" to 0.80, "종료" to 0.10))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertEquals(1, fixture.reprompts)
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_command"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    @Test
    fun aDoubtfulWakeWithADoubtfulCommandStaysSilent() = withFixture { fixture ->
        fixture.start()
        fixture.stream.send(words("길라잡이" to 0.50, "다음" to 0.30, "안내" to 0.95, "해줘" to 0.95))
        fixture.runtime.drain()
        assertEquals(0, fixture.reprompts)
        assertEquals("voice_hands_free=low_confidence_wake", fixture.statuses.last())
        // Unaddressed low-confidence audio while only the wake phrase is awaited stays silent too.
        fixture.stream.send(VoskTranscript("길라잡이 도움말", 0.30f, isFinal = true))
        fixture.runtime.drain()
        assertEquals(0, fixture.reprompts)
        assertEquals("voice_hands_free=low_confidence", fixture.statuses.last())
    }

    @Test
    fun aClearWakeWithALowCommandIsAskedAloudAndCountsAsTheOneReask() = withFixture { fixture ->
        fixture.start()
        fixture.stream.send(words("길라잡이" to 1.0, "안내" to 1.0, "종료" to 0.10, "해줘" to 1.0))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertEquals(1, fixture.reprompts)
        assertEquals(0, fixture.wakeCues)
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_command"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        fixture.stream.send(words("안내" to 0.9, "종료" to 0.9, "해줘" to 0.9))
        fixture.runtime.drain()
        assertEquals(listOf("안내 종료 해줘"), fixture.commands)
    }

    @Test
    fun theWindowReopensOnlyAfterTheReaskStopsBlockingRecognition() = withFixture { fixture ->
        fixture.openWindow()
        fixture.blockAfterReprompt = true
        fixture.stream.send(words("길안내" to 0.95, "종료" to 0.10))
        fixture.runtime.drain()
        assertEquals("voice_hands_free=low_confidence_reprompt", fixture.statuses.last())
        // Audio decoded while the re-ask's tail blocks recognition is fenced off.
        fixture.stream.send(words("길안내" to 0.9, "종료" to 0.9))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        fixture.recognitionBlocked = false
        fixture.runtime.advanceTo(fixture.runtime.nowMs() + 200L)
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        fixture.stream.send(words("길안내" to 0.9, "종료" to 0.9))
        fixture.runtime.drain()
        assertEquals(listOf("길안내 종료"), fixture.commands)
    }

    @Test
    fun aReaskThatNeverStopsBlockingReturnsToWakeListening() = withFixture { fixture ->
        fixture.openWindow()
        fixture.blockAfterReprompt = true
        fixture.stream.send(words("길안내" to 0.95, "종료" to 0.10))
        fixture.runtime.drain()
        fixture.runtime.advanceTo(fixture.runtime.nowMs() + 4_200L)
        assertEquals("voice_hands_free=waiting_wake_phrase", fixture.statuses.last())
        assertEquals(1, fixture.listeningStarted)
    }

    @Test
    fun aFailedReaskStillReopensTheWindowAndACancelledOneDoesNot() {
        withFixture { fixture ->
            fixture.openWindow()
            fixture.repromptResult = WakeAcknowledgementResult.FAILED
            fixture.stream.send(words("길안내" to 0.95, "종료" to 0.10))
            fixture.runtime.drain()
            assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_reprompt_failed"))
            assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        }
        withFixture { fixture ->
            fixture.openWindow()
            fixture.repromptResult = WakeAcknowledgementResult.CANCELLED
            fixture.stream.send(words("길안내" to 0.95, "종료" to 0.10))
            fixture.runtime.drain()
            assertEquals("voice_hands_free=waiting_wake_phrase", fixture.statuses.last())
            assertEquals(1, fixture.listeningStarted)
        }
    }

    @Test
    fun withoutASpokenReaskTheWakeCueIsTheReask() = withFixture(spokenReprompt = false) { fixture ->
        fixture.openWindow()
        fixture.stream.send(words("길안내" to 0.95, "종료" to 0.10))
        fixture.runtime.drain()
        assertEquals(0, fixture.reprompts)
        assertEquals(2, fixture.wakeCues)
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    private fun words(vararg pairs: Pair<String, Double>): VoskTranscript {
        val result = pairs.joinToString(",") { (word, conf) -> """{"conf":$conf,"word":"$word"}""" }
        val text = pairs.joinToString(" ") { it.first }
        return requireNotNull(parseVoskTranscript("""{"result":[$result],"text":"$text"}""", isFinal = true))
    }

    private fun withFixture(spokenReprompt: Boolean = true, test: (Fixture) -> Unit) {
        val fixture = Fixture(spokenReprompt)
        try {
            test(fixture)
        } finally {
            fixture.controller.close()
        }
    }

    private class Fixture(spokenReprompt: Boolean) {
        val runtime = ManualRuntime()
        val statuses = mutableListOf<String>()
        val commands = mutableListOf<String>()
        var reprompts = 0
        var wakeCues = 0
        var listeningStarted = 0
        var appSpeechActive = false
        var recognitionBlocked = false
        var blockAfterReprompt = false
        var repromptResult = WakeAcknowledgementResult.COMPLETED
        lateinit var stream: FakeStream

        val controller = HandsFreeVoiceController(
            dependencies = HandsFreeVoiceDependencies(
                runtime = runtime,
                createStream = { listener, _, inputGeneration ->
                    FakeStream(listener, inputGeneration).also { stream = it }
                },
                playAcknowledgement = { callback ->
                    wakeCues += 1
                    callback(WakeAcknowledgementResult.COMPLETED)
                },
                cancelAcknowledgement = {},
                closeAcknowledgement = {},
                playLowConfidenceReprompt = if (spokenReprompt) {
                    { callback ->
                        reprompts += 1
                        // The re-ask's own speaker tail may still block recognition when it ends.
                        if (blockAfterReprompt) recognitionBlocked = true
                        callback(repromptResult)
                    }
                } else null,
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
            onCommandListeningStarted = { listeningStarted += 1 },
            isSpeechRecognitionBlocked = { appSpeechActive || recognitionBlocked },
        )

        fun start() {
            assertTrue(controller.start())
            stream.ready()
            runtime.drain()
        }

        fun openWindow() {
            start()
            stream.send(words("길라잡이" to 1.0))
            runtime.drain()
            assertEquals("voice_hands_free=waiting_command", statuses.last())
        }

        private fun words(vararg pairs: Pair<String, Double>): VoskTranscript {
            val result = pairs.joinToString(",") { (word, conf) -> """{"conf":$conf,"word":"$word"}""" }
            val text = pairs.joinToString(" ") { it.first }
            return requireNotNull(parseVoskTranscript("""{"result":[$result],"text":"$text"}""", isFinal = true))
        }
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

        fun send(transcript: VoskTranscript) {
            listener.onTranscript(runId, transcript, currentGeneration())
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
}
