package kr.co.hanium.dreamup.walksafe.device.postlogin

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kr.co.hanium.dreamup.walksafe.device.*

/** A resume permit is not consumption: retain the old proof until a live receiver opens the host. */
internal object DeviceRuntimeLeaseRecovery {
    fun <R : Any> retirementAfterCompletion(pending: R?, captured: R?, current: Boolean,
        primaryClosing: Boolean): R? =
        if (current && !primaryClosing && pending === captured) null else pending

    fun <R : Any> retirementAfterReservation(pending: R?, captured: R?, reserved: Boolean,
        primaryClosing: Boolean): R? =
        if (reserved && !primaryClosing && pending === captured) null else pending
}

/**
 * Armed at reservation, triggered by actual owner destruction. Holds only release metadata;
 * no Activity, preparation request, presentation callback or allocation factory belongs here.
 */
internal object DeviceRuntimeLeaseTeardown {
    fun <O : Any, R : Any> afterOwnerGone(
        ownerGone: CompletionStage<CompletionStage<Boolean>>,
        primaryHostDrained: CompletionStage<Unit>, primaryReleased: CompletionStage<Unit>,
        oldOwner: O?, snapshot: (O) -> R?, knownRetirement: R?,
        actualReleased: (R) -> CompletionStage<*>, allocationAvailable: (R) -> CompletionStage<*>,
        requestExit: (R) -> Unit,
        hostDrained: CompletableFuture<Unit>, consumerDrained: CompletableFuture<Unit>?,
        consumerReleased: CompletionStage<*>?,
        trace: DeviceCheckTraceSink = DeviceCheckTraceSink.NONE, flowId: Long = 0L,
    ): CompletableFuture<Unit> = ownerGone.thenCompose { auxiliaryReleased ->
        fun event(stage: DeviceCheckTraceStage, outcome: DeviceCheckTraceOutcome,
            reason: DeviceCheckTraceReason = DeviceCheckTraceReason.NONE) =
            trace.emit(DeviceCheckTraceEvent(flowId, stage, outcome, reason))
        fun watch(stage: DeviceCheckTraceStage, proof: CompletionStage<*>, reason: DeviceCheckTraceReason) {
            event(stage, DeviceCheckTraceOutcome.WAITING)
            proof.whenComplete { _, error -> event(stage,
                if (error == null) DeviceCheckTraceOutcome.SUCCEEDED else DeviceCheckTraceOutcome.FAILED,
                if (error == null) DeviceCheckTraceReason.NONE else reason) }
        }
        event(DeviceCheckTraceStage.LEASE_PREPARATION, DeviceCheckTraceOutcome.OWNER_GONE,
            DeviceCheckTraceReason.OWNER_GONE)
        // An already captured old cohort can be stopped now. Query again after the serial host
        // barrier to include an old creation that had not acquired its cohort at destruction.
        knownRetirement?.let { runCatching { requestExit(it) } }
        val physical = DeviceRuntimeLeaseRetirement.afterHostDrain(oldOwner, primaryHostDrained,
            snapshot = snapshot, actualReleased = { captured ->
                val native = actualReleased(captured)
                val available = allocationAvailable(captured)
                watch(DeviceCheckTraceStage.NATIVE_RELEASE, native, DeviceCheckTraceReason.NATIVE_UNPROVEN)
                watch(DeviceCheckTraceStage.ALLOCATION_AVAILABLE, available, DeviceCheckTraceReason.JOURNAL_PENDING)
                CompletableFuture.allOf(native.toCompletableFuture(), available.toCompletableFuture())
            }, requestExit = requestExit, knownRetirement = knownRetirement)
        val auxiliary = auxiliaryReleased.thenApply { check(it) { "Auxiliary release unconfirmed" }; Unit }
        watch(DeviceCheckTraceStage.PRIMARY_DRAIN, primaryHostDrained, DeviceCheckTraceReason.HOST_UNPROVEN)
        watch(DeviceCheckTraceStage.PRIMARY_CLOSE, primaryReleased, DeviceCheckTraceReason.HOST_UNPROVEN)
        watch(DeviceCheckTraceStage.AUXILIARY_DRAIN, auxiliary, DeviceCheckTraceReason.AUXILIARY_UNPROVEN)
        CompletableFuture.allOf(primaryReleased.toCompletableFuture(), auxiliary.toCompletableFuture(), physical)
            .thenCompose {
                hostDrained.complete(Unit)
                consumerDrained?.complete(Unit)
                val registry = consumerReleased ?: CompletableFuture.completedFuture(Unit)
                watch(DeviceCheckTraceStage.CONSUMER_RELEASE, registry, DeviceCheckTraceReason.HOST_UNPROVEN)
                registry.thenApply { Unit }
            }
    }.toCompletableFuture()
}
