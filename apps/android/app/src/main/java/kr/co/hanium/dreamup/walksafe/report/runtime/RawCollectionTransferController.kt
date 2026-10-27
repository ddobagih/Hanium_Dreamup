package kr.co.hanium.dreamup.walksafe.report.runtime

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.rawcollection.RawDetectionMetadataSample
import kr.co.hanium.dreamup.walksafe.rawcollection.RawPerformanceMetadataSample
import kr.co.hanium.dreamup.walksafe.rawcollection.RawCollectionRuntimeCoordinator
import kr.co.hanium.dreamup.walksafe.rawcollection.RawRuntimeWindowAccumulator
import kr.co.hanium.dreamup.walksafe.rawcollection.RawMetadataTaskAdmission
import kr.co.hanium.dreamup.walksafe.rawcollection.RawDetectorRuntimeIdentity
import kr.co.hanium.dreamup.walksafe.rawcollection.RawCollectionRuntimeContext
import kr.co.hanium.dreamup.walksafe.rawcollection.RawCollectionUploadOutcome
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorRuntime
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionTransition
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.navigation.positioning.UserMotionStateSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.allowsPostWalkTransfer
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall

/** Per-window RAW metadata and post-walk transfer scheduling, with the existing RAW authority peer. */
internal class RawCollectionTransferController(
    private val integratedConsentSession: IntegratedConsentSession,
    private val executor: ExecutorService,
    private val runtime: Runtime,
    private val network: Network,
    private val capture: Capture,
    private val log: Log,
    private val postWalk: PostWalk,
    private val wallClockMs: () -> Long,
    private val elapsedRealtimeMs: () -> Long,
) {
    interface Runtime {
        fun sensitiveStorageBlocked(): Boolean = false
        fun isWalkReady(): Boolean
        fun walkSnapshot(): WalkSessionSnapshot
        fun deviceCheckSnapshot(): PostLoginDeviceCheckSnapshot
        fun deletionProcessingBlocked(): Boolean
        fun activeGatewayWalkLeaseOrNull(epoch: WalkRuntimeEpoch): GatewayWalkLease?
        fun isCharging(): Boolean
        fun isForeground(): Boolean
    }
    interface Network {
        fun isReady(): Boolean
        fun currentTransport(): ActiveNetworkTransport
        fun currentBinding(): IntegratedConsentNetworkBinding?
    }
    interface Capture {
        fun detectorAvailable(): Boolean
        fun runtimeIdentity(executedRuntime: AndroidDetectorRuntime?): RawDetectorRuntimeIdentity?
        fun peekMotion(nowElapsedRealtimeMs: Long): UserMotionStateSnapshot?
        fun thermalThrottledIfReady(): Boolean?
    }
    interface Log {
        fun isReady(): Boolean
        fun recordEvent(name: String, counters: Map<String, Long>)
    }
    interface PostWalk {
        fun scheduleTransfers()
        fun scheduleWalkInProgressRetry()
        fun scheduleRetryAfter(delayMs: Long)
    }
    interface Peer {
        fun revalidate(context: RawCollectionRuntimeContext)
        fun cancelActiveUpload()
        fun captureMetadataBatch(context: RawCollectionRuntimeContext, detection: RawDetectionMetadataSample,
            performance: RawPerformanceMetadataSample, observedAtElapsedRealtimeMs: Long)
        fun sealActiveSegment(context: RawCollectionRuntimeContext)
        fun startNextUpload(context: () -> RawCollectionRuntimeContext): CancellableNetworkCall<RawCollectionUploadOutcome>?
        fun onWalkEnded(context: RawCollectionRuntimeContext)
        fun discardEndedPartials(context: RawCollectionRuntimeContext)
        fun recordIdentitySplitWindows(count: Int)
        fun outcomeCountersSnapshot(): Map<String, Long>
        fun nextUploadRetryDelayMs(context: RawCollectionRuntimeContext): Long?
    }
    private lateinit var rawCollectionRuntimeCoordinator: Peer
    private val rawCollectionExecutor get() = executor
    private val rawRuntimeWindow = RawRuntimeWindowAccumulator()
    private val metadataAdmission = RawMetadataTaskAdmission()
    private val rawDetectionDueButInFlightDropCount = AtomicInteger(0)
    @Volatile private var rawCollectionUploadWalkId: String? = null
    private var lastLoggedRawCollectionOutcomes: Map<String, Long>? = null
    fun initializeCoordinator(coordinator: Peer) {
        check(!this::rawCollectionRuntimeCoordinator.isInitialized)
        rawCollectionRuntimeCoordinator = coordinator
    }
    fun markUploadWalk(walkId: String) { rawCollectionUploadWalkId = walkId }
    fun clearUploadWalk() { rawCollectionUploadWalkId = null }
    fun recordDueInferenceDrop() { rawDetectionDueButInFlightDropCount.incrementAndGet() }
    fun shutdownExecutor() { executor.shutdownNow() }

    private fun executeRawMetadataTask(task: () -> Unit) {
        if (!this::rawCollectionRuntimeCoordinator.isInitialized || executor.isShutdown) return
        metadataAdmission.submit(executor, resetWindow = {
            clearRawDetectionWindow(clearPendingDropCount = false)
        }, task = task)
    }

    fun currentRawCollectionRuntimeContextOrNull(): RawCollectionRuntimeContext? {
        if (
            !this::rawCollectionRuntimeCoordinator.isInitialized ||
            !runtime.isWalkReady()
        ) return null
        val gateway = GatewaySessionProcessCoordinator.snapshot()
        val walk = runtime.walkSnapshot()
        val session = gateway.session?.takeUnless {
            runtime.sensitiveStorageBlocked() || gateway.storageBlocked ||
                gateway.deletionRecoveryOnly ||
                runtime.deletionProcessingBlocked()
        }
        val transport = if (network.isReady()) {
            network.currentTransport()
        } else {
            ActiveNetworkTransport.OFFLINE
        }
        return RawCollectionRuntimeContext(
            session = session,
            sessionGeneration = gateway.generation,
            deviceCheck = runtime.deviceCheckSnapshot(),
            consentSession = integratedConsentSession,
            walk = walk,
            gatewayWalkLease = session?.let {
                runtime.activeGatewayWalkLeaseOrNull(walk.epoch)
            },
            networkTransport = transport,
            networkBinding = if (network.isReady()) {
                network.currentBinding()
            } else {
                null
            },
            // Read only after the walk: the RAW upload is the only consumer (fail closed when unknown).
            charging = walk.state.allowsPostWalkTransfer && runtime.isCharging(),
        )
    }

    fun executeRawCollectionTask(task: () -> Unit) {
        if (
            !this::rawCollectionRuntimeCoordinator.isInitialized ||
            rawCollectionExecutor.isShutdown
        ) return
        try {
            rawCollectionExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            Unit
        }
    }

    fun clearRawDetectionWindow(clearPendingDropCount: Boolean = true) {
        rawRuntimeWindow.resetAll()
        if (clearPendingDropCount) {
            rawDetectionDueButInFlightDropCount.set(0)
        }
    }

    fun recordRawCollectionDetectionMetadata(
        expectedWalkEpoch: WalkRuntimeEpoch,
        detectionCount: Int,
        inferenceMs: Long,
        modelRevision: String?,
        executedRuntime: AndroidDetectorRuntime?,
    ) {
        val observedAtEpochMs = wallClockMs()
        val observedAtElapsedMs = elapsedRealtimeMs()
        val detectorWasAvailable = capture.detectorAvailable()
        // S1617-6: hashes come from the verified running instance (S14-33), the delegate and
        // fallback from this inference; an unverified detector yields no identity (rejected).
        val runtimeIdentity = capture.runtimeIdentity(executedRuntime)
        val dueButInFlightDropCount = rawDetectionDueButInFlightDropCount.getAndSet(0)
        executeRawMetadataTask {
            val context = currentRawCollectionRuntimeContextOrNull()
            if (
                context == null ||
                context.walk.state != WalkSessionState.ACTIVE ||
                context.walk.epoch != expectedWalkEpoch
            ) {
                clearRawDetectionWindow()
                return@executeRawMetadataTask
            }
            rawRuntimeWindow.recordCompletedInference(
                walkSessionId = expectedWalkEpoch.walkSessionId,
                observedAtElapsedMs = observedAtElapsedMs,
                detectionCount = detectionCount,
                inferenceMs = inferenceMs,
                busyDroppedSincePrevious = dueButInFlightDropCount,
                modelRevision = modelRevision?.takeIf {
                    RAW_COLLECTION_MODEL_REVISION.matches(it)
                },
                runtimeIdentity = runtimeIdentity,
            )
            // ADD-MOTION-045: the motion state (enums and times only), read after the ACTIVE/epoch
            // and RAW consent checks with a read-only peek (F1): this path never publishes a
            // navigation motion snapshot, so it cannot cause INVALID_TIME or a missed ZUPT there.
            val motionCollectionAllowed = context.consentSession.currentConfirmationOrNull(
                setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
            ) != null
            rawRuntimeWindow.recordMotion(
                if (motionCollectionAllowed) {
                    capture.peekMotion(elapsedRealtimeMs())
                } else {
                    null
                },
                collectionAllowed = motionCollectionAllowed,
            )
            val elapsedWindowMs = rawRuntimeWindow.windowElapsedMs(observedAtElapsedMs)
            if (elapsedWindowMs < RAW_COLLECTION_CAPTURE_INTERVAL_MS) {
                return@executeRawMetadataTask
            }
            val capturedWindowStartedAtEpochMs =
                (observedAtEpochMs - elapsedWindowMs).coerceAtLeast(0L)
            val samples = rawRuntimeWindow.snapshot(
                windowStartedAtEpochMs = capturedWindowStartedAtEpochMs,
                capturedAtEpochMs = observedAtEpochMs,
                detectorAvailable = detectorWasAvailable,
                thermalThrottled = capture.thermalThrottledIfReady(),
            )
            // The next window keeps the last completion time so a stall across the boundary
            // still counts as one long frame interval.
            rawRuntimeWindow.resetWindow()
            rawCollectionRuntimeCoordinator.captureMetadataBatch(
                context,
                samples.detection,
                samples.performance,
                observedAtElapsedRealtimeMs = observedAtElapsedMs,
            )
        }
    }

    fun revalidateRawCollectionRuntime() {
        if (!this::rawCollectionRuntimeCoordinator.isInitialized) return
        val context = currentRawCollectionRuntimeContextOrNull()
        if (context == null) {
            rawCollectionRuntimeCoordinator.cancelActiveUpload()
        } else {
            rawCollectionRuntimeCoordinator.revalidate(context)
        }
    }

    /**
     * Owner decision 3(b) / S1617-13: the RAW upload starts only after the walk (never ACTIVE or
     * PAUSED), in the foreground, on Wi-Fi while charging (FP-035-DETAIL-04 interim) and with no
     * Gateway walk lease held. Each ended walk's segment keeps its own walk id and receipt.
     */
    fun scheduleRawCollectionPostWalkUpload() {
        executeRawCollectionTask {
            val initial = currentRawCollectionRuntimeContextOrNull()
                ?: return@executeRawCollectionTask
            rawCollectionRuntimeCoordinator.revalidate(initial)
            if (
                !initial.walk.state.allowsPostWalkTransfer ||
                !runtime.isForeground() ||
                rawCollectionUploadWalkId != initial.walk.epoch.walkSessionId ||
                initial.networkTransport != ActiveNetworkTransport.WIFI ||
                initial.networkBinding?.transport != IntegratedConsentNetworkTransport.WIFI ||
                !initial.charging ||
                initial.gatewayWalkLease != null
            ) return@executeRawCollectionTask
            // transfer-F3: the batch runs after the walk (often in a later app run), so its outcomes
            // are written to the internal field log at every exit of the batch.
            try {
                // The ended walk's segment (or an earlier walk's, left by a process end) is sealed first.
                rawCollectionRuntimeCoordinator.sealActiveSegment(initial)
                // One task uploads a bounded batch so resume, withdrawal and metadata tasks queued on
                // the same executor are not held behind a large backlog. Remaining items continue in a
                // new task that revalidates the post-walk, Wi-Fi, charging and consent gates from scratch.
                repeat(RAW_COLLECTION_POST_WALK_UPLOADS_PER_TASK) {
                    val fallback = currentRawCollectionRuntimeContextOrNull()
                        ?: return@executeRawCollectionTask
                    val call = rawCollectionRuntimeCoordinator.startNextUpload {
                        currentRawCollectionRuntimeContextOrNull()
                            ?: fallback.copy(session = null, gatewayWalkLease = null)
                    } ?: run {
                        rawCollectionRuntimeCoordinator.nextUploadRetryDelayMs(fallback)?.let { delay ->
                            postWalk.scheduleRetryAfter(delay)
                            return@executeRawCollectionTask
                        }
                        rawCollectionUploadWalkId = null
                        return@executeRawCollectionTask
                    }
                    val completed = runCatching { call.execute() }.getOrNull()
                    val retryDelay = rawCollectionRuntimeCoordinator.nextUploadRetryDelayMs(fallback)
                    if (retryDelay != null) {
                        postWalk.scheduleRetryAfter(retryDelay)
                    } else if (completed == RawCollectionUploadOutcome.WALK_IN_PROGRESS) {
                        postWalk.scheduleWalkInProgressRetry()
                    }
                    if (
                        completed != RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT &&
                        completed != RawCollectionUploadOutcome.QUARANTINED &&
                        completed != RawCollectionUploadOutcome.WALK_REJECTED
                    ) return@executeRawCollectionTask
                }
            } finally {
                recordRawCollectionOutcomesIfChanged()
            }
            scheduleRawCollectionPostWalkUpload()
        }
    }

    fun handleRawCollectionTransition(
        transition: WalkSessionTransition,
        event: WalkSessionEvent,
    ) {
        if (!transition.changed || !this::rawCollectionRuntimeCoordinator.isInitialized) return
        revalidateRawCollectionRuntime()
        when {
            transition.current.state == WalkSessionState.ENDED ||
                transition.current.state == WalkSessionState.SAFE_STOP -> {
                rawCollectionUploadWalkId = null
                rawCollectionRuntimeCoordinator.cancelActiveUpload()
                executeRawCollectionTask {
                    val context = currentRawCollectionRuntimeContextOrNull()
                        ?: return@executeRawCollectionTask
                    // S1617-13 (16-17 #13) / owner decision 3(b): the ended walk's segment is kept
                    // (sealed for the post-walk upload); only an unsealable remainder is deleted,
                    // and both are counted in the outcome log below.
                    rawCollectionRuntimeCoordinator.onWalkEnded(context)
                    rawCollectionRuntimeCoordinator.discardEndedPartials(context)
                    clearRawDetectionWindow()
                    // S1617-6-F1: windows dropped at an identity change become an internal count.
                    rawCollectionRuntimeCoordinator.recordIdentitySplitWindows(rawRuntimeWindow.takeIdentitySplitCount())
                    // 16-17 #12: counts only, debug field log only; never spoken or shown.
                    if (log.isReady()) {
                        val outcomes = rawCollectionRuntimeCoordinator.outcomeCountersSnapshot()
                        lastLoggedRawCollectionOutcomes = outcomes
                        log.recordEvent("raw_collection_outcomes", outcomes)
                    }
                }
                postWalk.scheduleTransfers()
            }
            // Owner decision 3(b): PAUSED is still the walk, so it starts no upload.
            transition.current.state != WalkSessionState.PAUSED -> {
                rawCollectionUploadWalkId = null
                executeRawCollectionTask(::clearRawDetectionWindow)
                if (
                    transition.current.state.allowsPostWalkTransfer &&
                    (
                        !transition.previous.state.allowsPostWalkTransfer ||
                            transition.previous.epoch.walkSessionId !=
                            transition.current.epoch.walkSessionId
                        )
                ) {
                    postWalk.scheduleTransfers()
                }
            }
        }
    }

    /**
     * transfer-F3: internal diagnostics only (debug field log; never spoken or shown). The counters
     * are written when a post-walk batch ends, once per change, and at walk end.
     */
    fun recordRawCollectionOutcomesIfChanged() {
        if (!log.isReady()) return
        val outcomes = rawCollectionRuntimeCoordinator.outcomeCountersSnapshot()
        if (outcomes == lastLoggedRawCollectionOutcomes) return
        lastLoggedRawCollectionOutcomes = outcomes
        log.recordEvent("raw_collection_outcomes", outcomes)
    }

    private companion object {
        const val RAW_COLLECTION_CAPTURE_INTERVAL_MS = 30_000L
        const val RAW_COLLECTION_POST_WALK_UPLOADS_PER_TASK = 4
        val RAW_COLLECTION_MODEL_REVISION = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
