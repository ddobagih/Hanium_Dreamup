package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S04-19 / S05-23: a sentence average must not hide a badly heard decisive word, and a clearly
 * heard wake phrase must not lift a doubtful command. The approved 0.60 threshold is unchanged.
 */
class VoskCommandKeyWordConfidenceTest {
    @Test
    fun oneLowNumberAmongHighWordsIsRejectedAlthoughTheAverageIs091() {
        val transcript = words(
            "길라잡이" to 1.0, "목적지" to 1.0, "후보" to 1.0, "중에서" to 1.0, "2번" to 0.10,
            "으로" to 1.0, "안내" to 1.0, "시작" to 1.0, "해" to 1.0, "줘" to 1.0,
        )
        assertEquals(0.91f, transcript.confidence!!, 0.0001f)
        assertTrue("old utterance-average rule accepts it", transcript.isTrustedForCommand())
        assertNull(transcript.commandSpanOrNull("목적지 후보 중에서 2번 으로 안내 시작 해 줘"))
    }

    @Test
    fun highWakePhraseDoesNotLiftALowCommand() {
        val transcript = words("길라잡이" to 0.99, "두번째" to 0.10, "선택" to 0.99)
        assertEquals(0.693f, transcript.confidence!!, 0.001f)
        assertTrue(transcript.isTrustedForCommand())
        assertNull(transcript.commandSpanOrNull("두번째 선택"))
    }

    @Test
    fun decisiveWordsAreCheckedAtEveryPosition() {
        for (low in 0 until 3) {
            val scores = mutableListOf(1.0, 1.0, 1.0)
            scores[low] = 0.2
            val transcript = words("3번" to scores[0], "말고" to scores[1], "1번" to scores[2])
            assertNull("low word at $low", transcript.commandSpanOrNull("3번 말고 1번"))
        }
        assertNull(words("역삼점" to 1.0, "말고" to 0.2, "선릉점" to 1.0).commandSpanOrNull("역삼점 말고 선릉점"))
        assertNull(words("아니" to 0.3, "도착" to 1.0, "안" to 1.0, "했어" to 1.0).commandSpanOrNull("아니 도착 안 했어"))
        assertNull(words("아직" to 1.0, "도착" to 1.0, "안" to 0.3, "했어" to 1.0).commandSpanOrNull("아직 도착 안 했어"))
    }

    @Test
    fun lowNonDecisiveWordStaysAllowedWhenTheCommandAverageHolds() {
        val command = words("길라잡이" to 1.0, "안내" to 1.0, "좀" to 0.30, "시작해줘" to 1.0)
            .commandSpanOrNull("안내 좀 시작해줘")
        assertNotNull(command)
        assertEquals("안내 좀 시작해줘", command!!.text)
        assertEquals(0.7667f, command.confidence!!, 0.001f)
    }

    @Test
    fun missingWordScoresOrMisalignedTextAreNotTrusted() {
        val missing = parseVoskTranscript(
            """{"result":[{"conf":1.0},{"conf":-1}],"text":"2번 선택"}""",
            isFinal = true,
        )!!
        assertNull(missing.confidence)
        assertNull(missing.commandSpanOrNull("2번 선택"))
        val parsed = parseVoskTranscript(
            """{"result":[{"conf":1.0,"word":"길라잡이"},{"conf":0.9,"word":"2번"},{"conf":0.8,"word":"선택"}],"text":"길라잡이 2번 선택"}""",
            isFinal = true,
        )!!
        assertEquals(listOf(1.0f, 0.9f, 0.8f), parsed.wordConfidences)
        assertEquals("2번 선택", parsed.commandSpanOrNull("2번 선택")!!.text)
        assertNull(parsed.commandSpanOrNull("3번 선택"))
        assertNull(VoskTranscript("2번 선택", 0.9f, isFinal = false).commandSpanOrNull("2번 선택"))
    }

    @Test
    fun aggregateOnlyTranscriptKeepsTheExistingThreshold() {
        assertNotNull(VoskTranscript("길라잡이 2번 선택", 0.60f, true).commandSpanOrNull("2번 선택"))
        assertNull(VoskTranscript("길라잡이 2번 선택", 0.59f, true).commandSpanOrNull("2번 선택"))
    }

    @Test
    fun decisiveTokenVocabulary() {
        for (token in listOf("1번", "2번으로", "첫번째", "두", "번째", "셋", "말고", "역삼점말고", "아니야", "안", "않았어", "그만", "취소해줘")) {
            assertTrue(token, isDecisiveCommandToken(token))
        }
        // S04-19-a: common spoken negations ("아냐", "하지 마", "말아", "싫어").
        for (token in listOf("아냐", "마", "말아", "말아요", "싫어", "싫다")) {
            assertTrue(token, isDecisiveCommandToken(token))
        }
        for (token in listOf("안내", "시작해줘", "좀", "목적지", "서울역으로", "선택", "마트", "마포역", "말씀")) {
            assertFalse(token, isDecisiveCommandToken(token))
        }
    }

    @Test
    fun lowConfidenceSpokenNegationBlocksTheCommand() {
        // The span average (0.825) passes; the badly heard "아냐" alone must stop the action.
        val transcript = words("아냐" to 0.30, "다음" to 1.0, "안내" to 1.0, "해줘" to 1.0)
        assertNull(transcript.commandSpanOrNull("아냐 다음 안내 해줘"))
        assertNull(words("길라잡이" to 1.0, "안내" to 1.0, "하지" to 1.0, "마" to 0.2).commandSpanOrNull("안내 하지 마"))
        assertNotNull(words("아냐" to 0.9, "다음" to 1.0, "안내" to 1.0, "해줘" to 1.0).commandSpanOrNull("아냐 다음 안내 해줘"))
    }

    @Test
    fun awaitedCommandWithLowSpokenNegationIsNotDispatched() = withFixture { fixture ->
        assertTrue(fixture.controller.start())
        fixture.stream.ready()
        fixture.runtime.drain()
        fixture.stream.send(words("길라잡이" to 1.0))
        fixture.runtime.drain()
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        fixture.stream.send(words("아냐" to 0.30, "다음" to 1.0, "안내" to 1.0, "해줘" to 1.0))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        // R18 (FP-026 "no-op 후 재질문"): rejected, then asked again once with the window reopened.
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence"))
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_reprompt"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    @Test
    fun wakeUtteranceWithLowCommandOpensTheCommandWindowInsteadOfActing() = withFixture { fixture ->
        assertTrue(fixture.controller.start())
        fixture.stream.ready()
        fixture.runtime.drain()
        fixture.stream.send(words("길라잡이" to 1.0, "목적지" to 1.0, "2번" to 0.10, "선택" to 1.0))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_command"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        // The repeated command, now heard clearly, is dispatched once.
        fixture.stream.send(words("목적지" to 0.9, "2번" to 0.9, "선택" to 0.9))
        fixture.runtime.drain()
        assertEquals(listOf("목적지 2번 선택"), fixture.commands)
        assertEquals(0.9f, fixture.confidences.single()!!, 0.0001f)
    }

    @Test
    fun awaitedCommandWithLowDecisiveWordIsNotDispatched() = withFixture { fixture ->
        assertTrue(fixture.controller.start())
        fixture.stream.ready()
        fixture.runtime.drain()
        fixture.stream.send(words("길라잡이" to 1.0))
        fixture.runtime.drain()
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        fixture.stream.send(words("목적지" to 1.0, "후보" to 1.0, "2번" to 0.10, "선택" to 1.0))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        // R18: rejected, then asked again once with the window reopened.
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence"))
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_reprompt"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    @Test
    fun clearWakeAndCommandInOneUtteranceIsDispatchedWithTheCommandConfidence() = withFixture { fixture ->
        assertTrue(fixture.controller.start())
        fixture.stream.ready()
        fixture.runtime.drain()
        fixture.stream.send(words("길라잡이" to 1.0, "2번" to 0.8, "선택" to 0.7))
        fixture.runtime.drain()
        assertEquals(listOf("2번 선택"), fixture.commands)
        assertEquals(0.75f, fixture.confidences.single()!!, 0.0001f)
    }

    // S04-19 (04.txt:258 "낮은 점수를 호출어·번호·동작 단어에 각각 배치") / S05-23 (05.txt:250 "명령 동사").
    @Test
    fun lowActionWordIsRejectedAlthoughTheCommandSpanAverageHolds() {
        val cases = listOf(
            listOf("안내" to 1.0, "시작" to 0.10, "해" to 1.0, "줘" to 1.0),
            listOf("안내" to 0.20, "종료" to 1.0, "해줘" to 1.0),
            listOf("다시" to 0.20, "듣기" to 1.0),
            listOf("더" to 0.20, "듣기" to 1.0),
            listOf("서울역" to 1.0, "찾아줘" to 0.20),
            listOf("3번" to 1.0, "선택" to 0.10, "해줘" to 1.0),
            listOf("보행" to 1.0, "일시정지" to 0.20, "해줘" to 1.0),
            listOf("보행" to 1.0, "재개" to 0.20, "해줘" to 1.0),
            listOf("이거" to 1.0, "신고해줘" to 0.20),
            listOf("다음" to 0.20, "안내" to 1.0, "해줘" to 1.0),
        )
        for (case in cases) {
            val command = case.joinToString(" ") { it.first }
            val transcript = words(*(listOf("길라잡이" to 1.0) + case).toTypedArray())
            assertTrue(command, transcript.isTrustedForCommand())
            assertNull(command, transcript.commandSpanOrNull(command))
            // The same words heard clearly are still a command.
            val clear = words(*(listOf("길라잡이" to 1.0) + case.map { it.first to 0.9 }).toTypedArray())
            assertEquals(command, clear.commandSpanOrNull(command)!!.text)
        }
    }

    @Test
    fun actionVocabularyIsTheFixedCommandGrammarNotPlaceNames() {
        for (token in listOf(
            "시작", "시작해", "시작해줘", "안내", "안내해줘", "안내시작해줘", "길안내", "종료", "보행종료", "중지해줘",
            "일시정지", "재개", "취소", "선택", "선택해줘", "신고", "신고해줘", "접수", "확인", "재탐색해줘", "도착",
            "찾아줘", "찾아주세요", "열어줘", "멈춰", "골라줘", "들려줘", "말해줘", "알려줘", "듣기", "다시듣기",
            "가자", "가고", "다시", "다음", "더", "이전", "앞",
        )) {
            assertTrue(token, isCommandActionToken(token))
        }
        for (token in listOf(
            "서울역", "편의점", "역삼점", "마포역", "안내소", "시작점", "도착지", "다음역", "고려대", "목적지", "후보",
            "좀", "해", "줘", "2번", "말씀", "싶어", "뭐야", "경로", "위치", "보행", "이거", "도움말",
        )) {
            assertFalse(token, isCommandActionToken(token))
        }
    }

    @Test
    fun lowWakePhraseIsNotLiftedByAClearCommand() {
        val lowWake = words("길라잡이" to 0.10, "안내" to 1.0, "시작해줘" to 1.0)
        assertEquals(0.70f, lowWake.confidence!!, 0.0001f)
        assertTrue("old utterance-average rule accepts it", lowWake.isTrustedForCommand())
        assertFalse(lowWake.wakeSpanTrustedBefore("안내 시작해줘"))
        assertFalse(words("길라" to 0.10, "잡이" to 0.9, "도움말" to 1.0).wakeSpanTrustedBefore("도움말"))
        assertTrue(words("길라" to 0.60, "잡이" to 0.60, "도움말" to 1.0).wakeSpanTrustedBefore("도움말"))
        assertTrue(words("길라잡이" to 0.9, "안내" to 1.0, "시작해줘" to 1.0).wakeSpanTrustedBefore("안내 시작해줘"))
        assertFalse(words("길라잡이" to 0.9, "안내" to 1.0).wakeSpanTrustedBefore("다시 안내"))
        // Aggregate-only transcripts keep the whole-utterance rule.
        assertTrue(VoskTranscript("길라잡이 도움말", 0.60f, true).wakeSpanTrustedBefore("도움말"))
        assertFalse(VoskTranscript("길라잡이 도움말", 0.59f, true).wakeSpanTrustedBefore("도움말"))
    }

    @Test
    fun wakeUtteranceWithALowWakePhraseIsIgnored() = withFixture { fixture ->
        assertTrue(fixture.controller.start())
        fixture.stream.ready()
        fixture.runtime.drain()
        fixture.stream.send(words("길라잡이" to 0.10, "안내" to 1.0, "시작해줘" to 1.0))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_wake"))
        assertFalse(fixture.statuses.contains("voice_hands_free=acknowledging_wake"))
        // R18: only the wake phrase was doubtful - not acted on, asked again once with the window open.
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_reprompt"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        // A clearly heard wake phrase still opens the command window.
        fixture.stream.send(words("길라잡이" to 0.9))
        fixture.runtime.drain()
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
    }

    @Test
    fun lowActionWordInTheWakeUtteranceOpensTheCommandWindowAndAwaitedOneIsIgnored() = withFixture { fixture ->
        assertTrue(fixture.controller.start())
        fixture.stream.ready()
        fixture.runtime.drain()
        fixture.stream.send(words("길라잡이" to 1.0, "안내" to 1.0, "종료" to 0.10, "해줘" to 1.0))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence_command"))
        assertEquals("voice_hands_free=waiting_command", fixture.statuses.last())
        fixture.stream.send(words("안내" to 1.0, "종료" to 0.10, "해줘" to 1.0))
        fixture.runtime.drain()
        assertTrue(fixture.commands.isEmpty())
        assertTrue(fixture.statuses.contains("voice_hands_free=low_confidence"))
        // R18: the wake utterance's doubtful command was the one re-ask; a second doubtful one in a
        // row closes the window, so the clear repetition needs the wake phrase again.
        assertEquals("voice_hands_free=waiting_wake_phrase", fixture.statuses.last())
        fixture.stream.send(words("길라잡이" to 0.9, "안내" to 0.9, "종료" to 0.9, "해줘" to 0.9))
        fixture.runtime.drain()
        assertEquals(listOf("안내 종료 해줘"), fixture.commands)
    }

    private fun words(vararg pairs: Pair<String, Double>): VoskTranscript {
        val result = pairs.joinToString(",") { (word, conf) -> """{"conf":$conf,"word":"$word"}""" }
        val text = pairs.joinToString(" ") { it.first }
        return requireNotNull(parseVoskTranscript("""{"result":[$result],"text":"$text"}""", isFinal = true))
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
        val confidences = mutableListOf<Float?>()
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
            onCommand = { text, confidence ->
                commands += text
                confidences += confidence
                appSpeechActive = true
            },
            onStatus = { statuses += it },
        )
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
