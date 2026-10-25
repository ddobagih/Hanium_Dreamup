package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.feedback.AndroidNonMetricObstacleAdvisoryPolicy
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricObstacleAdvisoryAction
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Live start/owner observations; implementations retain their original short-circuit queries. */
internal interface CameraAdvisoryDeliveryAuthority {
    fun newOutputAllowed(nowMs: Long): Boolean
    fun ownerCurrent(epoch: WalkRuntimeEpoch, fallbackGeneration: Int, lifecycleGeneration: Int, nowMs: Long): Boolean
    fun mountingAllowed(): Boolean
}

/** The existing speech controller still owns native scheduling, START, DONE and cancellation. */
internal fun interface CameraAdvisorySpeech {
    fun speak(message: String, validUntilMs: Long, current: () -> Boolean,
        delivered: () -> Unit, failed: () -> Unit): Boolean
}

/**
 * Delivers the existing policy's reservation without copying its state. Construction only captures
 * the policy and ports; SDK observations, the mounting lock and speech are obtained at use time.
 */
internal class CameraNonMetricAdvisoryDeliveryController(
    private val policy: AndroidNonMetricObstacleAdvisoryPolicy,
    private val authority: CameraAdvisoryDeliveryAuthority,
    private val speech: CameraAdvisorySpeech,
    private val mountingLock: () -> Any,
    private val elapsedRealtimeMs: () -> Long,
    private val onUiThread: (() -> Unit) -> Unit,
    private val recordDelivered: (NonMetricObstacleAdvisoryAction, Long) -> Unit,
) {
    fun dispatch(
        action: NonMetricObstacleAdvisoryAction,
        epoch: WalkRuntimeEpoch,
        fallbackGeneration: Int,
        lifecycleGeneration: Int,
    ) {
        if (!stillDeliverable(action, epoch, fallbackGeneration, lifecycleGeneration)) {
            policy.rejectDelivery(action)
            return
        }
        if (!policy.claimDelivery(action)) return
        if (!stillDeliverable(action, epoch, fallbackGeneration, lifecycleGeneration) ||
            !policy.isDeliveryCurrent(action)) {
            policy.rejectDelivery(action)
            return
        }
        val accepted = speech.speak(
            action.message, action.validUntilMs,
            current = {
                stillDeliverable(action, epoch, fallbackGeneration, lifecycleGeneration) &&
                    policy.isDeliveryCurrent(action)
            },
            delivered = {
                onUiThread {
                    val nowMs = elapsedRealtimeMs()
                    val confirmed = complete(action, nowMs) {
                        authority.ownerCurrent(epoch, fallbackGeneration, lifecycleGeneration, nowMs)
                    }
                    if (confirmed) recordDelivered(action, nowMs)
                }
            },
            failed = { policy.rejectDelivery(action) },
        )
        if (!accepted) policy.rejectDelivery(action)
    }

    /** A real DONE may follow start expiry/source loss; only its still-current owner acknowledges it. */
    fun complete(action: NonMetricObstacleAdvisoryAction, nowMs: Long, ownerCurrent: () -> Boolean): Boolean =
        synchronized(mountingLock()) {
            if (!ownerCurrent() || !authority.mountingAllowed()) {
                policy.rejectDelivery(action)
                false
            } else {
                policy.confirmDelivery(action, nowMs)
            }
        }

    private fun stillDeliverable(action: NonMetricObstacleAdvisoryAction, epoch: WalkRuntimeEpoch,
        fallbackGeneration: Int, lifecycleGeneration: Int): Boolean {
        val nowMs = elapsedRealtimeMs()
        return nowMs <= action.validUntilMs && authority.newOutputAllowed(nowMs) &&
            authority.ownerCurrent(epoch, fallbackGeneration, lifecycleGeneration, nowMs)
    }
}
