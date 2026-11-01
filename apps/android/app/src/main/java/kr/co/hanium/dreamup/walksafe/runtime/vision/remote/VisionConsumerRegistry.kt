package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Java consumers and native handles have different lifetimes. This registry retains only IDs
 * and receipts; an Activity, listener, frame or model wrapper must never be stored here. */
internal class VisionConsumerRegistry {
    data class Owner(val id: String, val authority: String) {
        init { require(id.isNotBlank() && authority.isNotBlank()) }
    }
    class Claim internal constructor(val owner: Owner, internal val serial: Long)
    class Transfer internal constructor(internal val source: Claim, val target: Owner,
        internal val cohort: String, internal val epoch: Long, internal val models: Set<String>)
    data class Adoption(val claim: Claim, val runtime: VisionTransferResult)
    private enum class Phase { OWNED, DRAINING, PARKED, RETIRING }
    private class Slot(var claim: Claim, var lease: VisionCohortLease, val models: Set<String>) {
        var phase = Phase.OWNED
        val released = CompletableFuture<Unit>()
        var transfer: Transfer? = null
        var consumerDrained = false
        var runtime: VisionTransferResult? = null
    }
    private var nextSerial = 0L
    private var slot: Slot? = null

    @Synchronized fun acquire(owner: Owner, lease: VisionCohortLease, models: List<VisionModelHandle>): Claim? {
        if (slot != null) return null
        require(lease.ownerId == owner.id)
        require(models.isNotEmpty() && models.all { it.cohortId == lease.cohortId && it.ownerEpoch == lease.ownerEpoch })
        val ids = models.map { it.modelInstanceId }.toSet()
        require(ids.size == models.size)
        return Claim(owner, ++nextSerial).also { slot = Slot(it, lease, ids) }
    }
    @Synchronized fun permits(claim: Claim) = slot?.let { it.claim === claim && it.phase == Phase.OWNED } == true
    @Synchronized fun pendingRelease(): CompletionStage<Unit>? = slot?.released

    /** Reservations revoke old admission without ever opening an unowned allocation gap. */
    @Synchronized fun reserveTransfer(claim: Claim, target: Owner, lease: VisionCohortLease,
        models: List<VisionModelHandle>): Transfer? {
        val current = slot ?: return null
        if (current.claim !== claim || current.phase != Phase.OWNED || target == claim.owner ||
            target.authority != claim.owner.authority) return null
        require(current.lease == lease && lease.ownerEpoch < Long.MAX_VALUE)
        require(models.isNotEmpty() && models.all { it.cohortId == lease.cohortId && it.ownerEpoch == lease.ownerEpoch })
        require(models.map { it.modelInstanceId }.toSet().size == models.size)
        require(models.map { it.modelInstanceId }.toSet() == current.models)
        return Transfer(claim, target, lease.cohortId, lease.ownerEpoch, current.models).also {
            current.transfer = it
            current.phase = Phase.DRAINING
        }
    }

    /** Call only with the old copy/invoke/decode/listener barrier, not a semantic close result. */
    @Synchronized fun consumersDrained(transfer: Transfer): Boolean {
        val current = slot ?: return false
        if (current.transfer !== transfer || current.phase != Phase.DRAINING) return false
        current.consumerDrained = true
        return true
    }

    @Synchronized fun park(transfer: Transfer, runtime: VisionTransferResult): Boolean {
        val current = slot ?: return false
        if (current.transfer !== transfer || current.phase != Phase.DRAINING || !current.consumerDrained) return false
        require(runtime.lease.cohortId == transfer.cohort && runtime.lease.ownerId == transfer.target.id)
        require(runtime.lease.ownerEpoch == transfer.epoch + 1)
        require(runtime.handles.isNotEmpty() && runtime.handles.all {
            it.cohortId == transfer.cohort && it.ownerEpoch == runtime.lease.ownerEpoch
        })
        require(runtime.handles.map { it.modelInstanceId }.toSet() == transfer.models &&
            runtime.handles.size == transfer.models.size)
        current.runtime = runtime
        current.phase = Phase.PARKED
        return true
    }

    /** Both wrappers must be attached before the caller admits any new frames. */
    @Synchronized fun adopt(transfer: Transfer, target: Owner, attachedModelIds: Set<String>): Adoption? {
        val current = slot ?: return null
        if (current.transfer !== transfer || current.phase != Phase.PARKED || target != transfer.target ||
            attachedModelIds != current.models) return null
        val claim = Claim(target, ++nextSerial)
        val runtime = requireNotNull(current.runtime)
        current.claim = claim
        current.lease = runtime.lease
        current.phase = Phase.OWNED
        current.transfer = null
        current.runtime = null
        current.consumerDrained = false
        return Adoption(claim, runtime)
    }

    /** Pass physical receipts, never operation/close-result failures. Failed proofs keep the fence. */
    fun retire(claim: Claim, consumerDrained: CompletionStage<Unit>,
        nativeReleased: CompletionStage<VisionReleaseReceipt>): Boolean {
        val retiring = synchronized(this) {
            val current = slot ?: return false
            if (current.claim !== claim || current.phase == Phase.RETIRING) return false
            current.phase = Phase.RETIRING
            current.transfer = null
            current.runtime = null
            current
        }
        val matchingNativeRelease = nativeReleased.thenApply { receipt ->
            require(receipt.cohortId == retiring.lease.cohortId) { "Native receipt belongs to a different cohort" }
            Unit
        }
        CompletableFuture.allOf(consumerDrained.toCompletableFuture(), matchingNativeRelease.toCompletableFuture())
            .whenComplete { _, failure ->
                if (failure == null) {
                    val finished = synchronized(this) {
                        if (slot !== retiring || retiring.claim !== claim || retiring.phase != Phase.RETIRING) false
                        else { slot = null; true }
                    }
                    if (finished) retiring.released.complete(Unit)
                }
            }
        return true
    }
}
