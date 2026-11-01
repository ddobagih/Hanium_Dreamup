package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef
import java.util.concurrent.CompletionStage

/** Current Java consumer ownership and the retained physical receipt are distinct facts.
 * This value may outlive an Activity; it stores no wrapper, frame, listener or UI callback. */
internal class VisionConsumerLease(
    val owner: DeviceCheckOwnerRef,
    val runtime: VisionPairSnapshot,
    val claim: VisionConsumerRegistry.Claim,
    val nativeReleased: CompletionStage<VisionReleaseReceipt>,
    val consumerReleased: CompletionStage<Unit>,
    private val registry: VisionConsumerRegistry = VisionConsumerRegistry.process,
) {
    init {
        require(owner.ownerId == runtime.lease.ownerId && claim.owner.id == owner.ownerId)
        require(runtime.primaryHandle.cohortId == runtime.lease.cohortId &&
            runtime.auxiliaryHandle.cohortId == runtime.lease.cohortId)
    }
    fun permits() = registry.permits(claim)
    /** A host drain alone is never reported as native destruction. */
    fun retire(hostDrained: CompletionStage<Unit>) = registry.retire(claim, hostDrained, nativeReleased)
}
