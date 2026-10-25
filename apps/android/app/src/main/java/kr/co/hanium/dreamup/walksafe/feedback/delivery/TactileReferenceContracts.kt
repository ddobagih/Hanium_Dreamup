package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** These calls run in the existing frame publication transaction, not a copied state snapshot. */
internal interface TactileReferenceSource<Receipt> {
    fun currentFrame(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun currentReceipt(receipt: Receipt): Boolean
    fun clearReadyRiskInput()
    fun updateLocalRouteDiagnostic(message: String)
}

internal interface TactileReferenceOutput {
    fun currentActor(): AndroidFeedbackActuator?
    fun isMainThread(): Boolean
    fun postRetiredCancellation(action: () -> Unit)
}
