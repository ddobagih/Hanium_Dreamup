package kr.co.hanium.dreamup.walksafe.report

import kr.co.hanium.dreamup.walksafe.network.GatewayCredentialPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal data class ReportQueueDrainContext(
    val walkState: WalkSessionState,
    val appForeground: Boolean,
    val stationary: Boolean,
    val networkAllowed: Boolean,
    val consentAllowed: Boolean,
    val automaticReportingAllowed: Boolean,
    val authorityAllowed: Boolean,
    val reporterActorId: String,
    val walkSessionId: String,
    val consentReceiptSha256: String,
    val movementGeneration: Long,
)

internal data class ReportQueueDrainLease(
    val leaseId: Long,
    val reportId: String,
    val priority: ReportQueuePriority,
    val reporterActorId: String,
    val sourceWalkSessionId: String,
    val runtimeWalkSessionId: String,
    val consentReceiptSha256: String,
    val movementGeneration: Long,
)

internal data class ReportQueueDrainDecision(
    val lease: ReportQueueDrainLease?,
    val allowedStatusRequests: Int,
    val allowedPostRequests: Int,
) {
    val allowed: Boolean
        get() = lease != null
}

/** Pure admission and single-lease policy. It performs no network operation. */
internal class ReportQueueDrainPolicy {
    private var currentLease: ReportQueueDrainLease? = null
    private var nextLeaseId = 1L

    @Synchronized
    fun acquire(
        report: QueuedReport?,
        context: ReportQueueDrainContext,
    ): ReportQueueDrainDecision {
        if (report == null || currentLease != null || !eligible(report, context)) {
            return BLOCKED
        }
        val lease = ReportQueueDrainLease(
            leaseId = nextLeaseId++,
            reportId = report.payload.reportId,
            priority = report.priority,
            reporterActorId = report.reporterActorId,
            sourceWalkSessionId = report.walkSessionId,
            runtimeWalkSessionId = context.walkSessionId,
            consentReceiptSha256 = context.consentReceiptSha256,
            movementGeneration = context.movementGeneration,
        )
        currentLease = lease
        return ReportQueueDrainDecision(
            lease = lease,
            allowedStatusRequests = 1,
            allowedPostRequests = 1,
        )
    }

    @Synchronized
    fun isCurrent(
        lease: ReportQueueDrainLease,
        context: ReportQueueDrainContext,
    ): Boolean {
        val current = currentLease
        val valid = current == lease &&
            context.walkState == WalkSessionState.PAUSED &&
            context.appForeground &&
            context.stationary &&
            context.networkAllowed &&
            context.consentAllowed &&
            (
                lease.priority != ReportQueuePriority.AUTOMATIC ||
                    context.automaticReportingAllowed
            ) &&
            context.authorityAllowed &&
            context.reporterActorId == lease.reporterActorId &&
            context.walkSessionId == lease.runtimeWalkSessionId &&
            context.consentReceiptSha256 == lease.consentReceiptSha256 &&
            context.movementGeneration == lease.movementGeneration
        if (!valid && current == lease) currentLease = null
        return valid
    }

    @Synchronized
    fun release(lease: ReportQueueDrainLease): Boolean {
        if (currentLease != lease) return false
        currentLease = null
        return true
    }

    private fun eligible(report: QueuedReport, context: ReportQueueDrainContext): Boolean =
        context.movementGeneration >= 0L &&
            context.walkState == WalkSessionState.PAUSED &&
            context.appForeground &&
            context.stationary &&
            context.networkAllowed &&
            context.consentAllowed &&
            (
                report.priority != ReportQueuePriority.AUTOMATIC ||
                    context.automaticReportingAllowed
            ) &&
            context.authorityAllowed &&
            report.reporterActorId == context.reporterActorId &&
            GatewayCredentialPolicy.normalizedActorIdOrNull(context.reporterActorId) ==
            context.reporterActorId &&
            REPORT_SHA256_HEX.matches(context.consentReceiptSha256)

    private companion object {
        val BLOCKED = ReportQueueDrainDecision(
            lease = null,
            allowedStatusRequests = 0,
            allowedPostRequests = 0,
        )
    }
}
