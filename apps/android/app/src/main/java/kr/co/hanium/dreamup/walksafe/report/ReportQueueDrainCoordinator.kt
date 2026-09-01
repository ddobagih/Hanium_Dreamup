package kr.co.hanium.dreamup.walksafe.report

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation

internal interface ReportQueueTransport {
    fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?>
    fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt>
}

internal class AndroidReportQueueTransport(
    private val uploader: AndroidReportUploader,
    private val session: GatewayFieldSession,
    private val consentConfirmation: IntegratedConsentConfirmation,
    private val networkBinding: IntegratedConsentNetworkBinding,
    private val permitProvider: (ReportTransferPurpose) -> ReportUploadPermit?,
) : ReportQueueTransport {
    private val approvedGatewayOrigin = requireNotNull(
        approvedReportQueueGatewayOriginOrNull(session.gatewayBaseUrl),
    )

    override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> =
        uploader.queuedStatusCall(
            permit = requireNotNull(permitProvider(report.purpose())),
            session = session,
            consentConfirmation = consentConfirmation,
            networkBinding = networkBinding,
            report = report,
            approvedGatewayOrigin = approvedGatewayOrigin,
        )

    override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> =
        uploader.queuedUploadCall(
            permit = requireNotNull(permitProvider(report.purpose())),
            session = session,
            consentConfirmation = consentConfirmation,
            networkBinding = networkBinding,
            report = report,
            approvedGatewayOrigin = approvedGatewayOrigin,
        )

    private fun QueuedReport.purpose(): ReportTransferPurpose =
        if (priority == ReportQueuePriority.EXPLICIT) {
            ReportTransferPurpose.EXPLICIT
        } else {
            ReportTransferPurpose.AUTOMATIC
        }

    init { require(session.actorId.isNotBlank()) }
}

internal enum class ReportQueueDrainOutcome {
    DELETED_AFTER_STATUS,
    DELETED_AFTER_UPLOAD,
    RECEIPT_REJECTED,
    CANCELLED,
}

/** Owns one status-then-POST attempt. Callers must revalidate on every movement/state change. */
internal class ReportQueueDrainCoordinator(
    private val store: AndroidReportQueueStore,
    private val policy: ReportQueueDrainPolicy = ReportQueueDrainPolicy(),
) {
    private data class ActiveDrain(
        val lease: ReportQueueDrainLease,
        val call: CancellableNetworkCall<ReportQueueDrainOutcome>,
    )

    private var activeDrain: ActiveDrain? = null

    @Synchronized
    fun startNext(
        contextProvider: () -> ReportQueueDrainContext,
        transport: ReportQueueTransport,
        beforeDeleteAfterReceipt: (QueuedReport) -> Boolean = { true },
    ): CancellableNetworkCall<ReportQueueDrainOutcome>? {
        if (activeDrain != null) return null
        val initialContext = contextProvider()
        val report = store.nextForRecoveryDrain(initialContext.reporterActorId) ?: return null
        val lease = policy.acquire(report, initialContext).lease ?: return null
        val nested = AtomicReference<CancellableNetworkCall<*>?>()
        lateinit var outer: CancellableNetworkCall<ReportQueueDrainOutcome>
        outer = CancellableNetworkCall(
            executeBlock = execute@{
                try {
                    if (!policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED
                    }
                    val statusCall = transport.statusCall(report)
                    nested.set(statusCall)
                    if (
                        outer.isCancelled() ||
                        !policy.isCurrent(lease, contextProvider())
                    ) {
                        nested.compareAndSet(statusCall, null)
                        statusCall.cancel()
                        return@execute ReportQueueDrainOutcome.CANCELLED
                    }
                    val statusReceipt = statusCall.execute()
                    nested.compareAndSet(statusCall, null)
                    if (!policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED
                    }
                    if (statusReceipt != null) {
                        return@execute if (
                            beforeDeleteAfterReceipt(report) &&
                            store.deleteAfterReceipt(lease.reporterActorId, statusReceipt)
                        ) {
                            ReportQueueDrainOutcome.DELETED_AFTER_STATUS
                        } else {
                            ReportQueueDrainOutcome.RECEIPT_REJECTED
                        }
                    }
                    val uploadCall = transport.uploadCall(report)
                    nested.set(uploadCall)
                    if (
                        outer.isCancelled() ||
                        !policy.isCurrent(lease, contextProvider())
                    ) {
                        nested.compareAndSet(uploadCall, null)
                        uploadCall.cancel()
                        return@execute ReportQueueDrainOutcome.CANCELLED
                    }
                    val uploadReceipt = uploadCall.execute()
                    nested.compareAndSet(uploadCall, null)
                    if (!policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED
                    }
                    if (
                        beforeDeleteAfterReceipt(report) &&
                        store.deleteAfterReceipt(lease.reporterActorId, uploadReceipt)
                    ) {
                        ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD
                    } else {
                        ReportQueueDrainOutcome.RECEIPT_REJECTED
                    }
                } catch (_: CancellationException) {
                    ReportQueueDrainOutcome.CANCELLED
                } finally {
                    policy.release(lease)
                    synchronized(this) {
                        if (activeDrain?.call === outer) activeDrain = null
                    }
                }
            },
            cancelBlock = {
                nested.getAndSet(null)?.cancel()
                policy.release(lease)
            },
        )
        activeDrain = ActiveDrain(lease, outer)
        return outer
    }

    @Synchronized
    fun revalidate(context: ReportQueueDrainContext): Boolean {
        val active = activeDrain ?: return false
        if (policy.isCurrent(active.lease, context)) return true
        activeDrain = null
        active.call.cancel()
        return false
    }

    @Synchronized
    fun onConsentRevoked(consentReceiptSha256: String): Boolean {
        cancelActiveLocked()
        return store.onConsentRevoked(consentReceiptSha256)
    }

    @Synchronized
    fun onAutomaticReportingRevoked(consentReceiptSha256: String): Boolean {
        cancelActiveLocked()
        return store.onAutomaticReportingRevoked(consentReceiptSha256)
    }

    @Synchronized
    fun onAccountDeleted(): Boolean {
        cancelActiveLocked()
        return store.onAccountDeleted()
    }

    @Synchronized
    fun cancelActive() {
        cancelActiveLocked()
    }

    private fun cancelActiveLocked() {
        val active = activeDrain ?: return
        activeDrain = null
        active.call.cancel()
    }
}
