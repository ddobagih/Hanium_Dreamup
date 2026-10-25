package kr.co.hanium.dreamup.walksafe.runtime.vision.composition

import android.os.Handler
import kr.co.hanium.dreamup.walksafe.runtime.vision.primary.PrimaryNativeFlightScheduler

internal class AndroidVisionScheduler(private val handler: () -> Handler) : PrimaryNativeFlightScheduler {
    override fun postDelayed(task: Runnable, delayMs: Long) = handler().postDelayed(task, delayMs)
    override fun remove(task: Runnable) { handler().removeCallbacks(task) }
}
