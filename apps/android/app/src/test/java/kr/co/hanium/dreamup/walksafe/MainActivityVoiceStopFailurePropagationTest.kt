package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints
import org.junit.Assert.*
import org.junit.Test

/** Main connection is static; checkpoint failures are executed without a native voice service. */
class MainActivityVoiceStopFailurePropagationTest {
    @Test
    fun voiceStopFailureMustReachTheSafetyCleanupCheckpoint() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        assertTrue(source.contains("override fun stopVoiceService() = stopHandsFreeVoiceService()"))
        val input = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/VoiceInputSessionController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(input, "stopHandsFreeVoiceService").contains("handsFreeRuntime.stopHandsFreeVoiceService()"))
        val voice = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt").readText(), "stopHandsFreeVoiceService")
        assertTrue(voice.contains("): Boolean {"))
        assertEquals(2, Regex("stopped = false").findAll(voice).count())
        assertTrue(voice.contains("return stopped"))
        val outputs = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionCleanupController.kt").readText(), "cancelWalkSessionOutputsInternal")
        val voiceStage = outputs.substringAfter("stage(\"voice_service\") {")
            .substringBefore("stage(\"voice_recognition\")")
        assertTrue(voiceStage.contains("val stopped = native.stopVoiceService()"))
        assertTrue(voiceStage.contains("if (checkpoints != null) check(stopped)"))
    }

    @Test
    fun failedVoiceStageRemainsUncertainWhileTheOtherCleanupStagesCanFinish() {
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        var stops = 0
        assertFalse(checkpoints.runStage("outputs:voice_service") {
            stops++
            check(false) { "voice_service_stop_failed" }
        })
        assertTrue(checkpoints.isUncertain("outputs:voice_service"))
        assertFalse(checkpoints.isComplete("outputs:voice_service"))
        var uploads = 0
        assertTrue(checkpoints.runStage("outputs:report_uploads") { uploads++ })
        assertTrue(checkpoints.isComplete("outputs:report_uploads"))
        assertFalse(checkpoints.runStage("outputs:voice_service") { stops++ })
        assertEquals(1, stops)
        assertEquals(1, uploads)
    }

    @Test
    fun confirmedVoiceStopCompletesOnceWithoutRepeatingItsNativeWork() {
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        var stops = 0
        repeat(2) {
            assertTrue(checkpoints.runStage("outputs:voice_service") { stops++; check(true) })
        }
        assertEquals(1, stops)
        assertTrue(checkpoints.isComplete("outputs:voice_service"))
        assertFalse(checkpoints.isUncertain("outputs:voice_service"))
    }
}
