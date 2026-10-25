package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test

/** S04-30: the notification stop is delivered to MainActivity as a user stop and blocks automatic restarts. */
class WalkVoiceUserStopStaticTest {
    private val activity = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/HandsFreeRuntimeSession.kt",
    )
    private val service = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/voice/WalkVoiceForegroundService.kt",
    )

    private fun block(signature: String) = ReportStaticSourceInspector.functionBlock(activity, signature)

    @Test
    fun theNotificationStopIsReportedAsAUserStopBeforeTheControllerStops() {
        val onStart = ReportStaticSourceInspector.functionBlock(service, "override fun onStartCommand")
        val stopBranch = onStart.substringAfter("ACTION_STOP -> {").substringBefore("else -> {")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                stopBranch,
                "if (stopSelfResult(startId)) {",
                "reportWalkVoiceUserStop(activeController)",
                "stopVoiceSession()",
            ),
        )
    }

    @Test
    fun theOwnerRecordsTheStopForTheCurrentWalkAndClearsItsStartRequest() {
        val attach = block("fun attachHandsFreeVoiceController")
        assertTrue(attach.contains("setUserStopListener(::handleHandsFreeVoiceUserStop)"))
        val handler = block("fun handleHandsFreeVoiceUserStop")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handler,
                "if (stopped !== handsFreeVoiceController",
                "recordUserStop(requireNotNull(environment.walkOrNull()).epoch.walkSessionId)",
                "handsFreeVoiceServiceRequested = false",
                "clearHandsFreeVoiceStartRequest()",
                "voice_hands_free=user_stopped",
            ),
        )
        assertTrue(block("fun closeHandsFreeVoice").contains("setUserStopListener(null)"))
    }

    @Test
    fun automaticStartsAreHeldOffUntilANewWalk() {
        val start = block("fun maybeStartHandsFreeVoiceService")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                start,
                "!environment.runtimeActive()",
                "platform.userStoppedWalk(requireNotNull(environment.walkOrNull()).epoch.walkSessionId)",
                "voice_hands_free=user_stopped",
                "return",
                "platform.registerStart(controller, ::handleWalkVoiceStartFailure)",
                "platform.startService(requestId)",
            ),
        )
    }
}
