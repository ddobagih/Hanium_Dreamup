package kr.co.hanium.dreamup.walksafe.voice

import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityInput
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityResolver
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotVoiceInputPolicyTest {
    @Test
    fun outputFailureAndPendingOutputDoNotBlockReadyInput() {
        for (tts in listOf(false, null)) {
            val input = readyInput().copy(offlineKoreanTextToSpeechAvailable = tts)
            val decision = resolve(input)

            assertEquals(WalkSafeStartupCapabilityTier.BLOCKED, decision.tier)
            assertTrue(available(input))
            assertFalse(decision.mayConfirmAndStart)
        }
    }

    @Test
    fun missingOrPendingInputCapabilityStillBlocksInput() {
        for (status in listOf(false, null)) {
            assertFalse(available(readyInput().copy(microphoneAvailable = status)))
            assertFalse(available(readyInput().copy(onDeviceSpeechRecognitionAvailable = status)))
        }
    }

    @Test
    fun permissionAndForegroundSafetyContextCannotBeBypassedByReadyCapabilities() {
        val decision = resolve(readyInput())

        assertFalse(OneShotVoiceInputPolicy.isAvailable(false, true, decision))
        assertFalse(OneShotVoiceInputPolicy.isAvailable(true, false, decision))
        assertFalse(OneShotVoiceInputPolicy.isAvailable(false, true, null))
        assertFalse(OneShotVoiceInputPolicy.isAvailable(true, false, null))
        assertTrue(OneShotVoiceInputPolicy.isAvailable(true, true, null))
    }

    @Test
    fun unrelatedPendingWalkCapabilityDoesNotBlockHomeInputOrAllowWalking() {
        val input = readyInput().copy(metricDistanceAvailable = null, cameraAvailable = null)

        assertTrue(available(input))
        assertFalse(resolve(input).mayConfirmAndStart)
    }

    @Test
    fun recognizedCommandThenOutputFailureStillAllowsASecondCommand() {
        val requests = VoskSpeechRequestFence()
        var input = readyInput()
        assertTrue(available(input))
        val first = checkNotNull(requests.begin())
        assertTrue(requests.finish(first))
        assertEquals(
            AndroidVoiceAction.SpeakNextNavigationInstruction,
            selectAndroidVoiceAction(listOf("다음 안내 알려줘"), floatArrayOf(0.95f)),
        )

        input = input.copy(offlineKoreanTextToSpeechAvailable = false)
        assertFalse(resolve(input).mayConfirmAndStart)
        assertTrue(available(input))
        val second = checkNotNull(requests.begin())
        assertNotEquals(first, second)
        assertFalse(requests.finish(first))
        assertTrue(requests.finish(second))
        assertEquals(
            AndroidVoiceAction.SpeakNextNavigationInstruction,
            selectAndroidVoiceAction(listOf("다음 안내 알려줘"), floatArrayOf(0.95f)),
        )
    }

    @Test
    fun unknownCommandIsTerminalForOnlyItsRequest() {
        val requests = VoskSpeechRequestFence()
        val unknown = checkNotNull(requests.begin())
        assertTrue(requests.finish(unknown))
        assertNull(selectAndroidVoiceAction(listOf("바나나 소나타"), floatArrayOf(0.95f)))

        assertTrue(available(readyInput()))
        val next = checkNotNull(requests.begin())
        assertTrue(requests.finish(next))
        assertEquals(
            AndroidVoiceAction.SearchDestination("서울역"),
            selectAndroidVoiceAction(listOf("서울역으로 안내해줘"), floatArrayOf(0.95f)),
        )
    }

    @Test
    fun immediateEngineErrorAndTimeoutReleaseOnlyTheReservedRequest() {
        // Both adapter terminal paths use this fence; an inline executor models an early callback.
        val requests = VoskSpeechRequestFence()
        var reservedRun: Long? = null
        for (runId in listOf(1L, 2L)) {
            assertTrue(available(readyInput()))
            val request = checkNotNull(requests.begin())
            var terminalCalls = 0
            submitReservedVoskStreamingRun(
                runId = runId,
                onRunReserved = { reservedRun = it },
                executor = Executor { it.run() },
                task = Runnable {
                    assertEquals(runId, reservedRun)
                    if (requests.finish(request)) terminalCalls += 1
                    if (requests.finish(request)) terminalCalls += 1
                },
            )
            assertEquals(1, terminalCalls)
            assertFalse(requests.accepts(request))
        }
        assertTrue(requests.accepts(checkNotNull(requests.begin())))
    }

    @Test
    fun cancellationAndStaleTerminalCannotConsumeReplacementInput() {
        val requests = VoskSpeechRequestFence()
        val cancelled = checkNotNull(requests.begin())
        requests.cancel()

        assertTrue(available(readyInput()))
        val next = checkNotNull(requests.begin())
        assertFalse(requests.accepts(cancelled))
        assertFalse(requests.finish(cancelled))
        assertTrue(requests.accepts(next))
        assertTrue(requests.finish(next))
    }

    @Test
    fun permissionWithdrawalBlocksNextRequestUntilAnExplicitGrantedRetry() {
        val requests = VoskSpeechRequestFence()
        val first = checkNotNull(requests.begin())
        assertTrue(requests.finish(first))
        val decision = resolve(readyInput())

        assertFalse(OneShotVoiceInputPolicy.isAvailable(true, false, decision))
        assertTrue(OneShotVoiceInputPolicy.isAvailable(true, true, decision))
        val second = checkNotNull(requests.begin())
        assertNotEquals(first, second)
        assertTrue(requests.finish(second))
    }

    private fun available(input: WalkSafeStartupCapabilityInput): Boolean =
        OneShotVoiceInputPolicy.isAvailable(
            contextAvailable = true,
            microphoneGranted = true,
            capabilityDecision = resolve(input),
        )

    private fun resolve(input: WalkSafeStartupCapabilityInput) =
        WalkSafeStartupCapabilityResolver.resolve(input, approvedDeviceProfileRequired = false)

    private fun readyInput() = WalkSafeStartupCapabilityInput(
        androidVersionSupported = true,
        cameraAvailable = true,
        gpsAvailable = true,
        microphoneAvailable = true,
        vibrationAvailable = true,
        onDeviceSpeechRecognitionAvailable = true,
        offlineKoreanTextToSpeechAvailable = true,
        metricDistanceAvailable = true,
        approvedDesignatedDeviceProfile = false,
        designatedDeviceProfileVersion = null,
    )
}
