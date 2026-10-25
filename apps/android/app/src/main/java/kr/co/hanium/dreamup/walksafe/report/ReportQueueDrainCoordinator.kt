package kr.co.hanium.dreamup.walksafe.report

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

    override fun uploadResultCall(report: QueuedReport): CancellableNetworkCall<ReportQueueUploadResult> =
        uploader.queuedUploadResultCall(
            permit = permitFor(report),
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
    AUTHORITY_BLOCKED,
    AUTHENTICATION_REQUIRED,
    PERMISSION_BLOCKED,
    RECEIPT_UNCONFIRMED,
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
    private val cancellationExecutor: Executor = defaultCancellationExecutor,
) {
    private data class ActiveDrain(
        val lease: ReportQueueDrainLease,
        val call: CancellableNetworkCall<ReportQueueDrainResult>,
        val admissionGeneration: Long,
    )

    @Volatile private var activeDrain: ActiveDrain? = null
    private val admissionGeneration = AtomicLong()
    @Volatile private var deletionRequested = false

    private companion object {
        val terminationMonitor = java.lang.Object()
        val ownedCalls = mutableSetOf<CancellableNetworkCall<*>>()
        val deletionBarrier = AtomicBoolean(false)
        val preparingDrains = AtomicInteger()
        // Standalone coordinators share a control worker; the runtime supplies its owned worker.
        val defaultCancellationExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "walksafe-report-queue-cancel").apply { isDaemon = true }
            }
        }
    }

    private fun releaseOwner(report: QueuedReport, call: CancellableNetworkCall<*>) {
        report.close()
        synchronized(terminationMonitor) {
            ownedCalls.remove(call)
            terminationMonitor.notifyAll()
        }
    }

    /** UI boundary: only atomic fences. Transport cancellation and waits are worker work. */
    fun fenceAccountDeletionAdmission() {
        deletionRequested = true
        deletionBarrier.set(true)
        admissionGeneration.incrementAndGet()
    }

    /** Retires this generation without taking the coordinator or storage monitor. */
    fun fenceActiveAdmission(): CancellableNetworkCall<ReportQueueDrainResult>? {
        val next = admissionGeneration.incrementAndGet()
        return activeDrain?.takeIf { it.admissionGeneration != next }?.call
    }

    /** Includes old Activity coordinators; cancellation alone is not plaintext termination. */
    fun cancelAndAwaitDrainTermination(timeoutMs: Long = 5_000L): Boolean {
        require(timeoutMs >= 0L)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtMost(60_000L))
        fenceAccountDeletionAdmission()
        val calls = synchronized(terminationMonitor) { ownedCalls.toList() }
        calls.forEach { call ->
            try { cancellationExecutor.execute { runCatching { call.cancel() } } }
            catch (_: RejectedExecutionException) {
                // Keep the barrier and registered owner until actual finally termination.
            }
        }
        synchronized(terminationMonitor) {
            while (ownedCalls.isNotEmpty() || preparingDrains.get() != 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try { TimeUnit.NANOSECONDS.timedWait(terminationMonitor, remaining) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return true
        }
    }

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
        if (activeDrain != null || deletionRequested || deletionBarrier.get()) return null
        val expectedAdmissionGeneration = admissionGeneration.get()
        preparingDrains.incrementAndGet()
        try {
        if (deletionRequested || deletionBarrier.get() || admissionGeneration.get() != expectedAdmissionGeneration) return null
        val initialContext = contextProvider()
        val report = store.nextForRecoveryDrain(
            initialContext.reporterActorId,
            initialContext.accountGeneration,
        ) ?: return null
        val lease = try { policy.acquire(report, initialContext).lease }
        catch (error: Throwable) { report.close(); throw error }
            ?: run { report.close(); return null }
        val nested = AtomicReference<CancellableNetworkCall<*>?>()
        val executionStarted = AtomicBoolean(false)
        lateinit var outer: CancellableNetworkCall<ReportQueueDrainResult>
        fun currentAdmission(): Boolean = !outer.isCancelled() && !deletionRequested && !deletionBarrier.get() &&
            admissionGeneration.get() == expectedAdmissionGeneration
        outer = CancellableNetworkCall(
            executeBlock = execute@{
                synchronized(this) {
                    if (outer.isCancelled()) return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    executionStarted.set(true)
                }
                var uploadAttempted = false
                try {
                    if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    val statusCall = transport.statusCall(report)
                    nested.set(statusCall)
                    if (
                        !currentAdmission() ||
                        !policy.isCurrent(lease, contextProvider())
                    ) {
                        nested.compareAndSet(statusCall, null)
                        statusCall.cancel()
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    val statusReceipt = statusCall.execute()
                    nested.compareAndSet(statusCall, null)
                    if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    if (statusReceipt != null) {
                        return@execute completeReceipt(
                            report, statusReceipt, lease, outer, contextProvider,
                            beforeDeleteAfterReceipt, ReportQueueDrainOutcome.DELETED_AFTER_STATUS,
                            currentAdmission = ::currentAdmission,
                        ).withCreationOutcome(report.priority)
                    }
                    val beforeUpload = contextProvider()
                    if (!currentAdmission() || !policy.isCurrent(lease, beforeUpload)) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    if (!policy.mayUpload(lease, beforeUpload)) {
                        return@execute ReportQueueDrainOutcome.RETRY_DEFERRED.withCreationOutcome(report.priority)
                    }
                    val uploadCall = transport.uploadResultCall(report)
                    nested.set(uploadCall)
                    if (
                        !currentAdmission() ||
                        !policy.mayUpload(lease, contextProvider())
                    ) {
                        nested.compareAndSet(uploadCall, null)
                        uploadCall.cancel()
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    uploadAttempted = true
                    val uploadResult = uploadCall.execute()
                    nested.compareAndSet(uploadCall, null)
                    if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
                        return@execute ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    }
                    if (uploadResult.creationOutcome == ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN) {
                        return@execute ReportQueueDrainOutcome.RECEIPT_REJECTED.withCreationOutcome(report.priority)
                    }
                    completeReceipt(
                        report, uploadResult.receipt, lease, outer, contextProvider,
                        beforeDeleteAfterReceipt, ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD,
                        currentAdmission = ::currentAdmission,
                    ).withCreationOutcome(report.priority, uploadResult.creationOutcome)
                } catch (_: CancellationException) {
                    ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                } catch (_: ReportUploadProtocolException) {
                    if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
                        ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    } else {
                        val saved = store.recordUploadFailure(report, false)
                        (if (saved) ReportQueueDrainOutcome.RECEIPT_UNCONFIRMED else ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED)
                            .withCreationOutcome(report.priority)
                    }
                } catch (error: ReportUploadHttpException) {
                    if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
                        ReportQueueDrainOutcome.CANCELLED.withCreationOutcome(report.priority)
                    } else when (error.error.statusCode) {
                        400, 413, 415, 422 -> (if (store.recordUploadFailure(report, permanent = true)) {
                            ReportQueueDrainOutcome.QUARANTINED
                        } else ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED).withCreationOutcome(report.priority)
                        401 -> ReportQueueDrainOutcome.AUTHENTICATION_REQUIRED.withCreationOutcome(report.priority)
                        403 -> ReportQueueDrainOutcome.PERMISSION_BLOCKED.withCreationOutcome(report.priority)
                        else -> {
                            val deferred = if (
                                !store.recordUploadFailure(report, false, error.error.retryAfterMs)
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
                    (if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
                        ReportQueueDrainOutcome.CANCELLED
                    } else if (store.recordUploadFailure(report, false)) {
                        ReportQueueDrainOutcome.RECEIPT_UNCONFIRMED
                    } else ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED).withCreationOutcome(report.priority)
                } finally {
                    releaseOwner(report, outer)
                    policy.release(lease)
                    synchronized(this) {
                        if (activeDrain?.call === outer) activeDrain = null
                    }
                }
            },
            cancelBlock = {
                try {
                    // A receipt/storage monitor must not delay physical transport cancellation.
                    nested.getAndSet(null)?.cancel()
                } finally {
                    synchronized(this) {
                        policy.release(lease)
                        if (!executionStarted.get()) {
                            releaseOwner(report, outer)
                            if (activeDrain?.call === outer) activeDrain = null
                        }
                    }
                }
            },
        )
        synchronized(terminationMonitor) {
            if (deletionBarrier.get() || deletionRequested || admissionGeneration.get() != expectedAdmissionGeneration) {
                report.close()
                policy.release(lease)
                return null
            }
            ownedCalls.add(outer)
            activeDrain = ActiveDrain(lease, outer, expectedAdmissionGeneration)
        }
        return outer
        } finally {
            preparingDrains.decrementAndGet()
            synchronized(terminationMonitor) { terminationMonitor.notifyAll() }
        }
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
    fun onAutomaticReportingRevoked(consentReceiptSha256: String, reporterActorId: String? = null, accountGeneration: Long? = null): Boolean {
        if (activeDrain?.lease?.priority == ReportQueuePriority.AUTOMATIC) cancelActiveLocked()
        return store.onAutomaticReportingRevoked(consentReceiptSha256, reporterActorId, accountGeneration)
    }

    fun onAccountDeleted(timeoutMs: Long = 5_000L): Boolean {
        if (!cancelAndAwaitDrainTermination(timeoutMs)) return false
        return try { store.onAccountDeleted() }
        finally {
            synchronized(terminationMonitor) {
                deletionBarrier.set(false)
                terminationMonitor.notifyAll()
            }
        }
    }

    fun cancelActive() {
        fenceActiveAdmission()?.cancel()
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
        currentAdmission: () -> Boolean = { !call.isCancelled() },
    ): ReportQueueDrainOutcome {
        if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
            return ReportQueueDrainOutcome.CANCELLED
        }
        if (!receipt.matches(report)) {
            return if (store.recordUploadFailure(report, false)) ReportQueueDrainOutcome.RECEIPT_UNCONFIRMED
            else ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED
        }
        if (!beforeDelete(report)) {
            store.recordUploadFailure(report, false)
            return ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED
        }
        if (!currentAdmission() || !policy.isCurrent(lease, contextProvider())) {
            return ReportQueueDrainOutcome.CANCELLED
        }
        return if (store.deleteAfterReceipt(lease.reporterActorId, receipt)) success
        else {
            store.recordUploadFailure(report, false)
            ReportQueueDrainOutcome.LOCAL_DELETE_FAILED
        }
    }
}
