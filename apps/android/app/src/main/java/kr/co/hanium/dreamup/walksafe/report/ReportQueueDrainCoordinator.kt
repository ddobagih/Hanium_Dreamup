package kr.co.hanium.dreamup.walksafe.report

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation

internal interface ReportQueueTransport {
    fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?>
    fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt>
    fun uploadResultCall(report: QueuedReport): CancellableNetworkCall<ReportQueueUploadResult> =
        uploadCall(report).map { ReportQueueUploadResult(it, creationOutcome = null) }
}

internal class AndroidReportQueueTransport(
    private val uploader: AndroidReportUploader,
    private val session: GatewayFieldSession,
    private val consentConfirmation: IntegratedConsentConfirmation,
    private val networkBinding: IntegratedConsentNetworkBinding,
    private val permitProvider: (ReportTransferPurpose) -> ReportUploadPermit?,
    private val onUploadStarting: (QueuedReport) -> Unit = {},
) : ReportQueueTransport {
    private val approvedGatewayOrigin = requireNotNull(
        approvedReportQueueGatewayOriginOrNull(session.gatewayBaseUrl),
    )

    override fun statusCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt?> =
        uploader.queuedStatusCall(
            permit = permitFor(report),
            session = session,
            consentConfirmation = consentConfirmation,
            networkBinding = networkBinding,
            report = report,
            approvedGatewayOrigin = approvedGatewayOrigin,
        )

    override fun uploadCall(report: QueuedReport): CancellableNetworkCall<ReportQueueReceipt> =
        uploadResultCall(report).map { it.receipt }

    override fun uploadResultCall(report: QueuedReport): CancellableNetworkCall<ReportQueueUploadResult> {
        val call = uploader.queuedUploadResultCall(
            permit = permitFor(report),
            session = session,
            consentConfirmation = consentConfirmation,
            networkBinding = networkBinding,
            report = report,
            approvedGatewayOrigin = approvedGatewayOrigin,
        )
        return CancellableNetworkCall(
            executeBlock = {
                onUploadStarting(report)
                call.execute()
            },
            cancelBlock = call::cancel,
        )
    }

    private fun QueuedReport.purpose(): ReportTransferPurpose =
        if (priority == ReportQueuePriority.EXPLICIT) {
            ReportTransferPurpose.EXPLICIT
        } else {
            ReportTransferPurpose.AUTOMATIC
        }

    private fun permitFor(report: QueuedReport): ReportUploadPermit {
        check(report.reporterActorId == session.actorId)
        check(report.accountGeneration == session.backendAccountGeneration)
        return requireNotNull(permitProvider(report.purpose()))
    }

    init { require(session.actorId.isNotBlank()) }
}

internal enum class ReportQueueDrainOutcome {
    DELETED_AFTER_STATUS,
    DELETED_AFTER_UPLOAD,
    RECEIPT_REJECTED,
    LOCAL_COMMIT_FAILED,
    LOCAL_DELETE_FAILED,
    QUARANTINED,
    RETRY_DEFERRED,
    AUTHENTICATION_REQUIRED,
    PERMISSION_BLOCKED,
    CANCELLED,
}

internal data class ReportQueueDrainResult(
    val outcome: ReportQueueDrainOutcome,
    val creationOutcome: ReportCreationOutcome? = null,
    val reportPriority: ReportQueuePriority? = null,
    /** transfer-F7: kept after 409 report_walk_in_progress; the caller retries once later. */
    val walkInProgress: Boolean = false,
)

private fun ReportQueueDrainOutcome.withCreationOutcome(
    reportPriority: ReportQueuePriority,
    creationOutcome: ReportCreationOutcome? = null,
): ReportQueueDrainResult = ReportQueueDrainResult(
    outcome = this,
    reportPriority = reportPriority,
    creationOutcome = creationOutcome.takeIf {
        this == ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD ||
            this == ReportQueueDrainOutcome.RETRY_DEFERRED
    },
)

/** Owns one status-then-POST attempt. Callers must revalidate on every movement/state change. */
internal class ReportQueueDrainCoordinator(
    private val store: AndroidReportQueueStore,
    private val policy: ReportQueueDrainPolicy = ReportQueueDrainPolicy(),
) {
    private data class ActiveDrain(
        val lease: ReportQueueDrainLease,
        val call: CancellableNetworkCall<ReportQueueDrainResult>,
    )

    private var activeDrain: ActiveDrain? = null

    @Synchronized
    fun startNext(
        contextProvider: () -> ReportQueueDrainContext,
        transport: ReportQueueTransport,
        beforeDeleteAfterReceipt: (QueuedReport) -> Boolean = {
            it.priority != ReportQueuePriority.AUTOMATIC
        },
    ): CancellableNetworkCall<ReportQueueDrainOutcome>? =
        startNextWithResult(contextProvider, transport, beforeDeleteAfterReceipt)?.map { it.outcome }

    @Synchronized
    fun startNextWithResult(
        contextProvider: () -> ReportQueueDrainContext,
        transport: ReportQueueTransport,
        beforeDeleteAfterReceipt: (QueuedReport) -> Boolean = {
            it.priority != ReportQueuePriority.AUTOMATIC
        },
    ): CancellableNetworkCall<ReportQueueDrainResult>? {
        if (activeDrain != null) return null
        val initialContext = contextProvider()
        val report = store.nextForRecoveryDrain(
            initialContext.reporterActorId,
            initialContext.accountGeneration,
        ) ?: return null
        val lease = try {
            policy.acquire(report, initialContext).lease
        } catch (error: Throwable) {
            report.close()
            throw error
        } ?: run { report.close(); return null }
        val nested = AtomicReference<CancellableNetworkCall<*>?>()
        val executionStarted = AtomicBoolean(false)
        lateinit var outer: CancellableNetworkCall<ReportQueueDrainResult>
        outer = CancellableNetworkCall(
            executeBlock = execute@{
                synchronized(this) {
                    if (outer.isCancelled()) return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    executionStarted.set(true)
                }
                var uploadAttempted = false
                try {
                    if (!policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    val statusCall = transport.statusCall(report)
                    nested.set(statusCall)
                    if (
                        outer.isCancelled() ||
                        !policy.isCurrent(lease, contextProvider())
                    ) {
                        nested.compareAndSet(statusCall, null)
                        statusCall.cancel()
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    val statusReceipt = statusCall.execute()
                    nested.compareAndSet(statusCall, null)
                    if (outer.isCancelled() || !policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    if (statusReceipt != null) {
                        return@execute completeReceipt(
                            report, statusReceipt, lease, outer, contextProvider,
                            beforeDeleteAfterReceipt, ReportQueueDrainOutcome.DELETED_AFTER_STATUS,
                        ).withCreationOutcome(report.priority)
                    }
                    val beforeUpload = contextProvider()
                    if (outer.isCancelled() || !policy.isCurrent(lease, beforeUpload)) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    if (!policy.mayUpload(lease, beforeUpload)) {
                        return@execute ReportQueueDrainOutcome.RETRY_DEFERRED.withCreationOutcome(report.priority)
                    }
                    val uploadCall = transport.uploadResultCall(report)
                    nested.set(uploadCall)
                    if (
                        outer.isCancelled() ||
                        !policy.mayUpload(lease, contextProvider())
                    ) {
                        nested.compareAndSet(uploadCall, null)
                        uploadCall.cancel()
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    uploadAttempted = true
                    val uploadResult = uploadCall.execute()
                    nested.compareAndSet(uploadCall, null)
                    if (outer.isCancelled() || !policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    if (uploadResult.creationOutcome == ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN) {
                        return@execute ReportQueueDrainOutcome.RECEIPT_REJECTED.withCreationOutcome(report.priority)
                    }
                    completeReceipt(
                        report, uploadResult.receipt, lease, outer, contextProvider,
                        beforeDeleteAfterReceipt, ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD,
                    ).withCreationOutcome(report.priority, uploadResult.creationOutcome)
                } catch (_: CancellationException) {
                    ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                } catch (error: ReportUploadHttpException) {
                    if (outer.isCancelled() || !policy.isCurrent(lease, contextProvider())) {
                        ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    } else when (error.error.statusCode) {
                        400, 413, 415, 422 -> (if (store.recordUploadFailure(report, permanent = true)) {
                            ReportQueueDrainOutcome.QUARANTINED
                        } else ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED).withCreationOutcome(report.priority)
                        401 -> ReportQueueDrainOutcome.AUTHENTICATION_REQUIRED.withCreationOutcome(report.priority)
                        403 -> ReportQueueDrainOutcome.PERMISSION_BLOCKED.withCreationOutcome(report.priority)
                        else -> {
                            val retryDelay = if (error.error.statusCode == 408 ||
                                error.error.statusCode == 429 || error.error.statusCode in 500..599
                            ) reportRetryDelayMs(1, error.error.statusCode, error.error.retryAfterMs)
                            else error.error.retryAfterMs
                            val deferred = if (retryDelay != null &&
                                !store.recordUploadFailure(report, false, retryDelay)
                            ) ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED
                            else ReportQueueDrainOutcome.RETRY_DEFERRED
                            deferred.withCreationOutcome(
                                report.priority,
                                ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN.takeIf {
                                    report.priority == ReportQueuePriority.AUTOMATIC &&
                                        uploadAttempted &&
                                        error.error.retryAfterMs != null &&
                                        error.error.isAutomaticCooldownSuppression()
                                },
                            ).copy(walkInProgress = error.error.isReportWalkInProgress())
                        }
                    }
                } catch (_: IOException) {
                    // A lost response may follow server acceptance. Retain the exact payload and
                    // defer the next status-first attempt across process restarts.
                    (if (outer.isCancelled() || !policy.isCurrent(lease, contextProvider())) {
                        ReportQueueDrainOutcome.CANCELLED
                    } else if (store.recordUploadFailure(report, false, reportRetryDelayMs(1))) {
                        ReportQueueDrainOutcome.RETRY_DEFERRED
                    } else ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED).withCreationOutcome(report.priority)
                } finally {
                    report.close()
                    policy.release(lease)
                    synchronized(this) {
                        if (activeDrain?.call === outer) activeDrain = null
                    }
                }
            },
            cancelBlock = {
                synchronized(this) {
                    policy.release(lease)
                    if (!executionStarted.get()) {
                        report.close()
                        if (activeDrain?.call === outer) activeDrain = null
                    }
                }
                nested.getAndSet(null)?.cancel()
            },
        )
        activeDrain = ActiveDrain(lease, outer)
        return outer
    }

    @Synchronized
    fun revalidate(context: ReportQueueDrainContext): Boolean {
        val active = activeDrain ?: return false
        if (policy.isCurrent(active.lease, context)) return true
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
        if (activeDrain?.lease?.priority == ReportQueuePriority.AUTOMATIC) cancelActiveLocked()
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
        active.call.cancel()
    }

    @Synchronized
    private fun completeReceipt(
        report: QueuedReport,
        receipt: ReportQueueReceipt,
        lease: ReportQueueDrainLease,
        call: CancellableNetworkCall<*>,
        contextProvider: () -> ReportQueueDrainContext,
        beforeDelete: (QueuedReport) -> Boolean,
        success: ReportQueueDrainOutcome,
    ): ReportQueueDrainOutcome {
        if (call.isCancelled() || !policy.isCurrent(lease, contextProvider())) {
            return ReportQueueDrainOutcome.CANCELLED
        }
        if (!receipt.matches(report)) return ReportQueueDrainOutcome.RECEIPT_REJECTED
        if (!beforeDelete(report)) return ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED
        if (call.isCancelled() || !policy.isCurrent(lease, contextProvider())) {
            return ReportQueueDrainOutcome.CANCELLED
        }
        return if (store.deleteAfterReceipt(lease.reporterActorId, receipt)) success
        else ReportQueueDrainOutcome.LOCAL_DELETE_FAILED
    }
}
