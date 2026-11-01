package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.content.Context
import android.os.SystemClock
import kr.co.hanium.dreamup.walksafe.device.AndroidDeviceCheckAuthority
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Application services and ownership metadata only. Screen effects never enter this singleton. */
internal object VisionPairTransfers {
    const val EXTRA_TICKET = "walksafe_vision_pair_ticket"
    private var process: VisionPairConsumerTransfer? = null

    @Synchronized fun forApp(context: Context): VisionPairConsumerTransfer = process ?: run {
        val app = context.applicationContext
        val client = RemoteVisionClient.forMainProcess(app)
        val authority = AndroidDeviceCheckAuthority.forApp(app)
        val timer = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "WalkSafePairHandoffDeadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        val retirement = object : VisionPairTransferRetirementPort {
            override fun ownerCurrent(owner: DeviceCheckOwnerRef) = authority.ownerRef(owner.ownerId) === owner
            override fun latestLease(source: VisionCohortLease, targetOwner: DeviceCheckOwnerRef): VisionCohortLease? {
                val target = client.retirementSnapshot(targetOwner)?.lease
                if (target?.cohortId == source.cohortId) return target
                return client.retirementSnapshot(source.ownerId)?.lease?.takeIf {
                    it === source && it.cohortId == source.cohortId
                }
            }
            override fun requestExit(lease: VisionCohortLease, reason: String) = client.requestExit(lease, reason)
        }
        VisionPairConsumerTransfer(VisionConsumerRegistry.process, retirement, SystemClock::elapsedRealtime,
            VisionPairTransferDeadlineScheduler { deadline, task ->
                val future = timer.schedule(task, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                AutoCloseable { future.cancel(false) }
            }).also { process = it }
    }
}
