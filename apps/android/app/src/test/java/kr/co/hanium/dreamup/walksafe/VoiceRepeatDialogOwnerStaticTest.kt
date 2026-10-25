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

    private val command = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceCommandInteractionController.kt")
    private val dialog = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt")
    private val peers = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/VoiceInteractionControllers.kt")
    private fun block(signature: String): String {
        val name = signature.substringAfter("fun ").substringBefore("(")
        return MainActivityRefactorSource.member(if (name == "executeVoiceAction") command else dialog, name)
    }

    @Test
    fun parserMappingIsUnchanged() {
        assertEquals(AndroidVoiceCommand.RepeatDestinationCandidates, parseAndroidVoiceCommand("다시 듣기"))
        assertEquals(AndroidVoiceCommand.RepeatGuidance, parseAndroidVoiceCommand("다시 말해줘"))
    }

    @Test
    fun repeatGuidanceDuringAnOpenCandidateDialogRepeatsTheCandidatePage() {
        val execute = block("private fun executeVoiceAction")
        MainActivityRefactorSource.composition(activity, "voiceInteractionControllers.commands")
        MainActivityRefactorSource.composition(activity, "voiceInteractionControllers.destinations")
        assertTrue(peers.contains("dialog = { destinations }"))
        val branch = execute.substringAfter("AndroidVoiceAction.SpeakCurrentGuidance -> {")
            .substringBefore("AndroidVoiceAction.SpeakVoiceHelp -> {")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                branch,
                "if (dialog().activeVoiceCandidateDialogOrNull() != null) dialog().repeatVoiceDestinationCandidates()",
                "else if (dialog().repeatPendingWalkingDestinationChange()) Unit",
                "else if (walk.runtimeActive()) speakNextNavigationInstruction()",
                "else output.speakInteraction(",
            ),
        )
    }

    @Test
    fun repeatCandidatesWithoutADialogFallsBackToTheCurrentGuidanceThenExplains() {
        val repeat = block("private fun repeatVoiceDestinationCandidates")
        assertTrue(peers.contains("repeatGuidance = { commands.speakNextNavigationInstruction() }"))
        val rejected = repeat.substringAfter("if (transition == null || !transition.accepted) {")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                rejected,
                "if (!search().state.inFlight && walk.runtimeActive() && routeRun().active) {",
                "repeatGuidance()",
                "output.speakInteraction(noVoiceDestinationCandidatesMessage())",
                "return",
                "speakHomeDestinationVoicePrompt(transition.state)",
            ),
        )
    }

    @Test
    fun theOwningDialogIsTheValidLeaseAtHomeOrTheCurrentQueryPageWhileWalking() {
        val owner = MainActivityRefactorSource.member(dialog, "activeVoiceCandidateDialogOrNull")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                owner,
                "if (ui.homeContextAvailable()) {",
                "currentVoiceDestinationDialogState()",
                "search().state.voicePage?.takeIf { it.query == search().state.query && !search().state.inFlight }",
            ),
        )
    }
}
