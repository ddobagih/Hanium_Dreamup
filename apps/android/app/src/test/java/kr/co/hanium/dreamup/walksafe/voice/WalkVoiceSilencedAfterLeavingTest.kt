package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S04-27-F1: an incoming call UI takes the screen first (onStop), then the system silences
 * capture. That leave counts, so the return after the call is the explicit retry, provided no call
 * or other recorder still holds the input; the outcome is spoken again on return. The lifecycle
 * below mirrors MainActivity onStop/onResume and is pinned by the static test at the end.
 */
class WalkVoiceSilencedAfterLeavingTest {
    private var pending: WalkVoiceStartFailurePresentation? = null

    @Test
    fun callEndReturnRetriesWhenTheSilencingCameAfterTheAppLeft() {
        val recovery = WalkVoiceStartRecovery()
        onStop(recovery)
        pending = recovery.recordFailure(WalkVoiceStartFailure.INPUT_SILENCED, microphoneGranted = true, appInForeground = false)
        assertTrue(recovery.blocksAutomaticRetry)
        assertTrue(recovery.retriesOnNextForegroundReturn)
        assertEquals(WalkVoiceStartRecovery.SILENCED_AFTER_LEAVING_TOKEN, pending?.statusToken)
        assertTrue(requireNotNull(pending).guidance.contains("화면 버튼은 계속 사용할 수 있습니다."))

        onResume(recovery, inputHeldElsewhere = false)
        assertFalse("the call-end return is the retry", recovery.blocksAutomaticRetry)
        assertEquals(
            "통화나 다른 앱의 마이크 사용으로 멈췄던 호출어 대기를 다시 시도합니다.",
            showPending(recovery),
        )
    }

    @Test
    fun aReturnDuringTheCallKeepsTheBlockUntilTheNextReturn() {
        val recovery = WalkVoiceStartRecovery()
        onStop(recovery)
        pending = recovery.recordFailure(WalkVoiceStartFailure.INPUT_SILENCED, microphoneGranted = true, appInForeground = false)
        onResume(recovery, inputHeldElsewhere = true)
        assertTrue(recovery.blocksAutomaticRetry)
        assertTrue(requireNotNull(showPending(recovery)).startsWith("아직 통화나 다른 앱이 마이크를 쓰고 있어"))
        // The user leaves again and comes back after the call: the existing rule clears it.
        onStop(recovery)
        onResume(recovery, inputHeldElsewhere = false)
        assertFalse(recovery.blocksAutomaticRetry)
        assertNull(showPending(recovery))
    }

    @Test
    fun silencingWhileOnScreenStillNeedsAnExplicitLeaveAndReturn() {
        val recovery = WalkVoiceStartRecovery()
        pending = recovery.recordFailure(WalkVoiceStartFailure.INPUT_SILENCED, microphoneGranted = true, appInForeground = true)
        assertEquals("microphone_input_silenced_retry_required", pending?.statusToken)
        assertFalse(recovery.retriesOnNextForegroundReturn)
        assertNull(showPending(recovery))
        onResume(recovery, inputHeldElsewhere = false)
        assertTrue("a UI refresh without leaving is not an explicit retry", recovery.blocksAutomaticRetry)
        onStop(recovery)
        onResume(recovery, inputHeldElsewhere = false)
        assertFalse(recovery.blocksAutomaticRetry)
    }

    @Test
    fun otherBackgroundFailuresKeepTheirSeeTheNoticeFirstRule() {
        val recovery = WalkVoiceStartRecovery()
        onStop(recovery)
        pending = recovery.recordFailure(
            WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED, microphoneGranted = true, appInForeground = false,
        )
        assertFalse(recovery.retriesOnNextForegroundReturn)
        onResume(recovery, inputHeldElsewhere = false)
        assertTrue(recovery.blocksAutomaticRetry)
        assertNull(showPending(recovery))
    }

    @Test
    fun aSilencingAgainAfterTheRetryDoesNotLoop() {
        val recovery = WalkVoiceStartRecovery()
        onStop(recovery)
        pending = recovery.recordFailure(WalkVoiceStartFailure.INPUT_SILENCED, microphoneGranted = true, appInForeground = false)
        onResume(recovery, inputHeldElsewhere = false)
        showPending(recovery)
        assertFalse(recovery.blocksAutomaticRetry)
        // The retried capture is silenced again while the user is on screen: blocked, no auto-retry.
        pending = recovery.recordFailure(WalkVoiceStartFailure.INPUT_SILENCED, microphoneGranted = true, appInForeground = true)
        showPending(recovery)
        onResume(recovery, inputHeldElsewhere = false)
        assertTrue(recovery.blocksAutomaticRetry)
    }

    @Test
    fun mainActivityWiresTheForegroundStateTheReturnGuardAndTheRepeatedNotice() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val resume = source.substringAfter("override fun onResume() {").substringBefore("accountRequestFence.enteredForeground()")
        assertTrue(resume.contains(
            "if (pendingHandsFreeVoiceStartFailure == null ||\n" +
                "            handsFreeVoiceStartRecovery.retriesOnNextForegroundReturn && !voiceInputHeldElsewhere()\n" +
                "        ) {\n" +
                "            handsFreeVoiceStartRecovery.onForegroundReturn()",
        ))
        val report = source.substringAfter("private fun reportWalkVoiceStartFailure(")
            .substringBefore("private fun showPendingHandsFreeVoiceStartFailure(")
        assertTrue(report.contains("appInForeground = isActivityForeground,"))
        val show = source.substringAfter("private fun showPendingHandsFreeVoiceStartFailure(")
            .substringBefore("private fun voiceInputHeldElsewhere(")
        assertTrue(show.indexOf("updateStatus(\"호출어 음성 시작 실패\", presentation.guidance)") <
            show.indexOf("handsFreeVoiceStartRecovery.spokenNoticeOnReturn(presentation)?.let(::speakInteraction)"))
        val held = source.substringAfter("private fun voiceInputHeldElsewhere(): Boolean").substringBefore("\n    }.getOrDefault(false)")
        assertTrue(held.contains("android.media.AudioManager.MODE_IN_CALL"))
        assertTrue(held.contains("android.media.AudioManager.MODE_IN_COMMUNICATION"))
        assertTrue(held.contains("audio.activeRecordingConfigurations.isNotEmpty()"))
    }

    /** MainActivity.onStop. */
    private fun onStop(recovery: WalkVoiceStartRecovery) {
        if (pending == null) recovery.onStopped()
    }

    /** MainActivity.onResume guard (S04-27-F1). */
    private fun onResume(recovery: WalkVoiceStartRecovery, inputHeldElsewhere: Boolean) {
        if (pending == null || recovery.retriesOnNextForegroundReturn && !inputHeldElsewhere) {
            recovery.onForegroundReturn()
        }
    }

    /** MainActivity.showPendingHandsFreeVoiceStartFailure in the foreground; returns the spoken notice. */
    private fun showPending(recovery: WalkVoiceStartRecovery): String? {
        val shown = pending ?: return null
        pending = null
        return recovery.spokenNoticeOnReturn(shown)
    }
}
