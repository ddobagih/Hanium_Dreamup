package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test

/** S04-30: the notification stop is delivered to MainActivity as a user stop and blocks automatic restarts. */
class WalkVoiceUserStopStaticTest {
    private val activity = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
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
        val attach = block("private fun attachHandsFreeVoiceController")
        assertTrue(attach.contains("setWalkVoiceUserStopListener(::handleHandsFreeVoiceUserStop)"))
        val handler = block("private fun handleHandsFreeVoiceUserStop")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                handler,
                "if (stopped !== handsFreeVoiceController",
                "recordUserStop(walkSessionLifecycle.snapshot().epoch.walkSessionId)",
                "handsFreeVoiceServiceRequested = false",
                "clearHandsFreeVoiceStartRequest()",
                "voice_hands_free=user_stopped",
            ),
        )
        assertTrue(block("private fun closeHandsFreeVoice").contains("setWalkVoiceUserStopListener(null)"))
    }

    @Test
    fun automaticStartsAreHeldOffUntilANewWalk() {
        val start = block("private fun maybeStartHandsFreeVoiceService")
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                start,
                "!isWalkSessionRuntimeActive()",
                "blocksAutomaticStart(walkSessionLifecycle.snapshot().epoch.walkSessionId) == true",
                "voice_hands_free=user_stopped",
                "return",
                "app.registerWalkVoiceStartRequest(controller, ::handleWalkVoiceStartFailure)",
                "startService(startIntent)",
            ),
        )
    }
}
