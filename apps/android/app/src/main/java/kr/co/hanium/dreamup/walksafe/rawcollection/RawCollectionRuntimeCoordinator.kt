package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kr.co.hanium.dreamup.walksafe.device.FeatureStorageAllowance
import kr.co.hanium.dreamup.walksafe.device.FeatureStorageHoldReason
import kr.co.hanium.dreamup.walksafe.device.FeatureStorageHolds
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.StorageDependentFeature
import kr.co.hanium.dreamup.walksafe.navigation.positioning.MOTION_LATENCY_BUCKET_UPPER_BOUNDS_MS
import kr.co.hanium.dreamup.walksafe.navigation.positioning.MotionStateSummary
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.allowsPostWalkTransfer
import kr.co.hanium.dreamup.walksafe.session.isAfterWalkSealPoint
import org.json.JSONArray
import org.json.JSONObject

/** Current app state supplied at every raw runtime boundary; no transition is automated here. */
data class RawCollectionRuntimeContext(
    val session: GatewayFieldSession?,
    val sessionGeneration: Long,
    val deviceCheck: PostLoginDeviceCheckSnapshot,
    val consentSession: IntegratedConsentSession,
    val walk: WalkSessionSnapshot,
    val gatewayWalkLease: GatewayWalkLease?,
    val networkTransport: ActiveNetworkTransport = ActiveNetworkTransport.OFFLINE,
    val networkBinding: IntegratedConsentNetworkBinding? = null,
    /** FP-035-DETAIL-04 interim (owner decision 3(b)): RAW upload needs charging; unknown = not charging. */
    val charging: Boolean = false,
)

sealed interface RawRuntimeMetadataSample {
    val windowStartedAtEpochMs: Long
    val capturedAtEpochMs: Long
}

/**
 * Aggregate detection counters only: no frame, box geometry, location, or route is accepted.
 * [processedFrameCount] counts completed inferences whose result was recorded in the window
 * (the same stage as [RawPerformanceMetadataSample.processedFrameCount]). The optional maximum
 * and fixed-bucket histogram ([RAW_RUNTIME_LATENCY_BUCKET_UPPER_BOUNDS_MS]) describe the slow tail.
 */
data class RawDetectionMetadataSample(
    override val windowStartedAtEpochMs: Long,
    override val capturedAtEpochMs: Long,
    val processedFrameCount: Int,
    /** Missing or different producer identities cannot form a capture batch. */
    val windowIdentity: RawRuntimeWindowIdentity? = null,
    val detectionCount: Int,
    val averageInferenceMs: Long,
    val detectorAvailable: Boolean,
    val modelRevision: String? = null,
    val maxInferenceMs: Long? = null,
    val inferenceMsHistogram: List<Int>? = null,
    val windowCount: Int = 1,
    /** Required for capture (16-17 #6); null only so an unidentified window can be rejected. */
    val runtimeIdentity: RawDetectorRuntimeIdentity? = null,
    /**
     * S1617-18-F2: windows merged although their runtime (identity, model revision or detector
     * availability) differed from the one the segment was opened with; the identity fields keep
     * the opening runtime. Only a segment without an approved queue profile can have them.
     */
    val boundaryWindowCount: Int = 0,
) : RawRuntimeMetadataSample {
    init {
        require(windowStartedAtEpochMs >= 0L)
        require(capturedAtEpochMs >= windowStartedAtEpochMs)
        require(processedFrameCount >= 0)
        require(detectionCount >= 0)
        require(averageInferenceMs >= 0L)
        require(modelRevision == null || RAW_MODEL_REVISION.matches(modelRevision))
        require(windowCount >= 1)
        require(boundaryWindowCount in 0..windowCount)
        require((maxInferenceMs == null) == (inferenceMsHistogram == null))
        require(maxInferenceMs == null || maxInferenceMs >= averageInferenceMs)
        require(
            inferenceMsHistogram == null ||
                validRuntimeHistogram(inferenceMsHistogram, processedFrameCount),
        )
    }
}

/**
 * Aggregate runtime counters only: no audio, video, location, or route payload is accepted.
 * [processedFrameCount] is the completed-inference stage, [droppedFrameCount] counts frames that
 * were due while an inference was still running, and the frame duration fields describe the
 * interval between consecutive completed inferences ([frameDurationSampleCount] intervals).
 */
data class RawPerformanceMetadataSample(
    override val windowStartedAtEpochMs: Long,
    override val capturedAtEpochMs: Long,
    val processedFrameCount: Int,
    /** Missing or different producer identities cannot form a capture batch. */
    val windowIdentity: RawRuntimeWindowIdentity? = null,
    val droppedFrameCount: Int,
    val averageFrameDurationMs: Long,
    val thermalThrottled: Boolean?,
    val maxFrameDurationMs: Long? = null,
    val frameDurationMsHistogram: List<Int>? = null,
    val frameDurationSampleCount: Int? = null,
    val thermalThrottledWindowCount: Int = if (thermalThrottled == true) 1 else 0,
    val windowCount: Int = 1,
    /** ADD-MOTION-045: motion-state aggregate of the window (no coordinate or sensor value). */
    val motion: MotionStateSummary? = null,
) : RawRuntimeMetadataSample {
    init {
        require(windowStartedAtEpochMs >= 0L)
        require(capturedAtEpochMs >= windowStartedAtEpochMs)
        require(processedFrameCount >= 0)
        require(droppedFrameCount >= 0)
        require(averageFrameDurationMs >= 0L)
        require(windowCount >= 1)
        require(thermalThrottledWindowCount in 0..windowCount)
        require((thermalThrottledWindowCount > 0) == (thermalThrottled == true))
        require(
            (maxFrameDurationMs == null) == (frameDurationMsHistogram == null) &&
                (maxFrameDurationMs == null) == (frameDurationSampleCount == null),
        )
        require(maxFrameDurationMs == null || maxFrameDurationMs >= averageFrameDurationMs)
        require(
            frameDurationMsHistogram == null ||
                validRuntimeHistogram(frameDurationMsHistogram, requireNotNull(frameDurationSampleCount)),
        )
    }
}

enum class RawMetadataCaptureResult {
    CAPTURED,
    GATE_BLOCKED,
    RATE_LIMITED,
    STORAGE_FAILURE,
    INVALID_WINDOW,
    /** Queue capacity or device space holds new RAW data; the walk is not affected. */
    CAPACITY_HOLD,
}

enum class RawSegmentSealResult {
    SEALED,
    NOTHING_TO_SEAL,
    GATE_BLOCKED,
    STORAGE_FAILURE,
}

enum class RawCollectionUploadOutcome {
    DELETED_AFTER_EXACT_RECEIPT,
    RECEIPT_REJECTED,
    LOCAL_READ_FAILED,
    ACKNOWLEDGED_PURGE_PENDING,
    QUARANTINED,
    RETRYABLE_FAILURE,
    AUTHORITY_BLOCKED,
    /** Server said raw ingest is disabled (503 raw_ingest_disabled): no retry in this process. */
    SERVER_DISABLED,
    /** Server rejected the bound RAW consent (409); the segment is deleted, never retried (S1617-18-F1). */
    CONSENT_REJECTED,
    /**
     * Owner decision 3(b): the Gateway will never accept the segment's walk (409
     * raw_collection_walk_not_uploadable: unknown, another account or device, past the 30-day
     * window, dropped from its bounded history); the segment is deleted, never retried.
     */
    WALK_REJECTED,
    /**
     * transfer-F7: 409 raw_collection_walk_in_progress (a walk of the account is still active on the
     * Gateway: the END command raced this write, or an earlier walk's lease has not expired yet
     * after a restart). The segment is kept; the caller schedules one delayed retry.
     */
    WALK_IN_PROGRESS,
    CANCELLED,
}

/**
 * Small integration surface for active-walk capture and the post-walk upload.
 *
 * DETECTION/PERFORMANCE aggregates diagnose runtime performance and availability only.
 * They have no ground-truth labels and cannot establish detection accuracy, recall, or mAP;
 * those require a separately approved, labeled evaluation dataset.
 *
 * The runtime shape is fixed at one DETECTION object and one PERFORMANCE object. One segment
 * covers one uninterrupted ACTIVE period: every later capture window is merged into it, so the
 * later part of a long walk is kept (16-17 #1). A segment is closed early only at a boundary the
 * aggregate cannot represent (model or detector change, wall-clock regression). Without an
 * approved queue profile a walk keeps at most [RAW_NO_PROFILE_WALK_SEGMENTS] uploadable segments;
 * once sealing would use that budget, a model/detector boundary is merged and counted instead
 * (S1617-18-F2). Owner decision 3(b) (2026-09-29): upload never starts while a walk is ACTIVE or
 * PAUSED. After the walk (ENDED, SAFE_STOP, or READY with a fresh walk id) the ended walk's segment
 * is sealed and sent on Wi-Fi while charging (FP-035-DETAIL-04 interim), oldest first, under its
 * original walk id and receipt (16-17 #13: never rewritten); a walk that becomes ACTIVE again
 * cancels the upload at its next boundary.
 */
class RawCollectionRuntimeCoordinator private constructor(
    private val store: RawCollectionStore,
    private val client: RawCollectionNetworkClient,
    private val idFactory: () -> UUID,
    private val elapsedRealtimeMs: () -> Long,
    private val minimumCaptureIntervalMs: Long,
    private val allocatableBytes: () -> Long?,
) {
    /**
     * [elapsedRealtimeMs] must be the same clock that produced the Gateway lease's local
     * deadline (Android: SystemClock.elapsedRealtime, which keeps counting in deep sleep).
     * [allocatableBytes] is the OS allocatable space of [rootDirectory]'s volume (14 #39).
     */
    constructor(
        rootDirectory: File,
        elapsedRealtimeMs: () -> Long,
        minimumCaptureIntervalMs: Long = RAW_DEFAULT_CAPTURE_INTERVAL_MS,
        allocatableBytes: () -> Long? = { rootDirectory.usableSpace },
    ) : this(
        store = RawCollectionStore(rootDirectory),
        client = AndroidRawCollectionClient(),
        idFactory = UUID::randomUUID,
        elapsedRealtimeMs = elapsedRealtimeMs,
        minimumCaptureIntervalMs = minimumCaptureIntervalMs,
        allocatableBytes = allocatableBytes,
    )

    internal constructor(
        store: RawCollectionStore,
        client: RawCollectionNetworkClient,
        idFactory: () -> UUID,
        elapsedRealtimeMs: () -> Long,
        minimumCaptureIntervalMs: Long,
        testOnly: Unit = Unit,
        allocatableBytes: () -> Long? = { Long.MAX_VALUE },
    ) : this(store, client, idFactory, elapsedRealtimeMs, minimumCaptureIntervalMs, allocatableBytes)

    private data class UploadLease(
        val owner: RawCollectionOwner,
        val session: GatewayFieldSession,
        val sessionInstanceId: String,
        val sessionGeneration: Long,
        val deviceAttemptGeneration: Long,
        val consentSession: IntegratedConsentSession,
        val confirmation: IntegratedConsentConfirmation,
        /** The segment's own (original) walk id; the post-walk upload never rewrites it (16-17 #13). */
        val segmentWalkSessionId: String,
        val networkBinding: IntegratedConsentNetworkBinding,
    )

    private data class ActiveUpload(
        val lease: UploadLease,
        val call: CancellableNetworkCall<RawCollectionUploadOutcome>,
    )

    private data class Admission(
        val owner: RawCollectionOwner,
        val session: GatewayFieldSession,
        val confirmation: IntegratedConsentConfirmation,
        /** Present only for ACTIVE capture; the post-walk upload requires that none is held. */
        val gatewayWalkLease: GatewayWalkLease?,
    )

    private sealed interface AdmissionResult {
        data class Admitted(val admission: Admission) : AdmissionResult
        data class Blocked(val reason: RawCollectionGateReason) : AdmissionResult
    }

    /** The cumulative content of the current ACTIVE segment (memory only; lost with the process). */
    private data class RuntimeAggregate(
        val owner: RawCollectionOwner,
        val walkSessionId: String,
        val consentReceiptSha256: String,
        val collectionId: String,
        val detection: RawDetectionMetadataSample,
        val performance: RawPerformanceMetadataSample,
    )

    /** Monotonic time of the last stored window; the wall clock is only recorded (16-17 #8). */
    private data class LastCapture(
        val owner: RawCollectionOwner,
        val walkSessionId: String,
        val elapsedRealtimeMs: Long,
    )

    private var retryOwner: RawCollectionOwner? = null
    private var retryNotBeforeElapsedMs = 0L
    private val activeUpload = AtomicReference<ActiveUpload?>()
    private val uploadCancellationGeneration = AtomicLong()
    private var aggregate: RuntimeAggregate? = null
    private var lastCapture: LastCapture? = null

    @Volatile
    private var serverIngestDisabled = false
    private val outcomeCounters = RawCollectionOutcomeCounters()
    /** 14 #39: RAW-only storage holds; recorded internally, never forwarded to the walk. */
    private val storageHolds = FeatureStorageHolds()
    /** S1617-14-F2: receipts replaced in this process whose continue/revoke decision is pending. */
    private val undecidedReceipts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        require(minimumCaptureIntervalMs >= RAW_MIN_CAPTURE_INTERVAL_MS)
    }

    /**
     * S1617-14-F2: call on the accepting thread before a new consent confirmation becomes visible
     * to capture. Until [onConsentContinued] or [onConsentRevoked] records the decision for
     * [previousReceiptSha256] (on the raw executor), a capture that already sees the new receipt
     * seals that receipt's complete aggregate under its own receipt instead of dropping it, and the
     * admission cleanup does not delete its segments as an unproven receipt change. Memory only and
     * lock-free: after the process ends the continuity can no longer be shown and they are deleted.
     */
    fun holdReceiptUntilDecided(previousReceiptSha256: String) {
        if (SHA256_HEX.matches(previousReceiptSha256)) undecidedReceipts += previousReceiptSha256
    }

    /**
     * [observedAtElapsedRealtimeMs] is the monotonic time the window was closed (Android:
     * SystemClock.elapsedRealtime, same boot as the coordinator). It alone decides the minimum
     * interval between stored windows; epoch fields are recorded for retention only.
     */
    @Synchronized
    fun captureMetadataBatch(
        context: RawCollectionRuntimeContext,
        detectionSample: RawDetectionMetadataSample,
        performanceSample: RawPerformanceMetadataSample,
        observedAtElapsedRealtimeMs: Long = elapsedRealtimeMs(),
    ): RawMetadataCaptureResult = try {
        captureMetadataBatchFromStorage(
            context,
            detectionSample,
            performanceSample,
            observedAtElapsedRealtimeMs,
        )
    } catch (_: RawCollectionStorageReadException) {
        aggregate = null
        RawMetadataCaptureResult.STORAGE_FAILURE
    }.also { result ->
        outcomeCounters.record(
            when (result) {
                RawMetadataCaptureResult.CAPTURED -> RawCollectionOutcomeCounter.CAPTURE_CAPTURED
                RawMetadataCaptureResult.GATE_BLOCKED ->
                    return@also
                RawMetadataCaptureResult.RATE_LIMITED ->
                    RawCollectionOutcomeCounter.CAPTURE_RATE_LIMITED
                RawMetadataCaptureResult.STORAGE_FAILURE ->
                    RawCollectionOutcomeCounter.CAPTURE_STORAGE_FAILURE
                RawMetadataCaptureResult.INVALID_WINDOW ->
                    RawCollectionOutcomeCounter.CAPTURE_INVALID_WINDOW
                RawMetadataCaptureResult.CAPACITY_HOLD ->
                    RawCollectionOutcomeCounter.CAPTURE_CAPACITY_HOLD
            },
        )
        when (result) {
            RawMetadataCaptureResult.CAPTURED -> storageHolds.clear(StorageDependentFeature.RAW_COLLECTION)
            RawMetadataCaptureResult.STORAGE_FAILURE -> storageHolds.record(
                StorageDependentFeature.RAW_COLLECTION,
                FeatureStorageHoldReason.IO_FAILURE,
            )
            else -> Unit
        }
    }

    /** Space needed for one more RAW write; null when the write may proceed (14 #39). */
    private fun deviceSpaceHold(): FeatureStorageHoldReason? =
        FeatureStorageAllowance.holdReason(
            runCatching(allocatableBytes).getOrNull(),
            RAW_MAX_COLLECTION_BYTES,
        )?.also { storageHolds.record(StorageDependentFeature.RAW_COLLECTION, it) }

    private fun captureMetadataBatchFromStorage(
        context: RawCollectionRuntimeContext,
        detectionSample: RawDetectionMetadataSample,
        performanceSample: RawPerformanceMetadataSample,
        observedAtElapsedRealtimeMs: Long,
    ): RawMetadataCaptureResult {
        val admission = when (
            val gate = admission(context, requiredState = WalkSessionState.ACTIVE)
        ) {
            is AdmissionResult.Blocked -> {
                outcomeCounters.recordCaptureGate(gate.reason)
                return RawMetadataCaptureResult.GATE_BLOCKED
            }
            is AdmissionResult.Admitted -> gate.admission
        }
        if (
            detectionSample.windowIdentity == null ||
            detectionSample.windowIdentity !== performanceSample.windowIdentity ||
            detectionSample.windowStartedAtEpochMs != performanceSample.windowStartedAtEpochMs ||
            detectionSample.capturedAtEpochMs != performanceSample.capturedAtEpochMs ||
            detectionSample.windowCount != 1 ||
            detectionSample.boundaryWindowCount != 0 ||
            performanceSample.windowCount != 1 ||
            // Both samples count the completed-inference stage (16-17 #4).
            detectionSample.processedFrameCount != performanceSample.processedFrameCount ||
            // The exact detector (hashes, delegate, fallback) must be known (16-17 #6).
            detectionSample.runtimeIdentity == null
        ) return RawMetadataCaptureResult.INVALID_WINDOW
        if (
            detectionSample.capturedAtEpochMs - detectionSample.windowStartedAtEpochMs <
            minimumCaptureIntervalMs
        ) return RawMetadataCaptureResult.RATE_LIMITED
        val walkSessionId = context.walk.epoch.walkSessionId
        val receipt = admission.confirmation.backendConsentReceiptSha256
        val previousCapture = lastCapture?.takeIf {
            it.owner == admission.owner && it.walkSessionId == walkSessionId
        }
        if (
            previousCapture != null &&
            (
                observedAtElapsedRealtimeMs < previousCapture.elapsedRealtimeMs ||
                    observedAtElapsedRealtimeMs - previousCapture.elapsedRealtimeMs <
                    minimumCaptureIntervalMs
                )
        ) return RawMetadataCaptureResult.RATE_LIMITED

        var active = store.activePartialManifest(admission.owner, walkSessionId)
        if (active != null && active.consentReceiptSha256 != receipt) {
            // 16-17 #14: a complete aggregate of a continued receipt (or, S1617-14-F2, of one whose
            // decision is still pending) is sealed under its own receipt; anything else from
            // another receipt is dropped.
            val continued = keepsReceiptAcrossChange(active.consentReceiptSha256) &&
                active.chunks.isCompleteRuntimeBatch() &&
                store.sealActiveSegment(admission.owner, walkSessionId) != null
            if (!continued && !store.discardActivePartial(admission.owner, walkSessionId)) {
                return RawMetadataCaptureResult.STORAGE_FAILURE
            }
            active = null
        }
        val current = aggregate?.takeIf {
            active != null &&
                it.collectionId == active.collectionId &&
                it.owner == admission.owner &&
                it.walkSessionId == walkSessionId &&
                it.consentReceiptSha256 == receipt &&
                active.chunks.isCompleteRuntimeBatch()
        }
        aggregate = null
        var merged = current?.let {
            mergeRuntimeWindow(it.detection, it.performance, detectionSample, performanceSample)
        }
        var boundaryMerged = false
        if (
            current != null &&
            merged == null &&
            store.sealWouldUseNoProfileWalkBudget(walkSessionId, receipt)
        ) {
            // S1617-18-F2: without an approved profile a model/detector boundary must not use the
            // walk's last slot (the rest of the walk would be held): merge and count it instead.
            merged = mergeAcrossBoundary(current.detection, current.performance, detectionSample, performanceSample)
            boundaryMerged = merged != null
        }
        if (merged != null) {
            // The rewrite stores a second copy before the old one is dropped.
            if (deviceSpaceHold() != null) {
                aggregate = current
                return RawMetadataCaptureResult.CAPACITY_HOLD
            }
            return replaceMergedAggregate(
                current = requireNotNull(current),
                owner = admission.owner,
                walkSessionId = walkSessionId,
                receipt = receipt,
                detection = merged.first,
                performance = merged.second,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            ).also { result ->
                if (boundaryMerged && result == RawMetadataCaptureResult.CAPTURED) {
                    outcomeCounters.record(RawCollectionOutcomeCounter.SEGMENT_MERGED_AT_BOUNDARY)
                }
            }
        }
        if (active != null) {
            // A complete earlier segment is closed and kept for the post-walk upload; an incomplete
            // one (legacy single chunk, failed write) cannot be sealed and is dropped.
            if (
                active.chunks.isCompleteRuntimeBatch() &&
                store.sealActiveSegment(admission.owner, walkSessionId) != null
            ) {
                outcomeCounters.record(RawCollectionOutcomeCounter.SEGMENT_SEALED_AT_BOUNDARY)
            } else {
                if (!store.discardActivePartial(admission.owner, walkSessionId)) {
                    return RawMetadataCaptureResult.STORAGE_FAILURE
                }
                if (active.chunks.isNotEmpty()) {
                    outcomeCounters.record(RawCollectionOutcomeCounter.SEGMENT_DISCARDED_INCOMPLETE)
                }
            }
        }
        // 16-17 #18 / 14 #39: a new segment needs queue capacity and device space; otherwise
        // only RAW collection is held (no user message, no walk change).
        val segmentAdmission =
            store.admitNewSegmentReportingStale(walkSessionId, receipt, undecidedReceipts.toSet())
        // transfer-F3: an earlier walk's remainder (its process ended before ENDED) sealed or
        // deleted here is counted like the walk-end seal (16-17 #13 reason record).
        when (segmentAdmission.staleSegment) {
            RawStaleSegmentOutcome.SEALED ->
                outcomeCounters.record(RawCollectionOutcomeCounter.SEGMENT_SEALED_AT_WALK_END)
            RawStaleSegmentOutcome.DISCARDED ->
                outcomeCounters.record(RawCollectionOutcomeCounter.SEGMENT_DISCARDED_AT_WALK_END)
            null -> Unit
        }
        if (segmentAdmission.capacity != RawCapacityAdmission.ALLOWED) {
            return RawMetadataCaptureResult.CAPACITY_HOLD
        }
        if (deviceSpaceHold() != null) return RawMetadataCaptureResult.CAPACITY_HOLD
        val collectionId = idFactory().toString()
        val storedDetection = detectionSample
        val storedPerformance = performanceSample

        if (
            !store.open(
                collectionId = collectionId,
                owner = admission.owner,
                walkSessionId = walkSessionId,
                consentReceiptSha256 = receipt,
                capturedStartedAtEpochMs = storedDetection.windowStartedAtEpochMs,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            )
        ) return RawMetadataCaptureResult.STORAGE_FAILURE

        val detectionPayload = encodeRuntimeMetadata(storedDetection)
        val performancePayload = encodeRuntimeMetadata(storedPerformance)
        val metadata = try {
            store.appendBatch(
                owner = admission.owner,
                walkSessionId = walkSessionId,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
                items = listOf(
                    RawPlaintextBatchItem(
                        type = RawChunkType.DETECTION,
                        capturedAtEpochMs = storedDetection.capturedAtEpochMs,
                        plaintext = detectionPayload,
                    ),
                    RawPlaintextBatchItem(
                        type = RawChunkType.PERFORMANCE,
                        capturedAtEpochMs = storedPerformance.capturedAtEpochMs,
                        plaintext = performancePayload,
                    ),
                ),
            )
        } finally {
            detectionPayload.fill(0)
            performancePayload.fill(0)
        }
        if (metadata != null) {
            aggregate = RuntimeAggregate(
                owner = admission.owner,
                walkSessionId = walkSessionId,
                consentReceiptSha256 = receipt,
                collectionId = collectionId,
                detection = storedDetection,
                performance = storedPerformance,
            )
            lastCapture = LastCapture(admission.owner, walkSessionId, observedAtElapsedRealtimeMs)
            return RawMetadataCaptureResult.CAPTURED
        }
        store.discardActivePartial(admission.owner, walkSessionId)
        return RawMetadataCaptureResult.STORAGE_FAILURE
    }

    /**
     * RAW-CORE-F1: the merged aggregate is written under a fresh collection id and becomes active
     * atomically before the old one is deleted. On any failure (AEAD, ENOSPC, clock stepped back)
     * only the new window is lost: the old PARTIAL stays stored and sealable and [aggregate]
     * keeps pointing at it, so the next window can merge again.
     */
    private fun replaceMergedAggregate(
        current: RuntimeAggregate,
        owner: RawCollectionOwner,
        walkSessionId: String,
        receipt: String,
        detection: RawDetectionMetadataSample,
        performance: RawPerformanceMetadataSample,
        observedAtElapsedRealtimeMs: Long,
    ): RawMetadataCaptureResult {
        val collectionId = idFactory().toString()
        val detectionPayload = encodeRuntimeMetadata(detection)
        val performancePayload = encodeRuntimeMetadata(performance)
        val metadata = try {
            store.replaceActiveBatch(
                newCollectionId = collectionId,
                owner = owner,
                walkSessionId = walkSessionId,
                consentReceiptSha256 = receipt,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
                items = listOf(
                    RawPlaintextBatchItem(
                        type = RawChunkType.DETECTION,
                        capturedAtEpochMs = detection.capturedAtEpochMs,
                        plaintext = detectionPayload,
                    ),
                    RawPlaintextBatchItem(
                        type = RawChunkType.PERFORMANCE,
                        capturedAtEpochMs = performance.capturedAtEpochMs,
                        plaintext = performancePayload,
                    ),
                ),
            )
        } finally {
            detectionPayload.fill(0)
            performancePayload.fill(0)
        }
        if (metadata == null) {
            aggregate = current
            return RawMetadataCaptureResult.STORAGE_FAILURE
        }
        aggregate = current.copy(
            collectionId = collectionId,
            detection = detection,
            performance = performance,
        )
        lastCapture = LastCapture(owner, walkSessionId, observedAtElapsedRealtimeMs)
        return RawMetadataCaptureResult.CAPTURED
    }

    @Synchronized
    fun sealActiveSegment(
        context: RawCollectionRuntimeContext,
    ): RawSegmentSealResult = try {
        sealActiveSegmentFromStorage(context)
    } catch (_: RawCollectionStorageReadException) {
        RawSegmentSealResult.STORAGE_FAILURE
    }

    private fun sealActiveSegmentFromStorage(
        context: RawCollectionRuntimeContext,
    ): RawSegmentSealResult {
        // S1617-13 / owner decision 3(b): only after the walk. The active segment then belongs to an
        // ended walk (this walk at ENDED or SAFE_STOP, or an earlier walk whose process ended first)
        // and is sealed under its own walk id and receipt for the post-walk upload. R4: SAFE_STOP
        // seals here although its upload waits for ENDED or READY.
        val admission = sealAdmissionOrNull(context) ?: return RawSegmentSealResult.GATE_BLOCKED
        val active = store.activePartialManifestOfAnyWalk(admission.owner)
            ?: return RawSegmentSealResult.NOTHING_TO_SEAL
        aggregate = null
        val sealed = (
            active.consentReceiptSha256 == admission.confirmation.backendConsentReceiptSha256 ||
                // S1617-14-F2: a continued or still undecided receipt keeps its aggregate.
                keepsReceiptAcrossChange(active.consentReceiptSha256)
            ) &&
            active.chunks.isCompleteRuntimeBatch() &&
            store.sealActiveSegment(admission.owner, active.walkSessionId) != null
        if (sealed) {
            outcomeCounters.record(RawCollectionOutcomeCounter.SEGMENT_SEALED_AT_WALK_END)
            return RawSegmentSealResult.SEALED
        }
        // S1617-13 (16-17 #13): the discard reason is recorded, not silent.
        store.discardActivePartial(admission.owner, active.walkSessionId)
        outcomeCounters.record(RawCollectionOutcomeCounter.SEGMENT_DISCARDED_AT_WALK_END)
        return RawSegmentSealResult.STORAGE_FAILURE
    }

    /**
     * Sealed segments this device may send after the walk (every ended walk, a selectable receipt).
     * A blocked gate or an unreadable store is never reported as an empty queue (16-17 #12).
     */
    @Synchronized
    fun readyStatus(context: RawCollectionRuntimeContext): RawReadyStatus {
        val admission = when (
            val gate = postWalkAdmission(context)
        ) {
            is AdmissionResult.Blocked -> return RawReadyStatus.GateBlocked(gate.reason)
            is AdmissionResult.Admitted -> gate.admission
        }
        val ready = try {
            store.readyManifests(admission.owner)
        } catch (_: RawCollectionStorageReadException) {
            return RawReadyStatus.ReadFailed
        }
        val receipts = try {
            selectableReceipts(admission.confirmation)
        } catch (_: RawCollectionStorageReadException) {
            return RawReadyStatus.ReadFailed
        }
        return RawReadyStatus.Count(
            ready.count { manifest ->
                isCanonicalUuid(manifest.walkSessionId) &&
                    manifest.consentReceiptSha256 in receipts
            },
        )
    }

    /**
     * Whether the post-walk upload could run now: server-disabled ingest, a walk in progress, the
     * network or charging gate is reported as [RawReadyStatus.GateBlocked], otherwise the
     * [readyStatus] count (16-17 #60).
     */
    @Synchronized
    fun uploadStatus(context: RawCollectionRuntimeContext): RawReadyStatus {
        if (serverIngestDisabled) {
            return RawReadyStatus.GateBlocked(RawCollectionGateReason.SERVER_DISABLED)
        }
        return when (val gate = uploadAdmission(context)) {
            is AdmissionResult.Blocked -> RawReadyStatus.GateBlocked(gate.reason)
            is AdmissionResult.Admitted -> if (retryDelayed(gate.admission.owner)) {
                RawReadyStatus.GateBlocked(RawCollectionGateReason.RETRY_DELAY)
            } else readyStatus(context)
        }
    }

    /** S1617-6-F1: windows the capture accumulator dropped at a detector identity change. */
    @Synchronized
    fun recordIdentitySplitWindows(count: Int) {
        outcomeCounters.record(RawCollectionOutcomeCounter.IDENTITY_SPLIT_WINDOW, count.toLong())
    }

    /** Internal diagnostics only: closed counter names and counts, never shown to the user. */
    @Synchronized
    fun outcomeCountersSnapshot(): Map<String, Long> {
        val expired = store.expiredDiscardCount()
        return outcomeCounters.snapshot().let { counters ->
            if (expired > 0L) {
                counters + (RawCollectionOutcomeCounter.EXPIRED_DISCARD.name.lowercase() to expired)
            } else {
                counters
            }
        } + storageHolds.snapshot()
    }

    @Synchronized
    fun startNextUpload(
        contextProvider: () -> RawCollectionRuntimeContext,
    ): CancellableNetworkCall<RawCollectionUploadOutcome>? {
        if (activeUpload.get() != null) return null
        val cancellationGeneration = uploadCancellationGeneration.get()
        if (serverIngestDisabled) {
            outcomeCounters.recordUploadGate(RawCollectionGateReason.SERVER_DISABLED)
            return null
        }
        val initialContext = contextProvider()
        val admission = when (val gate = uploadAdmission(initialContext)) {
            is AdmissionResult.Blocked -> {
                outcomeCounters.recordUploadGate(gate.reason)
                return null
            }
            is AdmissionResult.Admitted -> gate.admission
        }
        if (retryDelayed(admission.owner)) {
            outcomeCounters.recordUploadGate(RawCollectionGateReason.RETRY_DELAY)
            return null
        }
        val ready = try {
            store.readyManifests(admission.owner)
        } catch (_: RawCollectionStorageReadException) {
            return CancellableNetworkCall.blocking { RawCollectionUploadOutcome.LOCAL_READ_FAILED }
        }
        val receipts = try {
            selectableReceipts(admission.confirmation)
        } catch (_: RawCollectionStorageReadException) {
            return CancellableNetworkCall.blocking { RawCollectionUploadOutcome.LOCAL_READ_FAILED }
        }
        // S1617-13 / owner decision 3(b): any ended walk's segment, oldest first, sent under its own
        // walk id and receipt; the runtime walk id after the walk is never written into it.
        val localManifest = ready.firstOrNull { manifest ->
            isCanonicalUuid(manifest.walkSessionId) &&
                manifest.consentReceiptSha256 in receipts
        } ?: return null
        val backendManifest = localManifest.toBackendManifest() ?: return null
        val uploadBindings = backendManifest.chunkBindings.sortedBy {
            it.localOrdinal
        }.takeIf { bindings ->
            bindings.size == RAW_MAX_CHUNKS &&
                bindings.map(BackendRawChunkBinding::localOrdinal) ==
                (0 until RAW_MAX_CHUNKS).toList()
        } ?: return null
        val networkBinding = requireNotNull(initialContext.networkBinding)
        val lease = UploadLease(
            owner = admission.owner,
            session = admission.session,
            sessionInstanceId = admission.session.lease.instanceId,
            sessionGeneration = initialContext.sessionGeneration,
            deviceAttemptGeneration = initialContext.deviceCheck.attemptGeneration,
            consentSession = initialContext.consentSession,
            confirmation = admission.confirmation,
            segmentWalkSessionId = localManifest.walkSessionId,
            networkBinding = networkBinding,
        )
        val nested = AtomicReference<CancellableNetworkCall<*>?>()
        val executionStarted = AtomicBoolean(false)
        lateinit var outer: CancellableNetworkCall<RawCollectionUploadOutcome>
        outer = CancellableNetworkCall(
            executeBlock = execute@{
                executionStarted.set(true)
                if (outer.isCancelled()) {
                    clearActiveUpload(outer)
                    return@execute RawCollectionUploadOutcome.CANCELLED
                }
                runningRawUploads.incrementAndGet()
                val plaintextChunks = mutableListOf<RawPlaintextChunk>()
                var localWork = true
                try {
                    // Applying an HTTP rejection can fail in local storage too. Keep that
                    // work inside the same failure boundary as receipt persistence.
                    try {
                        if (!isLeaseCurrent(lease, contextProvider())) {
                            return@execute RawCollectionUploadOutcome.CANCELLED
                        }
                        if (localManifest.uploadDisposition == RawUploadDisposition.ACKNOWLEDGED) {
                            return@execute synchronized(this) {
                                if (outer.isCancelled() || !isLeaseCurrent(lease, contextProvider())) {
                                    RawCollectionUploadOutcome.CANCELLED
                                } else if (store.deleteAcknowledged(lease.owner, localManifest.collectionId)) {
                                    RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT
                                } else RawCollectionUploadOutcome.ACKNOWLEDGED_PURGE_PENDING
                            }
                        }
                        uploadBindings.forEach { binding ->
                            val chunk = store.readUploadChunk(
                                collectionId = localManifest.collectionId,
                                owner = lease.owner,
                                ordinal = binding.localOrdinal,
                            ) ?: return@execute RawCollectionUploadOutcome.LOCAL_READ_FAILED
                            if (
                                chunk.metadata.sizeBytes != binding.sizeBytes ||
                                chunk.metadata.sha256 != binding.sha256
                            ) {
                                chunk.plaintext.fill(0)
                                return@execute RawCollectionUploadOutcome.LOCAL_READ_FAILED
                            }
                            plaintextChunks += chunk
                        }
                        localWork = false
                        val uploadCall = client.uploadCall(
                            session = lease.session,
                            // The segment's own (earlier, continued) receipt is sent unchanged; the
                            // server verifies RAW continuity from it (16-17 #14).
                            consent = boundConsentView(lease.confirmation, localManifest.consentReceiptSha256),
                            networkBinding = lease.networkBinding,
                            localManifest = localManifest,
                            backendManifest = backendManifest,
                            chunks = plaintextChunks,
                            isCurrent = {
                                !outer.isCancelled() && isLeaseCurrent(lease, contextProvider())
                            },
                        )
                        nested.set(uploadCall)
                        if (
                            outer.isCancelled() ||
                            !isLeaseCurrent(lease, contextProvider())
                        ) {
                            nested.compareAndSet(uploadCall, null)
                            uploadCall.cancel()
                            return@execute RawCollectionUploadOutcome.CANCELLED
                        }
                        val receipt = uploadCall.execute()
                        localWork = true
                        nested.compareAndSet(uploadCall, null)
                        if (outer.isCancelled() || !isLeaseCurrent(lease, contextProvider())) {
                            return@execute RawCollectionUploadOutcome.CANCELLED
                        }
                        synchronized(this) {
                            if (outer.isCancelled() || !isLeaseCurrent(lease, contextProvider())) {
                                RawCollectionUploadOutcome.CANCELLED
                            } else when (store.completeAfterReceipt(lease.owner, receipt)) {
                                RawReceiptCompletion.DELETED ->
                                    RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT
                                RawReceiptCompletion.INVALID_RECEIPT ->
                                    RawCollectionUploadOutcome.RECEIPT_REJECTED
                                RawReceiptCompletion.ACKNOWLEDGED_PURGE_PENDING ->
                                    RawCollectionUploadOutcome.ACKNOWLEDGED_PURGE_PENDING
                            }
                        }
                    } catch (error: RawCollectionHttpException) {
                        localWork = true
                        if (outer.isCancelled() || !isLeaseCurrent(lease, contextProvider())) {
                            RawCollectionUploadOutcome.CANCELLED
                        } else when (error.statusCode) {
                            400, 413, 415, 422 -> if (store.quarantine(lease.owner, localManifest.collectionId)) {
                                RawCollectionUploadOutcome.QUARANTINED
                            } else RawCollectionUploadOutcome.LOCAL_READ_FAILED
                            401, 403 -> RawCollectionUploadOutcome.AUTHORITY_BLOCKED
                            409 -> when (rawErrorCode(error.errorBody)) {
                                in RAW_CONSENT_REJECTION_CODES -> {
                                    // S1617-18-F1: the server will not accept this consent binding; the
                                    // segment is deleted instead of holding the queue until it expires.
                                    if (store.discardConsentRejected(lease.owner, localManifest.collectionId)) {
                                        RawCollectionUploadOutcome.CONSENT_REJECTED
                                    } else RawCollectionUploadOutcome.LOCAL_READ_FAILED
                                }
                                RAW_WALK_NOT_UPLOADABLE_CODE -> {
                                    // Owner decision 3(b): the Gateway will never accept this walk, so it
                                    // must not hold the queue either (transfer-F8: its own marker).
                                    if (store.discardWalkRejected(lease.owner, localManifest.collectionId)) {
                                        RawCollectionUploadOutcome.WALK_REJECTED
                                    } else RawCollectionUploadOutcome.LOCAL_READ_FAILED
                                }
                                // transfer-F7: kept; the caller retries once after the Gateway lease TTL.
                                RAW_WALK_IN_PROGRESS_CODE -> RawCollectionUploadOutcome.WALK_IN_PROGRESS
                                // The rest: kept for a later try.
                                else -> RawCollectionUploadOutcome.RETRYABLE_FAILURE
                            }
                            503 -> if (rawErrorCode(error.errorBody) == RAW_INGEST_DISABLED_CODE) {
                                // A server setting, not a transient failure: stop until the next
                                // process start instead of retrying on every recheck (16-17 #60).
                                serverIngestDisabled = true
                                RawCollectionUploadOutcome.SERVER_DISABLED
                            } else RawCollectionUploadOutcome.RETRYABLE_FAILURE
                            else -> RawCollectionUploadOutcome.RETRYABLE_FAILURE
                        }.also { outcome ->
                            if (outcome == RawCollectionUploadOutcome.RETRYABLE_FAILURE ||
                                outcome == RawCollectionUploadOutcome.WALK_IN_PROGRESS) {
                                kr.co.hanium.dreamup.walksafe.network.parseHttpRetryAfterMs(error.retryAfterHeader)
                                    ?.takeIf { it > 0L }?.let { delay ->
                                        synchronized(this) {
                                            retryOwner = lease.owner
                                            val now = elapsedRealtimeMs().coerceAtLeast(0L)
                                            retryNotBeforeElapsedMs = now + delay.coerceAtMost(Long.MAX_VALUE - now)
                                        }
                                    }
                            }
                            when (outcome) {
                                RawCollectionUploadOutcome.CANCELLED -> Unit
                                RawCollectionUploadOutcome.SERVER_DISABLED -> outcomeCounters.record(
                                    RawCollectionOutcomeCounter.UPLOAD_SERVER_DISABLED,
                                )
                                RawCollectionUploadOutcome.WALK_REJECTED -> outcomeCounters.record(
                                    RawCollectionOutcomeCounter.UPLOAD_WALK_REJECTED,
                                )
                                else -> outcomeCounters.record(RawCollectionOutcomeCounter.UPLOAD_FAILED)
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    RawCollectionUploadOutcome.CANCELLED
                } catch (_: RawCollectionProtocolException) {
                    if (outer.isCancelled() || !isLeaseContextCurrent(lease, contextProvider())) {
                        RawCollectionUploadOutcome.CANCELLED
                    } else {
                        outcomeCounters.record(RawCollectionOutcomeCounter.UPLOAD_FAILED)
                        RawCollectionUploadOutcome.RECEIPT_REJECTED
                    }
                } catch (_: java.io.IOException) {
                    if (outer.isCancelled() || !isLeaseContextCurrent(lease, contextProvider())) {
                        RawCollectionUploadOutcome.CANCELLED
                    } else {
                        outcomeCounters.record(RawCollectionOutcomeCounter.UPLOAD_FAILED)
                        RawCollectionUploadOutcome.RETRYABLE_FAILURE
                    }
                } catch (_: RuntimeException) {
                    if (outer.isCancelled() || !isLeaseContextCurrent(lease, contextProvider())) {
                        RawCollectionUploadOutcome.CANCELLED
                    } else {
                        outcomeCounters.record(RawCollectionOutcomeCounter.UPLOAD_FAILED)
                        if (localWork) RawCollectionUploadOutcome.LOCAL_READ_FAILED
                        else RawCollectionUploadOutcome.RETRYABLE_FAILURE
                    }
                } finally {
                    runCatching { nested.getAndSet(null)?.cancel() }
                    plaintextChunks.forEach { it.plaintext.fill(0) }
                    runningRawUploads.decrementAndGet()
                    clearActiveUpload(outer)
                }
            },
            cancelBlock = {
                if (!executionStarted.get()) clearActiveUpload(outer)
                nested.getAndSet(null)?.cancel()
            },
        )
        activeUpload.set(ActiveUpload(lease, outer))
        // A UI cancellation can arrive while the store is being read, before publication.
        if (uploadCancellationGeneration.get() != cancellationGeneration) {
            outer.cancel()
            return null
        }
        return outer
    }

    private fun retryDelayed(owner: RawCollectionOwner): Boolean =
        retryOwner == owner && elapsedRealtimeMs() < retryNotBeforeElapsedMs

    // Called from the UI: compare authority in memory, never wait for storage/fsync.
    fun revalidate(context: RawCollectionRuntimeContext): Boolean {
        val active = activeUpload.get() ?: return false
        if (isLeaseContextCurrent(active.lease, context)) return true
        active.call.cancel()
        return false
    }

    /**
     * S1617-13 (16-17 #13) / owner decision 3(b): at ENDED or SAFE_STOP the walk's unsealed segment
     * is kept for the post-walk upload (sealed under its own walk id and receipt) instead of being
     * dropped; a remainder that cannot be sealed is deleted and counted
     * ([RawCollectionOutcomeCounter.SEGMENT_DISCARDED_AT_WALK_END]). Uploads start only after this.
     */
    @Synchronized
    fun onWalkEnded(context: RawCollectionRuntimeContext): RawSegmentSealResult =
        if (!context.walk.state.isWalkEnd()) {
            RawSegmentSealResult.GATE_BLOCKED
        } else {
            sealActiveSegment(context)
        }

    /** Deletes remaining PARTIAL copies; -1 means storage failure, never an empty successful purge. */
    @Synchronized
    fun discardEndedPartials(context: RawCollectionRuntimeContext): Int {
        if (!context.walk.state.isWalkEnd()) return 0
        val owner = ownerOrNull(
            context.session,
            context.sessionGeneration,
            context.deviceCheck,
        ) ?: return 0
        return try {
            store.discardPartials(owner, context.walk.epoch.walkSessionId)
        } catch (_: RawCollectionStorageReadException) {
            -1
        }
    }

    @Synchronized
    fun onConsentRevoked(consentReceiptSha256: String): Boolean {
        activeUpload.get()?.takeIf {
            it.lease.confirmation.backendConsentReceiptSha256 == consentReceiptSha256
        }?.call?.cancel()
        return try {
            store.onConsentRevoked(consentReceiptSha256)
        } finally {
            undecidedReceipts -= consentReceiptSha256
        }
    }

    /** 16-17 #14: RAW stayed granted across a receipt change; earlier segments stay selectable. */
    @Synchronized
    fun onConsentContinued(previousReceiptSha256: String, currentReceiptSha256: String): Boolean =
        // transfer-F1 (R3): a continuity is recorded only under the current policy version, so any
        // earlier-version chain is ended before this one is written.
        (
            store.alignConsentPolicy(INTEGRATED_CONSENT_POLICY_VERSION) &&
                store.onConsentContinued(previousReceiptSha256, currentReceiptSha256)
            ).also { recorded ->
            // An unrecorded continuity stays pending (kept, not selectable) until the process ends.
            if (recorded) undecidedReceipts -= previousReceiptSha256
        }

    /** S1617-14-F2: a receipt whose aggregate survives a receipt change (continued or undecided). */
    private fun keepsReceiptAcrossChange(receiptSha256: String): Boolean =
        receiptSha256 in undecidedReceipts || receiptSha256 in store.continuedReceipts()

    @Synchronized
    fun onAccountDeleted(): Boolean {
        cancelActiveLocked()
        val purged = store.onAccountDeleted()
        // Cancellation can return while the transport still holds decrypted chunks. The slot
        // clears only after its finally block zeroes them; do not attest deletion before that.
        return purged && activeUpload.get() == null && runningRawUploads.get() == 0
    }

    private fun selectableReceipts(current: IntegratedConsentConfirmation): Set<String> =
        store.continuedReceipts() + current.backendConsentReceiptSha256

    private fun boundConsentView(
        current: IntegratedConsentConfirmation,
        boundReceiptSha256: String,
    ): IntegratedConsentConfirmation =
        if (current.backendConsentReceiptSha256 == boundReceiptSha256) current
        else current.copy(backendConsentReceiptSha256 = boundReceiptSha256)

    @Synchronized
    fun resetForNewEnrollment(
        session: GatewayFieldSession,
        sessionGeneration: Long,
        deviceCheck: PostLoginDeviceCheckSnapshot,
    ): Boolean {
        cancelActiveLocked()
        if (activeUpload.get() != null || runningRawUploads.get() != 0) return false
        val owner = ownerOrNull(session, sessionGeneration, deviceCheck) ?: return false
        return store.resetForNewEnrollment(owner)
    }

    fun cancelActiveUpload() {
        uploadCancellationGeneration.incrementAndGet()
        activeUpload.get()?.call?.cancel()
    }

    private fun clearActiveUpload(call: CancellableNetworkCall<RawCollectionUploadOutcome>) {
        val active = activeUpload.get()
        if (active?.call === call) activeUpload.compareAndSet(active, null)
    }

    /**
     * Deletes expired local RAW collections (and quarantines unreadable ones) without waiting
     * for the next capture or paused upload. Returns -1 when the store could not be listed.
     */
    @Synchronized
    fun pruneExpired(): Int = try {
        store.pruneExpired()
    } catch (_: RawCollectionStorageReadException) {
        -1
    }

    private fun uploadAdmissionOrNull(context: RawCollectionRuntimeContext): Admission? =
        (uploadAdmission(context) as? AdmissionResult.Admitted)?.admission

    /**
     * Owner decision 3(b) post-walk upload gate: no walk in progress, no Gateway walk lease held, a
     * current owner and RAW consent, then Wi-Fi (RAW stays Wi-Fi only: FP-035-DETAIL-04 interim and
     * the Gateway GENERAL_RAW contract) and charging (unknown = not charging).
     */
    private fun uploadAdmission(context: RawCollectionRuntimeContext): AdmissionResult {
        val admitted = postWalkAdmission(context)
        if (admitted is AdmissionResult.Blocked) return admitted
        if (
            context.networkTransport != ActiveNetworkTransport.WIFI ||
            context.networkBinding?.transport != IntegratedConsentNetworkTransport.WIFI
        ) return AdmissionResult.Blocked(RawCollectionGateReason.NETWORK)
        if (!context.charging) return AdmissionResult.Blocked(RawCollectionGateReason.CHARGING)
        return admitted
    }

    /** R4: sealing runs at every after-walk state (SAFE_STOP too); no Gateway lease may be held. */
    private fun sealAdmissionOrNull(context: RawCollectionRuntimeContext): Admission? {
        if (!context.walk.state.isAfterWalkSealPoint || context.gatewayWalkLease != null) return null
        return (ownerAdmission(context) as? AdmissionResult.Admitted)?.admission
    }

    private fun postWalkAdmission(context: RawCollectionRuntimeContext): AdmissionResult {
        if (!context.walk.state.allowsPostWalkTransfer) {
            return AdmissionResult.Blocked(RawCollectionGateReason.WALK_STATE)
        }
        // A lease this device still holds means its walk has not ended on the Gateway yet.
        if (context.gatewayWalkLease != null) {
            return AdmissionResult.Blocked(RawCollectionGateReason.GATEWAY_WALK_LEASE)
        }
        return ownerAdmission(context)
    }

    /** Session, owner and current RAW consent; no walk condition. */
    private fun ownerAdmission(context: RawCollectionRuntimeContext): AdmissionResult {
        fun blocked(reason: RawCollectionGateReason) = AdmissionResult.Blocked(reason)
        val session = context.session ?: return blocked(RawCollectionGateReason.SESSION)
        val owner = ownerOrNull(session, context.sessionGeneration, context.deviceCheck)
            ?: return blocked(RawCollectionGateReason.OWNER)
        val confirmation = context.consentSession.currentConfirmationOrNull(
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        ) ?: return blocked(RawCollectionGateReason.CONSENT)
        if (
            confirmation.installationId != owner.deviceId ||
            !SHA256_HEX.matches(confirmation.backendConsentReceiptSha256) ||
            // transfer-F1 (R3): continuity from before this policy version is ended first.
            !store.alignConsentPolicy(confirmation.policyVersion)
        ) return blocked(RawCollectionGateReason.CONSENT_BINDING)
        return AdmissionResult.Admitted(Admission(owner, session, confirmation, gatewayWalkLease = null))
    }

    /** ACTIVE capture: the owner gate plus the current walk's Gateway lease. */
    private fun admission(
        context: RawCollectionRuntimeContext,
        requiredState: WalkSessionState,
    ): AdmissionResult {
        fun blocked(reason: RawCollectionGateReason) = AdmissionResult.Blocked(reason)
        if (context.walk.state != requiredState) return blocked(RawCollectionGateReason.WALK_STATE)
        val base = when (val owned = ownerAdmission(context)) {
            is AdmissionResult.Blocked -> return owned
            is AdmissionResult.Admitted -> owned.admission
        }
        val owner = base.owner
        val walkSessionId = context.walk.epoch.walkSessionId
        if (!isCanonicalUuid(walkSessionId)) return blocked(RawCollectionGateReason.WALK_ID)
        val gatewayLease = context.gatewayWalkLease
            ?: return blocked(RawCollectionGateReason.GATEWAY_WALK_LEASE)
        if (
            gatewayLease.actorId != owner.actorId ||
            gatewayLease.deviceId != owner.deviceId ||
            gatewayLease.walkId != walkSessionId ||
            !gatewayLease.isLocallyUsable(elapsedRealtimeMs())
        ) return blocked(RawCollectionGateReason.GATEWAY_WALK_LEASE)
        return AdmissionResult.Admitted(base.copy(gatewayWalkLease = gatewayLease))
    }

    private fun ownerOrNull(
        session: GatewayFieldSession?,
        sessionGeneration: Long,
        deviceCheck: PostLoginDeviceCheckSnapshot,
    ): RawCollectionOwner? {
        val currentSession = session ?: return null
        if (
            sessionGeneration <= 0L ||
            currentSession.sessionScope != GatewaySessionScope.GENERAL ||
            !currentSession.isBackendAccountDeviceBound ||
            !currentSession.isUsableFor(currentSession.actorId) ||
            !deviceCheck.passesFeatureGate ||
            deviceCheck.actorId != currentSession.actorId ||
            deviceCheck.sessionGeneration != sessionGeneration ||
            deviceCheck.attemptGeneration <= 0L
        ) return null
        val accountGeneration = currentSession.backendAccountGeneration ?: return null
        return runCatching {
            RawCollectionOwner(
                actorId = currentSession.actorId,
                accountGeneration = accountGeneration,
                deviceId = currentSession.deviceId,
            )
        }.getOrNull()
    }

    private fun isLeaseCurrent(
        expected: UploadLease,
        context: RawCollectionRuntimeContext,
    ): Boolean {
        // Worker-side checks retain the persisted policy/consent barrier.
        if (uploadAdmissionOrNull(context) == null) return false
        return isLeaseContextCurrent(expected, context)
    }

    private fun isLeaseContextCurrent(
        expected: UploadLease,
        context: RawCollectionRuntimeContext,
    ): Boolean {
        val session = context.session ?: return false
        val binding = context.networkBinding ?: return false
        val confirmation = context.consentSession.currentConfirmationOrNull(
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        ) ?: return false
        return context.walk.state.allowsPostWalkTransfer &&
            context.gatewayWalkLease == null &&
            context.charging &&
            context.networkTransport == ActiveNetworkTransport.WIFI &&
            binding.transport == IntegratedConsentNetworkTransport.WIFI &&
            ownerOrNull(session, context.sessionGeneration, context.deviceCheck) == expected.owner &&
            session === expected.session &&
            session.lease.instanceId == expected.sessionInstanceId &&
            context.sessionGeneration == expected.sessionGeneration &&
            context.deviceCheck.attemptGeneration == expected.deviceAttemptGeneration &&
            context.consentSession === expected.consentSession &&
            confirmation == expected.confirmation &&
            // The runtime walk id is not compared: after the walk it may move on (ENDED -> READY);
            // a walk that becomes ACTIVE again fails the post-walk gate above and cancels.
            binding.isSameNetworkBinding(expected.networkBinding)
    }

    private fun WalkSessionState.isWalkEnd(): Boolean =
        this == WalkSessionState.ENDED || this == WalkSessionState.SAFE_STOP

    private fun cancelActiveLocked() {
        cancelActiveUpload()
    }
}

enum class RawConsentSupersede { RAW_CONTINUED, RAW_REVOKED }

/**
 * 16-17 #14: a receipt change keeps RAW continuity only when both confirmations grant RAW, the
 * new one is the very next revision for the same installation, and the policy and item versions
 * are unchanged. The server ends continuity at any later consent event that drops RAW or changes
 * a version (`first_raw_collection_consent_interruption`), and this device never saw the events
 * inside a revision gap (another installation, the official web path), so a gap is judged an
 * interruption too (S1617-14-F1). Anything else ends the chain.
 */
fun rawConsentSupersede(
    previous: IntegratedConsentConfirmation?,
    current: IntegratedConsentConfirmation,
    currentRawEffective: Boolean,
): RawConsentSupersede = if (
    previous != null &&
    previous.selections.rawSourceCollection &&
    current.selections.rawSourceCollection &&
    currentRawEffective &&
    previous.installationId == current.installationId &&
    previous.policyVersion == current.policyVersion &&
    previous.itemVersions == current.itemVersions &&
    current.revision == previous.revision + 1L &&
    previous.backendConsentReceiptSha256 != current.backendConsentReceiptSha256
) RawConsentSupersede.RAW_CONTINUED else RawConsentSupersede.RAW_REVOKED

/**
 * Merges the next capture window into the ACTIVE segment aggregate. Returns null at a boundary
 * one aggregate cannot represent (including a different detector runtime identity); the caller
 * then seals the current segment and starts a new one.
 */
private fun mergeRuntimeWindow(
    previousDetection: RawDetectionMetadataSample,
    previousPerformance: RawPerformanceMetadataSample,
    detection: RawDetectionMetadataSample,
    performance: RawPerformanceMetadataSample,
): Pair<RawDetectionMetadataSample, RawPerformanceMetadataSample>? = runCatching {
    if (
        // A wall-clock regression or overlap would make the segment time range inconsistent.
        detection.windowStartedAtEpochMs < previousDetection.capturedAtEpochMs ||
        detection.modelRevision != previousDetection.modelRevision ||
        detection.runtimeIdentity != previousDetection.runtimeIdentity ||
        detection.detectorAvailable != previousDetection.detectorAvailable ||
        (detection.inferenceMsHistogram == null) != (previousDetection.inferenceMsHistogram == null) ||
        (performance.frameDurationMsHistogram == null) !=
        (previousPerformance.frameDurationMsHistogram == null)
    ) return@runCatching null
    val frames = Math.addExact(previousDetection.processedFrameCount, detection.processedFrameCount)
    val windows = Math.addExact(previousDetection.windowCount, detection.windowCount)
    val mergedDetection = RawDetectionMetadataSample(
        windowStartedAtEpochMs = previousDetection.windowStartedAtEpochMs,
        capturedAtEpochMs = detection.capturedAtEpochMs,
        processedFrameCount = frames,
        detectionCount = Math.addExact(previousDetection.detectionCount, detection.detectionCount),
        averageInferenceMs = weightedAverage(
            previousDetection.averageInferenceMs,
            previousDetection.processedFrameCount,
            detection.averageInferenceMs,
            detection.processedFrameCount,
        ),
        detectorAvailable = detection.detectorAvailable,
        modelRevision = detection.modelRevision,
        maxInferenceMs = maxOrNull(previousDetection.maxInferenceMs, detection.maxInferenceMs),
        inferenceMsHistogram = sumHistograms(
            previousDetection.inferenceMsHistogram,
            detection.inferenceMsHistogram,
        ),
        windowCount = windows,
        runtimeIdentity = detection.runtimeIdentity,
        boundaryWindowCount = Math.addExact(
            previousDetection.boundaryWindowCount,
            detection.boundaryWindowCount,
        ),
    )
    val previousIntervals =
        previousPerformance.frameDurationSampleCount ?: previousPerformance.processedFrameCount
    val nextIntervals = performance.frameDurationSampleCount ?: performance.processedFrameCount
    val mergedPerformance = RawPerformanceMetadataSample(
        windowStartedAtEpochMs = previousPerformance.windowStartedAtEpochMs,
        capturedAtEpochMs = performance.capturedAtEpochMs,
        processedFrameCount = Math.addExact(
            previousPerformance.processedFrameCount,
            performance.processedFrameCount,
        ),
        droppedFrameCount = Math.addExact(
            previousPerformance.droppedFrameCount,
            performance.droppedFrameCount,
        ),
        averageFrameDurationMs = weightedAverage(
            previousPerformance.averageFrameDurationMs,
            previousIntervals,
            performance.averageFrameDurationMs,
            nextIntervals,
        ),
        thermalThrottled = when {
            previousPerformance.thermalThrottled == true || performance.thermalThrottled == true -> true
            // An unobserved window cannot establish that the whole segment was unthrottled.
            previousPerformance.thermalThrottled == null || performance.thermalThrottled == null -> null
            else -> false
        },
        maxFrameDurationMs = maxOrNull(
            previousPerformance.maxFrameDurationMs,
            performance.maxFrameDurationMs,
        ),
        frameDurationMsHistogram = sumHistograms(
            previousPerformance.frameDurationMsHistogram,
            performance.frameDurationMsHistogram,
        ),
        frameDurationSampleCount = performance.frameDurationSampleCount?.let {
            Math.addExact(requireNotNull(previousPerformance.frameDurationSampleCount), it)
        },
        thermalThrottledWindowCount = Math.addExact(
            previousPerformance.thermalThrottledWindowCount,
            performance.thermalThrottledWindowCount,
        ),
        windowCount = Math.addExact(previousPerformance.windowCount, performance.windowCount),
        motion = when {
            previousPerformance.motion == null -> performance.motion
            performance.motion == null -> previousPerformance.motion
            else -> previousPerformance.motion.plus(performance.motion)
        },
    )
    mergedDetection to mergedPerformance
}.getOrNull()

/**
 * S1617-18-F2 (no approved profile, walk segment budget in use): merges a window that
 * [mergeRuntimeWindow] treats as a boundary because the detector identity, model revision or
 * detector availability changed. The aggregate keeps the runtime it was opened with and counts
 * the window in `boundary_window_count`, so a mixed aggregate is always marked. A wall-clock
 * regression (inconsistent time range) or a histogram shape change is still not merged (null).
 */
private fun mergeAcrossBoundary(
    previousDetection: RawDetectionMetadataSample,
    previousPerformance: RawPerformanceMetadataSample,
    detection: RawDetectionMetadataSample,
    performance: RawPerformanceMetadataSample,
): Pair<RawDetectionMetadataSample, RawPerformanceMetadataSample>? {
    if (detection.windowStartedAtEpochMs < previousDetection.capturedAtEpochMs) return null
    val boundaryWindow = runCatching {
        detection.copy(
            detectorAvailable = previousDetection.detectorAvailable,
            modelRevision = previousDetection.modelRevision,
            runtimeIdentity = previousDetection.runtimeIdentity,
            boundaryWindowCount = detection.windowCount,
        )
    }.getOrNull() ?: return null
    return mergeRuntimeWindow(previousDetection, previousPerformance, boundaryWindow, performance)
}

private fun weightedAverage(leftAverage: Long, leftWeight: Int, rightAverage: Long, rightWeight: Int): Long {
    val weight = leftWeight.toLong() + rightWeight.toLong()
    if (weight <= 0L) return 0L
    return Math.addExact(
        Math.multiplyExact(leftAverage, leftWeight.toLong()),
        Math.multiplyExact(rightAverage, rightWeight.toLong()),
    ) / weight
}

private fun maxOrNull(left: Long?, right: Long?): Long? =
    if (left == null || right == null) null else maxOf(left, right)

private fun sumHistograms(left: List<Int>?, right: List<Int>?): List<Int>? {
    if (left == null || right == null) return null
    require(left.size == right.size)
    return left.zip(right) { a, b -> Math.addExact(a, b) }
}

internal fun validRuntimeHistogram(histogram: List<Int>, expectedTotal: Int): Boolean =
    histogram.size == RAW_RUNTIME_LATENCY_BUCKET_COUNT &&
        histogram.all { it >= 0 } &&
        histogram.sumOf(Int::toLong) == expectedTotal.toLong()

private fun histogramJson(histogram: List<Int>?): Any = histogram?.let { counts ->
    JSONObject()
        .put("upper_bounds_ms", JSONArray(RAW_RUNTIME_LATENCY_BUCKET_UPPER_BOUNDS_MS))
        .put("counts", JSONArray(counts))
} ?: JSONObject.NULL

/** Closed enum counts and durations only; no location, speed, heading, or sensor value. */
private fun motionJson(motion: MotionStateSummary?): Any = motion?.let { summary ->
    JSONObject()
        .put("stationary_ms", summary.stationaryMs)
        .put("moving_ms", summary.movingMs)
        .put("unknown_ms", summary.unknownMs)
        .put("snapshot_count", summary.snapshotCount)
        .put("unknown_snapshot_count", summary.unknownSnapshotCount)
        .put("transition_count", summary.transitionCount)
        .put(
            "reason_counts",
            JSONObject().also { reasons ->
                summary.reasonCounts.toSortedMap(compareBy { it.name }).forEach { (reason, count) ->
                    reasons.put(reason.name.lowercase(), count)
                }
            },
        )
        .put(
            "stationary_entry_latency_ms_histogram",
            JSONObject()
                .put("upper_bounds_ms", JSONArray(MOTION_LATENCY_BUCKET_UPPER_BOUNDS_MS))
                .put("counts", JSONArray(summary.stationaryEntryLatencyHistogram)),
        )
        .put(
            "stationary_release_latency_ms_histogram",
            JSONObject()
                .put("upper_bounds_ms", JSONArray(MOTION_LATENCY_BUCKET_UPPER_BOUNDS_MS))
                .put("counts", JSONArray(summary.stationaryReleaseLatencyHistogram)),
        )
} ?: JSONObject.NULL

private fun encodeRuntimeMetadata(sample: RawRuntimeMetadataSample): ByteArray {
    val root = JSONObject()
        .put("schema_version", RAW_RUNTIME_METADATA_SCHEMA)
        .put("window_started_at_epoch_ms", sample.windowStartedAtEpochMs)
        .put("captured_at_epoch_ms", sample.capturedAtEpochMs)
    when (sample) {
        is RawDetectionMetadataSample -> root
            .put("kind", "DETECTION")
            .put("window_count", sample.windowCount)
            .put("completed_inference_count", sample.processedFrameCount)
            .put("detection_count", sample.detectionCount)
            .put("average_inference_ms", sample.averageInferenceMs)
            .put("max_inference_ms", sample.maxInferenceMs ?: JSONObject.NULL)
            .put("inference_ms_histogram", histogramJson(sample.inferenceMsHistogram))
            .put("detector_available", sample.detectorAvailable)
            .put("model_revision", sample.modelRevision ?: JSONObject.NULL)
            .put("model_artifact_sha256", requireNotNull(sample.runtimeIdentity).modelArtifactSha256)
            .put("runtime_config_sha256", sample.runtimeIdentity.runtimeConfigSha256)
            .put("class_list_sha256", sample.runtimeIdentity.classListSha256)
            .put("active_delegate", sample.runtimeIdentity.activeDelegate)
            .put("delegate_fallback_used", sample.runtimeIdentity.delegateFallbackUsed)
            // S1617-18-F2: windows merged from another runtime (0 = one exact runtime).
            .put("boundary_window_count", sample.boundaryWindowCount)
        is RawPerformanceMetadataSample -> root
            .put("kind", "PERFORMANCE")
            .put("window_count", sample.windowCount)
            .put("completed_inference_count", sample.processedFrameCount)
            .put("busy_dropped_frame_count", sample.droppedFrameCount)
            .put("frame_interval_count", sample.frameDurationSampleCount ?: JSONObject.NULL)
            .put("average_frame_interval_ms", sample.averageFrameDurationMs)
            .put("max_frame_interval_ms", sample.maxFrameDurationMs ?: JSONObject.NULL)
            .put("frame_interval_ms_histogram", histogramJson(sample.frameDurationMsHistogram))
            .put("thermal_throttled", sample.thermalThrottled ?: JSONObject.NULL)
            .put("thermal_throttled_window_count", sample.thermalThrottledWindowCount)
            .put("motion_state", motionJson(sample.motion))
    }
    return root.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size in 1..RAW_MAX_CHUNK_BYTES)
    }
}

private fun rawErrorCode(errorBody: String): String? = runCatching {
    JSONObject(errorBody).optJSONObject("detail")?.optString("code")?.takeIf(String::isNotEmpty)
}.getOrNull()

internal const val RAW_RUNTIME_METADATA_SCHEMA = "walksafe.android.raw-runtime-metadata.v2"
private const val RAW_MIN_CAPTURE_INTERVAL_MS = 30_000L
private const val RAW_DEFAULT_CAPTURE_INTERVAL_MS = RAW_MIN_CAPTURE_INTERVAL_MS
private val RAW_MODEL_REVISION = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private const val RAW_INGEST_DISABLED_CODE = "raw_ingest_disabled"
/** Gateway 409 for a walk it will never accept (owner decision 3(b)); `raw_collection_walk_in_progress` retries. */
private const val RAW_WALK_NOT_UPLOADABLE_CODE = "raw_collection_walk_not_uploadable"
private const val RAW_WALK_IN_PROGRESS_CODE = "raw_collection_walk_in_progress"
private val RAW_CONSENT_REJECTION_CODES = setOf(
    "raw_consent_receipt_not_found",
    "raw_consent_policy_not_current",
    "raw_source_collection_consent_required",
    "raw_source_collection_consent_interrupted",
)

// Process-wide: a recreated Activity must also wait for old decrypted upload buffers.
private val runningRawUploads = AtomicInteger()
