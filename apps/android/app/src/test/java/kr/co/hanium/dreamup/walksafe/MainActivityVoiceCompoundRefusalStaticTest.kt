package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.COMPOUND_VOICE_COMMAND_REFUSAL_KO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S05-6 (05.txt:78): a sentence with two actions is not executed and the user is asked for one
 * command at a time, instead of the generic "못 알아들었습니다" miss.
 */
class MainActivityVoiceCompoundRefusalStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun bothMissPathsAskForOneCommandAtATimeWhenTheSentenceWasCompound() {
        val helper = source.substringAfter("private fun unrecognizedCommandMessageKo(recognized: String): String =")
            .substringBefore("\n\n")
        assertTrue(helper.contains("kr.co.hanium.dreamup.walksafe.navigation.isCompoundVoiceCommand(recognized)"))
        assertTrue(helper.contains("navigation.COMPOUND_VOICE_COMMAND_REFUSAL_KO} \${unrecognizedCommandHintKo()}"))
        assertTrue(helper.contains("\"못 알아들었습니다. \${unrecognizedCommandHintKo()}\""))
        // The generic miss string now lives only in the helper.
        assertEquals(1, Regex(Regex.escape("\"못 알아들었습니다. \${unrecognizedCommandHintKo()}\"")).findAll(source).count())
        val handler = source.substringAfter("private fun handleVoiceCommandPhrases(")
            .substringBefore("executeVoiceAction(action, expectedNavigationDecisionToken)")
        val platformMiss = handler.substringAfter("PlatformVoiceCandidateDisposition.UNRECOGNIZED) {")
        assertTrue(platformMiss.substringBefore("return").contains("val message = unrecognizedCommandMessageKo(recognized)"))
        val scoredMiss = handler.substringAfter("if (action == null) {")
        assertTrue(scoredMiss.contains("navigation.isCompoundVoiceCommand(recognized)"))
        assertTrue(scoredMiss.contains("kr.co.hanium.dreamup.walksafe.navigation.COMPOUND_VOICE_COMMAND_REFUSAL_KO"))
        assertTrue(scoredMiss.contains("if (!retryHomeDestinationVoiceDialog(retryMessage)) {"))
        assertTrue(scoredMiss.contains("speakInteraction(unrecognizedCommandMessageKo(recognized))"))
    }

    @Test
    fun theRefusalSaysOneAtATime() {
        assertTrue(COMPOUND_VOICE_COMMAND_REFUSAL_KO.contains("한 번에 한 가지씩"))
    }
}
