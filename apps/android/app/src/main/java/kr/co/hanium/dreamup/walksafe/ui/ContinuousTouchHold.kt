package kr.co.hanium.dreamup.walksafe.ui

/** A cancelled touch never contributes time to a later unlock attempt. */
internal class ContinuousTouchHold(private val requiredMs: Long = 3_000L) {
    private var startedAt: Long? = null
    fun begin(now: Long) { startedAt = now }
    fun cancel() { startedAt = null }
    fun ready(now: Long): Boolean = startedAt?.let { now >= it && now - it >= requiredMs } == true
}
