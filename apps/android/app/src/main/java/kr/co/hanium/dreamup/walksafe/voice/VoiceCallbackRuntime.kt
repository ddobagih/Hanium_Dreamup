package kr.co.hanium.dreamup.walksafe.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Scheduling and clock effects only; lifecycle decisions stay with the caller. */
internal interface VoiceCallbackRuntime {
    fun checkMainThread(owner: String)
    fun nowMs(): Long
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
    fun removeCallbacks(task: Runnable)
    fun removeAllCallbacks()
}

internal class HandlerVoiceCallbackRuntime(
    private val handler: Handler,
) : VoiceCallbackRuntime {
    override fun checkMainThread(owner: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "$owner must be called on the main thread"
        }
    }

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun removeCallbacks(task: Runnable) {
        handler.removeCallbacks(task)
    }

    override fun removeAllCallbacks() {
        handler.removeCallbacksAndMessages(null)
    }
}
