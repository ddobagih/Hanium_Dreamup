package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*

/** Single reference policy/actor-receipt owner; shares the producer's existing publication lock. */
internal class TactileReferenceDeliveryController<Receipt>(
    private val frameStateLock: Any,
    private val source: TactileReferenceSource<Receipt>,
    private val output: TactileReferenceOutput,
    private val nowMs: () -> Long,
) {
    private val tactileDotReferenceDeliveryPolicy = TactileDotReferenceDeliveryPolicy()

    /** Guarded by frameStateLock; the receipt is installed only by Actor's registration callback. */
    private var tactileDotReferenceSpeech: TactileDotReferenceSpeech? = null

    private val crosswalkReferenceDeliveryPolicy = CrosswalkReferenceDeliveryPolicy()

    private data class TactileDotReferenceSpeech(
        val reservation: TactileDotReferenceDeliveryReservation,
        val actor: AndroidFeedbackActuator,
        val receipt: AdvisorySpeechReceipt,
    )

    fun updateCrosswalkReferenceObservation(
        notice: CrosswalkReferenceNotice?,
        owner: CrosswalkReferenceDeliveryOwner,
        nowElapsedRealtimeMs: Long,
    ) = crosswalkReferenceDeliveryPolicy.updateObservation(notice, owner, nowElapsedRealtimeMs)

    fun crosswalkReferenceDeviceGateAllowsSpeech(state: DeviceGateState): Boolean =
        state.allows(DeviceGatePurpose.ACTUATOR_READINESS)

    fun dispatchCrosswalkReference(
        owner: CrosswalkReferenceDeliveryOwner,
        nowElapsedRealtimeMs: () -> Long,
        outputIsCurrent: () -> Boolean,
        routeIsCurrent: (CrosswalkReferenceNotice) -> Boolean,
        completionOwnerIsCurrent: () -> Boolean = { true },
        speak: (CrosswalkReferenceNotice, () -> Boolean, () -> Unit, () -> Unit) -> Boolean,
    ): Boolean {
        if (!outputIsCurrent()) return false
        val reservation = crosswalkReferenceDeliveryPolicy.reserveCurrent(owner, nowElapsedRealtimeMs())
            ?: return false
        val current = {
            outputIsCurrent() && routeIsCurrent(reservation.notice) &&
                crosswalkReferenceDeliveryPolicy.isCurrent(reservation, nowElapsedRealtimeMs())
        }
        val failed = { crosswalkReferenceDeliveryPolicy.fail(reservation); Unit }
        if (!current()) {
            failed()
            return false
        }
        val accepted = speak(reservation.notice, current, {
            if (completionOwnerIsCurrent()) crosswalkReferenceDeliveryPolicy.complete(reservation, nowElapsedRealtimeMs())
            else crosswalkReferenceDeliveryPolicy.fail(reservation)
            Unit
        }, failed)
        if (!accepted) failed()
        return accepted
    }

    /** Same GL publication boundary; output ownership is checked before either reference changes. */
    fun publishTactileFrameReferenceObservation(
        tactileFrame: TactileSnapshotFrameResult,
        owner: TactileDotReferenceDeliveryOwner,
        nowElapsedRealtimeMs: () -> Long = nowMs,
    ): Boolean = publishConsumedTactileFrameReferenceObservation(
        tactileFrame, owner, nowElapsedRealtimeMs = nowElapsedRealtimeMs,
    )

    fun publishConsumedTactileFrameReferenceObservation(
        tactileFrame: TactileSnapshotFrameResult,
        owner: TactileDotReferenceDeliveryOwner,
        sourceReceipt: Receipt? = null,
        sourceIsCurrent: (() -> Boolean)? = null,
        nowElapsedRealtimeMs: () -> Long = nowMs,
    ): Boolean {
        var retired: TactileDotReferenceSpeech? = null
        val published = synchronized(frameStateLock) {
            if (!source.currentFrame(owner.frameGeneration, owner.epoch) ||
                (sourceReceipt != null && !source.currentReceipt(sourceReceipt)) ||
                sourceIsCurrent?.invoke() == false) return@synchronized false
            if (sourceReceipt != null) source.clearReadyRiskInput()
            val crosswalkOwner = CrosswalkReferenceDeliveryOwner(
                epoch = owner.epoch,
                feedbackLifecycleGeneration = owner.feedbackLifecycleGeneration,
                frameGeneration = owner.frameGeneration,
                arGeneration = owner.arGeneration,
            )
            updateCrosswalkReferenceObservation(
                tactileFrame.guidance.crosswalkReferenceNotice,
                crosswalkOwner,
                nowElapsedRealtimeMs(),
            )
            retired = updateTactileDotReferenceObservationLocked(
                tactileFrame.guidance.dotReferenceNotice,
                owner,
                nowElapsedRealtimeMs(),
            )
            source.updateLocalRouteDiagnostic(
                "localRoute=${tactileFrame.guidance.decision.mode.wireValue}:${tactileFrame.guidance.decision.reason} " +
                    tactileFrame.guidance.observationDiagnosticsText())
            true
        }
        retired?.let(::cancelRetiredTactileDotReferenceSpeech)
        return published
    }

    fun updateTactileDotReferenceObservation(
        notice: TactileDotReferenceNotice?,
        owner: TactileDotReferenceDeliveryOwner,
        nowElapsedRealtimeMs: Long,
    ) {
        val retired = synchronized(frameStateLock) {
            updateTactileDotReferenceObservationLocked(notice, owner, nowElapsedRealtimeMs)
        }
        retired?.let(::cancelRetiredTactileDotReferenceSpeech)
    }

    private fun updateTactileDotReferenceObservationLocked(
        notice: TactileDotReferenceNotice?,
        owner: TactileDotReferenceDeliveryOwner,
        nowElapsedRealtimeMs: Long,
    ): TactileDotReferenceSpeech? = detachTactileDotReferenceSpeechLocked(
        tactileDotReferenceDeliveryPolicy.updateObservation(notice, owner, nowElapsedRealtimeMs),
    )

    fun resetTactileDotReferenceDelivery(owner: TactileDotReferenceDeliveryOwner? = null) {
        val retired = synchronized(frameStateLock) {
            detachTactileDotReferenceSpeechLocked(tactileDotReferenceDeliveryPolicy.reset(owner))
        }
        retired?.let(::cancelRetiredTactileDotReferenceSpeech)
    }

    private fun detachTactileDotReferenceSpeechLocked(
        reservation: TactileDotReferenceDeliveryReservation?,
    ): TactileDotReferenceSpeech? {
        val current = tactileDotReferenceSpeech ?: return null
        if (reservation !== current.reservation) return null
        tactileDotReferenceSpeech = null
        return current
    }

    private fun retireTactileDotReferenceSpeech(reservation: TactileDotReferenceDeliveryReservation) {
        synchronized(frameStateLock) { detachTactileDotReferenceSpeechLocked(reservation) }
    }

    private fun cancelRetiredTactileDotReferenceSpeech(speech: TactileDotReferenceSpeech) {
        // Keep Actor/native work off GL and outside a nested frame-state transaction.
        if (Thread.holdsLock(frameStateLock) || !output.isMainThread()) {
            output.postRetiredCancellation { speech.actor.cancelAdvisorySpeech(speech.receipt) }
        } else {
            speech.actor.cancelAdvisorySpeech(speech.receipt)
        }
    }

    fun registerTactileDotReferenceSpeech(
        reservation: TactileDotReferenceDeliveryReservation,
        actor: AndroidFeedbackActuator,
        receipt: AdvisorySpeechReceipt,
        ownerIsCurrent: () -> Boolean,
    ) {
        val ownerCurrent = ownerIsCurrent()
        val bound = synchronized(frameStateLock) {
            if (!ownerCurrent || output.currentActor() !== actor ||
                !tactileDotReferenceDeliveryPolicy.isSourceCurrent(reservation)
            ) false else {
                tactileDotReferenceSpeech = TactileDotReferenceSpeech(reservation, actor, receipt)
                true
            }
        }
        if (!bound) cancelRetiredTactileDotReferenceSpeech(TactileDotReferenceSpeech(reservation, actor, receipt))
    }

    fun dotReferenceDeviceGateAllowsSpeech(state: DeviceGateState): Boolean =
        state.allows(DeviceGatePurpose.ACTUATOR_READINESS)

    fun dispatchTactileDotReference(
        owner: TactileDotReferenceDeliveryOwner,
        nowElapsedRealtimeMs: () -> Long,
        outputIsCurrent: () -> Boolean,
        completionOwnerIsCurrent: () -> Boolean = { true },
        onReserved: ((TactileDotReferenceDeliveryReservation) -> Unit)? = null,
        speak: (TactileDotReferenceNotice, () -> Boolean, () -> Unit, () -> Unit) -> Boolean,
    ): Boolean {
        if (!outputIsCurrent()) return false
        val reservation = tactileDotReferenceDeliveryPolicy.reserveCurrent(owner, nowElapsedRealtimeMs())
            ?: return false
        onReserved?.invoke(reservation)
        val current = {
            outputIsCurrent() && tactileDotReferenceDeliveryPolicy.isSourceCurrent(reservation) &&
                tactileDotReferenceDeliveryPolicy.isCurrent(reservation, nowElapsedRealtimeMs())
        }
        val failed = {
            tactileDotReferenceDeliveryPolicy.fail(reservation)
            retireTactileDotReferenceSpeech(reservation)
            Unit
        }
        if (!current()) {
            failed()
            return false
        }
        val accepted = speak(reservation.notice, current, {
            if (completionOwnerIsCurrent()) tactileDotReferenceDeliveryPolicy.complete(reservation, nowElapsedRealtimeMs())
            else tactileDotReferenceDeliveryPolicy.fail(reservation)
            retireTactileDotReferenceSpeech(reservation)
            Unit
        }, failed)
        if (!accepted) failed()
        return accepted
    }

    fun resetCrosswalk() = crosswalkReferenceDeliveryPolicy.reset()
}
