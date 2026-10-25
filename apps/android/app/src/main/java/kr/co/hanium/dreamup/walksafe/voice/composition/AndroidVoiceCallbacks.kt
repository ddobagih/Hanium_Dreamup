package kr.co.hanium.dreamup.walksafe.voice.composition

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.feedback.delivery.RiskFeedbackScheduler
import kr.co.hanium.dreamup.walksafe.voice.input.VoiceInputScheduler
import kr.co.hanium.dreamup.walksafe.voice.interaction.VoiceInteractionScheduler
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputScheduler

internal interface VoiceCallbackQueue {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

/** Existing retirement/deferred Handler is acquired only at the original post/remove point. */
internal class HandlerVoiceCallbackQueue(private val handler: () -> Handler) : VoiceCallbackQueue {
    override fun post(task: Runnable) { handler().post(task) }
    override fun postDelayed(task: Runnable, delayMs: Long) { handler().postDelayed(task, delayMs) }
    override fun remove(task: Runnable) { handler().removeCallbacks(task) }
}

internal interface VoiceViewCallbackTarget {
    fun initialized(): Boolean
    fun post(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

/** Shares only platform dispatch adapters; each controller retains its own task and cancellation state. */
internal class AndroidVoiceCallbacks(
    private val status: VoiceViewCallbackTarget,
    private val retired: VoiceCallbackQueue,
    private val runUi: (() -> Unit) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val mainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    readinessFactory: () -> VoiceCallbackQueue = {
        val handler = Handler(Looper.getMainLooper())
        HandlerVoiceCallbackQueue { handler }
    },
) : VoiceInputScheduler, SpeechOutputScheduler, VoiceInteractionScheduler, RiskFeedbackScheduler {
    // The factory is consumed once, only by readiness post/remove, as in the original output adapter.
    private val readiness by lazy { readinessFactory() }
    override fun nowMs() = clock()
    override fun elapsedRealtimeMs() = clock()
    override fun isMainThread() = mainThread()
    override fun runOnMain(action: () -> Unit) = runUi(action)
    override fun runOnUiThread(action: () -> Unit) = runUi(action)
    override fun canScheduleRestart() = status.initialized()
    override fun postRestart(task: Runnable, delayMs: Long) = status.post(task, delayMs)
    override fun removeRestart(task: Runnable) = status.remove(task)
    override fun postDeferred(task: Runnable, delayMs: Long) = retired.postDelayed(task, delayMs)
    override fun removeDeferred(task: Runnable) = retired.remove(task)
    override fun postReadiness(task: Runnable, delayMs: Long) = readiness.postDelayed(task, delayMs)
    override fun removeReadiness(task: Runnable) = readiness.remove(task)
    override fun postRetired(action: () -> Unit) = retired.post(Runnable(action))
    override fun postDialogExpiry(action: () -> Unit, delayMs: Long) = retired.postDelayed(Runnable(action), delayMs)
    override fun hasTerminalView() = status.initialized()
    override fun postTerminal(task: Runnable, delayMs: Long) = status.post(task, delayMs)
    override fun removeTerminal(task: Runnable) = status.remove(task)
    fun canScheduleInteraction() = status.initialized()
    fun postInteraction(task: Runnable, delayMs: Long) = status.post(task, delayMs)
    fun removeInteraction(task: Runnable) { if (status.initialized()) status.remove(task) }
}
