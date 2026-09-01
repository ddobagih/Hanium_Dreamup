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
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessRequirement
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawCollectionRuntimeCoordinatorTest {
    @Test
    fun captureNeedsV7DeviceConsentAndActiveWalkThenSealsOnlyDuringRecheck() {
        val fixture = fixture()
        val sample = detectionSample()

        assertEquals(
            RawMetadataCaptureResult.GATE_BLOCKED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE).copy(
                    deviceCheck = fixture.deviceCheck.copy(state = PostLoginDeviceCheckState.FAIL),
                ),
                sample,
                performanceSample(),
            ),
        )
        assertEquals(
            RawMetadataCaptureResult.RATE_LIMITED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                sample.copy(windowStartedAtEpochMs = NOW_EPOCH_MS - 1_000L),
                performanceSample().copy(windowStartedAtEpochMs = NOW_EPOCH_MS - 1_000L),
            ),
        )
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                sample,
                performanceSample(),
            ),
        )
        assertEquals(
            RawSegmentSealResult.GATE_BLOCKED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.ACTIVE)),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.PAUSED)),
        )
        assertEquals(1, fixture.coordinator.readyCount(fixture.context(WalkSessionState.PAUSED)))
        assertNull(fixture.coordinator.startNextUpload { fixture.context(WalkSessionState.ACTIVE) })
        assertNull(fixture.coordinator.startNextUpload { fixture.context(WalkSessionState.ENDED) })
    }

    @Test
    fun pausedWifiUploadIsSingleFlightAndDeletesOnlyAfterExactReceipt() {
        val fixture = fixture()
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(thermalThrottled = null),
            ),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.PAUSED)),
        )
        assertNull(
            fixture.coordinator.startNextUpload {
                fixture.context(WalkSessionState.PAUSED).copy(
                    networkTransport = ActiveNetworkTransport.CELLULAR,
                )
            },
        )

        val call = fixture.coordinator.startNextUpload {
            fixture.context(WalkSessionState.PAUSED)
        }
        assertNotNull(call)
        assertNull(
            fixture.coordinator.startNextUpload {
                fixture.context(WalkSessionState.PAUSED)
            },
        )
        assertEquals(
            RawCollectionUploadOutcome.DELETED_AFTER_EXACT_RECEIPT,
            requireNotNull(call).execute(),
        )
        assertEquals(1, fixture.client.calls)
        assertEquals(2, fixture.client.lastPlaintexts.size)
        assertTrue(
            fixture.client.lastPlaintexts.all { plaintext ->
                plaintext.all { it == 0.toByte() }
            },
        )
        assertEquals(0, fixture.coordinator.readyCount(fixture.context(WalkSessionState.PAUSED)))
    }

    @Test
    fun invalidationCancelsSingleFlightAndEndedPartialNeedsExplicitDiscard() {
        val fixture = fixture()
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertTrue(fixture.coordinator.onWalkEnded(fixture.context(WalkSessionState.ENDED)))
        assertNull(fixture.coordinator.startNextUpload { fixture.context(WalkSessionState.ENDED) })
        assertEquals(1, fixture.coordinator.discardEndedPartials(fixture.context(WalkSessionState.ENDED)))

        val uploadFixture = fixture()
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            uploadFixture.coordinator.captureMetadataBatch(
                uploadFixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            uploadFixture.coordinator.sealActiveSegment(
                uploadFixture.context(WalkSessionState.PAUSED),
            ),
        )
        val call = requireNotNull(
            uploadFixture.coordinator.startNextUpload {
                uploadFixture.context(WalkSessionState.PAUSED)
            },
        )
        assertFalse(
            uploadFixture.coordinator.revalidate(
                uploadFixture.context(WalkSessionState.ACTIVE),
            ),
        )
        assertTrue(call.isCancelled())
        assertEquals(1, uploadFixture.coordinator.readyCount(uploadFixture.context(WalkSessionState.PAUSED)))
    }

    @Test
    fun failedSecondBatchWriteDiscardsPartialBeforeTheNextCapture() {
        val fixture = fixture()
        fixture.storage.failChunkOrdinal = 1

        assertEquals(
            RawMetadataCaptureResult.STORAGE_FAILURE,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)

        fixture.storage.failChunkOrdinal = null
        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
    }

    @Test
    fun mismatchedBatchWindowIsRejectedBeforeOpeningACollection() {
        val fixture = fixture()

        assertEquals(
            RawMetadataCaptureResult.STORAGE_FAILURE,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample().copy(capturedAtEpochMs = NOW_EPOCH_MS - 1L),
            ),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)
    }

    @Test
    fun executorDelayAfterObservationDoesNotRejectTheCapturedBatch() {
        val fixture = fixture(storeNowEpochMs = NOW_EPOCH_MS + 1_000L)

        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertEquals(
            RawSegmentSealResult.SEALED,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.PAUSED)),
        )
        val ready = requireNotNull(fixture.store.readyManifests(OWNER).singleOrNull())
        assertEquals(
            30_000L,
            requireNotNull(ready.capturedEndedAtEpochMs) - ready.capturedStartedAtEpochMs,
        )
    }

    @Test
    fun staleConsentEmptyPartialIsDiscardedBeforeCapturingTheCurrentBatch() {
        val fixture = fixture()
        assertTrue(
            fixture.store.open(
                collectionId = COLLECTION_ID,
                owner = OWNER,
                walkSessionId = WALK_ID,
                consentReceiptSha256 = "d".repeat(64),
                capturedStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            ),
        )

        assertEquals(
            RawMetadataCaptureResult.CAPTURED,
            fixture.coordinator.captureMetadataBatch(
                fixture.context(WalkSessionState.ACTIVE),
                detectionSample(),
                performanceSample(),
            ),
        )
        assertEquals(
            CONSENT_SHA,
            fixture.store.activePartialManifest(OWNER, WALK_ID)?.consentReceiptSha256,
        )
    }

    @Test
    fun pausedSealDiscardsAnIncompletePartialCollection() {
        val fixture = fixture()
        assertTrue(
            fixture.store.open(
                collectionId = COLLECTION_ID,
                owner = OWNER,
                walkSessionId = WALK_ID,
                consentReceiptSha256 = CONSENT_SHA,
                capturedStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
                rawConsentGranted = true,
                walkState = RawWalkState.ACTIVE,
            ),
        )

        assertEquals(
            RawSegmentSealResult.STORAGE_FAILURE,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.PAUSED)),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)
    }

    @Test
    fun pausedSealDiscardsALegacySingleChunkPartialCollection() {
        val fixture = fixture()
        val legacyPartial = RawCollectionManifest(
            collectionId = COLLECTION_ID,
            owner = OWNER,
            walkSessionId = WALK_ID,
            consentReceiptSha256 = CONSENT_SHA,
            capturedStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
            capturedEndedAtEpochMs = null,
            expiresAtEpochMs = NOW_EPOCH_MS - 30_000L + RAW_COLLECTION_TTL_MS,
            state = RawManifestState.PARTIAL,
            chunks = listOf(
                RawChunkMetadata(
                    ordinal = 0,
                    type = RawChunkType.DETECTION,
                    capturedAtEpochMs = NOW_EPOCH_MS,
                    sizeBytes = 1,
                    sha256 = "a".repeat(64),
                ),
            ),
        )
        val envelope = requireNotNull(
            fixture.aead.seal(
                encodeRawManifest(legacyPartial),
                byteArrayOf(),
                AeadLimits(1_024 * 1_024, 1_024 * 1_024 + 16, 2 * 1_024 * 1_024),
            ) as? AeadSealResult.Sealed,
        ).envelope
        assertTrue(fixture.storage.writeManifestAtomically(COLLECTION_ID, envelope))
        assertTrue(fixture.storage.writeActiveCollectionIdAtomically(COLLECTION_ID))

        assertEquals(
            RawSegmentSealResult.STORAGE_FAILURE,
            fixture.coordinator.sealActiveSegment(fixture.context(WalkSessionState.PAUSED)),
        )
        assertNull(fixture.storage.activeCollectionId)
        assertEquals(0, fixture.storage.collectionCount)
    }

    private fun fixture(storeNowEpochMs: Long = NOW_EPOCH_MS): RuntimeFixture {
        val storage = RuntimeFakeStorage()
        val aead = RuntimeFakeAead()
        val store = RawCollectionStore(storage, aead, { storeNowEpochMs }, testOnly = Unit)
        val client = RuntimeFakeClient()
        val coordinator = RawCollectionRuntimeCoordinator(
            store = store,
            client = client,
            idFactory = { UUID.fromString(COLLECTION_ID) },
            elapsedRealtimeMs = { NOW_ELAPSED_MS },
            minimumCaptureIntervalMs = 30_000L,
            testOnly = Unit,
        )
        val consentSession = IntegratedConsentSession()
        assertTrue(consentSession.accept(confirmation()))
        return RuntimeFixture(
            coordinator,
            client,
            store,
            aead,
            storage,
            consentSession,
            v7Session(),
            deviceCheck(),
        )
    }

    private fun detectionSample() = RawDetectionMetadataSample(
        windowStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
        capturedAtEpochMs = NOW_EPOCH_MS,
        processedFrameCount = 30,
        detectionCount = 4,
        averageInferenceMs = 25L,
        detectorAvailable = true,
        modelRevision = "model-v1",
    )

    private fun performanceSample(
        thermalThrottled: Boolean? = false,
    ) = RawPerformanceMetadataSample(
        windowStartedAtEpochMs = NOW_EPOCH_MS - 30_000L,
        capturedAtEpochMs = NOW_EPOCH_MS,
        processedFrameCount = 30,
        droppedFrameCount = 2,
        averageFrameDurationMs = 40L,
        thermalThrottled = thermalThrottled,
    )

    private fun v7Session(): GatewayFieldSession = GatewayFieldSession.backendAccountDeviceSession(
        gatewayBaseUrl = "http://127.0.0.1:8081",
        binding = BackendAccountDeviceCookieBinding(
            actorId = ACTOR_ID,
            accountGeneration = 1L,
            authEpoch = 1L,
            deviceId = DEVICE_ID,
            sessionId = "s".repeat(32),
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
        ),
        cookiePair = "${GatewayFieldSession.COOKIE_NAME}=test-v7-cookie",
        expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
    )

    private fun deviceCheck() = PostLoginDeviceCheckSnapshot(
        state = PostLoginDeviceCheckState.FULL,
        actorId = ACTOR_ID,
        sessionGeneration = SESSION_GENERATION,
        attemptGeneration = 1L,
    )

    private fun confirmation() = IntegratedConsentConfirmation(
        schemaVersion = INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
        installationId = DEVICE_ID,
        requestId = "request_id_1234567890",
        itemVersions = IntegratedConsentItemVersions(),
        clientRevision = 1L,
        revision = 1L,
        selections = IntegratedConsentSelections(rawSourceCollection = true),
        confirmedAt = "2026-08-29T00:00:00Z",
        gatewayAuditRecordSha256 = "a".repeat(64),
        backendConsentReceiptSha256 = CONSENT_SHA,
        controlSecret = "b".repeat(64),
    )

    private companion object {
        const val COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val WALK_ID = "123e4567-e89b-42d3-a456-426614174001"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174002"
        const val DEVICE_ID = "device-installation-00000001"
        const val SESSION_GENERATION = 7L
        const val NOW_EPOCH_MS = 1_800_000_000_000L
        const val NOW_ELAPSED_MS = 50_000L
        val CONSENT_SHA = "c".repeat(64)
        val OWNER = RawCollectionOwner(ACTOR_ID, 1L, DEVICE_ID)
    }
}

private class RuntimeFixture(
    val coordinator: RawCollectionRuntimeCoordinator,
    val client: RuntimeFakeClient,
    val store: RawCollectionStore,
    val aead: RuntimeFakeAead,
    val storage: RuntimeFakeStorage,
    private val consentSession: IntegratedConsentSession,
    private val session: GatewayFieldSession,
    val deviceCheck: PostLoginDeviceCheckSnapshot,
) {
    private val epoch = WalkRuntimeEpoch(
        "123e4567-e89b-42d3-a456-426614174001",
        recoveryGeneration = 0L,
    )
    private val networkBinding = IntegratedConsentNetworkBinding.forTest(
        IntegratedConsentNetworkTransport.WIFI,
    ) { url -> url.openConnection() }

    fun context(state: WalkSessionState): RawCollectionRuntimeContext {
        val walk = when (state) {
            WalkSessionState.ACTIVE -> activeWalkSnapshot(epoch)
            WalkSessionState.PAUSED -> pausedWalkSnapshot(epoch)
            WalkSessionState.ENDED -> endedWalkSnapshot(epoch)
            else -> error("unsupported test state")
        }
        return RawCollectionRuntimeContext(
            session = session,
            sessionGeneration = 7L,
            deviceCheck = deviceCheck,
            consentSession = consentSession,
            walk = walk,
            gatewayWalkLease = GatewayWalkLease(
                result = "ACQUIRED",
                actorId = session.actorId,
                deviceId = session.deviceId,
                walkId = epoch.walkSessionId,
                leaseId = "lease-id",
                fencingToken = 1L,
                acquiredAtEpochMs = 1_800_000_000_000L,
                leaseExpiresAtEpochMs = 1_800_000_090_000L,
                serverTimeEpochMs = 1_800_000_000_000L,
                localDeadlineElapsedMs = 100_000L,
            ),
            networkTransport = ActiveNetworkTransport.WIFI,
            networkBinding = networkBinding,
        )
    }
}

private fun activeWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot {
    val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
    val observations = WalkSessionReadinessRequirement.entries.associateWith { requirement ->
        WalkSessionReadinessObservation(
            epoch = epoch,
            requirement = requirement,
            status = if (plan.requires(requirement)) {
                WalkSessionReadinessStatus.READY
            } else {
                WalkSessionReadinessStatus.NOT_REQUIRED
            },
        )
    }
    return WalkSessionSnapshot(
        epoch = epoch,
        state = WalkSessionState.ACTIVE,
        mode = WalkSessionMode.FULL,
        recoveryStage = null,
        isForeground = true,
        readiness = WalkSessionReadinessSnapshot(epoch, plan, 1L, observations),
    )
}

private fun pausedWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot = WalkSessionSnapshot(
    epoch = epoch,
    state = WalkSessionState.PAUSED,
    mode = WalkSessionMode.FULL,
    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
    isForeground = true,
    readiness = null,
)

private fun endedWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot = WalkSessionSnapshot(
    epoch = epoch,
    state = WalkSessionState.ENDED,
    mode = WalkSessionMode.UNAVAILABLE,
    recoveryStage = null,
    isForeground = true,
    readiness = null,
)

private class RuntimeFakeClient : RawCollectionNetworkClient {
    var calls = 0
    var lastPlaintexts: List<ByteArray> = emptyList()

    override fun uploadCall(
        session: GatewayFieldSession,
        consent: IntegratedConsentConfirmation,
        networkBinding: IntegratedConsentNetworkBinding,
        localManifest: RawCollectionManifest,
        backendManifest: BackendRawManifest,
        chunks: List<RawPlaintextChunk>,
        isCurrent: () -> Boolean,
    ): CancellableNetworkCall<RawCollectionReceipt> {
        calls += 1
        lastPlaintexts = chunks.map(RawPlaintextChunk::plaintext)
        return CancellableNetworkCall.blocking {
            check(isCurrent())
            exactRuntimeReceipt(backendManifest)
        }
    }
}

private fun exactRuntimeReceipt(manifest: BackendRawManifest): RawCollectionReceipt {
    val unsigned = RawCollectionReceipt(
        schemaVersion = BACKEND_RECEIPT_SCHEMA,
        collectionId = manifest.collectionId,
        manifestSha256 = manifest.manifestSha256,
        purpose = BACKEND_RAW_PURPOSE,
        persistenceMarker = RAW_RECEIPT_PERSISTENCE_MARKER,
        objectCount = manifest.objects.size,
        chunkCount = manifest.chunkCount,
        totalBytes = manifest.totalBytes,
        objects = manifest.objects,
        retentionClass = BACKEND_RETENTION_CLASS,
        committedAt = "2026-08-29T00:00:00Z",
        quarantineExpiresAt = "2026-09-12T00:00:00Z",
        receiptSha256 = "0".repeat(64),
    )
    return unsigned.copy(receiptSha256 = rawReceiptSha256(unsigned))
}

private class RuntimeFakeStorage : RawCollectionStorage {
    private var activeId: String? = null
    private val manifests = mutableMapOf<String, String>()
    private val chunks = mutableMapOf<Pair<String, Int>, String>()
    private val fences = mutableMapOf<String, String>()
    var failChunkOrdinal: Int? = null
    val activeCollectionId: String?
        get() = activeId
    val collectionCount: Int
        get() = manifests.size

    override fun readActiveCollectionId(): String? = activeId
    override fun writeActiveCollectionIdAtomically(collectionId: String): Boolean {
        activeId = collectionId
        return true
    }
    override fun clearActiveCollectionId(): Boolean {
        activeId = null
        return true
    }
    override fun readManifest(collectionId: String): String? = manifests[collectionId]
    override fun writeManifestAtomically(collectionId: String, envelope: String): Boolean {
        manifests[collectionId] = envelope
        return true
    }
    override fun readChunk(collectionId: String, ordinal: Int): String? = chunks[collectionId to ordinal]
    override fun writeChunkAtomically(collectionId: String, ordinal: Int, envelope: String): Boolean {
        if (ordinal == failChunkOrdinal) return false
        chunks[collectionId to ordinal] = envelope
        return true
    }
    override fun deleteChunk(collectionId: String, ordinal: Int): Boolean =
        chunks.remove(collectionId to ordinal) != null
    override fun listCollectionIds(): Set<String> = manifests.keys.toSet()
    override fun deleteCollection(collectionId: String): Boolean {
        manifests.remove(collectionId)
        chunks.keys.removeAll { it.first == collectionId }
        return true
    }
    override fun deleteAllCollections(): Boolean {
        manifests.clear()
        chunks.clear()
        return true
    }
    override fun verifiedEmptyAfterAccountPurge(): Boolean = manifests.isEmpty() && chunks.isEmpty()
    override fun hasFence(name: String): Boolean = name in fences
    override fun readFence(name: String): String? = fences[name]
    override fun writeFenceAtomically(name: String, value: String): Boolean {
        fences[name] = value
        return true
    }
    override fun listFenceNames(): Set<String> = fences.keys
    override fun clearAllFences(): Boolean {
        fences.clear()
        return true
    }
}

private class RuntimeFakeAead : LocalAead {
    private val values = mutableMapOf<String, ByteArray>()
    private var ordinal = 0

    override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
        val envelope = "runtime-envelope-${ordinal++}"
        values[envelope] = plaintext.copyOf()
        return AeadSealResult.Sealed(envelope)
    }
    override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
        values[envelope]?.copyOf()?.let { AeadOpenResult.Opened(it, 1, false) }
            ?: AeadOpenResult.Blocked(AeadBlockReason.MALFORMED_ENVELOPE)
    override fun destroyVersion(version: Int): Boolean = true
    override fun destroyKnownVersions(): Boolean {
        values.clear()
        return true
    }
    override fun createFreshAfterVerifiedPurge(): Boolean = true
}
