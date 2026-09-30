package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.parseAndroidVoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-11 (originals/05.txt:149): the parser keeps its fixed phrase mapping, but what is repeated is
 * decided by the dialog that owns the conversation now (FP-026: 확인 대화 > 길안내 > 설명).
 */
class VoiceRepeatDialogOwnerStaticTest {
    private val activity = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    )

    private fun block(signature: String) = ReportStaticSourceInspector.functionBlock(activity, signature)

    @Test
    fun parserMappingIsUnchanged() {
        assertEquals(AndroidVoiceCommand.RepeatDestinationCandidates, parseAndroidVoiceCommand("다시 듣기"))
        assertEquals(AndroidVoiceCommand.RepeatGuidance, parseAndroidVoiceCommand("다시 말해줘"))
    }

    @Test
    fun repeatGuidanceDuringAnOpenCandidateDialogRepeatsTheCandidatePage() {
        val execute = block("private fun executeVoiceAction")
        val branch = execute.substringAfter("AndroidVoiceAction.SpeakCurrentGuidance -> {")
            .substringBefore("AndroidVoiceAction.SpeakVoiceHelp -> {")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                branch,
                "if (activeVoiceCandidateDialogOrNull() != null) repeatVoiceDestinationCandidates()",
                "else if (isWalkSessionRuntimeActive()) speakNextNavigationInstruction()",
                "else speakInteraction(",
            ),
        )
    }

    @Test
    fun repeatCandidatesWithoutADialogFallsBackToTheCurrentGuidanceThenExplains() {
        val repeat = block("private fun repeatVoiceDestinationCandidates")
        val rejected = repeat.substringAfter("if (transition == null || !transition.accepted) {")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                rejected,
                "if (!destinationSearchInFlight && isWalkSessionRuntimeActive() && isRouteActive) {",
                "speakNextNavigationInstruction()",
                "speakInteraction(noVoiceDestinationCandidatesMessage())",
                "return",
                "speakHomeDestinationVoicePrompt(transition.state)",
            ),
        )
    }

    @Test
    fun theOwningDialogIsTheValidLeaseAtHomeOrTheCurrentQueryPageWhileWalking() {
        val owner = activity.substringAfter("private fun activeVoiceCandidateDialogOrNull()")
            .substringBefore("private fun ")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                owner,
                "if (nativeHomeFeatureContextAvailable()) {",
                "currentVoiceDestinationDialogState()",
                "destinationSearchVoiceState?.takeIf { it.query == destinationSearchQuery && !destinationSearchInFlight }",
            ),
        )
    }
}
