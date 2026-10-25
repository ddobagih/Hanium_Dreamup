package kr.co.hanium.dreamup.walksafe.report.runtime

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.network.ActivityOriginalStationarySnapshot
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.AndroidNetworkTransferPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.report.AndroidReportAttemptResult
import kr.co.hanium.dreamup.walksafe.report.AndroidReportAttemptStore
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCooldownPolicy
import kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStore
import kr.co.hanium.dreamup.walksafe.report.AndroidReportSuccessfulCooldown
import kr.co.hanium.dreamup.walksafe.report.PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE
import kr.co.hanium.dreamup.walksafe.report.QueuedReport
import kr.co.hanium.dreamup.walksafe.report.ReportCreationOutcome
import kr.co.hanium.dreamup.walksafe.report.ReportPrivacyConsentSession
import kr.co.hanium.dreamup.walksafe.report.ReportQueueDrainContext
import kr.co.hanium.dreamup.walksafe.report.ReportQueueDrainCoordinator
import kr.co.hanium.dreamup.walksafe.report.ReportQueueDrainOutcome
import kr.co.hanium.dreamup.walksafe.report.ReportQueueDrainResult
import kr.co.hanium.dreamup.walksafe.report.ReportQueuePriority
import kr.co.hanium.dreamup.walksafe.report.ReportQueueTransport
import kr.co.hanium.dreamup.walksafe.report.approvedReportQueueGatewayOriginOrNull
import kr.co.hanium.dreamup.walksafe.report.automaticCooldownScopeOrNull
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionTransition
import kr.co.hanium.dreamup.walksafe.session.allowsPostWalkTransfer

/** Owns a bounded post-walk drain, its cancellation generations and the exact-ACK batch coordinator. */
internal class ReportQueueDrainController(
    private val sources: Sources,
    private val runtime: Runtime,
    private val network: Network,
    private val scheduler: Scheduler,
    private val clock: Clock,
    private val effects: Effects,
    private val transportFactory: (ReportQueueDrainTrigger) -> ReportQueueTransport,
    private val persistCooldownForSuccess: (AndroidReportSuccessfulCooldown) -> Boolean,
    private val capacityProfileAvailable: () -> Boolean = { PRODUCTION_REPORT_QUEUE_CAPACITY_PROFILE != null },
    private val approvedOrigin: (String) -> String? = ::approvedReportQueueGatewayOriginOrNull,
    private val reportQueueDrainExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-report-queue-drain").apply { priority = Thread.NORM_PRIORITY - 1 }
    },
    // Cancellation cannot run behind the transport which owns the single drain worker.
    private val reportQueueDrainCancellationExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-report-queue-cancel").apply { isDaemon = true }
    },
) {
    /** References the caller's shared domain objects; mutable stores and policies remain suppliers. */
    data class Sources(
        val queueStore: () -> AndroidReportQueueStore,
        val attemptStore: () -> AndroidReportAttemptStore,
        val integratedConsent: IntegratedConsentSession,
        val privacyConsent: ReportPrivacyConsentSession,
        val permissionPolicy: () -> PermissionSessionPolicy,
        val gatewaySnapshot: () -> GatewaySessionProcessSnapshot,
        val walkSnapshot: () -> WalkSessionSnapshot,
        val reporterActorId: () -> String?,
        val automaticCleanupReady: () -> Boolean = { true },
    )

    interface Runtime {
        fun isForeground(): Boolean
        fun isWalkInitialized(): Boolean
        fun latestStepCount(): Int
    }

    interface Network {
        fun isReady(): Boolean
        fun currentTransport(): ActiveNetworkTransport
        fun currentBinding(): IntegratedConsentNetworkBinding?
    }

    interface Scheduler {
        fun isMainThread(): Boolean
        fun runOnUiThread(action: () -> Unit)
        fun currentActivityLease(): AccountDeletionActivityLease
        fun postActivityCallback(lease: AccountDeletionActivityLease, action: () -> Unit)
    }

    interface Clock {
        fun wallTimeMillis(): Long
        fun elapsedRealtime(): Long
        fun bootCount(): Int?
    }

    interface Effects {
        fun clearStepTrackingEpoch()
        fun stopStepTrackingNow()
        fun cancelRawUploadAndRevalidateIfReady()
        fun schedulePostWalkTransfers()
        fun schedulePostWalkRetryAfterWalkInProgress()
        fun schedulePostWalkRetry(delayMs: Long) {}
        fun isHandsFreeVoiceOutputActive(): Boolean
        fun speakStatusExplanation(message: String)
        fun showWalkLastResult(message: String)
    }

    data class ReportQueueDrainTrigger(
        val reporterActorId: String,
        val walkSessionId: String,
        val stationarySnapshot: ActivityOriginalStationarySnapshot,
        val consentConfirmation: IntegratedConsentConfirmation,
        val gatewaySession: GatewayFieldSession,
        val gatewaySessionGeneration: Long,
        val networkBinding: IntegratedConsentNetworkBinding,
        val networkTransport: ActiveNetworkTransport,
        val networkGeneration: Long,
        val movementGeneration: Long,
    )

    private val reportQueueStore get() = sources.queueStore()
    private val reportAttemptStore get() = sources.attemptStore()
    private val reportPrivacyConsentSession get() = sources.privacyConsent
    private val integratedConsentSession get() = sources.integratedConsent
    private val permissionSessionPolicy get() = sources.permissionPolicy()
    private val reportQueueDrainCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReportQueueDrainCoordinator(reportQueueStore, cancellationExecutor = reportQueueDrainCancellationExecutor)
    }
    private val reportQueueDrainLock = Any()
    private var reportQueueDrainTrigger: ReportQueueDrainTrigger? = null
    private var reportQueueDrainMovementGeneration = 0L
    private var reportQueueDrainNetworkGeneration = 0L
    private var reportQueueDrainWalkState: WalkSessionState? = null
    private var reportQueueDrainWalkSessionId: String? = null

    fun hasActiveDrain(): Boolean = synchronized(reportQueueDrainLock) { reportQueueDrainTrigger != null }
    fun onAutomaticReportingRevoked(receipt: String, reporterActorId: String? = null, accountGeneration: Long? = null): Boolean =
        reportQueueDrainCoordinator.onAutomaticReportingRevoked(receipt, reporterActorId, accountGeneration)
    fun cancelAndAwaitDrainTermination(timeoutMs: Long = 5_000L): Boolean = reportQueueDrainCoordinator.cancelAndAwaitDrainTermination(timeoutMs)
    fun onAccountDeleted(timeoutMs: Long = 5_000L): Boolean {
        if (scheduler.isMainThread()) {
            // The UI only closes admission and cancels. Physical purge and waiting belong to I/O.
            reportQueueDrainCoordinator.fenceAccountDeletionAdmission()
            return false
        }
        return reportQueueDrainCoordinator.onAccountDeleted(timeoutMs)
    }
    fun shutdownExecutor() {
        requestReportQueueCancellation()
        reportQueueDrainExecutor.shutdownNow()
        reportQueueDrainCancellationExecutor.shutdown() // Preserve already admitted cancellation tasks.
    }


    fun buildReportQueueDrainTrigger(
        walkSessionId: String,
        stationarySnapshot: ActivityOriginalStationarySnapshot,
    ): ReportQueueDrainTrigger? {
        if (
            !runtime.isForeground() ||
            !network.isReady() ||
            !reportPrivacyConsentSession.isGranted()
        ) return null
        val consentConfirmation =
            integratedConsentSession.currentConfirmationOrNull() ?: return null
        val reporter = sources.reporterActorId() ?: return null
        val gatewaySnapshot = sources.gatewaySnapshot()
        val gatewaySession = gatewaySnapshot.session ?: return null
        if (
            gatewaySnapshot.storageBlocked ||
            gatewaySnapshot.deletionRecoveryOnly ||
            gatewaySession.sessionScope != GatewaySessionScope.GENERAL ||
            !gatewaySession.isUsableFor(reporter) ||
            approvedOrigin(gatewaySession.gatewayBaseUrl) !=
            gatewaySession.gatewayBaseUrl
        ) {
            return null
        }
        val networkTransport = network.currentTransport()
        val networkPreference = permissionSessionPolicy.snapshot().mobileNetworkPreference
        if (!AndroidNetworkTransferPolicy.isAllowed(networkPreference, networkTransport)) {
            return null
        }
        val networkBinding = network.currentBinding() ?: return null
        if (!networkBinding.matches(networkTransport)) return null
        val generations = synchronized(reportQueueDrainLock) {
            if (reportQueueDrainTrigger != null) return@synchronized null
            reportQueueDrainMovementGeneration to reportQueueDrainNetworkGeneration
        } ?: return null
        return ReportQueueDrainTrigger(
            reporterActorId = reporter,
            walkSessionId = walkSessionId,
            stationarySnapshot = stationarySnapshot,
            consentConfirmation = consentConfirmation,
            gatewaySession = gatewaySession,
            gatewaySessionGeneration = gatewaySnapshot.generation,
            networkBinding = networkBinding,
            networkTransport = networkTransport,
            networkGeneration = generations.second,
            movementGeneration = generations.first,
        )
    }

    fun updateReportQueueDrainForTransition(
        transition: WalkSessionTransition,
    ) {
        val cancelActiveDrain = synchronized(reportQueueDrainLock) {
            reportQueueDrainWalkState = transition.current.state
            reportQueueDrainWalkSessionId = transition.current.epoch.walkSessionId
            val active = reportQueueDrainTrigger
            // Owner decision 3(b): a walk that becomes ACTIVE or PAUSED again stops the drain.
            if (
                active != null &&
                (
                    !transition.current.state.allowsPostWalkTransfer ||
                        transition.current.epoch.walkSessionId != active.walkSessionId
                )
            ) {
                reportQueueDrainTrigger = null
                reportQueueDrainMovementGeneration += 1L
                true
            } else {
                false
            }
        }
        if (cancelActiveDrain) {
            requestReportQueueCancellation()
            effects.stopStepTrackingNow()
        }
    }

    fun startBoundedReportQueueDrain(trigger: ReportQueueDrainTrigger): Boolean {
        val initialCount = reportQueueStore.countForRecoveryDrain(trigger.reporterActorId)
        if (initialCount <= 0) {
            scheduleStoredRetry(trigger.reporterActorId, trigger.gatewaySession.backendAccountGeneration)
            if (!reportQueueStore.maintenanceSucceeded()) effects.schedulePostWalkRetry(30_000L)
            return false
        }
        val installed = synchronized(reportQueueDrainLock) {
            if (
                reportQueueDrainTrigger != null ||
                reportQueueDrainMovementGeneration != trigger.movementGeneration ||
                reportQueueDrainNetworkGeneration != trigger.networkGeneration ||
                reportQueueDrainWalkState?.allowsPostWalkTransfer != true ||
                reportQueueDrainWalkSessionId != trigger.walkSessionId
            ) {
                false
            } else {
                reportQueueDrainTrigger = trigger
                effects.clearStepTrackingEpoch()
                true
            }
        }
        if (!installed) return false
        if (!reportQueueDrainContext(trigger).allRequiredBaseGatesAllowed()) {
            cancelReportQueueDrain()
            return false
        }
        return try {
            reportQueueDrainExecutor.execute {
                drainInitialExactReportQueue(trigger, initialCount)
            }
            true
        } catch (_: RejectedExecutionException) {
            cancelReportQueueDrain()
            false
        }
    }

    fun maybeStartPostWalkReportQueueDrain() {
        if (!scheduler.isMainThread()) {
            scheduler.runOnUiThread(::maybeStartPostWalkReportQueueDrain)
            return
        }
        if (!runtime.isForeground() || !runtime.isWalkInitialized()) return
        val walk = sources.walkSnapshot()
        if (!walk.state.allowsPostWalkTransfer || !reportQueueRecoveryBaseGatesAvailable()) return
        val reporter = sources.reporterActorId() ?: return
        if (reportQueueStore.countForRecoveryDrain(reporter) <= 0) {
            scheduleStoredRetry(reporter, sources.gatewaySnapshot().session?.backendAccountGeneration)
            if (!reportQueueStore.maintenanceSucceeded()) effects.schedulePostWalkRetry(30_000L)
            return
        }
        val idle = synchronized(reportQueueDrainLock) {
            if (reportQueueDrainTrigger != null) {
                false
            } else {
                reportQueueDrainWalkState = walk.state
                reportQueueDrainWalkSessionId = walk.epoch.walkSessionId
                true
            }
        }
        if (!idle) return
        val trigger = buildReportQueueDrainTrigger(
            walkSessionId = walk.epoch.walkSessionId,
            // Step baseline only; after the walk no step window gates the drain.
            stationarySnapshot = ActivityOriginalStationarySnapshot(
                stepCount = runtime.latestStepCount(),
                observedAtMs = clock.elapsedRealtime(),
            ),
        ) ?: return
        startBoundedReportQueueDrain(trigger)
    }

    fun reportQueueRecoveryBaseGatesAvailable(): Boolean {
        if (
            !capacityProfileAvailable() ||
            !reportPrivacyConsentSession.isGranted() ||
            !network.isReady()
        ) return false
        val reporter = sources.reporterActorId() ?: return false
        val confirmation = integratedConsentSession.currentConfirmationOrNull() ?: return false
        val gateway = sources.gatewaySnapshot()
        val session = gateway.session ?: return false
        if (
            gateway.storageBlocked ||
            gateway.deletionRecoveryOnly ||
            session.sessionScope != GatewaySessionScope.GENERAL ||
            !session.isUsableFor(reporter) ||
            approvedOrigin(session.gatewayBaseUrl) !=
            session.gatewayBaseUrl
        ) return false
        val transport = network.currentTransport()
        val binding = network.currentBinding() ?: return false
        return binding.matches(transport) &&
            AndroidNetworkTransferPolicy.isAllowed(
                permissionSessionPolicy.snapshot().mobileNetworkPreference,
                transport,
            ) &&
            confirmation.backendConsentReceiptSha256.isNotEmpty()
    }

    private fun IntegratedConsentNetworkBinding.matches(
        transport: ActiveNetworkTransport,
    ): Boolean = when (transport) {
        ActiveNetworkTransport.WIFI ->
            this.transport == IntegratedConsentNetworkTransport.WIFI
        ActiveNetworkTransport.CELLULAR ->
            this.transport == IntegratedConsentNetworkTransport.CELLULAR
        ActiveNetworkTransport.OFFLINE,
        ActiveNetworkTransport.OTHER,
        -> false
    }

    fun reportQueueDrainContext(
        trigger: ReportQueueDrainTrigger,
    ): ReportQueueDrainContext {
        val runtime = synchronized(reportQueueDrainLock) {
            ReportQueueDrainRuntimeSnapshot(
                triggerCurrent = reportQueueDrainTrigger === trigger,
                walkState = reportQueueDrainWalkState,
                walkSessionId = reportQueueDrainWalkSessionId,
                movementGeneration = reportQueueDrainMovementGeneration,
                networkGeneration = reportQueueDrainNetworkGeneration,
            )
        }
        val currentConfirmation = integratedConsentSession.currentConfirmationOrNull()
        val consentAllowed =
            runtime.triggerCurrent &&
                reportPrivacyConsentSession.isGranted() &&
                currentConfirmation == trigger.consentConfirmation
        val currentGateway = sources.gatewaySnapshot()
        val reporter = sources.reporterActorId()
        val authorityAllowed =
            runtime.triggerCurrent &&
                reporter == trigger.reporterActorId &&
                currentGateway.generation == trigger.gatewaySessionGeneration &&
                currentGateway.session === trigger.gatewaySession &&
                !currentGateway.storageBlocked &&
                !currentGateway.deletionRecoveryOnly &&
                trigger.gatewaySession.sessionScope == GatewaySessionScope.GENERAL &&
                trigger.gatewaySession.isUsableFor(reporter)
        val currentTransport = if (network.isReady()) {
            network.currentTransport()
        } else {
            ActiveNetworkTransport.OFFLINE
        }
        val currentBinding = if (network.isReady()) {
            network.currentBinding()
        } else {
            null
        }
        val preference = permissionSessionPolicy.snapshot().mobileNetworkPreference
        val networkAllowed =
            runtime.triggerCurrent &&
                runtime.networkGeneration == trigger.networkGeneration &&
                currentTransport == trigger.networkTransport &&
                currentBinding != null &&
                currentBinding.matches(currentTransport) &&
                trigger.networkBinding.isSameNetworkBinding(currentBinding) &&
                AndroidNetworkTransferPolicy.isAllowed(preference, currentTransport)
        return ReportQueueDrainContext(
            walkState = runtime.walkState ?: WalkSessionState.ACTIVE,
            appForeground = runtime.triggerCurrent && this.runtime.isForeground(),
            stationary =
                runtime.triggerCurrent &&
                    runtime.movementGeneration == trigger.movementGeneration,
            networkAllowed = networkAllowed,
            consentAllowed = consentAllowed,
            automaticReportingAllowed =
                consentAllowed && sources.automaticCleanupReady() &&
                    currentConfirmation?.selections?.automaticReporting == true &&
                    integratedConsentSession.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING),
            authorityAllowed = authorityAllowed,
            reporterActorId = reporter.orEmpty(),
            walkSessionId = runtime.walkSessionId.orEmpty(),
            consentReceiptSha256 =
                currentConfirmation?.backendConsentReceiptSha256.orEmpty(),
            movementGeneration = runtime.movementGeneration,
            accountGeneration = currentGateway.session?.backendAccountGeneration,
            sessionGeneration = currentGateway.generation,
        )
    }

    fun drainInitialExactReportQueue(
        trigger: ReportQueueDrainTrigger,
        initialCount: Int,
    ) {
        val activityLease = scheduler.currentActivityLease()
        val walkEpoch = sources.walkSnapshot().epoch
        val notices = linkedSetOf<String>()
        val spokenNotices = linkedSetOf<String>()
        var remaining = initialCount
        try {
            val transport = transportFactory(trigger)
            while (remaining > 0) {
                val context = reportQueueDrainContext(trigger)
                if (!context.allRequiredBaseGatesAllowed()) break
                val call = reportQueueDrainCoordinator.startNextWithResult(
                    contextProvider = { reportQueueDrainContext(trigger) },
                    transport = transport,
                    beforeDeleteAfterReceipt =
                        ::persistAutomaticReportCooldownBeforeQueueDelete,
                ) ?: break
                val result = try {
                    call.execute()
                } catch (_: Exception) {
                    effects.schedulePostWalkRetry(30_000L)
                    break
                }
                // transfer-F7: the Gateway still holds this device's walk; one delayed retry.
                if (result.walkInProgress) effects.schedulePostWalkRetryAfterWalkInProgress()
                reportQueueDrainResultMessage(result)?.let { message ->
                    notices.add(message)
                    if (result.reportPriority == ReportQueuePriority.EXPLICIT) spokenNotices.add(message)
                }
                val outcome = result.outcome
                if (outcome == ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED || outcome == ReportQueueDrainOutcome.LOCAL_DELETE_FAILED) {
                    effects.schedulePostWalkRetry(30_000L)
                }
                if (
                    outcome != ReportQueueDrainOutcome.DELETED_AFTER_STATUS &&
                    outcome != ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD &&
                    outcome != ReportQueueDrainOutcome.QUARANTINED
                ) {
                    break
                }
                remaining -= 1
            }
        } finally {
            scheduleStoredRetry(trigger.reporterActorId, trigger.gatewaySession.backendAccountGeneration)
            if (!reportQueueStore.maintenanceSucceeded()) effects.schedulePostWalkRetry(30_000L)
            if (finishReportQueueDrain(trigger) && notices.isNotEmpty()) {
                postReportQueueDrainNotice(
                    trigger, activityLease, walkEpoch, notices.joinToString(" "),
                    spokenNotices.takeIf { it.isNotEmpty() }?.joinToString(" "),
                )
            }
        }
    }

    private fun scheduleStoredRetry(actorId: String, accountGeneration: Long?) {
        val next = runCatching { reportQueueStore.nextRecoveryRetryEpochMs(actorId, accountGeneration) }.getOrNull() ?: return
        effects.schedulePostWalkRetry((next - clock.wallTimeMillis()).coerceAtLeast(1L))
    }

    fun postReportQueueDrainNotice(
        trigger: ReportQueueDrainTrigger,
        activityLease: AccountDeletionActivityLease,
        walkEpoch: WalkRuntimeEpoch,
        message: String,
        spokenMessage: String?,
    ) {
        scheduler.postActivityCallback(activityLease) {
            val walk = sources.walkSnapshot()
            val gateway = sources.gatewaySnapshot()
            val drainCompletedWithoutReplacement = synchronized(reportQueueDrainLock) {
                reportQueueDrainTrigger == null &&
                    reportQueueDrainMovementGeneration == trigger.movementGeneration + 1L &&
                    reportQueueDrainNetworkGeneration == trigger.networkGeneration
            }
            if (!drainCompletedWithoutReplacement || !runtime.isForeground() ||
                walk.epoch != walkEpoch || !walk.state.allowsPostWalkTransfer || !walk.isForeground ||
                sources.reporterActorId() != trigger.reporterActorId ||
                gateway.generation != trigger.gatewaySessionGeneration || gateway.session !== trigger.gatewaySession ||
                gateway.storageBlocked || gateway.deletionRecoveryOnly ||
                !trigger.gatewaySession.isUsableFor(trigger.reporterActorId) ||
                !reportPrivacyConsentSession.isGranted() ||
                integratedConsentSession.currentConfirmationOrNull() != trigger.consentConfirmation
            ) return@postActivityCallback
            // Automatic reporting stays silent; an explicit receipt cannot interrupt active output.
            if (spokenMessage != null && !effects.isHandsFreeVoiceOutputActive()) effects.speakStatusExplanation(spokenMessage)
            // Command speech also updates this view. Restore the full mixed-batch result afterwards.
            effects.showWalkLastResult(message)
        }
    }

    fun persistAutomaticReportCooldownBeforeQueueDelete(
        report: QueuedReport,
    ): Boolean {
        if (report.priority != ReportQueuePriority.AUTOMATIC) return true
        val scope = report.automaticCooldownScopeOrNull() ?: return false
        val nowMs = clock.wallTimeMillis()
        val admission = reportAttemptStore.acquire(
            scope = scope,
            nowMs = nowMs,
            bypassAutomaticCooldown = true,
        ) as? AndroidReportAttemptResult.Allowed ?: return false
        return try {
            if (
                !persistCooldownForSuccess(
                    AndroidReportSuccessfulCooldown(
                        scope = scope,
                        lastUploadedAtMs = nowMs,
                        cooldownMonotonicMarkMs = clock.elapsedRealtime(),
                        cooldownRemainingAtMarkMs = AndroidReportCooldownPolicy.AUTOMATIC_COOLDOWN_MS,
                        bootCount = clock.bootCount(),
                    ),
                )
            ) {
                false
            } else {
                reportAttemptStore.markSucceeded(admission.lease, nowMs)
                true
            }
        } finally {
            reportAttemptStore.release(admission.lease)
        }
    }

    fun finishReportQueueDrain(trigger: ReportQueueDrainTrigger): Boolean {
        val finished = synchronized(reportQueueDrainLock) {
            if (reportQueueDrainTrigger !== trigger) {
                false
            } else {
                reportQueueDrainTrigger = null
                reportQueueDrainMovementGeneration += 1L
                true
            }
        }
        if (finished) {
            requestReportQueueCancellation()
            effects.stopStepTrackingNow()
        }
        return finished
    }

    private fun requestReportQueueCancellation() {
        val call = reportQueueDrainCoordinator.fenceActiveAdmission() ?: return
        try { reportQueueDrainCancellationExecutor.execute { call.cancel() } }
        catch (_: RejectedExecutionException) {
            // The atomic fence still rejects late status/POST/results; a live call owns its deadline.
        }
    }

    fun cancelReportQueueDrain() {
        val cancelled = synchronized(reportQueueDrainLock) {
            if (reportQueueDrainTrigger == null) {
                false
            } else {
                reportQueueDrainTrigger = null
                reportQueueDrainMovementGeneration += 1L
                effects.clearStepTrackingEpoch()
                true
            }
        }
        if (cancelled) {
            requestReportQueueCancellation()
            effects.stopStepTrackingNow()
        }
    }

    fun onReportQueueDrainNetworkChanged() {
        effects.cancelRawUploadAndRevalidateIfReady()
        val cancelled = synchronized(reportQueueDrainLock) {
            reportQueueDrainNetworkGeneration += 1L
            if (reportQueueDrainTrigger == null) {
                false
            } else {
                reportQueueDrainTrigger = null
                reportQueueDrainMovementGeneration += 1L
                effects.clearStepTrackingEpoch()
                true
            }
        }
        if (cancelled) {
            requestReportQueueCancellation()
            effects.stopStepTrackingNow()
        }
        // Owner decision 3(b): the changed network may now allow the post-walk transfers.
        effects.schedulePostWalkTransfers()
    }

    private data class ReportQueueDrainRuntimeSnapshot(
        val triggerCurrent: Boolean,
        val walkState: WalkSessionState?,
        val walkSessionId: String?,
        val movementGeneration: Long,
        val networkGeneration: Long,
    )

    private fun ReportQueueDrainContext.allRequiredBaseGatesAllowed(): Boolean =
        walkState.allowsPostWalkTransfer && appForeground && stationary && networkAllowed && consentAllowed && authorityAllowed


    fun reportQueueDrainResultMessage(result: ReportQueueDrainResult): String? = when (result.outcome) {
        ReportQueueDrainOutcome.DELETED_AFTER_STATUS -> "이전에 접수된 신고를 확인했습니다. 중복 접수하지 않았습니다."
        ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD -> when (result.creationOutcome) {
            ReportCreationOutcome.CREATED -> "신고가 접수되었습니다."
            ReportCreationOutcome.REPLAYED -> "이전에 접수된 신고를 확인했습니다. 중복 접수하지 않았습니다."
            null -> "신고 접수를 확인했습니다."
            ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN -> null
        }
        ReportQueueDrainOutcome.RECEIPT_UNCONFIRMED, ReportQueueDrainOutcome.RECEIPT_REJECTED ->
            if (result.reportPriority == ReportQueuePriority.EXPLICIT) "신고 접수를 아직 확인하지 못했습니다. 기기에 보관하고 접수 상태부터 다시 확인합니다." else null
        ReportQueueDrainOutcome.LOCAL_COMMIT_FAILED, ReportQueueDrainOutcome.LOCAL_DELETE_FAILED ->
            if (result.reportPriority == ReportQueuePriority.EXPLICIT) "신고의 기기 저장 상태를 정리하지 못했습니다. 상태를 다시 확인합니다." else null
        ReportQueueDrainOutcome.AUTHENTICATION_REQUIRED -> "신고 접수 상태를 확인하려면 다시 로그인해 주세요."
        ReportQueueDrainOutcome.PERMISSION_BLOCKED -> "현재 신고 전송 권한을 확인할 수 없어 기기에 보관합니다."
        ReportQueueDrainOutcome.RETRY_DEFERRED -> if (
            result.creationOutcome == ReportCreationOutcome.SUPPRESSED_BY_COOLDOWN
        ) "최근 같은 위치의 신고가 있어 이번에는 추가 접수하지 않았습니다." else null
        // The isolated entry stays encrypted on the device; later reports keep draining.
        ReportQueueDrainOutcome.QUARANTINED ->
            "서버가 받을 수 없는 신고는 다시 보내지 않고 기기에 보관했습니다. 다른 신고는 계속 확인합니다."
        else -> null
    }


}
