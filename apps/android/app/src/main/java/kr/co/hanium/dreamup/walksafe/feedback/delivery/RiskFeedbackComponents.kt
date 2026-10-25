package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.navigation.*

/** Composition owns one shared policy; it never exposes mutable policy state to Main. */
internal class RiskFeedbackComponents<Receipt>(
    frameStateLock: Any,
    phoneMountingObservationLock: Any,
    admission: RiskFeedbackAdmission,
    output: RiskFeedbackOutput,
    scheduler: RiskFeedbackScheduler,
    log: RiskFeedbackLog,
    frames: RiskFeedbackFrameDelivery<Receipt>,
) {
    private val policy = WalkSafeFeedbackPolicy()
    val delivery = RiskFeedbackDeliveryController(policy, frameStateLock, phoneMountingObservationLock,
        admission, output, scheduler, log, frames)
    val frameCoordinator = createProductionAndroidTactileFrameCoordinator(policy,
        TactileFrameFeedbackActuator { frames.deliver(it) })
    val frozenReportFrameCoordinator by lazy {
        createProductionAndroidTactileFrameCoordinator(policy, TactileFrameFeedbackActuator { _ -> })
    }
}
