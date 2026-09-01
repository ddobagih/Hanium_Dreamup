package kr.co.hanium.dreamup.walksafe.rawcollection

import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
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
)

sealed interface RawRuntimeMetadataSample {
    val windowStartedAtEpochMs: Long
    val capturedAtEpochMs: Long
}

/** Aggregate detection counters only: no frame, box geometry, location, or route is accepted. */
data class RawDetectionMetadataSample(
    override val windowStartedAtEpochMs: Long,
    override val capturedAtEpochMs: Long,
    val processedFrameCount: Int,
    val detectionCount: Int,
    val averageInferenceMs: Long,
    val detectorAvailable: Boolean,
    val modelRevision: String? = null,
) : RawRuntimeMetadataSample {
    init {
        require(windowStartedAtEpochMs >= 0L)
        require(capturedAtEpochMs >= windowStartedAtEpochMs)
        require(processedFrameCount >= 0)
        require(detectionCount >= 0)
        require(averageInferenceMs >= 0L)
        require(modelRevision == null || RAW_MODEL_REVISION.matches(modelRevision))
    }
}

/** Aggregate runtime counters only: no audio, video, location, or route payload is accepted. */
data class RawPerformanceMetadataSample(
    override val windowStartedAtEpochMs: Long,
    override val capturedAtEpochMs: Long,
    val processedFrameCount: Int,
    val droppedFrameCount: Int,
    val averageFrameDurationMs: Long,
    val thermalThrottled: Boolean?,
) : RawRuntimeMetadataSample {
    init {
        require(windowStartedAtEpochMs >= 0L)
        require(capturedAtEpochMs >= windowStartedAtEpochMs)
        require(processedFrameCount >= 0)
        require(droppedFrameCount >= 0)
        require(averageFrameDurationMs >= 0L)
    }
}

enum class RawMetadataCaptureResult {
    CAPTURED,
    GATE_BLOCKED,
    RATE_LIMITED,
    SEGMENT_ALREADY_FULL,
    STORAGE_FAILURE,
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
    CANCELLED,
}

/**
 * Small integration surface for active-walk capture and explicit pause/recheck upload.
 *
 * The runtime shape is fixed at one DETECTION object and one PERFORMANCE object. Upload never
 * starts while the local walk is ACTIVE or ENDED, and the Gateway walk lease must remain active.
 */
class RawCollectionRuntimeCoordinator private constructor(
    private val store: RawCollectionStore,
    private val client: RawCollectionNetworkClient,
    private val idFactory: () -> UUID,
    private val elapsedRealtimeMs: () -> Long,
    private val minimumCaptureIntervalMs: Long,
) {
    constructor(
        rootDirectory: File,
        minimumCaptureIntervalMs: Long = RAW_DEFAULT_CAPTURE_INTERVAL_MS,
    ) : this(
        store = RawCollectionStore(rootDirectory),
        client = AndroidRawCollectionClient(),
        idFactory = UUID::randomUUID,
        elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
        minimumCaptureIntervalMs = minimumCaptureIntervalMs,
    )

    internal constructor(
        store: RawCollectionStore,
        client: RawCollectionNetworkClient,
        idFactory: () -> UUID,
        elapsedRealtimeMs: () -> Long,
        minimumCaptureIntervalMs: Long,
        testOnly: Unit = Unit,
    ) : this(store, client, idFactory, elapsedRealtimeMs, minimumCaptureIntervalMs)

    private data class UploadLease(
        val owner: RawCollectionOwner,
        val session: GatewayFieldSession,
        val sessionInstanceId: String,
        val sessionGeneration: Long,
        val deviceAttemptGeneration: Long,
        val consentSession: IntegratedConsentSession,
        val confirmation: IntegratedConsentConfirmation,
        val walkSessionId: String,
        val gatewayWalkLease: GatewayWalkLease,
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
        val gatewayWalkLease: GatewayWalkLease,
    )

    private var activeUpload: ActiveUpload? = null

    init {
        require(minimumCaptureIntervalMs >= RAW_MIN_CAPTURE_INTERVAL_MS)
    }

    @Synchronized
    fun captureMetadataBatch(
        context: RawCollectionRuntimeContext,
        detectionSample: RawDetectionMetadataSample,
        performanceSample: RawPerformanceMetadataSample,
    ): RawMetadataCaptureResult {
        val admission = admissionOrNull(context, requiredState = WalkSessionState.ACTIVE)
            ?: return RawMetadataCaptureResult.GATE_BLOCKED
        if (
            detectionSample.windowStartedAtEpochMs != performanceSample.windowStartedAtEpochMs ||
            detectionSample.capturedAtEpochMs != performanceSample.capturedAtEpochMs
        ) return RawMetadataCaptureResult.STORAGE_FAILURE
        if (
            detectionSample.capturedAtEpochMs - detectionSample.windowStartedAtEpochMs <
            minimumCaptureIntervalMs
        ) return RawMetadataCaptureResult.RATE_LIMITED
        val walkSessionId = context.walk.epoch.walkSessionId

        var active = store.activePartialManifest(admission.owner, walkSessionId)
        if (
            active != null &&
            active.consentReceiptSha256 != admission.confirmation.backendConsentReceiptSha256
        ) {
            if (!store.discardActivePartial(admission.owner, walkSessionId)) {
                return RawMetadataCaptureResult.STORAGE_FAILURE
            }
            active = null
        }
        if (active?.chunks?.isCompleteRuntimeBatch() == true) {
            return RawMetadataCaptureResult.SEGMENT_ALREADY_FULL
        }
        if (active?.chunks?.isNotEmpty() == true) {
            if (!store.discardActivePartial(admission.owner, walkSessionId)) {
                return RawMetadataCaptureResult.STORAGE_FAILURE
            }
            active = null
        }

        val previousCapture = store.latestCapturedAtEpochMs(admission.owner, walkSessionId)
        if (
            previousCapture != null &&
            (
                detectionSample.capturedAtEpochMs < previousCapture ||
                    detectionSample.capturedAtEpochMs - previousCapture < minimumCaptureIntervalMs
                )
        ) return RawMetadataCaptureResult.RATE_LIMITED

        val openedHere = active == null
        if (
            openedHere &&
            !store.open(
                collectionId = idFactory().toString(),
                owner = admission.owner,
                walkSessionId = walkSessionId,
                consentReceiptSha256 = admission.confirmation.backendConsentReceiptSha256,
                capturedStartedAtEpochMs = detectionSample.windowStartedAtEpochMs,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            )
        ) return RawMetadataCaptureResult.STORAGE_FAILURE

        val detectionPayload = encodeRuntimeMetadata(detectionSample)
        val performancePayload = encodeRuntimeMetadata(performanceSample)
        val metadata = try {
            store.appendBatch(
                owner = admission.owner,
                walkSessionId = walkSessionId,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
                items = listOf(
                    RawPlaintextBatchItem(
                        type = RawChunkType.DETECTION,
                        capturedAtEpochMs = detectionSample.capturedAtEpochMs,
                        plaintext = detectionPayload,
                    ),
                    RawPlaintextBatchItem(
                        type = RawChunkType.PERFORMANCE,
                        capturedAtEpochMs = performanceSample.capturedAtEpochMs,
                        plaintext = performancePayload,
                    ),
                ),
            )
        } finally {
            detectionPayload.fill(0)
            performancePayload.fill(0)
        }
        if (metadata != null) return RawMetadataCaptureResult.CAPTURED
        if (openedHere || store.activePartialManifest(admission.owner, walkSessionId) != null) {
            store.discardActivePartial(admission.owner, walkSessionId)
        }
        return RawMetadataCaptureResult.STORAGE_FAILURE
    }

    @Synchronized
    fun sealActiveSegment(
        context: RawCollectionRuntimeContext,
    ): RawSegmentSealResult {
        val admission = admissionOrNull(context, requiredState = WalkSessionState.PAUSED)
            ?: return RawSegmentSealResult.GATE_BLOCKED
        val walkSessionId = context.walk.epoch.walkSessionId
        val active = store.activePartialManifest(admission.owner, walkSessionId)
            ?: return RawSegmentSealResult.NOTHING_TO_SEAL
        if (
            active.consentReceiptSha256 != admission.confirmation.backendConsentReceiptSha256 ||
            !active.chunks.isCompleteRuntimeBatch()
        ) {
            store.discardActivePartial(admission.owner, walkSessionId)
            return RawSegmentSealResult.STORAGE_FAILURE
        }
        if (store.sealActiveSegment(admission.owner, walkSessionId) != null) {
            return RawSegmentSealResult.SEALED
        }
        store.discardActivePartial(admission.owner, walkSessionId)
        return RawSegmentSealResult.STORAGE_FAILURE
    }

    @Synchronized
    fun readyCount(context: RawCollectionRuntimeContext): Int {
        val admission = admissionOrNull(context, requiredState = WalkSessionState.PAUSED)
            ?: return 0
        return store.readyManifests(admission.owner).count { manifest ->
            manifest.walkSessionId == context.walk.epoch.walkSessionId &&
                manifest.consentReceiptSha256 ==
                admission.confirmation.backendConsentReceiptSha256
        }
    }

    @Synchronized
    fun startNextUpload(
        contextProvider: () -> RawCollectionRuntimeContext,
    ): CancellableNetworkCall<RawCollectionUploadOutcome>? {
        if (activeUpload != null) return null
        val initialContext = contextProvider()
        val admission = uploadAdmissionOrNull(initialContext) ?: return null
        val localManifest = store.readyManifests(admission.owner).firstOrNull { manifest ->
            manifest.walkSessionId == initialContext.walk.epoch.walkSessionId &&
                manifest.consentReceiptSha256 ==
                admission.confirmation.backendConsentReceiptSha256
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
            walkSessionId = initialContext.walk.epoch.walkSessionId,
            gatewayWalkLease = admission.gatewayWalkLease,
            networkBinding = networkBinding,
        )
        val nested = AtomicReference<CancellableNetworkCall<*>?>()
        lateinit var outer: CancellableNetworkCall<RawCollectionUploadOutcome>
        outer = CancellableNetworkCall(
            executeBlock = execute@{
                val plaintextChunks = mutableListOf<RawPlaintextChunk>()
                try {
                    if (!isLeaseCurrent(lease, contextProvider())) {
                        return@execute RawCollectionUploadOutcome.CANCELLED
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
                    val uploadCall = client.uploadCall(
                        session = lease.session,
                        consent = lease.confirmation,
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
                    nested.compareAndSet(uploadCall, null)
                    if (!isLeaseCurrent(lease, contextProvider())) {
                        return@execute RawCollectionUploadOutcome.CANCELLED
                    }
                    if (store.deleteAfterReceipt(lease.owner, receipt)) {
                        RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT
                    } else {
                        RawCollectionUploadOutcome.RECEIPT_REJECTED
                    }
                } catch (_: CancellationException) {
                    RawCollectionUploadOutcome.CANCELLED
                } finally {
                    plaintextChunks.forEach { it.plaintext.fill(0) }
                    synchronized(this) {
                        if (activeUpload?.call === outer) activeUpload = null
                    }
                }
            },
            cancelBlock = {
                nested.getAndSet(null)?.cancel()
                synchronized(this) {
                    if (activeUpload?.call === outer) activeUpload = null
                }
            },
        )
        activeUpload = ActiveUpload(lease, outer)
        return outer
    }

    @Synchronized
    fun revalidate(context: RawCollectionRuntimeContext): Boolean {
        val active = activeUpload ?: return false
        if (isLeaseCurrent(active.lease, context)) return true
        activeUpload = null
        active.call.cancel()
        return false
    }

    @Synchronized
    fun onWalkEnded(context: RawCollectionRuntimeContext): Boolean {
        cancelActiveLocked()
        if (context.walk.state != WalkSessionState.ENDED) return false
        val owner = ownerOrNull(
            context.session,
            context.sessionGeneration,
            context.deviceCheck,
        ) ?: return false
        return store.onSessionEnded(owner, context.walk.epoch.walkSessionId)
    }

    @Synchronized
    fun discardEndedPartials(context: RawCollectionRuntimeContext): Int {
        if (context.walk.state != WalkSessionState.ENDED) return 0
        val owner = ownerOrNull(
            context.session,
            context.sessionGeneration,
            context.deviceCheck,
        ) ?: return 0
        return store.discardPartials(owner, context.walk.epoch.walkSessionId)
    }

    @Synchronized
    fun onConsentRevoked(consentReceiptSha256: String): Boolean {
        cancelActiveLocked()
        return store.onConsentRevoked(consentReceiptSha256)
    }

    @Synchronized
    fun onAccountDeleted(): Boolean {
        cancelActiveLocked()
        return store.onAccountDeleted()
    }

    @Synchronized
    fun resetForNewEnrollment(
        session: GatewayFieldSession,
        sessionGeneration: Long,
        deviceCheck: PostLoginDeviceCheckSnapshot,
    ): Boolean {
        cancelActiveLocked()
        val owner = ownerOrNull(session, sessionGeneration, deviceCheck) ?: return false
        return store.resetForNewEnrollment(owner)
    }

    @Synchronized
    fun cancelActiveUpload() {
        cancelActiveLocked()
    }

    private fun uploadAdmissionOrNull(context: RawCollectionRuntimeContext): Admission? {
        if (
            context.walk.recoveryStage != WalkSessionRecoveryStage.RECHECK_REQUIRED ||
            context.networkTransport != ActiveNetworkTransport.WIFI ||
            context.networkBinding?.transport != IntegratedConsentNetworkTransport.WIFI
        ) return null
        return admissionOrNull(context, requiredState = WalkSessionState.PAUSED)
    }

    private fun admissionOrNull(
        context: RawCollectionRuntimeContext,
        requiredState: WalkSessionState,
    ): Admission? {
        if (context.walk.state != requiredState) return null
        if (
            requiredState == WalkSessionState.PAUSED &&
            context.walk.recoveryStage == null
        ) return null
        val session = context.session ?: return null
        val owner = ownerOrNull(session, context.sessionGeneration, context.deviceCheck)
            ?: return null
        val confirmation = context.consentSession.currentConfirmationOrNull(
            setOf(IntegratedConsentItem.RAW_SOURCE_COLLECTION),
        ) ?: return null
        if (
            confirmation.installationId != owner.deviceId ||
            !SHA256_HEX.matches(confirmation.backendConsentReceiptSha256)
        ) return null
        val walkSessionId = context.walk.epoch.walkSessionId
        if (!isCanonicalUuid(walkSessionId)) return null
        val gatewayLease = context.gatewayWalkLease ?: return null
        if (
            gatewayLease.actorId != owner.actorId ||
            gatewayLease.deviceId != owner.deviceId ||
            gatewayLease.walkId != walkSessionId ||
            !gatewayLease.isLocallyUsable(elapsedRealtimeMs())
        ) return null
        return Admission(owner, session, confirmation, gatewayLease)
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
        val admission = uploadAdmissionOrNull(context) ?: return false
        val binding = context.networkBinding ?: return false
        return admission.owner == expected.owner &&
            admission.session === expected.session &&
            admission.session.lease.instanceId == expected.sessionInstanceId &&
            context.sessionGeneration == expected.sessionGeneration &&
            context.deviceCheck.attemptGeneration == expected.deviceAttemptGeneration &&
            context.consentSession === expected.consentSession &&
            admission.confirmation == expected.confirmation &&
            context.walk.epoch.walkSessionId == expected.walkSessionId &&
            admission.gatewayWalkLease == expected.gatewayWalkLease &&
            binding.isSameNetworkBinding(expected.networkBinding)
    }

    private fun cancelActiveLocked() {
        val active = activeUpload ?: return
        activeUpload = null
        active.call.cancel()
    }
}

private fun encodeRuntimeMetadata(sample: RawRuntimeMetadataSample): ByteArray {
    val root = JSONObject()
        .put("schema_version", RAW_RUNTIME_METADATA_SCHEMA)
        .put("window_started_at_epoch_ms", sample.windowStartedAtEpochMs)
        .put("captured_at_epoch_ms", sample.capturedAtEpochMs)
    when (sample) {
        is RawDetectionMetadataSample -> root
            .put("kind", "DETECTION")
            .put("processed_frame_count", sample.processedFrameCount)
            .put("detection_count", sample.detectionCount)
            .put("average_inference_ms", sample.averageInferenceMs)
            .put("detector_available", sample.detectorAvailable)
            .put("model_revision", sample.modelRevision ?: JSONObject.NULL)
        is RawPerformanceMetadataSample -> root
            .put("kind", "PERFORMANCE")
            .put("processed_frame_count", sample.processedFrameCount)
            .put("dropped_frame_count", sample.droppedFrameCount)
            .put("average_frame_duration_ms", sample.averageFrameDurationMs)
            .put("thermal_throttled", sample.thermalThrottled ?: JSONObject.NULL)
    }
    return root.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size in 1..RAW_MAX_CHUNK_BYTES)
    }
}

private const val RAW_RUNTIME_METADATA_SCHEMA = "walksafe.android.raw-runtime-metadata.v1"
private const val RAW_MIN_CAPTURE_INTERVAL_MS = 30_000L
private const val RAW_DEFAULT_CAPTURE_INTERVAL_MS = RAW_MIN_CAPTURE_INTERVAL_MS
private val RAW_MODEL_REVISION = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
