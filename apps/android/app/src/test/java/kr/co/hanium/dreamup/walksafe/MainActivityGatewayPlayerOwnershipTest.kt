package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.voice.input.AndroidVoiceInputPlatform
import kr.co.hanium.dreamup.walksafe.voice.input.GatewayVoicePlayer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main ownership method; native MediaPlayer and Activity lifecycle are not executed. */
class MainActivityGatewayPlayerOwnershipTest {
    @Test
    fun obsoletePlayerCanReleaseItsOwnOutputReservation() {
        val activity = MainActivity()
        val player = AndroidVoiceInputPlatform(activity).createPlayer()
        playerField.set(activity, player)
        assertTrue(release(activity, player))
        assertNull(playerField.get(activity))
    }

    @Test
    fun lateTerminalEventCannotReleaseTheReplacementPlayer() {
        val activity = MainActivity()
        val old = AndroidVoiceInputPlatform(activity).createPlayer()
        val replacement = AndroidVoiceInputPlatform(activity).createPlayer()
        playerField.set(activity, replacement)
        assertFalse(release(activity, old))
        assertSame(replacement, playerField.get(activity))
        assertTrue(release(activity, replacement))
        assertNull(playerField.get(activity))
    }

    @Test
    fun repeatedTerminalEventCannotAcquireAReservationAgain() {
        val activity = MainActivity()
        val player = AndroidVoiceInputPlatform(activity).createPlayer()
        playerField.set(activity, player)
        assertTrue(release(activity, player))
        assertFalse(release(activity, player))
        assertNull(playerField.get(activity))
    }

    @Test
    fun allTerminalPathsReleaseOwnershipAndOnlyCurrentFailuresSpeakFallback() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val factory = MainActivityRefactorSource.member(source, "createVoiceInputController")
        assertTrue(factory.contains("val platform = AndroidVoiceInputPlatform(this)"))
        assertTrue(factory.contains("gatewayPlatform = platform"))
        assertTrue(MainActivityRefactorSource.member(source, "cancelGatewaySpeechInteraction")
            .contains("voiceInputController.gateway.cancelGatewaySpeechInteraction(reason)"))
        val play = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/GatewayVoiceCaptureController.kt").readText(),
            "speakGatewayInteractionOrLocalFallback")
        val completed = play.substringAfter("onCompleted = {").substringBefore("onFailed = {")
        val failed = play.substringAfter("onFailed = {").substringBefore("},")
        val refused = play.substringAfter("if (!started &&")
        assertTrue(completed.contains("releaseGatewaySpeechPlayerIfOwned(player)"))
        assertTrue(completed.contains("finishGatewaySpeechInteraction(active)"))
        assertFalse(completed.contains("speakInteraction(message)"))
        for (terminal in listOf(failed, refused)) {
            assertTrue(terminal.contains("releaseGatewaySpeechPlayerIfOwned(player)"))
            val current = terminal.indexOf("val wasCurrent = isGatewaySpeechInteractionCurrent(active)")
            val finish = terminal.indexOf("finishGatewaySpeechInteraction(active)")
            val fallback = terminal.indexOf("if (wasCurrent) output.speakInteraction(message)")
            assertTrue(current >= 0 && finish > current && fallback > finish)
        }
    }

    private val playerField = MainActivityControllerTestFixture.field("gatewaySpeechPlayer")
        .apply { isAccessible = true }
    private fun release(activity: MainActivity, player: GatewayVoicePlayer): Boolean =
        MainActivityControllerTestFixture.voiceInput(activity).gateway.releaseGatewaySpeechPlayerIfOwned(player)
}
