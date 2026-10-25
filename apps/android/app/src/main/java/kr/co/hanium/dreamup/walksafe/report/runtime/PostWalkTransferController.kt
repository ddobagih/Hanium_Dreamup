package kr.co.hanium.dreamup.walksafe.report.runtime

import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.allowsPostWalkTransfer

/** Common foreground post-walk triggers and one walk-in-progress retry; no new timer/executor. */
internal class PostWalkTransferController(private val ports: Ports,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    interface Ports {
        fun isForeground(): Boolean
        fun isWalkReady(): Boolean
        fun walkSnapshot(): WalkSessionSnapshot
        fun isRawReady(): Boolean
        fun markRawUploadWalk(walkId: String)
        fun scheduleRawUpload()
        fun maybeStartReportQueueDrain()
        fun runOnUiThread(action: () -> Unit)
        fun isFinishing(): Boolean
        fun isDestroyed(): Boolean
        fun removeRetry(task: Runnable)
        fun postRetry(task: Runnable, delayMs: Long)
        fun registerPowerReceiver(): Boolean
        fun unregisterPowerReceiver()
        fun isCharging(): Boolean
    }
    @Volatile private var postWalkTransferCharging = false
    private var postWalkPowerConnectedReceiverRegistered = false
    private var postWalkWalkInProgressRetryWalkId: String? = null
    private var retryNotBeforeMs: Long? = null
    private var retryEpoch: kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch? = null
    private val postWalkWalkInProgressRetry = Runnable {
        val expected = retryEpoch
        retryNotBeforeMs = null
        retryEpoch = null
        if (expected != null && ports.isWalkReady() && ports.walkSnapshot().epoch == expected) schedulePostWalkTransfers()
    }
    fun schedulePostWalkTransfers() {
        if (!ports.isForeground() || !ports.isWalkReady()) return
        val walk = ports.walkSnapshot()
        if (!walk.state.allowsPostWalkTransfer) return
        if (ports.isRawReady()) {
            ports.markRawUploadWalk(walk.epoch.walkSessionId)
            ports.scheduleRawUpload()
        }
        ports.maybeStartReportQueueDrain()
    }
    fun schedulePostWalkRetryAfterWalkInProgress() {
        ports.runOnUiThread {
            if (!ports.isWalkReady() || ports.isFinishing() || ports.isDestroyed()) return@runOnUiThread
            val walkId = ports.walkSnapshot().epoch.walkSessionId
            if (postWalkWalkInProgressRetryWalkId == walkId) return@runOnUiThread
            postWalkWalkInProgressRetryWalkId = walkId
            schedulePostWalkRetryAfter(POST_WALK_WALK_IN_PROGRESS_RETRY_DELAY_MS)
        }
    }
    /** One shared timer chooses the earliest pending retry; stores recheck their own backoff. */
    fun schedulePostWalkRetryAfter(delayMs: Long) {
        if (delayMs <= 0L) return
        ports.runOnUiThread {
            if (!ports.isForeground() || !ports.isWalkReady() || ports.isFinishing() || ports.isDestroyed()) return@runOnUiThread
            val walk = ports.walkSnapshot()
            if (!walk.state.allowsPostWalkTransfer) return@runOnUiThread
            val now = elapsedRealtimeMs()
            val due = if (delayMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMs
            if (retryEpoch == walk.epoch && retryNotBeforeMs?.let { it <= due } == true) return@runOnUiThread
            retryEpoch = walk.epoch
            retryNotBeforeMs = due
            ports.removeRetry(postWalkWalkInProgressRetry)
            ports.postRetry(postWalkWalkInProgressRetry, delayMs)
        }
    }

    fun registerPostWalkPowerConnectedReceiver() {
        if (postWalkPowerConnectedReceiverRegistered) return
        postWalkPowerConnectedReceiverRegistered = ports.registerPowerReceiver()
        onPostWalkChargingMaybeChanged()
    }
    fun unregisterPostWalkPowerConnectedReceiver() {
        if (!postWalkPowerConnectedReceiverRegistered) return
        postWalkPowerConnectedReceiverRegistered = false
        ports.unregisterPowerReceiver()
    }
    fun onPostWalkChargingMaybeChanged() {
        val charging = ports.isCharging()
        val started = charging && !postWalkTransferCharging
        postWalkTransferCharging = charging
        if (started) schedulePostWalkTransfers()
    }
    fun cancelPendingRetry() {
        retryNotBeforeMs = null
        retryEpoch = null
        ports.removeRetry(postWalkWalkInProgressRetry)
    }
    private companion object {
        const val POST_WALK_WALK_IN_PROGRESS_RETRY_DELAY_MS = 90_000L + 15_000L
    }
}
