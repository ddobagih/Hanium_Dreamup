package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.UUID
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.json.JSONObject

/** Owner decision 3(b): the charging input of the post-walk RAW upload (FP-035-DETAIL-04 interim). */
internal fun RawCollectionRuntimeContext.withCharging(charging: Boolean): RawCollectionRuntimeContext =
    copy(charging = charging)

/**
 * Post-walk RAW rig (owner decision 3(b)): the production no-profile store on fake storage/AEAD, a
 * recording upload client, and coordinators that can be replaced to model a process restart.
 * Contexts come from [NoProfileRawFixture] (v7 device session, current RAW consent, device check).
 */
internal class PostWalkRawRig {
    private val template = NoProfileRawFixture()
    var nowEpochMs = NP_BASE_EPOCH_MS
    var elapsedMs = 1_000_000L
    val storage = NoProfileFakeStorage()
    val aead = NoProfileFakeAead()
    val store = RawCollectionStore(storage, aead, { nowEpochMs }, testOnly = Unit, capacityProfile = null)
    val client = RecordingPostWalkClient()
    private var ids = 0
    private var window = 0
    var coordinator = newCoordinator()

    /** A new coordinator on the same stored queue: the process ended and started again. */
    fun restartProcess() {
        coordinator = newCoordinator()
    }

    private fun newCoordinator() = RawCollectionRuntimeCoordinator(
        store = store,
        client = client,
        idFactory = { UUID.nameUUIDFromBytes("post-walk-${ids++}".toByteArray()) },
        elapsedRealtimeMs = { elapsedMs },
        minimumCaptureIntervalMs = 30_000L,
        testOnly = Unit,
        allocatableBytes = { Long.MAX_VALUE },
    )

    fun active(walkId: String): RawCollectionRuntimeContext {
        template.walkId = walkId
        return template.context(WalkSessionState.ACTIVE)
    }

    fun paused(walkId: String): RawCollectionRuntimeContext {
        val base = active(walkId)
        return base.copy(
            walk = WalkSessionSnapshot(
                epoch = WalkRuntimeEpoch(walkId, recoveryGeneration = 1L),
                state = WalkSessionState.PAUSED,
                mode = WalkSessionMode.FULL,
                recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
                isForeground = true,
                readiness = null,
            ),
        ).withCharging(true)
    }

    /** After the walk: no walk in progress and no Gateway walk lease held. */
    fun after(
        walkId: String,
        state: WalkSessionState = WalkSessionState.ENDED,
        charging: Boolean = true,
        transport: ActiveNetworkTransport = ActiveNetworkTransport.WIFI,
    ): RawCollectionRuntimeContext {
        val base = active(walkId)
        return base.copy(
            walk = WalkSessionSnapshot(
                epoch = WalkRuntimeEpoch(walkId, recoveryGeneration = 2L),
                state = state,
                mode = WalkSessionMode.UNAVAILABLE,
                recoveryStage = null,
                isForeground = true,
                readiness = null,
            ),
            gatewayWalkLease = null,
            networkTransport = transport,
            networkBinding = when (transport) {
                ActiveNetworkTransport.WIFI -> base.networkBinding
                ActiveNetworkTransport.CELLULAR -> CELLULAR_BINDING
                else -> null
            },
        ).withCharging(charging)
    }

    fun capture(walkId: String): RawMetadataCaptureResult {
        window += 1
        val start = NP_BASE_EPOCH_MS - 30_000L + window * 30_000L
        val end = NP_BASE_EPOCH_MS + window * 30_000L
        nowEpochMs = end
        elapsedMs += 30_000L
        return coordinator.captureMetadataBatch(
            active(walkId),
            RawDetectionMetadataSample(
        windowIdentity = RAW_TEST_WINDOW_IDENTITY,
                windowStartedAtEpochMs = start, capturedAtEpochMs = end, processedFrameCount = 30,
                detectionCount = 3, averageInferenceMs = 30L, detectorAvailable = true,
                modelRevision = "model-v1", maxInferenceMs = 50L,
                inferenceMsHistogram = MutableList(RAW_RUNTIME_LATENCY_BUCKET_COUNT) { 0 }.also {
                    it[0] += 29
                    it[rawRuntimeLatencyBucket(50L)] += 1
                },
                runtimeIdentity = NP_IDENTITY,
            ),
            RawPerformanceMetadataSample(
        windowIdentity = RAW_TEST_WINDOW_IDENTITY,
                windowStartedAtEpochMs = start, capturedAtEpochMs = end, processedFrameCount = 30,
                droppedFrameCount = 1, averageFrameDurationMs = 100L, thermalThrottled = false,
                maxFrameDurationMs = 150L, frameDurationMsHistogram = listOf(0, 0, 10, 19, 0, 0, 0),
                frameDurationSampleCount = 29,
            ),
        )
    }

    private companion object {
        val CELLULAR_BINDING = IntegratedConsentNetworkBinding.forTest(
            IntegratedConsentNetworkTransport.CELLULAR,
        ) { it.openConnection() }
    }
}

/** Records what each upload sent; [failure] injects one HTTP failure per call while set. */
internal class RecordingPostWalkClient : RawCollectionNetworkClient {
    var failure: RawCollectionHttpException? = null
    var beforeReturn: (() -> Unit)? = null
    var lastPlaintexts: List<ByteArray> = emptyList()
    val sentWalkIds = mutableListOf<String>()
    val sentManifestWalkIds = mutableListOf<String>()
    val sentConsentReceipts = mutableListOf<String>()
    val calls: Int get() = sentWalkIds.size

    override fun uploadCall(
        session: GatewayFieldSession,
        consent: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        chunks: List<RawPlaintextChunk>,
        isCurrent: () -> Boolean,
    ): CancellableNetworkCall<RawCollectionReceipt> {
        lastPlaintexts = chunks.map { it.plaintext }
        sentWalkIds += localManifest.walkSessionId
        sentManifestWalkIds += JSONObject(String(backendManifest.jsonUtf8, Charsets.UTF_8)).getString("walk_id")
        sentConsentReceipts += consent.backendConsentReceiptSha256
        return CancellableNetworkCall.blocking {
            check(isCurrent())
            beforeReturn?.invoke()
            failure?.let { throw it }
            val unsigned = RawCollectionReceipt(
                schemaVersion = BACKEND_RECEIPT_SCHEMA, collectionId = backendManifest.collectionId,
                manifestSha256 = backendManifest.manifestSha256, purpose = BACKEND_RAW_PURPOSE,
                persistenceMarker = RAW_RECEIPT_PERSISTENCE_MARKER, objectCount = backendManifest.objects.size,
                chunkCount = backendManifest.chunkCount, totalBytes = backendManifest.totalBytes,
                objects = backendManifest.objects, retentionClass = BACKEND_RETENTION_CLASS,
                committedAt = "2026-08-29T00:00:00Z", quarantineExpiresAt = "2026-09-12T00:00:00Z",
                receiptSha256 = "0".repeat(64),
            )
            unsigned.copy(receiptSha256 = rawReceiptSha256(unsigned))
        }
    }
}

private val RAW_TEST_WINDOW_IDENTITY = RawRuntimeWindowIdentity()
