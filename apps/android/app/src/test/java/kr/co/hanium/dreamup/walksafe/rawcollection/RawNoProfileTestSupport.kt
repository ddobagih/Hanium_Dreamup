package kr.co.hanium.dreamup.walksafe.rawcollection

import java.util.UUID
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.BackendAccountDeviceCookieBinding
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.security.AeadBlockReason
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessRequirement
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.json.JSONObject

/**
 * Shared no-profile RAW fixture for RawNoProfileQueueCoverageTest, RawReceiptSupersedeBarrierTest and
 * RawIdentitySplitCounterTest: capacityProfile = null (the production setting), fake storage with
 * quarantine, fake AEAD and a fake upload client with failure injection. No test methods here.
 */
internal class NoProfileRawFixture {
    var nowEpochMs = NP_BASE_EPOCH_MS
    var elapsedMs = 1_000_000L
    var walkId = NP_WALK_1
    val storage = NoProfileFakeStorage()
    val store = RawCollectionStore(storage, NoProfileFakeAead(), { nowEpochMs }, testOnly = Unit, capacityProfile = null)
    private var nextId = 0
    val client = NoProfileFakeClient()
    val coordinator = RawCollectionRuntimeCoordinator(
        store = store,
        client = client,
        idFactory = { UUID.nameUUIDFromBytes("no-profile-${nextId++}".toByteArray()) },
        elapsedRealtimeMs = { elapsedMs },
        minimumCaptureIntervalMs = 30_000L,
        testOnly = Unit,
        allocatableBytes = { Long.MAX_VALUE },
    )
    private val deviceCheck = PostLoginDeviceCheckSnapshot(
        state = PostLoginDeviceCheckState.FULL, actorId = NP_ACTOR_ID, sessionGeneration = 7L, attemptGeneration = 1L,
    )
    private val consentSession = IntegratedConsentSession().also { check(it.accept(confirmation())) }
    private val session = GatewayFieldSession.backendAccountDeviceSession(
        gatewayBaseUrl = "http://127.0.0.1:8081",
        binding = BackendAccountDeviceCookieBinding(
            actorId = NP_ACTOR_ID, accountGeneration = 1L, authEpoch = 1L, deviceId = NP_DEVICE_ID,
            sessionId = "s".repeat(32), expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
        ),
        cookiePair = "${GatewayFieldSession.COOKIE_NAME}=test-v7-cookie",
        expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
    )
    private val binding = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { it.openConnection() }

    /** What MainActivity runs for the current walk at ENDED (S1617-13: the remainder is sealed, not dropped). */
    fun endWalk() {
        coordinator.onWalkEnded(context(WalkSessionState.ENDED))
        coordinator.discardEndedPartials(context(WalkSessionState.ENDED))
    }

    fun supersede(receipt: String, revision: Long = 2L) {
        check(
            consentSession.accept(
                confirmation().copy(
                    revision = revision, clientRevision = revision, backendConsentReceiptSha256 = receipt,
                    selections = IntegratedConsentSelections(rawSourceCollection = true, automaticReporting = true),
                ),
            ),
        )
    }

    fun capture(
        window: Int,
        identity: RawDetectorRuntimeIdentity = NP_IDENTITY,
        model: String = "model-v1",
        detectorAvailable: Boolean = true,
    ): RawMetadataCaptureResult {
        val start = NP_BASE_EPOCH_MS - 30_000L + window * 30_000L
        val end = NP_BASE_EPOCH_MS + window * 30_000L
        nowEpochMs = end
        elapsedMs += 30_000L
        return captureAt(start, end, identity, model, detectorAvailable)
    }

    fun captureAt(
        start: Long,
        end: Long,
        identity: RawDetectorRuntimeIdentity = NP_IDENTITY,
        model: String = "model-v1",
        detectorAvailable: Boolean = true,
    ): RawMetadataCaptureResult = coordinator.captureMetadataBatch(
        context(WalkSessionState.ACTIVE),
        RawDetectionMetadataSample(
            windowIdentity = fixtureRawNoProfileTestSupportWindowIdentity,
            windowStartedAtEpochMs = start, capturedAtEpochMs = end, processedFrameCount = 30,
            detectionCount = 3, averageInferenceMs = 30L, detectorAvailable = detectorAvailable,
            modelRevision = model, maxInferenceMs = 50L,
            inferenceMsHistogram = MutableList(RAW_RUNTIME_LATENCY_BUCKET_COUNT) { 0 }.also {
                it[0] += 29
                it[rawRuntimeLatencyBucket(50L)] += 1
            },
            runtimeIdentity = identity,
        ),
        RawPerformanceMetadataSample(
            windowIdentity = fixtureRawNoProfileTestSupportWindowIdentity,
            windowStartedAtEpochMs = start, capturedAtEpochMs = end, processedFrameCount = 30,
            droppedFrameCount = 1, averageFrameDurationMs = 100L, thermalThrottled = false,
            maxFrameDurationMs = 150L, frameDurationMsHistogram = listOf(0, 0, 10, 19, 0, 0, 0),
            frameDurationSampleCount = 29,
        ),
    )

    fun payload(manifest: RawCollectionManifest, ordinal: Int): JSONObject {
        val chunk = requireNotNull(store.readUploadChunk(manifest.collectionId, NP_OWNER, ordinal))
        return JSONObject(String(chunk.plaintext, Charsets.UTF_8))
    }

    fun context(state: WalkSessionState): RawCollectionRuntimeContext {
        val epoch = WalkRuntimeEpoch(walkId, recoveryGeneration = 0L)
        return RawCollectionRuntimeContext(
            session = session, sessionGeneration = 7L, deviceCheck = deviceCheck, consentSession = consentSession,
            walk = when (state) {
                WalkSessionState.ACTIVE -> noProfileActiveWalk(epoch)
                WalkSessionState.PAUSED -> WalkSessionSnapshot(
                    epoch = epoch, state = WalkSessionState.PAUSED, mode = WalkSessionMode.FULL,
                    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED, isForeground = true, readiness = null,
                )
                // Owner decision 3(b): after the walk (ENDED, READY) the post-walk upload runs; R4: SAFE_STOP
                // only seals.
                else -> WalkSessionSnapshot(
                    epoch = epoch, state = state, mode = WalkSessionMode.UNAVAILABLE,
                    recoveryStage = null, isForeground = true, readiness = null,
                )
            },
            gatewayWalkLease = if (state != WalkSessionState.ACTIVE && state != WalkSessionState.PAUSED) null else GatewayWalkLease(
                result = "ACQUIRED", actorId = NP_ACTOR_ID, deviceId = NP_DEVICE_ID, walkId = walkId, leaseId = "lease-id",
                fencingToken = 1L, acquiredAtEpochMs = NP_BASE_EPOCH_MS, leaseExpiresAtEpochMs = NP_BASE_EPOCH_MS + 90_000L,
                serverTimeEpochMs = NP_BASE_EPOCH_MS, localDeadlineElapsedMs = Long.MAX_VALUE / 4,
            ),
            networkTransport = ActiveNetworkTransport.WIFI,
            networkBinding = binding,
            charging = true,
        )
    }

    fun confirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = NP_DEVICE_ID,
        requestId = "request_id_1234567890",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(rawSourceCollection = true),
        confirmedAt = "2026-08-29T00:00:00.000Z",
        gatewayAuditRecordSha256 = "a".repeat(64),
        backendConsentReceiptSha256 = NP_CONSENT_SHA,
        controlSecret = "b".repeat(64),
    )
}

internal class NoProfileFakeClient : RawCollectionNetworkClient {
    var failure: RawCollectionHttpException? = null

    override fun uploadCall(
        session: GatewayFieldSession,
        consent: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        chunks: List<RawPlaintextChunk>,
        isCurrent: () -> Boolean,
    ): CancellableNetworkCall<RawCollectionReceipt> = CancellableNetworkCall.blocking {
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

internal class NoProfileFakeStorage : RawCollectionStorage {
    private var activeId: String? = null
    private val manifests = mutableMapOf<String, String>()
    private val chunks = mutableMapOf<Pair<String, Int>, String>()
    private val quarantined = mutableSetOf<String>()
    private val fences = mutableMapOf<String, String>()
    val collectionCount: Int get() = manifests.size
    val quarantinedCount: Int get() = quarantined.size

    /** A stored collection whose manifest can no longer be opened (e.g. a lost key version). */
    fun plantUnreadable(collectionId: String) {
        manifests[collectionId] = "unreadable-envelope"
    }

    override fun readActiveCollectionId(): String? = activeId
    override fun writeActiveCollectionIdAtomically(collectionId: String): Boolean { activeId = collectionId; return true }
    override fun clearActiveCollectionId(): Boolean { activeId = null; return true }
    override fun readManifest(collectionId: String): String? = manifests[collectionId]
    override fun writeManifestAtomically(collectionId: String, envelope: String): Boolean { manifests[collectionId] = envelope; return true }
    override fun readChunk(collectionId: String, ordinal: Int): String? = chunks[collectionId to ordinal]
    override fun writeChunkAtomically(collectionId: String, ordinal: Int, envelope: String): Boolean {
        chunks[collectionId to ordinal] = envelope
        return true
    }
    override fun deleteChunk(collectionId: String, ordinal: Int): Boolean = chunks.remove(collectionId to ordinal) != null
    override fun listCollectionIds(): Set<String> = manifests.keys.toSet()
    override fun quarantineCollection(collectionId: String): Boolean {
        if (manifests.remove(collectionId) == null) return collectionId in quarantined
        chunks.keys.removeAll { it.first == collectionId }
        quarantined += collectionId
        if (activeId == collectionId) activeId = null
        return true
    }
    override fun listQuarantinedCollectionIds(): Set<String> = quarantined.toSet()
    /** transfer-F8: models a delete that fails once the marker was written. */
    var failCollectionDeletes = false
    override fun deleteCollection(collectionId: String): Boolean {
        if (failCollectionDeletes) return false
        manifests.remove(collectionId)
        chunks.keys.removeAll { it.first == collectionId }
        return true
    }
    override fun deleteAllCollections(): Boolean { manifests.clear(); chunks.clear(); quarantined.clear(); return true }
    override fun verifiedEmptyAfterAccountPurge(): Boolean = manifests.isEmpty() && chunks.isEmpty()
    override fun hasFence(name: String): Boolean = name in fences
    override fun readFence(name: String): String? = fences[name]
    override fun writeFenceAtomically(name: String, value: String): Boolean { fences[name] = value; return true }
    override fun listFenceNames(): Set<String> = fences.keys.toSet()
    override fun clearAllFences(): Boolean { fences.clear(); return true }
    override fun measureUsage(): RawStorageUsage =
        RawStorageUsage(manifests.size, quarantined.size, (manifests.values + chunks.values).sumOf { it.length.toLong() })
    /** transfer-F1: models storage written by a build before a fence type existed. */
    fun removeFenceForTest(name: String) {
        fences.remove(name)
    }
}

internal class NoProfileFakeAead : LocalAead {
    private val values = mutableMapOf<String, ByteArray>()
    private var ordinal = 0
    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
        val envelope = "no-profile-envelope-${ordinal++}"
        values[envelope] = plaintext.copyOf()
        return AeadSealResult.Sealed(envelope)
    }
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        values[envelope]?.copyOf()?.let { AeadOpenResult.Opened(it, 1, false) }
            ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
    override fun destroyVersion(version: Int): Boolean = true
    override fun destroyKnownVersions(): Boolean { values.clear(); return true }
    override fun createFreshAfterVerifiedPurge(): Boolean = true
}

internal const val NP_WALK_1 = "123e4567-e89b-42d3-a456-426614174001"
internal const val NP_WALK_2 = "123e4567-e89b-42d3-a456-426614174009"
internal const val NP_WALK_3 = "123e4567-e89b-42d3-a456-426614174010"
internal const val NP_ACTOR_ID = "123e4567-e89b-42d3-a456-426614174002"
internal const val NP_DEVICE_ID = "device-installation-00000001"
internal const val NP_BASE_EPOCH_MS = 1_800_000_000_000L
internal val NP_CONSENT_SHA = "c".repeat(64)
internal val NP_OWNER = RawCollectionOwner(NP_ACTOR_ID, 1L, NP_DEVICE_ID)
internal val NP_IDENTITY = RawDetectorRuntimeIdentity("1".repeat(64), "2".repeat(64), "3".repeat(64), "gpu", false)

private fun noProfileActiveWalk(epoch: WalkRuntimeEpoch): WalkSessionSnapshot {
    val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
    val observations = WalkSessionReadinessRequirement.entries.associateWith { requirement ->
        WalkSessionReadinessObservation(
            epoch = epoch,
            requirement = requirement,
            status = if (plan.requires(requirement)) WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.NOT_REQUIRED,
        )
    }
    return WalkSessionSnapshot(
        epoch = epoch, state = WalkSessionState.ACTIVE, mode = WalkSessionMode.FULL, recoveryStage = null,
        isForeground = true, readiness = WalkSessionReadinessSnapshot(epoch, plan, 1L, observations),
    )
}

private val fixtureRawNoProfileTestSupportWindowIdentity = RawRuntimeWindowIdentity(1L)
