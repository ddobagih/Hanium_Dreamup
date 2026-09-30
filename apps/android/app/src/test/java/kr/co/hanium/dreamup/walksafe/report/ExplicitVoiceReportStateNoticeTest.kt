package kr.co.hanium.dreamup.walksafe.report

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-34 (originals/05.txt:325-328): a voice report command's parse success, the phone-side save
 * and the server acceptance are separate states; "접수" is never claimed before the server result,
 * and one voice task's duplicate final cannot become two reports.
 */
class ExplicitVoiceReportStateNoticeTest {
    private val source = String(
        Files.readAllBytes(Paths.get("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")),
        Charsets.UTF_8,
    )

    private fun block(start: String, end: String): String =
        source.substringAfter(start).substringBefore(end)

    @Test
    fun savedNoticeSaysNotYetAcceptedAndSentAfterTheWalk() {
        val saved = ExplicitReportSubmissionNotice.SAVED_NOT_SUBMITTED_KO
        assertTrue(saved.contains("휴대전화에 저장했습니다"))
        assertTrue(saved.contains("아직 접수되지 않았"))
        assertTrue(saved.contains("보행을 마친 뒤 전송합니다"))
        assertFalse(saved.contains("접수되었습니다"))
        assertFalse(source.contains("안전하게 저장했습니다"))
    }

    @Test
    fun disclosureAndSavedNoticeNameTheSameAfterWalkTransferTiming() {
        // w24 misc-F2: owner decision 3(b) + R4 send a report only after the walk is ended
        // (ENDED/READY), so the first disclosure and the save notice of one report flow must
        // name that same timing; the old "paused and re-confirmed" timing is gone.
        val disclosure = REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO
        val saved = ExplicitReportSubmissionNotice.SAVED_NOT_SUBMITTED_KO
        val timing = "보행을 마친 뒤"
        assertTrue(disclosure.contains("$timing 허용된 연결에서 WalkSafe 테스트 서버로 전송합니다"))
        assertTrue(saved.contains("$timing 전송합니다"))
        assertTrue(disclosure.contains("보행 중·일시정지 중에는 전송하지 않습니다"))
        listOf(disclosure, saved).forEach { text ->
            assertFalse(text.contains("보행을 멈추고 재확인한 상태에서"))
            assertFalse(text.contains("재확인한 상태"))
        }
        // The disclosure is display-only; the screen and TalkBack read the same text.
        val render = block(
            "private fun renderExplicitReportConfirmationDisclosure(",
            "private fun renderExplicitReportConfirmationIdle(",
        )
        assertTrue(render.contains("val disclosure = \"\$REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO \$action\""))
        assertTrue(render.contains("explicitReportConfirmationText.contentDescription = disclosure"))
    }
    @Test
    fun confirmationSaveUsesOneSentenceForSpeechScreenAndTalkBack() {
        val submit = block(
            "private fun submitFrozenExplicitReportAfterConfirmation(",
            "private fun prepareExplicitReportCandidateIfCurrent(",
        )
        assertTrue(submit.contains("ExplicitReportSubmissionNotice.SAVED_NOT_SUBMITTED_KO"))
        assertTrue(submit.contains("renderExplicitReportConfirmationIdle(savedNotice)"))
        assertTrue(submit.contains("speakInteraction(savedNotice)"))
        assertTrue(
            "the save notice is decided only from a queued status",
            submit.contains("status.startsWith(\"reportCandidate=queued\")"),
        )
        assertFalse(submit.contains("접수"))
    }

    @Test
    fun acceptanceIsAnnouncedOnlyFromTheServerCreatedResult() {
        assertEquals(1, source.split("\"신고가 접수되었습니다.\"").size - 1)
        val drain = block(
            "private fun reportQueueDrainResultMessage(",
            "private fun postReportQueueDrainNotice(",
        )
        assertTrue(drain.contains("ReportCreationOutcome.CREATED -> \"신고가 접수되었습니다.\""))
        listOf(
            block("private fun requestExplicitReport(", "private fun ensureExplicitReportAuthorityPreconditions("),
            block("private fun processReportCandidate(", "private fun requestExplicitReport("),
            block("private fun beginExplicitReportConfirmationDisclosure(", "private fun deliverExplicitReportDisclosureAfterDraw("),
        ).forEach { beforeServer ->
            assertFalse(beforeServer.contains("접수되었습니다"))
            assertFalse(beforeServer.contains("접수했습니다"))
        }
        // Parse success only records a status token; it announces nothing by itself.
        val action = block("AndroidVoiceAction.CreateReport -> {", "is AndroidVoiceAction.SearchDestination ->")
        assertTrue(action.contains("updateNavigationStatus(\"voice=report_command_recognized\")"))
        assertTrue(action.contains("requestExplicitReport(ExplicitReportRequestSource.VOICE)"))
        assertFalse(action.contains("speak"))
    }

    @Test
    fun oneVoiceTaskDeliversOneFinalAndOneStagedReportIsConsumedOnce() {
        // One-shot recognizer: a second onResults of the same lease fails the generation check.
        val oneShot = block("override fun onResults(results: Bundle?) {", "private fun isVoiceRecognitionLeaseCurrent(")
        val lease = oneShot.indexOf("isVoiceRecognitionLeaseCurrent(")
        val bump = oneShot.indexOf("voiceRecognitionGeneration += 1")
        assertTrue(lease in 0 until bump)
        // Hands-free: each listening capture is consumed by its first command.
        assertTrue(
            source.contains(
                "val capture = handsFreeNavigationDecisionCapture ?: return@command\n" +
                    "                handsFreeNavigationDecisionCapture = null",
            ),
        )
        // Gateway STT: the interaction is finished before its command runs.
        val gateway = block("private fun handleGatewaySpeechTranscript(", "private fun speakGatewayInteractionOrLocalFallback(")
        assertTrue(gateway.indexOf("finishGatewaySpeechInteraction(active)") < gateway.indexOf("handleVoiceCommandPhrases("))
        // A repeated request while the disclosure plays is absorbed; only an armed payload is
        // consumed (once) by the second action, before any new capture is prepared.
        val request = block("private fun requestExplicitReport(", "private fun ensureExplicitReportAuthorityPreconditions(")
        val consume = request.indexOf("explicitReportConfirmationPolicy.consumeIfConfirmed(")
        val awaiting = request.indexOf("explicitReportConfirmationPolicy.isAwaitingDisclosure(context)")
        val prepare = request.indexOf("prepareExplicitReportCandidateIfCurrent(")
        assertTrue(consume in 0 until awaiting)
        assertTrue(awaiting < prepare)
    }
}
