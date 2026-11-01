package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import kr.co.hanium.dreamup.walksafe.device.DeviceCheckOwnerRef
import kr.co.hanium.dreamup.walksafe.inference.PrimaryTransferredDetector
import kr.co.hanium.dreamup.walksafe.inference.composite.JointPairReadyProof
import kr.co.hanium.dreamup.walksafe.inference.composite.JointPairTransferWitness
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamTransferredModel
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Only detach metadata crosses the screen boundary; wrappers and their listeners stay in the host. */
internal data class VisionPairDetachTokens(val primary: PrimaryTransferredDetector,
    val auxiliary: FastSamTransferredModel)

internal data class VisionPairTransferCompletion(val adopted: Boolean, val reason: String)

/** A bounded mailbox value, never a host, effect callback, Context, listener, frame or model wrapper. */
internal class VisionPairConsumerTransferTicket internal constructor(
    val id: String,
    val source: VisionConsumerLease,
    val witness: JointPairTransferWitness,
    val targetOwner: DeviceCheckOwnerRef,
    val authorityTag: String,
    val deadlineMs: Long,
) {
    internal var detached: VisionPairDetachTokens? = null
    internal val terminal = CompletableFuture<VisionPairTransferCompletion>()
    val completion: CompletionStage<VisionPairTransferCompletion> get() = terminal
    val detachTokens: VisionPairDetachTokens get() = requireNotNull(detached) { "Pair consumers have not drained" }
}

internal data class VisionPairConsumerAdoption(val consumer: VisionConsumerLease, val proof: JointPairReadyProof)

/** Implement with an application client/authority only; the watchdog retains this port. No host I/O. */
internal interface VisionPairTransferRetirementPort {
    fun ownerCurrent(owner: DeviceCheckOwnerRef): Boolean
    /** Return only the same cohort's canonical source/next lease, including retired-but-unreleased. */
    fun latestLease(source: VisionCohortLease, targetOwner: DeviceCheckOwnerRef): VisionCohortLease?
    fun requestExit(lease: VisionCohortLease, reason: String)
}

/** Scheduling and cancellation must be nonblocking; a timer cannot run native/host work. */
internal fun interface VisionPairTransferDeadlineScheduler {
    fun schedule(deadlineMs: Long, task: Runnable): AutoCloseable
}

/** Used only on the calling worker; never retained by a ticket, mailbox or watchdog. */
internal interface VisionPairTransferSourceEffects {
    fun drainSource(deadlineMs: Long): CompletionStage<VisionPairDetachTokens>
    fun park(lease: VisionCohortLease, deadlineMs: Long)
    fun transfer(lease: VisionCohortLease, targetOwner: DeviceCheckOwnerRef, deadlineMs: Long): VisionTransferResult
}

/** Attach into dormant host-owned wrappers; activation follows the registry's atomic adoption. */
internal interface VisionPairTransferTargetEffects {
    fun attachBoth(tokens: VisionPairDetachTokens, runtime: VisionTransferResult,
        deadlineMs: Long): CompletionStage<Set<String>>
    /** Must confirm native adoption, activate both wrappers, then reissue the same-model proof. */
    fun activateAndReissue(witness: JointPairTransferWitness, runtime: VisionTransferResult,
        deadlineMs: Long): JointPairReadyProof
}

/** One in-flight handoff process-wide. All retained state is ownership metadata and drain receipts. */
internal class VisionPairConsumerTransfer(
    private val registry: VisionConsumerRegistry,
    private val retirement: VisionPairTransferRetirementPort,
    private val nowMs: () -> Long,
    private val scheduler: VisionPairTransferDeadlineScheduler,
) {
    private enum class Phase { RESERVED, PREPARING, PARKED, ATTACHING, ADOPTED, FAILED }
    private class Slot(val ticket: VisionPairConsumerTransferTicket, val transfer: VisionConsumerRegistry.Transfer,
        val sourceDrained: CompletionStage<Unit>) {
        var phase = Phase.RESERVED
        var taken = false
        var runtime: VisionTransferResult? = null
        var latestLease = ticket.source.runtime.lease
        var latestClaim = ticket.source.claim
        var exitedLease: VisionCohortLease? = null
        var targetDrained: CompletionStage<Unit> = CompletableFuture.completedFuture(Unit)
        var watch: AutoCloseable? = null
        var watchRevision = 0L
    }
    private val lock = Any()
    private var slot: Slot? = null

    fun reserve(source: VisionConsumerLease, witness: JointPairTransferWitness,
        targetOwner: DeviceCheckOwnerRef, authorityTag: String, sourceHostDrained: CompletionStage<Unit>,
        deadlineMs: Long): VisionPairConsumerTransferTicket? {
        require(deadlineMs > nowMs()) { "Pair transfer deadline expired" }
        require(JointPairTransferWitness.owns(witness) && witness.sourceLease === source.runtime.lease &&
            witness.primaryHandle === source.runtime.primaryHandle && witness.auxiliaryHandle === source.runtime.auxiliaryHandle)
        require(source.owner !== targetOwner && targetOwner.ownerId != source.owner.ownerId &&
            targetOwner.sessionGeneration == source.owner.sessionGeneration &&
            targetOwner.source.binding == witness.sourceBinding && source.owner.source.binding == witness.sourceBinding)
        if (authorityTag != source.claim.owner.authority || !retirement.ownerCurrent(source.owner) ||
            !retirement.ownerCurrent(targetOwner)) return null
        val record = synchronized(lock) {
            if (slot != null) return null
            val transfer = registry.reserveTransfer(source.claim,
                VisionConsumerRegistry.Owner(targetOwner.ownerId, authorityTag), source.runtime.lease, source.runtime.handles()) ?: return null
            val ticket = VisionPairConsumerTransferTicket(UUID.randomUUID().toString(), source, witness, targetOwner,
                authorityTag, deadlineMs)
            Slot(ticket, transfer, sourceHostDrained).also { slot = it }
        }
        armDeadline(record)
        return record.ticket
    }

    /** Worker-only effects. The same deadline spans draining, native parking and durable transfer. */
    fun prepare(ticket: VisionPairConsumerTransferTicket, effects: VisionPairTransferSourceEffects) {
        val record = requireSlot(ticket)
        synchronized(lock) { checkPhase(record, Phase.RESERVED); record.phase = Phase.PREPARING }
        try {
            admit(record)
            val tokens = await(record, effects.drainSource(ticket.deadlineMs))
            validateTokens(ticket, tokens)
            await(record, record.sourceDrained)
            admit(record)
            synchronized(lock) {
                checkPhase(record, Phase.PREPARING)
                check(registry.consumersDrained(record.transfer))
                ticket.detached = tokens
            }
            effects.park(ticket.source.runtime.lease, ticket.deadlineMs)
            admit(record)
            val runtime = effects.transfer(ticket.source.runtime.lease, ticket.targetOwner, ticket.deadlineMs)
            validateRuntime(ticket, runtime)
            synchronized(lock) {
                // Retain the newly returned canonical capability even if a timer just won failure.
                record.latestLease = runtime.lease
                checkPhase(record, Phase.PREPARING)
                check(registry.park(record.transfer, runtime))
                record.runtime = runtime; record.phase = Phase.PARKED
            }
            admit(record)
        } catch (error: Throwable) { fail(record, "source_transfer_failed"); throw error }
    }

    /** Exact target/source metadata and one take; an old actor cannot claim or cancel a newer ticket. */
    fun pending(id: String): VisionPairConsumerTransferTicket? {
        val record = synchronized(lock) { slot?.takeIf { it.ticket.id == id && it.phase == Phase.PARKED && !it.taken } }
            ?: return null
        if (runCatching { admit(record) }.isFailure) return null
        return synchronized(lock) {
            record.ticket.takeIf { slot === record && record.phase == Phase.PARKED && !record.taken }
        }
    }

    /** Exact target/source metadata and one take; an old actor cannot claim or cancel a newer ticket. */
    fun take(id: String, targetOwner: DeviceCheckOwnerRef, authorityTag: String): VisionPairConsumerTransferTicket? {
        val record = synchronized(lock) {
            slot?.takeIf { it.ticket.id == id && it.ticket.targetOwner === targetOwner &&
                it.ticket.authorityTag == authorityTag && it.phase == Phase.PARKED && !it.taken }
        } ?: return null
        try { admit(record) } catch (_: Throwable) { fail(record, "target_not_current"); return null }
        return synchronized(lock) {
            if (slot !== record || record.phase != Phase.PARKED || record.taken) null
            else { record.taken = true; record.ticket }
        }
    }

    fun adopt(ticket: VisionPairConsumerTransferTicket, targetOwner: DeviceCheckOwnerRef, authorityTag: String,
        targetHostDrained: CompletionStage<Unit>,
        effects: VisionPairTransferTargetEffects): VisionPairConsumerAdoption {
        val record = requireSlot(ticket)
        val runtime = synchronized(lock) {
            checkPhase(record, Phase.PARKED); check(record.taken)
            require(targetOwner === ticket.targetOwner && authorityTag == ticket.authorityTag) { "Target owner differs" }
            record.phase = Phase.ATTACHING
            record.targetDrained = targetHostDrained // Before the first wrapper can be attached.
            requireNotNull(record.runtime)
        }
        try {
            admit(record)
            val ids = await(record, effects.attachBoth(ticket.detachTokens, runtime, ticket.deadlineMs))
            admit(record)
            val adopted = synchronized(lock) {
                checkPhase(record, Phase.ATTACHING)
                require(ids == runtime.handles.map { it.modelInstanceId }.toSet())
                requireNotNull(registry.adopt(record.transfer,
                    VisionConsumerRegistry.Owner(ticket.targetOwner.ownerId, ticket.authorityTag), ids)).also {
                    // A failure from this point must retire the target claim, never the old source claim.
                    record.latestClaim = it.claim
                    record.latestLease = it.runtime.lease
                }
            }
            admit(record)
            val proof = effects.activateAndReissue(ticket.witness, runtime, ticket.deadlineMs)
            require(JointPairReadyProof.ownsProof(proof) && proof.measurementId == ticket.witness.measurementId &&
                proof.sourceBinding == ticket.witness.sourceBinding && proof.snapshot.lease === runtime.lease &&
                proof.snapshot.primaryHandle.modelInstanceId == ticket.witness.primaryHandle.modelInstanceId &&
                proof.snapshot.auxiliaryHandle.modelInstanceId == ticket.witness.auxiliaryHandle.modelInstanceId)
            check(proof.bindConsumerGateOnce { registry.permits(adopted.claim) } && proof.isCurrent())
            admit(record)
            val consumer = VisionConsumerLease(ticket.targetOwner, proof.snapshot, adopted.claim,
                ticket.source.nativeReleased, ticket.source.consumerReleased, registry)
            val watch = synchronized(lock) {
                checkPhase(record, Phase.ATTACHING)
                record.phase = Phase.ADOPTED; slot = null
                record.watch.also { record.watch = null }
            }
            runCatching { watch?.close() }
            ticket.terminal.complete(VisionPairTransferCompletion(true, "adopted"))
            return VisionPairConsumerAdoption(consumer, proof)
        } catch (error: Throwable) { fail(record, "target_adoption_failed"); throw error }
    }

    fun cancel(ticket: VisionPairConsumerTransferTicket, reason: String): Boolean {
        val record = synchronized(lock) { slot?.takeIf { it.ticket === ticket } } ?: return false
        return fail(record, reason.take(120))
    }

    private fun admit(record: Slot) {
        val ticket = record.ticket
        check(nowMs() < ticket.deadlineMs) { "Pair transfer deadline expired" }
        check(retirement.ownerCurrent(ticket.source.owner) && retirement.ownerCurrent(ticket.targetOwner)) { "Pair transfer owner retired" }
        synchronized(lock) { check(slot === record && record.phase !in setOf(Phase.ADOPTED, Phase.FAILED)) { "Pair transfer retired" } }
    }
    private fun requireSlot(ticket: VisionPairConsumerTransferTicket): Slot = synchronized(lock) {
        requireNotNull(slot?.takeIf { it.ticket === ticket }) { "Unknown or completed pair transfer" }
    }
    private fun checkPhase(record: Slot, phase: Phase) {
        check(slot === record && record.phase == phase) { "Pair transfer is not in $phase" }
    }
    private fun armDeadline(record: Slot) {
        val revision = synchronized(lock) {
            if (slot !== record || record.phase in setOf(Phase.ADOPTED, Phase.FAILED)) return
            ++record.watchRevision
        }
        try {
            val watch = scheduler.schedule(record.ticket.deadlineMs, Runnable {
                if (nowMs() < record.ticket.deadlineMs) armDeadline(record)
                else fail(record, "transfer_deadline")
            })
            val previous = synchronized(lock) {
                if (slot === record && record.watchRevision == revision && record.phase !in setOf(Phase.ADOPTED, Phase.FAILED))
                    record.watch.also { record.watch = watch }
                else watch
            }
            runCatching { previous?.close() }
        } catch (_: Throwable) { fail(record, "transfer_watchdog_unavailable") }
    }
    private fun <T> await(record: Slot, future: CompletionStage<T>): T {
        admit(record)
        try {
            CompletableFuture.anyOf(future.toCompletableFuture(), record.ticket.terminal)
                .get((record.ticket.deadlineMs - nowMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            admit(record)
            return future.toCompletableFuture().get(0, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) { throw error.cause ?: error }
    }
    private fun fail(record: Slot, reason: String): Boolean {
        val plan = synchronized(lock) {
            if (slot !== record || record.phase == Phase.ADOPTED) return false
            val first = record.phase != Phase.FAILED
            record.phase = Phase.FAILED
            val watch = record.watch.also { record.watch = null }
            FailurePlan(first, record.latestLease, record.latestClaim, record.sourceDrained, record.targetDrained, watch)
        }
        runCatching { plan.watch?.close() }
        val source = record.ticket.source.runtime.lease
        val latest = runCatching { retirement.latestLease(source, record.ticket.targetOwner) }.getOrNull()
            ?.takeIf { it.cohortId == source.cohortId && (it === source || it.ownerId == record.ticket.targetOwner.ownerId &&
                source.ownerEpoch < Long.MAX_VALUE && it.ownerEpoch == source.ownerEpoch + 1) &&
                it.ownerEpoch >= plan.lease.ownerEpoch } ?: plan.lease
        val exit = synchronized(lock) {
            if (record.exitedLease === latest || (record.exitedLease?.ownerEpoch ?: 0) > latest.ownerEpoch) false
            else { record.exitedLease = latest; true }
        }
        if (exit) runCatching { retirement.requestExit(latest, "pair_transfer_$reason") }
        val hostDrained = CompletableFuture.allOf(plan.sourceDrained.toCompletableFuture(), plan.targetDrained.toCompletableFuture()).thenApply { Unit }
        registry.retire(plan.claim, hostDrained, record.ticket.source.nativeReleased)
        if (plan.first) {
            record.ticket.source.consumerReleased.whenComplete { _, error ->
                if (error == null) synchronized(lock) { if (slot === record && record.phase == Phase.FAILED) slot = null }
            }
            record.ticket.terminal.complete(VisionPairTransferCompletion(false, reason))
        }
        return plan.first
    }
    private data class FailurePlan(val first: Boolean, val lease: VisionCohortLease,
        val claim: VisionConsumerRegistry.Claim, val sourceDrained: CompletionStage<Unit>,
        val targetDrained: CompletionStage<Unit>, val watch: AutoCloseable?)

    private fun validateTokens(ticket: VisionPairConsumerTransferTicket, tokens: VisionPairDetachTokens) {
        require(!tokens.primary.isConsumed && !tokens.auxiliary.isConsumed &&
            tokens.primary.oldLease === ticket.source.runtime.lease && tokens.auxiliary.oldLease === ticket.source.runtime.lease &&
            tokens.primary.oldHandles.single().modelInstanceId == ticket.witness.primaryHandle.modelInstanceId &&
            tokens.auxiliary.oldHandle.modelInstanceId == ticket.witness.auxiliaryHandle.modelInstanceId)
    }
    private fun validateRuntime(ticket: VisionPairConsumerTransferTicket, runtime: VisionTransferResult) {
        val source = ticket.source.runtime
        require(source.lease.ownerEpoch < Long.MAX_VALUE && runtime.lease.cohortId == source.lease.cohortId &&
            runtime.lease.ownerId == ticket.targetOwner.ownerId && runtime.lease.ownerEpoch == source.lease.ownerEpoch + 1 &&
            runtime.handles.size == 2 && runtime.handles.all { it.cohortId == source.lease.cohortId && it.ownerEpoch == runtime.lease.ownerEpoch } &&
            runtime.handles.single { it.role == VisionRole.PRIMARY }.modelInstanceId == source.primaryHandle.modelInstanceId &&
            runtime.handles.single { it.role == VisionRole.FASTSAM }.modelInstanceId == source.auxiliaryHandle.modelInstanceId)
    }
    private fun VisionPairSnapshot.handles() = listOf(primaryHandle, auxiliaryHandle)
}
