package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-35 (05.txt:333): the spoken help and the recovery hints may name only phrases the parsers
 * really accept, and every example is run through the parser here, so a help sentence and the
 * grammar cannot drift apart. A new example in the help text fails until its intent is listed below.
 */
class VoiceHelpExamplesParseTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt").readText()

    @Test
    fun everySpokenHelpExampleParsesToItsIntendedCommand() {
        val help = voiceCommandHelpKo(paused = false) + " " + voiceCommandHelpKo(paused = true)
        val examples = sentences(help).flatMap(::examplesIn)
        assertTrue("help examples found: $examples", examples.size >= 18)
        for (example in examples) assertIntended(example)
    }

    @Test
    fun everyRecoveryHintExampleParsesToItsIntendedCommand() {
        val hints = literals(
            source.substringAfter("private fun unrecognizedCommandHintKo(): String = when {").substringBefore("\n    }\n"),
        )
        val examples = sentences(hints).flatMap(::examplesIn)
        // The two former arrival choices have been withdrawn; every remaining hint stays parseable.
        assertTrue("hint examples found: $examples", examples.size >= 12)
        assertFalse(examples.contains("도착했어"))
        assertFalse(examples.contains("아직 도착 아니야"))
        for (example in examples) assertIntended(example)
    }

    @Test
    fun extractorReadsTheHelpSentenceShapes() {
        assertEquals(listOf("서울역으로 안내해줘"), examplesIn("목적지는 서울역으로 안내해줘처럼 말해 주세요."))
        assertEquals(listOf("1번", "첫 번째"), examplesIn("후보 안내 뒤에는 1번 또는 첫 번째라고 선택할 수 있습니다."))
        assertEquals(listOf("더 듣기", "설정"), examplesIn("더 듣기, 설정을 사용할 수 있습니다."))
        assertEquals(listOf("위치 다시 확인"), examplesIn("위치 다시 확인이라고 말씀해 주세요."))
        assertEquals(listOf("도움말"), examplesIn("목적지를 말하거나 도움말이라고 말씀해 주세요."))
        assertEquals(listOf("새 경로 요청", "길안내 종료"), examplesIn("새 경로 요청, 길안내 종료 중에서 말씀해 주세요."))
        assertEquals(listOf("기기 신고 1번 선택"), examplesIn("기기 신고 목록을 들은 뒤 기기 신고 1번 선택이라고 말씀해 주세요."))
        assertEquals(listOf("기기 신고 삭제 확인"), examplesIn("삭제하려면 선택한 안내를 듣고 기기 신고 삭제 확인이라고 말씀해 주세요."))
        assertEquals(emptyList<String>(), examplesIn("신고와 보행 종료는 확인 안내를 따라 주세요."))
    }

    private fun assertIntended(example: String) {
        val expected = INTENDED[example]
        assertTrue("help example '$example' has no listed intent", expected != null)
        when (expected) {
            is WalkSessionVoiceAction -> assertEquals(example, expected, walkSessionAction(example))
            else -> {
                assertEquals(example, expected, parseAndroidVoiceCommand(example, allowBareDestinationIndex = true))
                if (expected !is AndroidVoiceCommand.SelectDestinationCandidate) {
                    // Outside the candidate dialog the same words mean the same command.
                    assertEquals(example, expected, parseAndroidVoiceCommand(example))
                }
            }
        }
    }

    private fun walkSessionAction(phrase: String): WalkSessionVoiceAction {
        val epoch = WalkRuntimeEpoch(walkSessionId = "walk-help", recoveryGeneration = 1L)
        val policy = WalkSessionVoiceControlPolicy()
        return when (phrase) {
            "보행 재개" -> policy.evaluate(phrase, 0.9f, WalkSessionState.PAUSED, epoch).action
            "보행 종료 확인", "보행 종료 취소" -> {
                policy.evaluate("보행 종료", 0.9f, WalkSessionState.ACTIVE, epoch)
                policy.evaluate(phrase, 0.9f, WalkSessionState.ACTIVE, epoch).action
            }
            else -> policy.evaluate(phrase, 0.9f, WalkSessionState.ACTIVE, epoch).action
        }
    }

    private companion object {
        val INTENDED: Map<String, Any> = mapOf(
            "서울역으로 안내해줘" to AndroidVoiceCommand.SetDestination("서울역"),
            "1번 상세" to AndroidVoiceCommand.DestinationCandidateDetail(1),
            "기기 신고 목록" to AndroidVoiceCommand.ListLocalQuarantinedReports,
            "기기 신고 1번 선택" to AndroidVoiceCommand.SelectLocalQuarantinedReport(1),
            "기기 신고 삭제 확인" to AndroidVoiceCommand.ConfirmLocalQuarantinedReportDelete,
            "1번" to AndroidVoiceCommand.SelectDestinationCandidate(1),
            "첫 번째" to AndroidVoiceCommand.SelectDestinationCandidate(1),
            "더 듣기" to AndroidVoiceCommand.HearMoreDestinationCandidates,
            "이전 후보" to AndroidVoiceCommand.PreviousDestinationCandidates,
            "목적지 취소" to AndroidVoiceCommand.CancelDestination,
            "안내 시작" to AndroidVoiceCommand.StartNavigation,
            "설정" to AndroidVoiceCommand.OpenSettings,
            "도움말" to AndroidVoiceCommand.Help,
            "다음 안내 알려줘" to AndroidVoiceCommand.NextNavigationInstruction,
            "다시 말해줘" to AndroidVoiceCommand.RepeatGuidance,
            "경로 다시 찾아줘" to AndroidVoiceCommand.RequestReroute,
            "새 경로 요청" to AndroidVoiceCommand.RequestReroute,
            "위치 다시 확인" to AndroidVoiceCommand.RecheckLocation,
            "현재 상태" to AndroidVoiceCommand.CurrentState,
            "남은 거리" to AndroidVoiceCommand.CurrentState,
            "길안내 종료" to AndroidVoiceCommand.StopNavigation,
            "신고해" to AndroidVoiceCommand.CreateReport,
            "보행 일시정지" to WalkSessionVoiceAction.PAUSE,
            "보행 재개" to WalkSessionVoiceAction.RESUME,
            "보행 종료" to WalkSessionVoiceAction.REQUEST_END,
            "보행 종료 확인" to WalkSessionVoiceAction.CONFIRM_END,
            "보행 종료 취소" to WalkSessionVoiceAction.CANCEL_END,
        )

        /** Instructions inside a hint sentence that are not a phrase to say. */
        val NOT_EXAMPLES = setOf("목적지를 말하")

        val LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val SENTENCE_END = Regex("(?<=[.?])\\s*")
        val MARKER = Regex("처럼 말|라고 선택|라고 말|을 사용할 수|를 말할 수| 중에서 말")
        val ITEM_SEPARATOR = Regex(", | 또는 |거나 ")
        val TOPIC = Regex("(?<!또)(?:는|은) ")

        fun literals(code: String): String = LITERAL.findAll(code).joinToString(" ") { it.groupValues[1] }

        fun sentences(text: String): List<String> = text.split(SENTENCE_END).map(String::trim).filter(String::isNotEmpty)

        fun examplesIn(sentence: String): List<String> {
            val marker = MARKER.find(sentence) ?: return emptyList()
            var body = sentence.substring(0, marker.range.first)
            // Remove sequence instructions before the topic scan; "들은 뒤" is not a topic.
            body = body.removePrefix("기기 신고 목록을 들은 뒤 ").removePrefix("삭제하려면 선택한 안내를 듣고 ")
            // Drop the topic ("보행 중에는 ", "목적지는 "); "또는" is a separator, not a topic.
            body = when {
                body.contains("에는 ") -> body.substringAfterLast("에는 ")
                else -> TOPIC.find(body)?.let { body.substring(it.range.last + 1) } ?: body
            }
            val items = body.split(ITEM_SEPARATOR).map(String::trim).filter { it.isNotEmpty() && it !in NOT_EXAMPLES }
            if (!marker.value.startsWith("라고")) return items
            // "확인이라고": the copula after a final consonant is not part of the phrase.
            return items.mapIndexed { index, item ->
                if (index == items.lastIndex && item.endsWith("이") && item.length >= 2 && hasFinalConsonant(item[item.length - 2])) {
                    item.dropLast(1)
                } else item
            }
        }

        fun hasFinalConsonant(syllable: Char): Boolean =
            syllable in '\uAC00'..'\uD7A3' && (syllable - '\uAC00') % 28 != 0
    }
}
