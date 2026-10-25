package kr.co.hanium.dreamup.walksafe.navigation.capture

import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryReservation
import kr.co.hanium.dreamup.walksafe.feedback.delivery.TactileReferenceDeliveryController

/** Production admission around the actual reference/Actor receipt and native DONE owner. */
internal class TactileDotReferenceDispatchController<L, Receipt>(
    private val reference: () -> TactileReferenceDeliveryController<Receipt>,
    private val authority: TactileDotDispatchAuthority<L>,
    private val actor: TactileDotDispatchActor,
) {
    /**
     * Uses the production owner/metric/device/startup/safety checks. Only the native AR observation
     * and platform scheduling/clock/effect boundaries are supplied; no output permission is injected.
     */
    fun maybeSpeak(
        owner: TactileDotReferenceDeliveryOwner,
        arLease: L,
        observeRuntimeAr: () -> L,
        nowElapsedRealtimeMs: () -> Long,
        onUiThread: (() -> Unit) -> Unit,
        onDispatchResult: ((Boolean) -> Unit)? = null,
        sourceIsCurrent: (() -> Boolean)? = null,
    ) {
        onUiThread {
            val ownerIsCurrent = {
                authority.feedbackLifecycleCurrent(owner.feedbackLifecycleGeneration) &&
                    authority.frameCurrent(owner.frameGeneration, owner.epoch) &&
                    authority.leaseCurrent(arLease, observeRuntimeAr)
            }
            var reserved: TactileDotReferenceDeliveryReservation? = null
            val accepted = reference().dispatchTactileDotReference(
                owner = owner,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                outputIsCurrent = {
                    val safety = authority.safety(nowElapsedRealtimeMs())
                    sourceIsCurrent?.invoke() != false && ownerIsCurrent() &&
                        authority.metricOutputAllowed(owner.arGeneration, observeRuntimeAr) &&
                        reference().dotReferenceDeviceGateAllowsSpeech(authority.deviceGate(
                            owner.arGeneration, observeRuntimeAr,
                        )) &&
                        authority.voiceFeatureEnabled() &&
                        authority.offlineTtsAllowed() &&
                        safety.epoch == owner.epoch && safety.safetyOutputsAllowed &&
                        !authority.guidanceVoiceInputActive()
                },
                completionOwnerIsCurrent = ownerIsCurrent,
                onReserved = { reserved = it },
                speak = { notice, current, completed, failed ->
                    val reservation = requireNotNull(reserved)
                    val actor = actor.ensureActor()
                    actor.speakAdvisoryWithReceipt(
                        message = notice.message,
                        validUntilMs = notice.validUntilElapsedRealtimeMs,
                        isStillValid = current,
                        onRegistered = { receipt ->
                            reference().registerTactileDotReferenceSpeech(reservation, actor, receipt, ownerIsCurrent)
                        },
                        onCompleted = completed,
                        onFailed = failed,
                        onCancelled = failed,
                    )
                },
            )
            // This is admission only; native START/DONE remain owned by the existing callbacks.
            onDispatchResult?.invoke(accepted)
        }
    }
}
